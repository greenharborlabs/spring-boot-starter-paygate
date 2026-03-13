package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCredentialStore implements CredentialStore {

    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final ConcurrentHashMap<String, CachedCredential> entries = new ConcurrentHashMap<>();
    private final int maxSize;

    public InMemoryCredentialStore() {
        this(DEFAULT_MAX_SIZE);
    }

    public InMemoryCredentialStore(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
        }
        this.maxSize = maxSize;
    }

    @Override
    public synchronized void store(String tokenId, L402Credential credential, long ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        CachedCredential cached = new CachedCredential(credential, expiresAt);

        // If updating an existing entry, always allow it
        if (entries.containsKey(tokenId)) {
            entries.put(tokenId, cached);
            return;
        }

        // If under capacity, store directly
        if (entries.size() < maxSize) {
            entries.put(tokenId, cached);
            return;
        }

        // At capacity: evict expired entries first
        evictExpired();

        if (entries.size() < maxSize) {
            entries.put(tokenId, cached);
            return;
        }

        // Still full: evict the entry nearest to expiry (earliest expiresAt)
        evictNearestExpiry();
        entries.put(tokenId, cached);
    }

    @Override
    public L402Credential get(String tokenId) {
        CachedCredential cached = entries.get(tokenId);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired()) {
            entries.remove(tokenId);
            return null;
        }
        return cached.credential();
    }

    @Override
    public void revoke(String tokenId) {
        entries.remove(tokenId);
    }

    @Override
    public long activeCount() {
        long count = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
            } else {
                count++;
            }
        }
        return count;
    }

    private void evictExpired() {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
            }
        }
    }

    private void evictNearestExpiry() {
        Map.Entry<String, CachedCredential> oldest = null;
        for (var entry : entries.entrySet()) {
            if (oldest == null || entry.getValue().expiresAt().isBefore(oldest.getValue().expiresAt())) {
                oldest = entry;
            }
        }
        if (oldest != null) {
            entries.remove(oldest.getKey());
        }
    }
}
