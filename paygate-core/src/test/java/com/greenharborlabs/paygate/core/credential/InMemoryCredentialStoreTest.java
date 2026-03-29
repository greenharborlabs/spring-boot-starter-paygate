package com.greenharborlabs.paygate.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryCredentialStore")
class InMemoryCredentialStoreTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();

  private InMemoryCredentialStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryCredentialStore(10_000, 0);
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
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
      try (var boundedStore = new InMemoryCredentialStore(3, 0)) {
        for (int i = 0; i < 10; i++) {
          String tokenId = randomTokenId();
          boundedStore.store(tokenId, createTestCredential(tokenId), 3600);
        }

        assertThat(boundedStore.activeCount()).isLessThanOrEqualTo(3);
      }
    }

    @Test
    @DisplayName("evicts expired entries first when at capacity")
    void evictsExpiredEntriesFirst() throws InterruptedException {
      try (var boundedStore = new InMemoryCredentialStore(3, 0)) {
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
    }

    @Test
    @DisplayName("evicts LRU entry when at capacity and no expired entries")
    void evictsLruEntryWhenNoExpired() {
      try (var boundedStore = new InMemoryCredentialStore(3, 0)) {
        String first = randomTokenId();
        boundedStore.store(first, createTestCredential(first), 3600);

        String second = randomTokenId();
        boundedStore.store(second, createTestCredential(second), 3600);

        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        // Store a 4th — should evict the LRU (first)
        String fourth = randomTokenId();
        boundedStore.store(fourth, createTestCredential(fourth), 3600);

        assertThat(boundedStore.get(first)).isNull();
        assertThat(boundedStore.get(second)).isNotNull();
        assertThat(boundedStore.get(third)).isNotNull();
        assertThat(boundedStore.get(fourth)).isNotNull();
        assertThat(boundedStore.activeCount()).isEqualTo(3);
      }
    }

    @Test
    @DisplayName("LRU ordering: accessing an entry prevents its eviction")
    void lruOrderingAccessPreventsEviction() {
      try (var boundedStore = new InMemoryCredentialStore(3, 0)) {
        String first = randomTokenId();
        boundedStore.store(first, createTestCredential(first), 3600);

        String second = randomTokenId();
        boundedStore.store(second, createTestCredential(second), 3600);

        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        // Access the first entry — moves it to most-recently-used
        boundedStore.get(first);

        // Store a 4th — should evict second (now LRU), not first
        String fourth = randomTokenId();
        boundedStore.store(fourth, createTestCredential(fourth), 3600);

        assertThat(boundedStore.get(first)).isNotNull();
        assertThat(boundedStore.get(second)).isNull();
        assertThat(boundedStore.get(third)).isNotNull();
        assertThat(boundedStore.get(fourth)).isNotNull();
      }
    }

    @Test
    @DisplayName("updating existing entry does not trigger eviction")
    void updatingExistingEntryDoesNotEvict() {
      try (var boundedStore = new InMemoryCredentialStore(2, 0)) {
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

    @Test
    @DisplayName("expired entries evicted before LRU when at capacity")
    void expiredBeforeLruEviction() throws InterruptedException {
      try (var boundedStore = new InMemoryCredentialStore(3, 0)) {
        String first = randomTokenId();
        boundedStore.store(first, createTestCredential(first), 1); // will expire

        String second = randomTokenId();
        boundedStore.store(second, createTestCredential(second), 3600);

        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        Thread.sleep(1200);

        // Store a 4th — expired first should be evicted, second and third survive
        String fourth = randomTokenId();
        boundedStore.store(fourth, createTestCredential(fourth), 3600);

        assertThat(boundedStore.get(first)).isNull();
        assertThat(boundedStore.get(second)).isNotNull();
        assertThat(boundedStore.get(third)).isNotNull();
        assertThat(boundedStore.get(fourth)).isNotNull();
      }
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
      try (var boundedStore = new InMemoryCredentialStore(maxSize, 0)) {
        var barrier = new CyclicBarrier(threadCount);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          Thread t =
              Thread.ofVirtual()
                  .unstarted(
                      () -> {
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

    @Test
    @DisplayName("concurrent get and store operations are safe")
    void concurrentGetAndStoreAreSafe() throws InterruptedException {
      int maxSize = 10;
      int threadCount = 50;
      try (var boundedStore = new InMemoryCredentialStore(maxSize, 0)) {
        var barrier = new CyclicBarrier(threadCount);
        List<String> tokenIds = new ArrayList<>();
        for (int i = 0; i < maxSize; i++) {
          String tokenId = randomTokenId();
          tokenIds.add(tokenId);
          boundedStore.store(tokenId, createTestCredential(tokenId), 3600);
        }

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          final int idx = i;
          Thread t =
              Thread.ofVirtual()
                  .unstarted(
                      () -> {
                        try {
                          barrier.await();
                        } catch (Exception e) {
                          throw new RuntimeException(e);
                        }
                        if (idx % 2 == 0) {
                          String tokenId = randomTokenId();
                          boundedStore.store(tokenId, createTestCredential(tokenId), 3600);
                        } else {
                          boundedStore.get(tokenIds.get(idx % tokenIds.size()));
                        }
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

  @Nested
  @DisplayName("periodic cleanup")
  class PeriodicCleanup {

    @Test
    @DisplayName("cleanup thread removes expired entries")
    void cleanupThreadRemovesExpiredEntries() throws InterruptedException {
      // Use 1-second cleanup interval for testing
      try (var cleanupStore = new InMemoryCredentialStore(100, 1)) {
        String tokenId = randomTokenId();
        cleanupStore.store(tokenId, createTestCredential(tokenId), 1);

        assertThat(cleanupStore.activeCount()).isEqualTo(1);

        // Wait for expiration + cleanup cycle
        Thread.sleep(2500);

        // The cleanup should have removed the expired entry
        assertThat(cleanupStore.get(tokenId)).isNull();
      }
    }

    @Test
    @DisplayName("cleanup is disabled when interval is 0")
    void cleanupDisabledWhenZero() {
      // Should not throw, and close should be safe
      try (var noCleanupStore = new InMemoryCredentialStore(100, 0)) {
        String tokenId = randomTokenId();
        noCleanupStore.store(tokenId, createTestCredential(tokenId), 3600);
        assertThat(noCleanupStore.get(tokenId)).isNotNull();
      }
    }

    @Test
    @DisplayName("close shuts down cleanup executor")
    void closeShutdownsExecutor() {
      var cleanupStore = new InMemoryCredentialStore(100, 1);
      String tokenId = randomTokenId();
      cleanupStore.store(tokenId, createTestCredential(tokenId), 3600);

      cleanupStore.close();

      // After close, store should still be queryable (just no more cleanup)
      assertThat(cleanupStore.get(tokenId)).isNotNull();
    }

    @Test
    @DisplayName("close is safe to call multiple times")
    void closeIsSafeMultipleTimes() {
      var cleanupStore = new InMemoryCredentialStore(100, 1);
      cleanupStore.close();
      cleanupStore.close(); // Should not throw
    }

    @Test
    @DisplayName("close is safe when cleanup is disabled")
    void closeIsSafeWhenCleanupDisabled() {
      var noCleanupStore = new InMemoryCredentialStore(100, 0);
      noCleanupStore.close(); // Should not throw
    }
  }

  @Nested
  @DisplayName("eviction listener")
  class EvictionListenerTests {

    @Test
    @DisplayName("listener is called on expired eviction via get")
    void listenerCalledOnExpiredEvictionViaGet() throws InterruptedException {
      var events = new CopyOnWriteArrayList<EvictionEvent>();
      store.setEvictionListener(
          (tokenId, reason) -> events.add(new EvictionEvent(tokenId, reason)));

      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 1);

      Thread.sleep(1200);

      store.get(tokenId);

      assertThat(events).hasSize(1);
      assertThat(events.getFirst().tokenId()).isEqualTo(tokenId);
      assertThat(events.getFirst().reason()).isEqualTo(EvictionReason.EXPIRED);
    }

    @Test
    @DisplayName("listener is called on revoke")
    void listenerCalledOnRevoke() {
      var events = new CopyOnWriteArrayList<EvictionEvent>();
      store.setEvictionListener(
          (tokenId, reason) -> events.add(new EvictionEvent(tokenId, reason)));

      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 3600);
      store.revoke(tokenId);

      assertThat(events).hasSize(1);
      assertThat(events.getFirst().tokenId()).isEqualTo(tokenId);
      assertThat(events.getFirst().reason()).isEqualTo(EvictionReason.REVOKED);
    }

    @Test
    @DisplayName("listener is called with CAPACITY reason on LRU eviction")
    void listenerCalledOnLruEviction() {
      try (var boundedStore = new InMemoryCredentialStore(2, 0)) {
        var events = new CopyOnWriteArrayList<EvictionEvent>();
        boundedStore.setEvictionListener(
            (tokenId, reason) -> events.add(new EvictionEvent(tokenId, reason)));

        String first = randomTokenId();
        boundedStore.store(first, createTestCredential(first), 3600);

        String second = randomTokenId();
        boundedStore.store(second, createTestCredential(second), 3600);

        // Store a 3rd — should evict first (LRU)
        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().tokenId()).isEqualTo(first);
        assertThat(events.getFirst().reason()).isEqualTo(EvictionReason.CAPACITY);
      }
    }

    @Test
    @DisplayName("listener is called with EXPIRED reason during capacity eviction")
    void listenerCalledWithExpiredReasonDuringCapacityEviction() throws InterruptedException {
      try (var boundedStore = new InMemoryCredentialStore(2, 0)) {
        var events = new CopyOnWriteArrayList<EvictionEvent>();
        boundedStore.setEvictionListener(
            (tokenId, reason) -> events.add(new EvictionEvent(tokenId, reason)));

        String expired = randomTokenId();
        boundedStore.store(expired, createTestCredential(expired), 1);

        String alive = randomTokenId();
        boundedStore.store(alive, createTestCredential(alive), 3600);

        Thread.sleep(1200);

        // Store a 3rd — should evict expired entry
        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().tokenId()).isEqualTo(expired);
        assertThat(events.getFirst().reason()).isEqualTo(EvictionReason.EXPIRED);
      }
    }

    @Test
    @DisplayName("throwing listener does not block eviction")
    void throwingListenerDoesNotBlockEviction() {
      try (var boundedStore = new InMemoryCredentialStore(2, 0)) {
        boundedStore.setEvictionListener(
            (_, _) -> {
              throw new RuntimeException("Listener failure");
            });

        String first = randomTokenId();
        boundedStore.store(first, createTestCredential(first), 3600);

        String second = randomTokenId();
        boundedStore.store(second, createTestCredential(second), 3600);

        // Store a 3rd — listener throws but eviction should still complete
        String third = randomTokenId();
        boundedStore.store(third, createTestCredential(third), 3600);

        assertThat(boundedStore.get(first)).isNull();
        assertThat(boundedStore.get(third)).isNotNull();
        assertThat(boundedStore.activeCount()).isEqualTo(2);
      }
    }

    @Test
    @DisplayName("revoking unknown tokenId does not invoke listener")
    void revokingUnknownDoesNotInvokeListener() {
      AtomicBoolean called = new AtomicBoolean(false);
      store.setEvictionListener((_, _) -> called.set(true));

      store.revoke(randomTokenId());

      assertThat(called).isFalse();
    }

    @Test
    @DisplayName("null listener does not cause NPE")
    void nullListenerDoesNotCauseNpe() {
      store.setEvictionListener(null);

      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 3600);
      store.revoke(tokenId);
      // Should complete without NPE
    }

    @Test
    @DisplayName("default setEvictionListener on CredentialStore interface is no-op")
    void defaultSetEvictionListenerIsNoOp() {
      CredentialStore credStore = store;
      // Should not throw
      credStore.setEvictionListener((_, _) -> {});
    }
  }

  @Nested
  @DisplayName("AutoCloseable")
  class AutoCloseableTests {

    @Test
    @DisplayName("implements AutoCloseable")
    void implementsAutoCloseable() {
      assertThat(store).isInstanceOf(AutoCloseable.class);
    }
  }

  record EvictionEvent(String tokenId, EvictionReason reason) {}
}
