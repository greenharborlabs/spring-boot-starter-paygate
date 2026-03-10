package com.greenharborlabs.l402.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.protocol.L402Credential;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed {@link CredentialStore} with per-entry TTL.
 *
 * <p>Each entry expires individually based on the {@code ttlSeconds} provided
 * at store time (derived from the credential's {@code valid_until} caveat).
 * The cache is bounded by a configurable maximum size.
 */
public class CaffeineCredentialStore implements CredentialStore {

    private record CacheEntry(L402Credential credential, long ttlNanos) {}

    private final Cache<String, CacheEntry> cache;

    public CaffeineCredentialStore(int maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry value,
                                                  long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry value,
                                                long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
        long ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
        cache.put(tokenId, new CacheEntry(credential, ttlNanos));
    }

    @Override
    public L402Credential get(String tokenId) {
        CacheEntry entry = cache.getIfPresent(tokenId);
        return entry != null ? entry.credential() : null;
    }

    @Override
    public void revoke(String tokenId) {
        cache.invalidate(tokenId);
    }

    @Override
    public long activeCount() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
