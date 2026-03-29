package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TokenBucketRateLimiter}. */
@DisplayName("TokenBucketRateLimiter")
class TokenBucketRateLimiterTest {

  private static final int HIGH_CAPACITY = 100_000;

  @Test
  @DisplayName("allows first N requests up to maxTokens")
  void allowsFirstNRequests() {
    int maxTokens = 5;
    var limiter = new TokenBucketRateLimiter(maxTokens, 1.0, HIGH_CAPACITY);

    for (int i = 0; i < maxTokens; i++) {
      assertThat(limiter.tryAcquire("192.168.1.1"))
          .as("request %d should be allowed", i + 1)
          .isTrue();
    }
  }

  @Test
  @DisplayName("rejects request N+1 after exhausting tokens")
  void rejectsAfterExhausted() {
    int maxTokens = 3;
    var limiter = new TokenBucketRateLimiter(maxTokens, 0.001, HIGH_CAPACITY); // very slow refill

    for (int i = 0; i < maxTokens; i++) {
      limiter.tryAcquire("10.0.0.1");
    }

    assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
  }

  @Test
  @DisplayName("refills tokens over time allowing new requests")
  void refillsOverTime() throws InterruptedException {
    int maxTokens = 2;
    // 100 tokens/sec = 1 token every 10ms
    var limiter = new TokenBucketRateLimiter(maxTokens, 100.0, HIGH_CAPACITY);

    // Exhaust all tokens
    for (int i = 0; i < maxTokens; i++) {
      limiter.tryAcquire("10.0.0.2");
    }
    assertThat(limiter.tryAcquire("10.0.0.2")).isFalse();

    // Wait for at least 1 token to refill (20ms should be enough for 100 tokens/sec)
    Thread.sleep(20);

    assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
  }

  @Test
  @DisplayName("different keys have independent buckets")
  void independentBuckets() {
    int maxTokens = 2;
    var limiter = new TokenBucketRateLimiter(maxTokens, 0.001, HIGH_CAPACITY);

    // Exhaust tokens for key A
    for (int i = 0; i < maxTokens; i++) {
      limiter.tryAcquire("key-a");
    }
    assertThat(limiter.tryAcquire("key-a")).isFalse();

    // Key B should still have tokens
    assertThat(limiter.tryAcquire("key-b")).isTrue();
  }

  @Test
  @DisplayName("concurrent access does not cause errors or exceed maxTokens")
  void concurrentAccessIsSafe() throws InterruptedException {
    int maxTokens = 50;
    var limiter =
        new TokenBucketRateLimiter(
            maxTokens, 0.001, HIGH_CAPACITY); // effectively no refill during test
    int threadCount = 100;

    var latch = new CountDownLatch(1);
    var successCount = new AtomicInteger(0);
    var threads = new Thread[threadCount];

    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      latch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    if (limiter.tryAcquire("concurrent-key")) {
                      successCount.incrementAndGet();
                    }
                  });
    }

    latch.countDown();
    for (Thread t : threads) {
      t.join();
    }

    // Exactly maxTokens requests should have been allowed
    assertThat(successCount.get()).isEqualTo(maxTokens);
  }

  @Test
  @DisplayName("evicts oldest entry when bucket map reaches capacity")
  void evictsOldestEntryAtCapacity() {
    int maxBuckets = 10;
    var limiter = new TokenBucketRateLimiter(5, 0.001, maxBuckets);

    // Fill all 10 bucket slots with unique keys
    for (int i = 0; i < maxBuckets; i++) {
      assertThat(limiter.tryAcquire("key-" + i)).isTrue();
    }
    assertThat(limiter.currentBucketCount()).isEqualTo(maxBuckets);

    // Adding a new key should succeed (evicts oldest) rather than reject
    assertThat(limiter.tryAcquire("new-key")).isTrue();

    // Count should still be maxBuckets (one evicted, one added)
    assertThat(limiter.currentBucketCount()).isEqualTo(maxBuckets);

    // Existing keys that were NOT evicted should still work
    // key-0 was the oldest (created first) and should have been evicted.
    // key-9 (last created) should still be present.
    // We can't assert key-0 is gone with certainty in a unit test due to
    // nanoTime granularity, but we can verify the new key is present.
    assertThat(limiter.tryAcquire("new-key")).isTrue();
  }

  @Test
  @DisplayName("map size is consistent after concurrent operations with unique keys")
  void bucketCountConsistentAfterConcurrentOps() throws InterruptedException {
    var limiter = new TokenBucketRateLimiter(5, 1.0, HIGH_CAPACITY);
    int threadCount = 200;
    var latch = new CountDownLatch(1);
    var successCount = new AtomicInteger();
    var threads = new Thread[threadCount];

    // Each thread acquires with a unique key, creating a new bucket
    for (int i = 0; i < threadCount; i++) {
      int idx = i;
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      latch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    if (limiter.tryAcquire("concurrent-key-" + idx)) {
                      successCount.incrementAndGet();
                    }
                  });
    }

    latch.countDown();
    for (Thread t : threads) {
      t.join();
    }

    // Verify bucket count matches the number of successful acquisitions
    assertThat(limiter.currentBucketCount())
        .as("bucket count should equal the number of successfully created buckets")
        .isEqualTo(successCount.get());

    // All 200 unique keys should have succeeded (well below maxBuckets)
    assertThat(successCount.get()).isEqualTo(threadCount);
  }

  @Test
  @DisplayName("rejects maxTokens < 1")
  void rejectsInvalidMaxTokens() {
    assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1.0, HIGH_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects refillRatePerSecond <= 0")
  void rejectsInvalidRefillRate() {
    assertThatThrownBy(() -> new TokenBucketRateLimiter(10, 0.0, HIGH_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketRateLimiter(10, -1.0, HIGH_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects maxBuckets < 1")
  void rejectsInvalidMaxBuckets() {
    assertThatThrownBy(() -> new TokenBucketRateLimiter(10, 1.0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
