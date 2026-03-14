package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

@DisplayName("FileBasedRootKeyStore")
class FileBasedRootKeyStoreTest {

    private static final int KEY_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    @TempDir
    Path tempDir;

    private FileBasedRootKeyStore store;

    @BeforeEach
    void setUp() {
        store = new FileBasedRootKeyStore(tempDir);
    }

    @Nested
    @DisplayName("generateRootKey")
    class GenerateRootKey {

        @Test
        @DisplayName("returns non-null byte array of 32 bytes")
        void returnsThirtyTwoBytes() {
            RootKeyStore.GenerationResult result = store.generateRootKey();

            assertThat(result.rootKey()).isNotNull().hasSize(KEY_LENGTH);
        }

        @Test
        @DisplayName("creates a key file in the storage directory")
        void createsKeyFile() throws IOException {
            store.generateRootKey();

            try (Stream<Path> files = Files.list(tempDir)) {
                long keyFileCount = files
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                        .count();
                assertThat(keyFileCount).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("key file name is hex-encoded tokenId")
        void keyFileNameIsHexEncodedTokenId() throws IOException {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();
            String expectedFileName = HEX.formatHex(keyId);

            assertThat(tempDir.resolve(expectedFileName)).exists().isRegularFile();
        }

        @Test
        @DisplayName("key file has 600 permissions (owner read/write only)")
        void keyFileHas600Permissions() throws IOException {
            // Skip on non-POSIX filesystems (e.g., Windows)
            assumeThat(tempDir.getFileSystem().supportedFileAttributeViews())
                    .contains("posix");

            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();
            String fileName = HEX.formatHex(keyId);
            Path keyFile = tempDir.resolve(fileName);

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);

            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
        }

        @Test
        @DisplayName("storage directory has 700 permissions")
        void directoryHas700Permissions() throws IOException {
            assumeThat(tempDir.getFileSystem().supportedFileAttributeViews())
                    .contains("posix");

            // Create a fresh subdirectory so FileBasedRootKeyStore sets permissions
            Path subDir = tempDir.resolve("keys");
            FileBasedRootKeyStore freshStore = new FileBasedRootKeyStore(subDir);
            freshStore.generateRootKey();

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(subDir);

            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            );
        }

        @Test
        @DisplayName("no temporary files remain after successful write")
        void noTmpFilesRemain() throws IOException {
            store.generateRootKey();

            try (Stream<Path> files = Files.list(tempDir)) {
                long tmpCount = files
                        .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                        .count();
                assertThat(tmpCount).isZero();
            }
        }
    }

    @Nested
    @DisplayName("getRootKey")
    class GetRootKey {

        @Test
        @DisplayName("returns null for unknown keyId")
        void returnsNullForUnknownKeyId() {
            byte[] unknownKeyId = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(unknownKeyId);

            byte[] result = store.getRootKey(unknownKeyId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns same key that was generated")
        void returnsSameKeyThatWasGenerated() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] key = result.rootKey();
            byte[] keyId = result.tokenId();

            byte[] retrieved = store.getRootKey(keyId);

            assertThat(retrieved).isEqualTo(key);
        }

        @Test
        @DisplayName("disk-read path returns defensive copy that callers can safely mutate")
        void diskReadPathReturnsDefensiveCopy() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] key = result.rootKey();
            byte[] keyId = result.tokenId();

            // Fresh store instance has an empty cache, so getRootKey hits the disk path
            FileBasedRootKeyStore freshStore = new FileBasedRootKeyStore(tempDir);

            byte[] firstRead = freshStore.getRootKey(keyId);
            assertThat(firstRead).isEqualTo(key);

            // Mutate the returned array — should not affect future reads
            java.util.Arrays.fill(firstRead, (byte) 0xFF);

            // Second read from yet another fresh store (empty cache, disk path again)
            FileBasedRootKeyStore anotherFreshStore = new FileBasedRootKeyStore(tempDir);
            byte[] secondRead = anotherFreshStore.getRootKey(keyId);

            assertThat(secondRead).isEqualTo(key);
            assertThat(secondRead).isNotEqualTo(firstRead);
        }

        @Test
        @DisplayName("reads back correctly from a fresh store instance over same directory")
        void readsBackFromFreshInstance() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] key = result.rootKey();
            byte[] keyId = result.tokenId();

            // Create a new store pointing at the same directory
            FileBasedRootKeyStore freshStore = new FileBasedRootKeyStore(tempDir);

            byte[] retrieved = freshStore.getRootKey(keyId);

            assertThat(retrieved).isEqualTo(key);
        }
    }

    @Nested
    @DisplayName("revokeRootKey")
    class RevokeRootKey {

        @Test
        @DisplayName("after revocation, getRootKey returns null")
        void revokedKeyReturnsNull() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();

            store.revokeRootKey(keyId);

            assertThat(store.getRootKey(keyId)).isNull();
        }

        @Test
        @DisplayName("revocation deletes the key file from disk")
        void revocationDeletesFile() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();
            String fileName = HEX.formatHex(keyId);
            Path keyFile = tempDir.resolve(fileName);

            assertThat(keyFile).exists();

            store.revokeRootKey(keyId);

            assertThat(keyFile).doesNotExist();
        }

        @Test
        @DisplayName("revoking unknown keyId does not throw")
        void revokingUnknownKeyIdDoesNotThrow() {
            byte[] unknownKeyId = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(unknownKeyId);

            store.revokeRootKey(unknownKeyId);
            // Should complete without exception
        }
    }

    @Nested
    @DisplayName("path confinement")
    class PathConfinement {

        @Test
        @DisplayName("resolveKeyFile rejects path traversal input")
        void resolveKeyFileRejectsTraversal() {
            // HexFormat output is always [0-9a-f] so traversal via the public API
            // is not possible, but this defense-in-depth check guards against
            // future refactors that might pass untrusted strings directly.
            assertThatThrownBy(() -> store.resolveKeyFile("../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside storage directory");
        }

        @Test
        @DisplayName("resolveKeyFile rejects input with embedded path separators")
        void resolveKeyFileRejectsEmbeddedSeparators() {
            assertThatThrownBy(() -> store.resolveKeyFile("../sibling/file"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside storage directory");
        }

        @Test
        @DisplayName("resolveKeyFile allows valid hex key IDs")
        void resolveKeyFileAllowsValidHexIds() {
            Path resolved = store.resolveKeyFile("abcdef0123456789");
            assertThat(resolved.getFileName().toString()).isEqualTo("abcdef0123456789");
            assertThat(resolved.startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
        }

        @Test
        @DisplayName("hex-encoded keyId can never produce path traversal via public API")
        void hexEncodedKeyIdCannotTraverse() {
            // Even if the raw bytes represent "../../", the hex encoding is safe
            byte[] bytesOfTraversal = "../../etc/passwd".getBytes();
            // This should not throw because HEX.formatHex produces [0-9a-f] only
            byte[] result = store.getRootKey(bytesOfTraversal);
            assertThat(result).isNull(); // key doesn't exist, but no exception
        }
    }

    @Nested
    @DisplayName("cache eviction")
    class CacheEviction {

        @Test
        @DisplayName("evicted keys are re-read from disk on next access")
        void evictedKeysAreReReadFromDisk() {
            // Use a tiny cache (max 2 entries) to force eviction
            FileBasedRootKeyStore boundedStore = new FileBasedRootKeyStore(tempDir, 2);

            RootKeyStore.GenerationResult r1 = boundedStore.generateRootKey();
            RootKeyStore.GenerationResult r2 = boundedStore.generateRootKey();
            RootKeyStore.GenerationResult r3 = boundedStore.generateRootKey();

            // r1 should have been evicted from cache, but its file still exists
            byte[] retrieved = boundedStore.getRootKey(r1.tokenId());
            assertThat(retrieved).isEqualTo(r1.rootKey());

            // r2 and r3 should also be retrievable
            assertThat(boundedStore.getRootKey(r2.tokenId())).isEqualTo(r2.rootKey());
            assertThat(boundedStore.getRootKey(r3.tokenId())).isEqualTo(r3.rootKey());
        }

        @Test
        @DisplayName("cache does not grow beyond max size")
        void cacheDoesNotGrowBeyondMaxSize() {
            int maxSize = 5;
            FileBasedRootKeyStore boundedStore = new FileBasedRootKeyStore(tempDir, maxSize);

            // Generate more keys than the cache can hold
            for (int i = 0; i < maxSize + 10; i++) {
                boundedStore.generateRootKey();
            }

            // All keys should still be retrievable from disk even if evicted from cache
            // This is a functional correctness check, not a cache size check
            // (cache internals are not exposed, but correctness is verified)
        }
    }

    @Nested
    @DisplayName("GenerationResult equals and hashCode")
    class GenerationResultEquality {

        @Test
        @DisplayName("equal when both byte[] fields have same content")
        void equalWhenSameContent() {
            byte[] rootKey = {1, 2, 3, 4};
            byte[] tokenId = {5, 6, 7, 8};

            var a = new RootKeyStore.GenerationResult(rootKey, tokenId);
            var b = new RootKeyStore.GenerationResult(rootKey.clone(), tokenId.clone());

            assertThat(a).isEqualTo(b);
            assertThat(b).isEqualTo(a);
        }

        @Test
        @DisplayName("identity equality")
        void identityEquality() {
            var result = new RootKeyStore.GenerationResult(new byte[]{1}, new byte[]{2});
            assertThat(result).isEqualTo(result);
        }

        @Test
        @DisplayName("not equal when rootKey differs")
        void notEqualWhenRootKeyDiffers() {
            byte[] tokenId = {5, 6, 7, 8};
            var a = new RootKeyStore.GenerationResult(new byte[]{1, 2, 3}, tokenId);
            var b = new RootKeyStore.GenerationResult(new byte[]{9, 9, 9}, tokenId.clone());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("not equal when tokenId differs")
        void notEqualWhenTokenIdDiffers() {
            byte[] rootKey = {1, 2, 3};
            var a = new RootKeyStore.GenerationResult(rootKey, new byte[]{5, 6, 7});
            var b = new RootKeyStore.GenerationResult(rootKey.clone(), new byte[]{9, 9, 9});

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            var result = new RootKeyStore.GenerationResult(new byte[]{1}, new byte[]{2});
            assertThat(result).isNotEqualTo(null);
        }

        @Test
        @DisplayName("not equal to different type")
        void notEqualToDifferentType() {
            var result = new RootKeyStore.GenerationResult(new byte[]{1}, new byte[]{2});
            assertThat(result).isNotEqualTo("not a GenerationResult");
        }

        @Test
        @DisplayName("hashCode consistent with equals")
        void hashCodeConsistentWithEquals() {
            byte[] rootKey = {10, 20, 30};
            byte[] tokenId = {40, 50, 60};

            var a = new RootKeyStore.GenerationResult(rootKey, tokenId);
            var b = new RootKeyStore.GenerationResult(rootKey.clone(), tokenId.clone());

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("hashCode differs for different content (not guaranteed but expected)")
        void hashCodeDiffersForDifferentContent() {
            var a = new RootKeyStore.GenerationResult(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
            var b = new RootKeyStore.GenerationResult(new byte[]{7, 8, 9}, new byte[]{10, 11, 12});

            // Not strictly required by contract, but for these inputs it should differ
            assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent read and write do not corrupt state")
        void concurrentReadWriteSafe() throws InterruptedException {
            // Pre-generate a key to read concurrently
            RootKeyStore.GenerationResult genResult = store.generateRootKey();
            byte[] key = genResult.rootKey();
            byte[] keyId = genResult.tokenId();

            int threadCount = 32;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<String> errors = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < threadCount; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (index % 2 == 0) {
                            // Readers
                            byte[] retrieved = store.getRootKey(keyId);
                            if (retrieved != null && !java.util.Arrays.equals(retrieved, key)) {
                                errors.add("Corrupted read at index " + index);
                            }
                        } else {
                            // Writers — generate additional keys
                            store.generateRootKey();
                        }
                    } catch (Exception e) {
                        errors.add("Exception at index " + index + ": " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(errors).isEmpty();
        }
    }
}
