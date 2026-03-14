package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

public class InMemoryCredentialStore implements CredentialStore {

    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final ConcurrentHashMap<String, CachedCredential> entries = new ConcurrentHashMap<>();
    private final ReentrantLock storeLock = new ReentrantLock();
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
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        CachedCredential cached = new CachedCredential(credential, expiresAt);

        storeLock.lock();
        try {
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

            // Still full: evict a random entry
            evictRandom();
            entries.put(tokenId, cached);
        } finally {
            storeLock.unlock();
        }
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

    private void evictRandom() {
        int size = entries.size();
        if (size == 0) {
            return;
        }
        // Iterator-based random sampling: skip a random number of entries
        int skip = ThreadLocalRandom.current().nextInt(size);
        var iterator = entries.keySet().iterator();
        for (int i = 0; i < skip && iterator.hasNext(); i++) {
            iterator.next();
        }
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
