package com.greenharborlabs.paygate.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Caffeine-backed token-bucket rate limiter for challenge issuance.
 *
 * <p>Each key (typically a client IP) gets an independent token bucket stored
 * in a Caffeine cache. Caffeine handles bounded size (LRU eviction when full)
 * and automatic expiry of idle entries, eliminating manual cleanup logic.
 *
 * <p>Thread safety is achieved via a single {@code cache.asMap().compute()}
 * call per {@code tryAcquire}, which atomically creates or updates the bucket.
 *
 * <p>An injectable time source ({@link LongSupplier} for nanoTime) controls
 * bucket refill timestamps, enabling deterministic unit tests. A separate
 * Caffeine {@link Ticker} controls cache expiry timing.
 */
public class CaffeineTokenBucketRateLimiter implements PaygateRateLimiter {

    private static final System.Logger log = System.getLogger(CaffeineTokenBucketRateLimiter.class.getName());

    private final int maxTokens;
    private final double refillRatePerSecond;
    private final LongSupplier nanoTimeSource;
    private final Cache<String, Bucket> cache;

    private volatile BiConsumer<String, String> evictionCallback;

    /**
     * Creates a new Caffeine-backed token-bucket rate limiter.
     *
     * @param maxTokens            maximum tokens (burst capacity) per key; must be >= 1
     * @param refillRatePerSecond  tokens added per second; must be > 0
     * @param maxBuckets           maximum number of tracked keys (Caffeine LRU bound); must be >= 1
     * @param nanoTimeSource       time source for bucket refill calculations (e.g., System::nanoTime)
     * @param ticker               Caffeine ticker for cache expiry (e.g., Ticker.systemTicker())
     */
    public CaffeineTokenBucketRateLimiter(int maxTokens, double refillRatePerSecond,
                                          int maxBuckets, LongSupplier nanoTimeSource,
                                          Ticker ticker) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0, got " + refillRatePerSecond);
        }
        if (maxBuckets < 1) {
            throw new IllegalArgumentException("maxBuckets must be >= 1, got " + maxBuckets);
        }

        this.maxTokens = maxTokens;
        this.refillRatePerSecond = refillRatePerSecond;
        this.nanoTimeSource = nanoTimeSource;

        long staleTtlSeconds = Math.max(60, (long) ((maxTokens / refillRatePerSecond) * 2));

        this.cache = Caffeine.newBuilder()
                .maximumSize(maxBuckets)
                .expireAfterAccess(Duration.ofSeconds(staleTtlSeconds))
                .ticker(ticker)
                .removalListener((String key, Bucket value, RemovalCause cause) -> {
                    BiConsumer<String, String> callback = this.evictionCallback;
                    if (callback == null) {
                        return;
                    }
                    try {
                        callback.accept(key, cause.name());
                    } catch (Exception e) {
                        log.log(System.Logger.Level.WARNING,
                                "Eviction callback threw for cause={0}: {1}",
                                cause, e.getMessage());
                    }
                })
                .build();
    }

    @Override
    public boolean tryAcquire(String key) {
        var result = cache.asMap().compute(key, (_, existing) -> {
            long now = nanoTimeSource.getAsLong();

            if (existing == null) {
                // New bucket: start full, consume one token immediately
                return new Bucket(maxTokens - 1.0, now, true);
            }

            // Refill tokens based on elapsed time
            double elapsedSeconds = (now - existing.lastRefillNanos) / 1_000_000_000.0;
            double newTokens = Math.min(maxTokens, existing.tokens + elapsedSeconds * refillRatePerSecond);

            if (newTokens >= 1.0) {
                return new Bucket(newTokens - 1.0, now, true);
            } else {
                // Not enough tokens; update timestamp for partial refill tracking
                return new Bucket(newTokens, now, false);
            }
        });

        return result.allowed;
    }

    /**
     * Returns the estimated number of active buckets in the cache.
     * Triggers Caffeine maintenance (cleanup of expired entries) first.
     */
    public long currentBucketCount() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /**
     * Sets a callback invoked when Caffeine evicts a bucket entry.
     *
     * @param callback receives the evicted key and the removal cause name (e.g., "SIZE", "EXPIRED")
     */
    public void setEvictionCallback(BiConsumer<String, String> callback) {
        this.evictionCallback = callback;
    }

    private static final class Bucket {
        final double tokens;
        final long lastRefillNanos;
        final boolean allowed;

        Bucket(double tokens, long lastRefillNanos, boolean allowed) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
            this.allowed = allowed;
        }
    }
}
