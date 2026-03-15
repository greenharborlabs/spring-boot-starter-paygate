package com.greenharborlabs.l402.core.macaroon;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-based implementation of {@link RootKeyStore}. Each root key is persisted as a
 * hex-encoded file whose name is the hex-encoded tokenId.
 *
 * <p>Thread safety is provided by a {@link ReadWriteLock}: read lock for
 * {@link #getRootKey}, write lock for {@link #generateRootKey} and {@link #revokeRootKey}.
 *
 * <p>On POSIX systems the storage directory is created with {@code 700} permissions and
 * key files with {@code 600} permissions. Writes are atomic (tmp file + rename).
 */
public final class FileBasedRootKeyStore implements RootKeyStore {

    private static final int KEY_LENGTH = 32;
    private static final int DEFAULT_MAX_CACHE_SIZE = 10_000;
    private static final HexFormat HEX = HexFormat.of();

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
        this.cache = new LinkedHashMap<>(16, 0.75f, false) {
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

            lock.writeLock().lock();
            try {
                ensureOpen();
                writeKeyFile(hexKeyId, rootKey);
                cache.put(hexKeyId, rootKey.clone());
            } finally {
                lock.writeLock().unlock();
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
        lock.readLock().lock();
        try {
            ensureOpen();
            byte[] cached = cache.get(hexKeyId);
            if (cached != null) {
                return new SensitiveBytes(cached.clone());
            }
        } finally {
            lock.readLock().unlock();
        }

        // Slow path: write lock for disk read + cache population
        lock.writeLock().lock();
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
            String hexContent = Files.readString(keyFile).strip();
            byte[] rootKey = HEX.parseHex(hexContent);
            try {
                cache.put(hexKeyId, rootKey.clone());
                return new SensitiveBytes(rootKey.clone());
            } finally {
                KeyMaterial.zeroize(rootKey);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read root key: " + hexKeyId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
        ensureOpen();
        String hexKeyId = HEX.formatHex(keyId);
        Path keyFile = resolveKeyFile(hexKeyId);

        lock.writeLock().lock();
        try {
            ensureOpen();
            Files.deleteIfExists(keyFile);
            byte[] removed = cache.remove(hexKeyId);
            KeyMaterial.zeroize(removed);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to revoke root key: " + hexKeyId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
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
                    Files.createDirectories(directory,
                            PosixFilePermissions.asFileAttribute(dirPerms));
                } else {
                    Files.createDirectories(directory);
                }
            } else if (posix) {
                Files.setPosixFilePermissions(directory,
                        PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create key storage directory: " + directory, e);
        }
    }

    private void writeKeyFile(String hexKeyId, byte[] rootKey) {
        try {
            String hexContent = HEX.formatHex(rootKey);
            Path targetFile = resolveKeyFile(hexKeyId);
            Path tmpFile = resolveKeyFile(hexKeyId + ".tmp");

            if (posix) {
                // Create temp file with owner-only permissions from the start,
                // so the key is never world-readable even briefly.
                Files.createFile(tmpFile,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                try {
                    Files.writeString(tmpFile, hexContent);
                } catch (IOException e) {
                    Files.deleteIfExists(tmpFile);
                    throw e;
                }
            } else {
                try {
                    Files.writeString(tmpFile, hexContent);
                } catch (IOException e) {
                    Files.deleteIfExists(tmpFile);
                    throw e;
                }
            }
            Files.move(tmpFile, targetFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write root key: " + hexKeyId, e);
        }
    }
}
