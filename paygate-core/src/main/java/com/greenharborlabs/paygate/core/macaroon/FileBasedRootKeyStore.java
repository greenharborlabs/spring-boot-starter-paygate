package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
    if (keyId.length == 0) {
      return null;
    }
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
      if (!isSecureKeyFile(keyFile)) {
        return null;
      }
      byte[] hexContentBytes = readKeyFileNoFollow(keyFile);
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
    if (keyId.length == 0) {
      return;
    }
    String hexKeyId = HEX.formatHex(keyId);
    Path keyFile = resolveKeyFile(hexKeyId);

    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      ensureOpen();
      // Do not delete a link or another special filesystem object. Besides avoiding link
      // traversal, this leaves suspicious state available for investigation.
      if (isSecureKeyFile(keyFile)) {
        Files.delete(keyFile);
      }
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
    if (!posix) {
      throw new IllegalStateException(
          "Root key storage requires a filesystem with POSIX permissions: " + directory);
    }
    try {
      if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
            .isDirectory()) {
          throw new IllegalStateException("Root key storage path is not a directory: " + directory);
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      } else {
        Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwx------");
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(dirPerms));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create key storage directory: " + directory, e);
    }
  }

  private void writeKeyFile(String hexKeyId, byte[] rootKey) {
    byte[] hexContentBytes = encodeHex(rootKey);
    Path tmpFile = null;
    try {
      Path targetFile = resolveKeyFile(hexKeyId);
      if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Refusing to replace an existing root key file");
      }
      tmpFile =
          Files.createTempFile(
              directory,
              "." + hexKeyId + "-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.write(tmpFile, hexContentBytes);
      Files.move(tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write root key: " + hexKeyId, e);
    } finally {
      if (tmpFile != null) {
        try {
          Files.deleteIfExists(tmpFile);
        } catch (IOException ignored) {
          // The original operation has already failed or atomically published the file.
        }
      }
      KeyMaterial.zeroize(hexContentBytes);
    }
  }

  /** Returns whether the path is a non-link regular file with exactly owner read/write access. */
  private boolean isSecureKeyFile(Path keyFile) throws IOException {
    if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    if (!Files.readAttributes(keyFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .isRegularFile()) {
      return false;
    }
    return Files.getPosixFilePermissions(keyFile, LinkOption.NOFOLLOW_LINKS)
        .equals(PosixFilePermissions.fromString("rw-------"));
  }

  private static byte[] readKeyFileNoFollow(Path keyFile) throws IOException {
    try (var channel =
            Files.newByteChannel(keyFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var output = new ByteArrayOutputStream(HEX_KEY_LENGTH)) {
      ByteBuffer buffer = ByteBuffer.allocate(128);
      while (channel.read(buffer) != -1) {
        buffer.flip();
        if (output.size() + buffer.remaining() > 256) {
          throw new IOException("Root key file is unexpectedly large");
        }
        output.write(buffer.array(), buffer.position(), buffer.remaining());
        buffer.clear();
      }
      return output.toByteArray();
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
