package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryCredentialStore")
class InMemoryCredentialStoreTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private InMemoryCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCredentialStore();
    }

    private static L402Credential createTestCredential(String tokenId) {
        byte[] identifier = new byte[66];
        RANDOM.nextBytes(identifier);
        byte[] signature = new byte[32];
        RANDOM.nextBytes(signature);
        byte[] preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);

        Macaroon macaroon = new Macaroon(identifier, "https://example.com", List.of(), signature);
        PaymentPreimage preimage = new PaymentPreimage(preimageBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }

    private static String randomTokenId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    @Nested
    @DisplayName("store and retrieve")
    class StoreAndRetrieve {

        @Test
        @DisplayName("stored credential can be retrieved by tokenId")
        void storedCredentialCanBeRetrieved() {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 3600);

            L402Credential retrieved = store.get(tokenId);
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.tokenId()).isEqualTo(tokenId);
        }

        @Test
        @DisplayName("get returns null for unknown tokenId")
        void returnsNullForUnknownTokenId() {
            L402Credential result = store.get(randomTokenId());

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("multiple credentials stored independently")
        void multipleCredentialsStoredIndependently() {
            String tokenId1 = randomTokenId();
            String tokenId2 = randomTokenId();
            L402Credential cred1 = createTestCredential(tokenId1);
            L402Credential cred2 = createTestCredential(tokenId2);

            store.store(tokenId1, cred1, 3600);
            store.store(tokenId2, cred2, 3600);

            assertThat(store.get(tokenId1)).isNotNull();
            assertThat(store.get(tokenId1).tokenId()).isEqualTo(tokenId1);
            assertThat(store.get(tokenId2)).isNotNull();
            assertThat(store.get(tokenId2).tokenId()).isEqualTo(tokenId2);
        }
    }

    @Nested
    @DisplayName("TTL expiration")
    class TtlExpiration {

        @Test
        @DisplayName("credential with zero TTL expires immediately and returns null on get")
        void zeroTtlExpiresImmediately() throws InterruptedException {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 0);

            // Small delay to ensure expiration
            Thread.sleep(50);

            assertThat(store.get(tokenId)).isNull();
        }

        @Test
        @DisplayName("credential with short TTL returns null after expiry")
        void shortTtlReturnsNullAfterExpiry() throws InterruptedException {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 1); // 1 second TTL

            // Wait for expiration
            Thread.sleep(1200);

            assertThat(store.get(tokenId)).isNull();
        }

        @Test
        @DisplayName("credential with long TTL is still retrievable before expiry")
        void longTtlStillRetrievableBeforeExpiry() {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 3600); // 1 hour TTL

            assertThat(store.get(tokenId)).isNotNull();
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("revoked credential returns null on get")
        void revokedCredentialReturnsNull() {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 3600);
            store.revoke(tokenId);

            assertThat(store.get(tokenId)).isNull();
        }

        @Test
        @DisplayName("revoking unknown tokenId does not throw")
        void revokingUnknownTokenIdDoesNotThrow() {
            store.revoke(randomTokenId());
            // Should complete without exception
        }

        @Test
        @DisplayName("revocation does not affect other credentials")
        void revocationDoesNotAffectOtherCredentials() {
            String tokenId1 = randomTokenId();
            String tokenId2 = randomTokenId();
            store.store(tokenId1, createTestCredential(tokenId1), 3600);
            store.store(tokenId2, createTestCredential(tokenId2), 3600);

            store.revoke(tokenId1);

            assertThat(store.get(tokenId1)).isNull();
            assertThat(store.get(tokenId2)).isNotNull();
        }
    }

    @Nested
    @DisplayName("activeCount")
    class ActiveCount {

        @Test
        @DisplayName("empty store has zero active count")
        void emptyStoreHasZeroCount() {
            assertThat(store.activeCount()).isZero();
        }

        @Test
        @DisplayName("active count increases after storing credentials")
        void countIncreasesAfterStore() {
            String tokenId1 = randomTokenId();
            store.store(tokenId1, createTestCredential(tokenId1), 3600);
            String tokenId2 = randomTokenId();
            store.store(tokenId2, createTestCredential(tokenId2), 3600);

            assertThat(store.activeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("active count decreases after revocation")
        void countDecreasesAfterRevocation() {
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 3600);
            String tokenId2 = randomTokenId();
            store.store(tokenId2, createTestCredential(tokenId2), 3600);

            store.revoke(tokenId);

            assertThat(store.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("active count reflects TTL expiration via lazy eviction")
        void countReflectsLazyEviction() throws InterruptedException {
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 1); // 1 second TTL

            assertThat(store.activeCount()).isEqualTo(1);

            Thread.sleep(1200);

            // Trigger lazy eviction by calling get on the expired entry
            store.get(tokenId);

            assertThat(store.activeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("max size bound")
    class MaxSizeBound {

        @Test
        @DisplayName("constructor rejects non-positive maxSize")
        void rejectsNonPositiveMaxSize() {
            assertThatThrownBy(() -> new InMemoryCredentialStore(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InMemoryCredentialStore(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("store never exceeds maxSize")
        void storeNeverExceedsMaxSize() {
            var boundedStore = new InMemoryCredentialStore(3);

            for (int i = 0; i < 10; i++) {
                String tokenId = randomTokenId();
                boundedStore.store(tokenId, createTestCredential(tokenId), 3600);
            }

            assertThat(boundedStore.activeCount()).isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("evicts expired entries first when at capacity")
        void evictsExpiredEntriesFirst() throws InterruptedException {
            var boundedStore = new InMemoryCredentialStore(3);

            // Fill with short-TTL entries
            String shortTtl1 = randomTokenId();
            String shortTtl2 = randomTokenId();
            boundedStore.store(shortTtl1, createTestCredential(shortTtl1), 1);
            boundedStore.store(shortTtl2, createTestCredential(shortTtl2), 1);

            // One long-TTL entry
            String longTtl = randomTokenId();
            boundedStore.store(longTtl, createTestCredential(longTtl), 3600);

            // Wait for short-TTL entries to expire
            Thread.sleep(1200);

            // Store a new entry — should evict expired entries, not the long-TTL one
            String newTokenId = randomTokenId();
            boundedStore.store(newTokenId, createTestCredential(newTokenId), 3600);

            assertThat(boundedStore.get(longTtl)).isNotNull();
            assertThat(boundedStore.get(newTokenId)).isNotNull();
            assertThat(boundedStore.activeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("evicts oldest entry when at capacity and no expired entries")
        void evictsOldestEntryWhenNoExpired() {
            var boundedStore = new InMemoryCredentialStore(3);

            // Store 3 entries with increasing TTLs (oldest = shortest expiresAt)
            String oldest = randomTokenId();
            boundedStore.store(oldest, createTestCredential(oldest), 100);

            String middle = randomTokenId();
            boundedStore.store(middle, createTestCredential(middle), 200);

            String newest = randomTokenId();
            boundedStore.store(newest, createTestCredential(newest), 300);

            // Store a 4th — should evict the oldest (shortest expiresAt)
            String fourth = randomTokenId();
            boundedStore.store(fourth, createTestCredential(fourth), 400);

            assertThat(boundedStore.get(oldest)).isNull();
            assertThat(boundedStore.get(middle)).isNotNull();
            assertThat(boundedStore.get(newest)).isNotNull();
            assertThat(boundedStore.get(fourth)).isNotNull();
            assertThat(boundedStore.activeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("updating existing entry does not trigger eviction")
        void updatingExistingEntryDoesNotEvict() {
            var boundedStore = new InMemoryCredentialStore(2);

            String tokenId1 = randomTokenId();
            String tokenId2 = randomTokenId();
            boundedStore.store(tokenId1, createTestCredential(tokenId1), 3600);
            boundedStore.store(tokenId2, createTestCredential(tokenId2), 3600);

            // Update existing entry — should not evict
            boundedStore.store(tokenId1, createTestCredential(tokenId1), 7200);

            assertThat(boundedStore.get(tokenId1)).isNotNull();
            assertThat(boundedStore.get(tokenId2)).isNotNull();
            assertThat(boundedStore.activeCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent stores never exceed maxSize")
        void concurrentStoresNeverExceedMaxSize() throws InterruptedException {
            int maxSize = 5;
            int threadCount = 20;
            var boundedStore = new InMemoryCredentialStore(maxSize);
            var barrier = new CyclicBarrier(threadCount);

            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Thread t = Thread.ofVirtual().unstarted(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    String tokenId = randomTokenId();
                    boundedStore.store(tokenId, createTestCredential(tokenId), 3600);
                });
                threads.add(t);
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join(5000);
            }

            assertThat(boundedStore.activeCount()).isLessThanOrEqualTo(maxSize);
        }
    }

    @Nested
    @DisplayName("lazy eviction")
    class LazyEviction {

        @Test
        @DisplayName("expired entry is evicted on get and no longer counted")
        void expiredEntryEvictedOnGet() throws InterruptedException {
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 1);

            Thread.sleep(1200);

            // get triggers lazy eviction
            L402Credential result = store.get(tokenId);
            assertThat(result).isNull();

            // After eviction, activeCount should not include the expired entry
            assertThat(store.activeCount()).isZero();
        }
    }
}
