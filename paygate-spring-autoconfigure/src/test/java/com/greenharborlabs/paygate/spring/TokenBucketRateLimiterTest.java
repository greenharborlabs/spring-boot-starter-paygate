package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 */
@DisplayName("TokenBucketRateLimiter")
class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("allows first N requests up to maxTokens")
    void allowsFirstNRequests() {
        int maxTokens = 5;
        var limiter = new TokenBucketRateLimiter(maxTokens, 1.0);

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
        var limiter = new TokenBucketRateLimiter(maxTokens, 0.001); // very slow refill

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
        var limiter = new TokenBucketRateLimiter(maxTokens, 100.0);

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
        var limiter = new TokenBucketRateLimiter(maxTokens, 0.001);

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
        var limiter = new TokenBucketRateLimiter(maxTokens, 0.001); // effectively no refill during test
        int threadCount = 100;

        var latch = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
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
    @DisplayName("rejects new keys when bucket map reaches hard cap")
    void rejectsNewKeysAtHardCap() throws Exception {
        var limiter = new TokenBucketRateLimiter(5, 1.0);

        // Use reflection to pre-fill the internal map up to MAX_BUCKETS
        Field bucketsField = TokenBucketRateLimiter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var buckets = (ConcurrentHashMap<String, Object>) bucketsField.get(limiter);

        Field maxBucketsField = TokenBucketRateLimiter.class.getDeclaredField("MAX_BUCKETS");
        maxBucketsField.setAccessible(true);
        int maxBuckets = (int) maxBucketsField.get(null);

        // Add a known key first, then fill up to capacity with dummy entries
        assertThat(limiter.tryAcquire("existing-key")).isTrue();

        // Use the Bucket inner class to create dummy entries
        Class<?> bucketClass = Class.forName(TokenBucketRateLimiter.class.getName() + "$Bucket");
        var bucketConstructor = bucketClass.getDeclaredConstructor(double.class, long.class);
        bucketConstructor.setAccessible(true);
        Object dummyBucket = bucketConstructor.newInstance(5.0, System.nanoTime());

        int currentSize = buckets.size();
        for (int i = currentSize; i < maxBuckets; i++) {
            buckets.put("dummy-" + i, dummyBucket);
        }

        assertThat(buckets.size()).isEqualTo(maxBuckets);

        // New key should be rejected
        assertThat(limiter.tryAcquire("brand-new-key")).isFalse();

        // Existing key should still work
        assertThat(limiter.tryAcquire("existing-key")).isTrue();
    }

    @Test
    @DisplayName("map size is consistent after concurrent operations with unique keys")
    void bucketCountConsistentAfterConcurrentOps() throws Exception {
        var limiter = new TokenBucketRateLimiter(5, 1.0);
        int threadCount = 200;
        var latch = new CountDownLatch(1);
        var threads = new Thread[threadCount];
        var successCount = new AtomicInteger();

        // Each thread acquires with a unique key, creating a new bucket
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            threads[i] = Thread.ofVirtual().start(() -> {
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

        // Verify map size matches the number of successful acquisitions.
        // Since bucketCount was eliminated, the map IS the source of truth.
        Field bucketsField = TokenBucketRateLimiter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var buckets = (ConcurrentHashMap<String, Object>) bucketsField.get(limiter);

        assertThat(buckets.size())
                .as("map size should equal the number of successfully created buckets")
                .isEqualTo(successCount.get());

        // All 200 unique keys should have succeeded (well below MAX_BUCKETS)
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("rejects maxTokens < 1")
    void rejectsInvalidMaxTokens() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects refillRatePerSecond <= 0")
    void rejectsInvalidRefillRate() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(10, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(10, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
