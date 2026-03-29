package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CaffeineTokenBucketRateLimiter}. */
@DisplayName("CaffeineTokenBucketRateLimiter")
class CaffeineTokenBucketRateLimiterTest {

  private static Ticker tickerFrom(AtomicLong nanos) {
    return nanos::get;
  }

  @Test
  @DisplayName("allows first N requests up to maxTokens, rejects N+1")
  void happyPath() {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(5, 1.0, 100, nanoTime::get, tickerFrom(nanoTime));

    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire("192.168.1.1"))
          .as("request %d should be allowed", i + 1)
          .isTrue();
    }
    assertThat(limiter.tryAcquire("192.168.1.1")).as("6th request should be rejected").isFalse();
  }

  @Test
  @DisplayName("refills tokens over time without Thread.sleep")
  void refill() {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(2, 100.0, 100, nanoTime::get, tickerFrom(nanoTime));

    // Exhaust all tokens
    limiter.tryAcquire("ip");
    limiter.tryAcquire("ip");
    assertThat(limiter.tryAcquire("ip")).isFalse();

    // Advance 20ms = 2_000_000 nanos => 100 tokens/sec * 0.02s = 2 tokens refilled
    nanoTime.addAndGet(20_000_000L);

    assertThat(limiter.tryAcquire("ip")).isTrue();
  }

  @Test
  @DisplayName("different keys have independent buckets")
  void independentBuckets() {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(2, 0.001, 100, nanoTime::get, tickerFrom(nanoTime));

    limiter.tryAcquire("key-a");
    limiter.tryAcquire("key-a");
    assertThat(limiter.tryAcquire("key-a")).isFalse();

    assertThat(limiter.tryAcquire("key-b")).isTrue();
  }

  @Test
  @DisplayName("LRU eviction: new key succeeds when maxBuckets is full")
  void lruEviction() {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(5, 1.0, 10, nanoTime::get, tickerFrom(nanoTime));

    // Fill 10 buckets
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryAcquire("key-" + i)).isTrue();
    }

    // 11th key should still succeed — LRU entry evicted, new entry created
    assertThat(limiter.tryAcquire("new-key")).isTrue();
  }

  @Test
  @DisplayName("concurrent access: exactly maxTokens succeed")
  void concurrentSafety() throws InterruptedException {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(50, 0.001, 1000, nanoTime::get, tickerFrom(nanoTime));

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
                    if (limiter.tryAcquire("same-key")) {
                      successCount.incrementAndGet();
                    }
                  });
    }

    latch.countDown();
    for (Thread t : threads) {
      t.join();
    }

    assertThat(successCount.get()).isEqualTo(50);
  }

  @Test
  @DisplayName("idle expiry: bucket removed after stale TTL")
  void idleExpiry() {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(5, 1.0, 100, nanoTime::get, tickerFrom(nanoTime));

    limiter.tryAcquire("expiring-key");
    assertThat(limiter.currentBucketCount()).isEqualTo(1);

    // staleTTL = max(60, (5 / 1.0) * 2) = 60 seconds = 60_000_000_000 nanos
    // Advance past that
    nanoTime.addAndGet(61_000_000_000L);
    assertThat(limiter.currentBucketCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("eviction callback invoked on SIZE eviction")
  void evictionCallback() throws InterruptedException {
    var nanoTime = new AtomicLong(0);
    var limiter =
        new CaffeineTokenBucketRateLimiter(5, 1.0, 5, nanoTime::get, tickerFrom(nanoTime));

    var evictedKey = new AtomicReference<String>();
    var evictedReason = new AtomicReference<String>();
    var callbackLatch = new CountDownLatch(1);
    limiter.setEvictionCallback(
        (key, reason) -> {
          evictedKey.set(key);
          evictedReason.set(reason);
          callbackLatch.countDown();
        });

    // Fill to capacity
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire("fill-" + i);
    }

    // Trigger eviction with one more key
    limiter.tryAcquire("new-key");

    // Force Caffeine maintenance and wait for async removal listener
    limiter.currentBucketCount();
    callbackLatch.await(2, TimeUnit.SECONDS);

    assertThat(evictedKey.get()).isNotNull();
    assertThat(evictedReason.get()).isEqualTo("SIZE");
  }

  @Test
  @DisplayName("rejects maxTokens < 1")
  void rejectsInvalidMaxTokens() {
    assertThatThrownBy(
            () ->
                new CaffeineTokenBucketRateLimiter(
                    0, 1.0, 100, System::nanoTime, Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects refillRatePerSecond <= 0")
  void rejectsInvalidRefillRate() {
    assertThatThrownBy(
            () ->
                new CaffeineTokenBucketRateLimiter(
                    10, 0.0, 100, System::nanoTime, Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CaffeineTokenBucketRateLimiter(
                    10, -1.0, 100, System::nanoTime, Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects maxBuckets < 1")
  void rejectsInvalidMaxBuckets() {
    assertThatThrownBy(
            () ->
                new CaffeineTokenBucketRateLimiter(
                    10, 1.0, 0, System::nanoTime, Ticker.systemTicker()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
