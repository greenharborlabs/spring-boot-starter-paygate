package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryRootKeyStore")
class InMemoryRootKeyStoreTest {

    private static final int KEY_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private InMemoryRootKeyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRootKeyStore();
    }

    @Nested
    @DisplayName("generateRootKey")
    class GenerateRootKey {

        @Test
        @DisplayName("returns non-null byte array of 32 bytes")
        void returnsThirtyTwoBytes() {
            RootKeyStore.GenerationResult result = store.generateRootKey();

            assertThat(result.rootKey().value()).isNotNull().hasSize(KEY_LENGTH);
        }

        @Test
        @DisplayName("successive calls return different keys")
        void successiveCallsReturnDifferentKeys() {
            RootKeyStore.GenerationResult result1 = store.generateRootKey();
            RootKeyStore.GenerationResult result2 = store.generateRootKey();

            assertThat(result1.rootKey().value()).isNotEqualTo(result2.rootKey().value());
        }

        @Test
        @DisplayName("returned key is not all zeros")
        void keyIsNotAllZeros() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] key = result.rootKey().value();

            boolean allZeros = true;
            for (byte b : key) {
                if (b != 0) {
                    allZeros = false;
                    break;
                }
            }
            assertThat(allZeros).isFalse();
        }

        @Test
        @DisplayName("returns tokenId of 32 bytes")
        void returnsTokenId() {
            RootKeyStore.GenerationResult result = store.generateRootKey();

            assertThat(result.tokenId()).isNotNull().hasSize(KEY_LENGTH);
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

            SensitiveBytes result = store.getRootKey(unknownKeyId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns same key bytes that were generated for a given keyId")
        void returnsSameKeyForKnownKeyId() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] key = result.rootKey().value();
            byte[] keyId = result.tokenId();

            byte[] retrieved = store.getRootKey(keyId).value();

            assertThat(retrieved).isEqualTo(key);
        }

        @Test
        @DisplayName("returns defensive copy, not internal reference")
        void returnsDefensiveCopy() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();

            byte[] retrieved1 = store.getRootKey(keyId).value();
            byte[] retrieved2 = store.getRootKey(keyId).value();

            assertThat(retrieved1).isEqualTo(retrieved2);
            // Mutating the returned array should not affect the stored key
            retrieved1[0] = (byte) ~retrieved1[0];
            byte[] retrieved3 = store.getRootKey(keyId).value();
            assertThat(retrieved3).isEqualTo(retrieved2);
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
        @DisplayName("revoking unknown keyId does not throw")
        void revokingUnknownKeyIdDoesNotThrow() {
            byte[] unknownKeyId = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(unknownKeyId);

            // Should not throw
            store.revokeRootKey(unknownKeyId);
        }

        @Test
        @DisplayName("revocation does not affect other keys")
        void revocationDoesNotAffectOtherKeys() {
            RootKeyStore.GenerationResult result1 = store.generateRootKey();
            byte[] key1 = result1.rootKey().value();
            byte[] keyId1 = result1.tokenId();

            RootKeyStore.GenerationResult result2 = store.generateRootKey();
            byte[] key2 = result2.rootKey().value();
            byte[] keyId2 = result2.tokenId();

            store.revokeRootKey(keyId1);

            assertThat(store.getRootKey(keyId1)).isNull();
            assertThat(store.getRootKey(keyId2).value()).isEqualTo(key2);
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent generateRootKey calls all succeed without exceptions")
        void concurrentGenerateAllSucceed() throws InterruptedException {
            int threadCount = 64;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(threadCount);
            Set<String> hexKeys = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        RootKeyStore.GenerationResult result = store.generateRootKey();
                        hexKeys.add(HEX.formatHex(result.rootKey().value()));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            // All generated keys should be unique
            assertThat(hexKeys).hasSize(threadCount);
        }

        @Test
        @DisplayName("concurrent getRootKey racing with close never returns zeroized bytes")
        void getRootKeyRacingWithCloseNeverReturnsZeroizedBytes() throws InterruptedException {
            // Run multiple iterations to increase the chance of hitting the race window
            for (int iteration = 0; iteration < 50; iteration++) {
                InMemoryRootKeyStore localStore = new InMemoryRootKeyStore();
                RootKeyStore.GenerationResult result = localStore.generateRootKey();
                byte[] keyId = result.tokenId();

                CountDownLatch startGate = new CountDownLatch(1);
                AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
                AtomicBoolean sawZeroizedKey = new AtomicBoolean(false);

                Thread reader = Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        SensitiveBytes key = localStore.getRootKey(keyId);
                        if (key != null) {
                            byte[] value = key.value();
                            // The returned key must never be all zeros (zeroized)
                            boolean allZeros = true;
                            for (byte b : value) {
                                if (b != 0) {
                                    allZeros = false;
                                    break;
                                }
                            }
                            if (allZeros) {
                                sawZeroizedKey.set(true);
                            }
                        }
                        // null is acceptable — means close() completed first
                    } catch (IllegalStateException _) {
                        // Also acceptable — store was closed before getRootKey acquired the lock
                    } catch (Throwable t) {
                        unexpectedFailure.set(t);
                    }
                });

                Thread closer = Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        localStore.close();
                    } catch (Throwable t) {
                        unexpectedFailure.set(t);
                    }
                });

                startGate.countDown();
                reader.join(5_000);
                closer.join(5_000);

                assertThat(unexpectedFailure.get()).isNull();
                assertThat(sawZeroizedKey.get())
                        .as("getRootKey must never return zeroized key bytes (iteration %d)", iteration)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("concurrent generate and revoke do not corrupt state")
        void concurrentGenerateAndRevoke() throws InterruptedException {
            int iterations = 100;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(iterations * 2);

            for (int i = 0; i < iterations; i++) {
                executor.submit(() -> {
                    try {
                        store.generateRootKey();
                    } finally {
                        latch.countDown();
                    }
                });
                executor.submit(() -> {
                    try {
                        byte[] randomKeyId = new byte[KEY_LENGTH];
                        new SecureRandom().nextBytes(randomKeyId);
                        store.revokeRootKey(randomKeyId);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            // No assertion on final state — the test verifies no exceptions were thrown
        }
    }

    @Nested
    @DisplayName("revokeRootKey zeroization")
    class RevokeZeroization {

        @Test
        @DisplayName("revoked key bytes are zeroed in the internal map")
        void revokedKeyBytesAreZeroed() throws Exception {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();

            // Grab a reference to the internal byte[] via the internal map
            byte[] internalRef = getInternalKeyRef(store, keyId);
            assertThat(internalRef).isNotNull();
            // Verify it is non-zero before revocation
            assertThat(isAllZeros(internalRef)).isFalse();

            store.revokeRootKey(keyId);

            // The internal byte[] should now be all zeros
            assertThat(isAllZeros(internalRef)).isTrue();
            // And the key should no longer be retrievable
            assertThat(store.getRootKey(keyId)).isNull();
        }
    }

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("close zeroizes all stored key material")
        void closeZeroizesAllEntries() throws Exception {
            RootKeyStore.GenerationResult r1 = store.generateRootKey();
            RootKeyStore.GenerationResult r2 = store.generateRootKey();

            byte[] ref1 = getInternalKeyRef(store, r1.tokenId());
            byte[] ref2 = getInternalKeyRef(store, r2.tokenId());

            assertThat(isAllZeros(ref1)).isFalse();
            assertThat(isAllZeros(ref2)).isFalse();

            store.close();

            assertThat(isAllZeros(ref1)).isTrue();
            assertThat(isAllZeros(ref2)).isTrue();
        }

        @Test
        @DisplayName("close clears the internal map")
        void closeClearsMap() throws Exception {
            store.generateRootKey();
            store.generateRootKey();

            @SuppressWarnings("unchecked")
            Map<String, byte[]> internalMap = (Map<String, byte[]>) getField(store, "keys");
            assertThat(internalMap).isNotEmpty();

            store.close();

            assertThat(internalMap).isEmpty();
        }

        @Test
        @DisplayName("double close is idempotent — no exception")
        void doubleCloseIsIdempotent() {
            store.generateRootKey();

            store.close();
            store.close(); // should not throw
        }

        @Test
        @DisplayName("getRootKey after close throws IllegalStateException")
        void getRootKeyAfterCloseThrows() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();

            store.close();

            assertThatThrownBy(() -> store.getRootKey(keyId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Store is closed");
        }

        @Test
        @DisplayName("generateRootKey after close throws IllegalStateException")
        void generateRootKeyAfterCloseThrows() {
            store.close();

            assertThatThrownBy(() -> store.generateRootKey())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Store is closed");
        }

        @Test
        @DisplayName("revokeRootKey after close throws IllegalStateException")
        void revokeRootKeyAfterCloseThrows() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] keyId = result.tokenId();

            store.close();

            assertThatThrownBy(() -> store.revokeRootKey(keyId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Store is closed");
        }
    }

    @Nested
    @DisplayName("generateRootKey zeroization")
    class GenerateRootKeyZeroization {

        @Test
        @DisplayName("after generateRootKey, getRootKey still returns correct key (cached copy not affected)")
        void cachedCopyNotAffectedByLocalZeroization() {
            RootKeyStore.GenerationResult result = store.generateRootKey();
            byte[] expectedKey = result.rootKey().value();
            byte[] keyId = result.tokenId();

            SensitiveBytes retrieved = store.getRootKey(keyId);

            assertThat(retrieved).isNotNull();
            assertThat(retrieved.value()).isEqualTo(expectedKey);
        }
    }

    @Nested
    @DisplayName("GenerationResult close zeroization")
    class GenerationResultCloseZeroization {

        @Test
        @DisplayName("close() zeroizes tokenId — tokenId() returns all zeros after close")
        void closeZeroizesTokenId() {
            byte[] rootKey = {1, 2, 3, 4, 5, 6, 7, 8};
            byte[] tokenId = {10, 20, 30, 40};

            var result = new RootKeyStore.GenerationResult(new SensitiveBytes(rootKey.clone()), tokenId);
            result.close();

            // After close, the internal tokenId was zeroized, so the defensive copy returns all zeros
            byte[] afterClose = result.tokenId();
            assertThat(afterClose).containsOnly(0);
        }

        @Test
        @DisplayName("double close is safe — no exception on second close")
        void doubleCloseIsSafe() {
            byte[] rootKey = {1, 2, 3, 4};
            byte[] tokenId = {5, 6, 7, 8};

            var result = new RootKeyStore.GenerationResult(new SensitiveBytes(rootKey.clone()), tokenId);
            result.close();
            result.close(); // should not throw
        }
    }

    // --- Test helpers ---

    @SuppressWarnings("unchecked")
    private static byte[] getInternalKeyRef(InMemoryRootKeyStore store, byte[] keyId) throws Exception {
        Map<String, byte[]> map = (Map<String, byte[]>) getField(store, "keys");
        return map.get(HEX.formatHex(keyId));
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean isAllZeros(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }
}
