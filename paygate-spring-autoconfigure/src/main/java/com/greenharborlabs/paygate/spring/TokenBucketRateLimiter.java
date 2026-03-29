package com.greenharborlabs.paygate.spring;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP-based token-bucket rate limiter for L402 challenge issuance.
 *
 * <p>Each key (typically a client IP) gets an independent bucket with a configurable maximum token
 * count and refill rate. Thread safety is achieved via {@link ConcurrentHashMap#compute}.
 *
 * <p>Stale entries are cleaned lazily: every 1000 {@code tryAcquire} calls, entries older than 2x
 * the full-refill period are removed.
 *
 * <p>When the bucket map reaches {@code maxBuckets}, the entry with the oldest {@code
 * lastRefillNanos} is evicted to make room for the new key.
 */
public class TokenBucketRateLimiter implements PaygateRateLimiter {

  private static final long CLEANUP_INTERVAL = 1000;

  private final int maxTokens;
  private final double refillRatePerSecond;
  private final int maxBuckets;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final AtomicLong callCounter = new AtomicLong();

  /**
   * Creates a new token-bucket rate limiter.
   *
   * @param maxTokens maximum tokens (burst capacity) per key
   * @param refillRatePerSecond tokens added per second
   * @param maxBuckets maximum number of tracked keys before eviction
   */
  public TokenBucketRateLimiter(int maxTokens, double refillRatePerSecond, int maxBuckets) {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
    }
    if (refillRatePerSecond <= 0) {
      throw new IllegalArgumentException(
          "refillRatePerSecond must be > 0, got " + refillRatePerSecond);
    }
    if (maxBuckets < 1) {
      throw new IllegalArgumentException("maxBuckets must be >= 1, got " + maxBuckets);
    }
    this.maxTokens = maxTokens;
    this.refillRatePerSecond = refillRatePerSecond;
    this.maxBuckets = maxBuckets;
  }

  @Override
  public boolean tryAcquire(String key) {
    if (callCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
      cleanupStaleEntries();
    }

    // Phase 1: Best-effort eviction for new keys when at capacity.
    // This check-then-act is not atomic with the subsequent compute(), so under
    // high concurrency the map may transiently exceed maxBuckets by up to the
    // number of concurrent request threads. This is acceptable for the JDK-only
    // fallback; CaffeineTokenBucketRateLimiter provides strict bounds when
    // Caffeine is on the classpath.
    if (!buckets.containsKey(key) && buckets.size() >= maxBuckets) {
      evictOldestEntry();
    }

    // Phase 2: Normal compute — create or update the bucket.
    var allowed = new AtomicBoolean();
    buckets.compute(
        key,
        (_, existing) -> {
          long now = System.nanoTime();
          if (existing == null) {
            // New bucket: start full, consume one token immediately
            allowed.set(true);
            return new Bucket(maxTokens - 1.0, now);
          }

          // Refill tokens based on elapsed time
          double elapsedSeconds = (now - existing.lastRefillNanos) / 1_000_000_000.0;
          double newTokens =
              Math.min(maxTokens, existing.tokens + elapsedSeconds * refillRatePerSecond);

          if (newTokens >= 1.0) {
            allowed.set(true);
            return new Bucket(newTokens - 1.0, now);
          } else {
            // Not enough tokens; update timestamp for partial refill tracking
            allowed.set(false);
            return new Bucket(newTokens, now);
          }
        });

    return allowed.get();
  }

  /** Returns the current number of tracked buckets (keys). */
  public long currentBucketCount() {
    return buckets.size();
  }

  private static final int EVICTION_SAMPLE_SIZE = 8;

  private void evictOldestEntry() {
    String oldestKey = null;
    long oldestNanos = Long.MAX_VALUE;
    int sampled = 0;

    for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
      if (entry.getValue().lastRefillNanos < oldestNanos) {
        oldestNanos = entry.getValue().lastRefillNanos;
        oldestKey = entry.getKey();
      }
      if (++sampled >= EVICTION_SAMPLE_SIZE) {
        break;
      }
    }

    // If another thread cleaned up concurrently, oldestKey may be null — safe to skip.
    if (oldestKey != null) {
      buckets.remove(oldestKey);
    }
  }

  private void cleanupStaleEntries() {
    long now = System.nanoTime();
    // A bucket is stale if it has been idle long enough to fully refill twice over
    double staleThresholdNanos = (maxTokens / refillRatePerSecond) * 2_000_000_000.0;

    // Collect stale keys first, then remove individually. This avoids the
    // previous race where removeIf's side-effecting predicate decremented a
    // separate bucketCount that could drift from the actual map size.
    var staleKeys = new ArrayList<String>();
    buckets.forEach(
        (staleKey, bucket) -> {
          if ((now - bucket.lastRefillNanos) > staleThresholdNanos) {
            staleKeys.add(staleKey);
          }
        });
    for (String staleKey : staleKeys) {
      buckets.remove(staleKey);
    }
  }

  private static final class Bucket {
    final double tokens;
    final long lastRefillNanos;

    Bucket(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }
}
