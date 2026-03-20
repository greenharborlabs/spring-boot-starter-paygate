package com.greenharborlabs.paygate.spring;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP-based token-bucket rate limiter for L402 challenge issuance.
 *
 * <p>Each key (typically a client IP) gets an independent bucket with a
 * configurable maximum token count and refill rate. Thread safety is
 * achieved via {@link ConcurrentHashMap#compute}.
 *
 * <p>Stale entries are cleaned lazily: every 1000 {@code tryAcquire} calls,
 * entries older than 2x the full-refill period are removed.
 */
public class TokenBucketRateLimiter implements PaygateRateLimiter {

    private static final long CLEANUP_INTERVAL = 1000;
    private static final int MAX_BUCKETS = 100_000;

    private final int maxTokens;
    private final double refillRatePerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong callCounter = new AtomicLong();

    /**
     * Creates a new token-bucket rate limiter.
     *
     * @param maxTokens            maximum tokens (burst capacity) per key
     * @param refillRatePerSecond  tokens added per second
     */
    public TokenBucketRateLimiter(int maxTokens, double refillRatePerSecond) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0, got " + refillRatePerSecond);
        }
        this.maxTokens = maxTokens;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (callCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            cleanupStaleEntries();
        }

        var allowed = new AtomicBoolean();
        buckets.compute(key, (_, existing) -> {
            long now = System.nanoTime();
            if (existing == null) {
                // Check map size directly — eliminates the race between a separate
                // bucketCount AtomicInteger and actual map mutations. size() on
                // ConcurrentHashMap is O(counterCells) which is negligible here.
                if (buckets.size() >= MAX_BUCKETS) {
                    return null; // don't create bucket — at capacity
                }
                // New bucket: start full, consume one token immediately
                allowed.set(true);
                return new Bucket(maxTokens - 1.0, now);
            }

            // Refill tokens based on elapsed time
            double elapsedSeconds = (now - existing.lastRefillNanos) / 1_000_000_000.0;
            double newTokens = Math.min(maxTokens, existing.tokens + elapsedSeconds * refillRatePerSecond);

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

    private void cleanupStaleEntries() {
        long now = System.nanoTime();
        // A bucket is stale if it has been idle long enough to fully refill twice over
        double staleThresholdNanos = (maxTokens / refillRatePerSecond) * 2_000_000_000.0;

        // Collect stale keys first, then remove individually. This avoids the
        // previous race where removeIf's side-effecting predicate decremented a
        // separate bucketCount that could drift from the actual map size.
        var staleKeys = new ArrayList<String>();
        buckets.forEach((key, bucket) -> {
            if ((now - bucket.lastRefillNanos) > staleThresholdNanos) {
                staleKeys.add(key);
            }
        });
        for (String key : staleKeys) {
            buckets.remove(key);
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
