package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.credential.EvictionReason;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  private static String preimageHex(L402Credential credential) {
    return credential.preimage().toHex();
  }

  private static void assertCredentialUsable(L402Credential credential) {
    assertThat(preimageHex(credential)).hasSize(64);
  }

  private static void assertCredentialDestroyed(L402Credential credential) {
    assertThatThrownBy(() -> credential.preimage().toHex())
        .isInstanceOf(IllegalStateException.class);
  }

  private static void awaitUnchecked(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
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
    @DisplayName("store retains private copy, not caller-owned credential")
    void storeRetainsPrivateCopy() {
      String tokenId = randomTokenId();
      L402Credential credential = createTestCredential(tokenId);

      store.store(tokenId, credential, 3600);
      credential.destroy();

      L402Credential retrieved = store.get(tokenId);
      assertThat(retrieved).isNotNull();
      assertCredentialUsable(retrieved);
    }

    @Test
    @DisplayName("get returns caller-owned copy, not retained cache credential")
    void getReturnsCallerOwnedCopy() {
      String tokenId = randomTokenId();
      L402Credential credential = createTestCredential(tokenId);

      store.store(tokenId, credential, 3600);
      L402Credential retained = store.peekRetainedForTesting(tokenId);
      L402Credential retrieved = store.get(tokenId);

      assertThat(retrieved).isNotSameAs(retained);
      assertThat(preimageHex(retrieved)).isEqualTo(preimageHex(retained));

      retrieved.destroy();

      assertCredentialDestroyed(retrieved);
      assertCredentialUsable(retained);
      assertCredentialUsable(store.get(tokenId));
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
    @DisplayName("TTL expiry destroys retained credential and leaves caller credential usable")
    void ttlExpiryDestroysRetainedCredential() throws InterruptedException {
      String tokenId = randomTokenId();
      L402Credential credential = createTestCredential(tokenId);

      store.store(tokenId, credential, 1);
      L402Credential retained = store.peekRetainedForTesting(tokenId);

      Thread.sleep(1200);

      assertThat(store.activeCount()).isZero();
      assertThat(store.get(tokenId)).isNull();
      assertCredentialDestroyed(retained);
      assertCredentialUsable(credential);
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
    @DisplayName("revoke destroys retained credential and leaves caller-owned copies usable")
    void revokeDestroysRetainedCredentialOnly() {
      String tokenId = randomTokenId();
      L402Credential credential = createTestCredential(tokenId);

      store.store(tokenId, credential, 3600);
      L402Credential retained = store.peekRetainedForTesting(tokenId);
      L402Credential retrieved = store.get(tokenId);

      store.revoke(tokenId);
      store.activeCount();

      assertThat(store.get(tokenId)).isNull();
      assertCredentialDestroyed(retained);
      assertCredentialUsable(credential);
      assertCredentialUsable(retrieved);
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

    @Test
    @DisplayName("get copying retained credential wins race with revoke")
    void getCopyingRetainedCredentialWinsRaceWithRevoke() throws InterruptedException {
      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 3600);
      L402Credential retained = store.peekRetainedForTesting(tokenId);

      var copyStarted = new CountDownLatch(1);
      var allowCopy = new CountDownLatch(1);
      store.setBeforeCopyForTesting(
          () -> {
            copyStarted.countDown();
            awaitUnchecked(allowCopy);
          });

      var retrieved = new AtomicReference<L402Credential>();
      var getFailure = new AtomicReference<Throwable>();
      Thread getter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      retrieved.set(store.get(tokenId));
                    } catch (Throwable t) {
                      getFailure.set(t);
                    }
                  });

      assertThat(copyStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var revokeFailure = new AtomicReference<Throwable>();
      Thread revoker =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      store.revoke(tokenId);
                    } catch (Throwable t) {
                      revokeFailure.set(t);
                    }
                  });

      allowCopy.countDown();
      getter.join();
      revoker.join();
      store.setBeforeCopyForTesting(null);

      assertThat(getFailure.get()).isNull();
      assertThat(revokeFailure.get()).isNull();
      assertThat(retrieved.get()).isNotNull();
      assertCredentialUsable(retrieved.get());
      assertCredentialDestroyed(retained);
      assertThat(store.get(tokenId)).isNull();
    }

    @Test
    @DisplayName("revoke winning before get copy returns null")
    void revokeWinningBeforeGetCopyReturnsNull() {
      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 3600);
      L402Credential retained = store.peekRetainedForTesting(tokenId);

      store.revoke(tokenId);
      store.activeCount();

      assertThat(store.get(tokenId)).isNull();
      assertCredentialDestroyed(retained);
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

    @Test
    @DisplayName("size eviction destroys evicted retained credentials and leaves originals usable")
    void sizeEvictionDestroysRetainedCredentialsOnly() {
      var smallStore = new CaffeineCredentialStore(2);
      var originals = new ConcurrentHashMap<String, L402Credential>();
      var retained = new ConcurrentHashMap<String, L402Credential>();

      for (int i = 0; i < 2; i++) {
        String tokenId = randomTokenId();
        L402Credential credential = createTestCredential(tokenId);
        originals.put(tokenId, credential);
        smallStore.store(tokenId, credential, 3600);
        retained.put(tokenId, smallStore.peekRetainedForTesting(tokenId));
      }

      for (int i = 0; i < 10; i++) {
        String tokenId = randomTokenId();
        L402Credential credential = createTestCredential(tokenId);
        originals.put(tokenId, credential);
        smallStore.store(tokenId, credential, 3600);
      }

      smallStore.activeCount();

      long destroyed =
          retained.entrySet().stream()
              .filter(entry -> smallStore.peekRetainedForTesting(entry.getKey()) == null)
              .peek(entry -> assertCredentialDestroyed(entry.getValue()))
              .count();

      assertThat(destroyed).isGreaterThanOrEqualTo(1);
      assertThat(smallStore.activeCount()).isLessThanOrEqualTo(2);
      originals.values().forEach(CaffeineCredentialStoreTest::assertCredentialUsable);
    }
  }

  @Nested
  @DisplayName("replacement")
  class Replacement {

    @Test
    @DisplayName("replacement destroys old retained credential without public eviction event")
    void replacementDestroysOldRetainedCredentialWithoutEvictionEvent() {
      var evictions = new ConcurrentHashMap<String, EvictionReason>();
      store.setEvictionListener(evictions::put);

      String tokenId = randomTokenId();
      L402Credential first = createTestCredential(tokenId);
      L402Credential second = createTestCredential(tokenId);

      store.store(tokenId, first, 3600);
      L402Credential oldRetained = store.peekRetainedForTesting(tokenId);
      L402Credential oldReturnedCopy = store.get(tokenId);
      String secondPreimage = preimageHex(second);

      store.store(tokenId, second, 3600);
      store.activeCount();

      L402Credential current = store.get(tokenId);
      assertThat(current).isNotNull();
      assertThat(preimageHex(current)).isEqualTo(secondPreimage);
      assertCredentialDestroyed(oldRetained);
      assertCredentialUsable(oldReturnedCopy);
      assertThat(evictions).isEmpty();
    }

    @Test
    @DisplayName("get copying old retained credential wins race with replacement")
    void getCopyingOldRetainedCredentialWinsRaceWithReplacement() throws InterruptedException {
      var evictions = new ConcurrentHashMap<String, EvictionReason>();
      store.setEvictionListener(evictions::put);

      String tokenId = randomTokenId();
      L402Credential first = createTestCredential(tokenId);
      L402Credential second = createTestCredential(tokenId);

      store.store(tokenId, first, 3600);
      L402Credential oldRetained = store.peekRetainedForTesting(tokenId);
      String firstPreimage = preimageHex(first);
      String secondPreimage = preimageHex(second);

      var copyStarted = new CountDownLatch(1);
      var allowCopy = new CountDownLatch(1);
      store.setBeforeCopyForTesting(
          () -> {
            copyStarted.countDown();
            awaitUnchecked(allowCopy);
          });

      var retrieved = new AtomicReference<L402Credential>();
      var getFailure = new AtomicReference<Throwable>();
      Thread getter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      retrieved.set(store.get(tokenId));
                    } catch (Throwable t) {
                      getFailure.set(t);
                    }
                  });

      assertThat(copyStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var replaceFailure = new AtomicReference<Throwable>();
      Thread replacer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      store.store(tokenId, second, 3600);
                    } catch (Throwable t) {
                      replaceFailure.set(t);
                    }
                  });

      allowCopy.countDown();
      getter.join();
      replacer.join();
      store.setBeforeCopyForTesting(null);

      L402Credential current = store.get(tokenId);
      assertThat(getFailure.get()).isNull();
      assertThat(replaceFailure.get()).isNull();
      assertThat(retrieved.get()).isNotNull();
      assertThat(preimageHex(retrieved.get())).isEqualTo(firstPreimage);
      assertCredentialUsable(retrieved.get());
      assertCredentialDestroyed(oldRetained);
      assertThat(current).isNotNull();
      assertThat(preimageHex(current)).isEqualTo(secondPreimage);
      assertThat(evictions).isEmpty();
    }
  }

  @Nested
  @DisplayName("close")
  class Close {

    @Test
    @DisplayName("close invalidates all entries, destroys retained credentials, and is idempotent")
    void closeInvalidatesAllAndDestroysRetainedCredentials() {
      var retained = new ConcurrentHashMap<String, L402Credential>();
      for (int i = 0; i < 3; i++) {
        String tokenId = randomTokenId();
        store.store(tokenId, createTestCredential(tokenId), 3600);
        retained.put(tokenId, store.peekRetainedForTesting(tokenId));
      }

      store.close();
      store.close();

      assertThat(store.activeCount()).isZero();
      retained.values().forEach(CaffeineCredentialStoreTest::assertCredentialDestroyed);
    }

    @Test
    @DisplayName("get copying retained credential wins race with close")
    void getCopyingRetainedCredentialWinsRaceWithClose() throws InterruptedException {
      String tokenId = randomTokenId();
      store.store(tokenId, createTestCredential(tokenId), 3600);
      L402Credential retained = store.peekRetainedForTesting(tokenId);

      var copyStarted = new CountDownLatch(1);
      var allowCopy = new CountDownLatch(1);
      store.setBeforeCopyForTesting(
          () -> {
            copyStarted.countDown();
            awaitUnchecked(allowCopy);
          });

      var retrieved = new AtomicReference<L402Credential>();
      var getFailure = new AtomicReference<Throwable>();
      Thread getter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      retrieved.set(store.get(tokenId));
                    } catch (Throwable t) {
                      getFailure.set(t);
                    }
                  });

      assertThat(copyStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var closeFailure = new AtomicReference<Throwable>();
      Thread closer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      store.close();
                    } catch (Throwable t) {
                      closeFailure.set(t);
                    }
                  });

      allowCopy.countDown();
      getter.join();
      closer.join();
      store.setBeforeCopyForTesting(null);

      assertThat(getFailure.get()).isNull();
      assertThat(closeFailure.get()).isNull();
      if (retrieved.get() != null) {
        assertCredentialUsable(retrieved.get());
      }
      assertCredentialDestroyed(retained);
      assertThat(store.activeCount()).isZero();
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

      store.setEvictionListener(
          (tokenId, reason) -> {
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

      store.setEvictionListener(
          (tokenId, reason) -> {
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

      smallStore.setEvictionListener(
          (tokenId, reason) -> {
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

      store.setEvictionListener(
          (tokenId, reason) -> {
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

      store.setEvictionListener(
          (id, reason) -> {
            capturedReason.set(reason);
            latch.countDown();
          });

      store.revoke(tokenId);

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(capturedReason.get()).isEqualTo(EvictionReason.REVOKED);
    }
  }
}
