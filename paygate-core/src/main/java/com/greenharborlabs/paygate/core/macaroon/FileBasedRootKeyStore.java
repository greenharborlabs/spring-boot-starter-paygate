package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-based implementation of {@link RootKeyStore}. Each root key is persisted as a hex-encoded
 * file whose name is the hex-encoded tokenId.
 *
 * <p>Thread safety is provided by a {@link ReadWriteLock}: read lock for {@link #getRootKey}, write
 * lock for {@link #generateRootKey} and {@link #revokeRootKey}.
 *
 * <p>On POSIX systems the storage directory is created with {@code 700} permissions and key files
 * with {@code 600} permissions. Writes are atomic (tmp file + rename).
 */
public final class FileBasedRootKeyStore implements RootKeyStore {

  private static final int KEY_LENGTH = 32;
  private static final int HEX_KEY_LENGTH = KEY_LENGTH * 2;
  private static final int DEFAULT_MAX_CACHE_SIZE = 10_000;
  private static final HexFormat HEX = HexFormat.of();
  private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

  private final Path directory;
  private final SecureRandom secureRandom = new SecureRandom();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Map<String, byte[]> cache;
  private final boolean posix;
  private volatile boolean closed;

  public FileBasedRootKeyStore(Path directory) {
    this(directory, DEFAULT_MAX_CACHE_SIZE);
  }

  // package-private for testing cache eviction behavior
  FileBasedRootKeyStore(Path directory, int maxCacheSize) {
    this.directory = directory.toAbsolutePath().normalize();
    this.posix = this.directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    this.cache =
        new LinkedHashMap<>(16, 0.75f, false) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            if (size() > maxCacheSize) {
              KeyMaterial.zeroize(eldest.getValue());
              return true;
            }
            return false;
          }
        };
    ensureDirectory();
  }

  @Override
  public GenerationResult generateRootKey() {
    ensureOpen();
    byte[] rootKey = new byte[KEY_LENGTH];
    try {
      secureRandom.nextBytes(rootKey);

      byte[] tokenId = new byte[KEY_LENGTH];
      secureRandom.nextBytes(tokenId);

      String hexKeyId = HEX.formatHex(tokenId);

      Lock writeLock = lock.writeLock();
      writeLock.lock();
      try {
        ensureOpen();
        writeKeyFile(hexKeyId, rootKey);
        cache.put(hexKeyId, rootKey.clone());
      } finally {
        writeLock.unlock();
      }

      return new GenerationResult(new SensitiveBytes(rootKey.clone()), tokenId);
    } finally {
      KeyMaterial.zeroize(rootKey);
    }
  }

  @Override
  public SensitiveBytes getRootKey(byte[] keyId) {
    ensureOpen();
    String hexKeyId = HEX.formatHex(keyId);
    Path keyFile = resolveKeyFile(hexKeyId);

    // Fast path: read lock for cache hit (safe because accessOrder=false)
    Lock readLock = lock.readLock();
    readLock.lock();
    try {
      ensureOpen();
      byte[] cached = cache.get(hexKeyId);
      if (cached != null) {
        return new SensitiveBytes(cached.clone());
      }
    } finally {
      readLock.unlock();
    }

    // Slow path: write lock for disk read + cache population
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      ensureOpen();
      // Double-check after lock promotion
      byte[] cached = cache.get(hexKeyId);
      if (cached != null) {
        return new SensitiveBytes(cached.clone());
      }
      if (!Files.exists(keyFile)) {
        return null;
      }
      byte[] hexContentBytes = Files.readAllBytes(keyFile);
      byte[] rootKey = decodeHexKeyFileContent(hexContentBytes);
      try {
        cache.put(hexKeyId, rootKey.clone());
        return new SensitiveBytes(rootKey.clone());
      } finally {
        KeyMaterial.zeroize(rootKey);
        KeyMaterial.zeroize(hexContentBytes);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read root key: " + hexKeyId, e);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void revokeRootKey(byte[] keyId) {
    ensureOpen();
    String hexKeyId = HEX.formatHex(keyId);
    Path keyFile = resolveKeyFile(hexKeyId);

    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      ensureOpen();
      Files.deleteIfExists(keyFile);
      byte[] removed = cache.remove(hexKeyId);
      KeyMaterial.zeroize(removed);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to revoke root key: " + hexKeyId, e);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      if (closed) {
        return;
      }
      for (byte[] value : cache.values()) {
        KeyMaterial.zeroize(value);
      }
      cache.clear();
      closed = true;
    } finally {
      writeLock.unlock();
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("RootKeyStore has been closed");
    }
  }

  // package-private for testing
  Path resolveKeyFile(String hexKeyId) {
    Path keyFile = directory.resolve(hexKeyId).normalize();
    if (!keyFile.startsWith(directory)) {
      throw new IllegalArgumentException("Key ID resolves outside storage directory");
    }
    return keyFile;
  }

  private void ensureDirectory() {
    try {
      if (!Files.exists(directory)) {
        if (posix) {
          Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwx------");
          Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(dirPerms));
        } else {
          Files.createDirectories(directory);
        }
      } else if (posix) {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create key storage directory: " + directory, e);
    }
  }

  private void writeKeyFile(String hexKeyId, byte[] rootKey) {
    byte[] hexContentBytes = encodeHex(rootKey);
    try {
      Path targetFile = resolveKeyFile(hexKeyId);
      Path tmpFile = resolveKeyFile(hexKeyId + ".tmp");

      if (posix) {
        // Create temp file with owner-only permissions from the start,
        // so the key is never world-readable even briefly.
        Files.createFile(
            tmpFile,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
          Files.write(tmpFile, hexContentBytes);
        } catch (IOException e) {
          Files.deleteIfExists(tmpFile);
          throw e;
        }
      } else {
        try {
          Files.write(tmpFile, hexContentBytes);
        } catch (IOException e) {
          Files.deleteIfExists(tmpFile);
          throw e;
        }
      }
      Files.move(
          tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write root key: " + hexKeyId, e);
    } finally {
      KeyMaterial.zeroize(hexContentBytes);
    }
  }

  private static byte[] decodeHexKeyFileContent(byte[] fileBytes) {
    int start = 0;
    int end = fileBytes.length;
    while (start < end && isAsciiWhitespace(fileBytes[start])) {
      start++;
    }
    while (end > start && isAsciiWhitespace(fileBytes[end - 1])) {
      end--;
    }

    int length = end - start;
    if (length != HEX_KEY_LENGTH) {
      throw new IllegalArgumentException("Invalid root key length in key file");
    }
    byte[] out = new byte[KEY_LENGTH];
    for (int i = 0; i < KEY_LENGTH; i++) {
      int hi = hexNibble(fileBytes[start + (i * 2)]);
      int lo = hexNibble(fileBytes[start + (i * 2) + 1]);
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static byte[] encodeHex(byte[] raw) {
    byte[] out = new byte[raw.length * 2];
    for (int i = 0; i < raw.length; i++) {
      int v = raw[i] & 0xFF;
      out[i * 2] = HEX_DIGITS[v >>> 4];
      out[(i * 2) + 1] = HEX_DIGITS[v & 0x0F];
    }
    return out;
  }

  private static int hexNibble(byte b) {
    if (b >= '0' && b <= '9') {
      return b - '0';
    }
    if (b >= 'a' && b <= 'f') {
      return 10 + (b - 'a');
    }
    if (b >= 'A' && b <= 'F') {
      return 10 + (b - 'A');
    }
    throw new IllegalArgumentException("Invalid hex character in key file");
  }

  private static boolean isAsciiWhitespace(byte b) {
    return b == ' ' || b == '\n' || b == '\r' || b == '\t';
  }
}
