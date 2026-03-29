package com.greenharborlabs.paygate.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed {@link CapabilityCache} with per-entry TTL.
 *
 * <p>Each entry expires individually based on the {@code ttlSeconds} provided at store time. The
 * cache is bounded by a configurable maximum size; Caffeine evicts least-recently-used entries when
 * the limit is reached.
 */
public class CaffeineCapabilityCache implements CapabilityCache {

  private record CacheEntry(String capability, long ttlNanos) {}

  private final Cache<String, CacheEntry> cache;

  public CaffeineCapabilityCache(int maxSize) {
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfter(
                new Expiry<String, CacheEntry>() {
                  @Override
                  public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterUpdate(
                      String key, CacheEntry value, long currentTime, long currentDuration) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterRead(
                      String key, CacheEntry value, long currentTime, long currentDuration) {
                    return currentDuration;
                  }
                })
            .build();
  }

  @Override
  public void store(String tokenId, String capability, long ttlSeconds) {
    Objects.requireNonNull(tokenId, "tokenId must not be null");
    if (ttlSeconds < 0) {
      throw new IllegalArgumentException("ttlSeconds must not be negative: " + ttlSeconds);
    }
    if (capability == null || capability.isEmpty()) {
      return;
    }
    long ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
    cache.put(tokenId, new CacheEntry(capability, ttlNanos));
  }

  @Override
  public String get(String tokenId) {
    CacheEntry entry = cache.getIfPresent(tokenId);
    return entry != null ? entry.capability() : null;
  }
}
