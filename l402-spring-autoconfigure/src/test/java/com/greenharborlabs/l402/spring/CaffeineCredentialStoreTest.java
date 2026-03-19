package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.EvictionReason;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CaffeineCredentialStore")
class CaffeineCredentialStoreTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaffeineCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new CaffeineCredentialStore(10_000);
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
        void zeroTtlExpiresImmediately() {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 0);

            // Caffeine uses zero TTL as immediate expiry
            assertThat(store.get(tokenId)).isNull();
        }

        @Test
        @DisplayName("credential with short TTL returns null after expiry")
        void shortTtlReturnsNullAfterExpiry() throws InterruptedException {
            String tokenId = randomTokenId();
            L402Credential credential = createTestCredential(tokenId);

            store.store(tokenId, credential, 1); // 1 second TTL

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

        @Test
        @DisplayName("different entries can have different TTLs")
        void perEntryTtl() throws InterruptedException {
            String shortLived = randomTokenId();
            String longLived = randomTokenId();

            store.store(shortLived, createTestCredential(shortLived), 1);
            store.store(longLived, createTestCredential(longLived), 3600);

            Thread.sleep(1200);

            assertThat(store.get(shortLived)).isNull();
            assertThat(store.get(longLived)).isNotNull();
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
        @DisplayName("active count reflects TTL expiration after cleanup")
        void countReflectsExpiration() throws InterruptedException {
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 1); // 1 second TTL

            assertThat(store.activeCount()).isEqualTo(1);

            Thread.sleep(1200);

            // activeCount calls cleanUp internally
            assertThat(store.activeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("max size eviction")
    class MaxSizeEviction {

        @Test
        @DisplayName("cache does not exceed max size")
        void cacheDoesNotExceedMaxSize() {
            var smallStore = new CaffeineCredentialStore(5);

            for (int i = 0; i < 20; i++) {
                String tokenId = randomTokenId();
                smallStore.store(tokenId, createTestCredential(tokenId), 3600);
            }

            // Caffeine eviction is asynchronous; cleanUp forces it
            assertThat(smallStore.activeCount()).isLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("eviction listener")
    class EvictionListenerTests {

        @Test
        @DisplayName("listener called with REVOKED reason on revoke()")
        void listenerCalledOnRevoke() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var capturedId = new AtomicReference<String>();
            var capturedReason = new AtomicReference<EvictionReason>();

            store.setEvictionListener((tokenId, reason) -> {
                capturedId.set(tokenId);
                capturedReason.set(reason);
                latch.countDown();
            });

            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 3600);
            store.revoke(tokenId);

            // Caffeine removal listener is async
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedId.get()).isEqualTo(tokenId);
            assertThat(capturedReason.get()).isEqualTo(EvictionReason.REVOKED);
        }

        @Test
        @DisplayName("listener called with EXPIRED reason on TTL expiry")
        void listenerCalledOnTtlExpiry() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var capturedId = new AtomicReference<String>();
            var capturedReason = new AtomicReference<EvictionReason>();

            store.setEvictionListener((tokenId, reason) -> {
                capturedId.set(tokenId);
                capturedReason.set(reason);
                latch.countDown();
            });

            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 1); // 1 second TTL

            // Trigger cleanup after expiry
            Thread.sleep(1500);
            store.activeCount(); // forces cleanUp

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedId.get()).isEqualTo(tokenId);
            assertThat(capturedReason.get()).isEqualTo(EvictionReason.EXPIRED);
        }

        @Test
        @DisplayName("listener called with CAPACITY reason on size eviction")
        void listenerCalledOnCapacityEviction() throws InterruptedException {
            var smallStore = new CaffeineCredentialStore(2);
            var evictions = new ConcurrentHashMap<String, EvictionReason>();
            var latch = new CountDownLatch(1);

            smallStore.setEvictionListener((tokenId, reason) -> {
                evictions.put(tokenId, reason);
                latch.countDown();
            });

            // Insert more than max size to trigger capacity eviction
            for (int i = 0; i < 10; i++) {
                String tokenId = randomTokenId();
                smallStore.store(tokenId, createTestCredential(tokenId), 3600);
            }
            smallStore.activeCount(); // forces cleanUp

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(evictions.values()).allMatch(r -> r == EvictionReason.CAPACITY);
        }

        @Test
        @DisplayName("listener exception is caught and does not propagate")
        void listenerExceptionIsCaught() throws InterruptedException {
            var latch = new CountDownLatch(1);

            store.setEvictionListener((tokenId, reason) -> {
                latch.countDown();
                throw new RuntimeException("test exception");
            });

            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 3600);
            store.revoke(tokenId);

            // If exception propagated, Caffeine would break; verify listener was called
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            // Verify cache still works after listener exception
            String tokenId2 = randomTokenId();
            store.store(tokenId2, createTestCredential(tokenId2), 3600);
            assertThat(store.get(tokenId2)).isNotNull();
        }

        @Test
        @DisplayName("null listener results in no-op on eviction")
        void nullListenerIsNoOp() {
            // Default state: no listener set
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 3600);
            store.revoke(tokenId);
            store.activeCount(); // forces cleanUp — should not throw
        }

        @Test
        @DisplayName("listener set after cache construction applies to subsequent evictions")
        void listenerSetAfterConstructionWorks() throws InterruptedException {
            String tokenId = randomTokenId();
            store.store(tokenId, createTestCredential(tokenId), 3600);

            // Set listener after entries exist
            var latch = new CountDownLatch(1);
            var capturedReason = new AtomicReference<EvictionReason>();

            store.setEvictionListener((id, reason) -> {
                capturedReason.set(reason);
                latch.countDown();
            });

            store.revoke(tokenId);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedReason.get()).isEqualTo(EvictionReason.REVOKED);
        }
    }
}
