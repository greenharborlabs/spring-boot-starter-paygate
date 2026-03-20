package com.greenharborlabs.paygate.core.credential;

import com.greenharborlabs.paygate.core.protocol.L402Credential;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class InMemoryCredentialStore implements CredentialStore, AutoCloseable {

    private static final System.Logger log = System.getLogger(InMemoryCredentialStore.class.getName());
    private static final int DEFAULT_MAX_SIZE = 10_000;
    private static final long DEFAULT_CLEANUP_INTERVAL_SECONDS = 60;

    private final LinkedHashMap<String, CachedCredential> entries;
    private final ReentrantLock storeLock = new ReentrantLock();
    private final int maxSize;
    private final ScheduledExecutorService cleanupExecutor;
    private volatile EvictionListener evictionListener;

    public InMemoryCredentialStore() {
        this(DEFAULT_MAX_SIZE, DEFAULT_CLEANUP_INTERVAL_SECONDS);
    }

    public InMemoryCredentialStore(int maxSize) {
        this(maxSize, DEFAULT_CLEANUP_INTERVAL_SECONDS);
    }

    public InMemoryCredentialStore(int maxSize, long cleanupIntervalSeconds) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
        }
        if (cleanupIntervalSeconds < 0) {
            throw new IllegalArgumentException("cleanupIntervalSeconds must be non-negative, got: " + cleanupIntervalSeconds);
        }
        this.maxSize = maxSize;
        this.entries = new LinkedHashMap<>(maxSize, 0.75f, true);

        if (cleanupIntervalSeconds > 0) {
            this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "l402-credential-cleanup");
                t.setDaemon(true);
                return t;
            });
            this.cleanupExecutor.scheduleAtFixedRate(
                    this::scheduledCleanup,
                    cleanupIntervalSeconds,
                    cleanupIntervalSeconds,
                    TimeUnit.SECONDS
            );
        } else {
            this.cleanupExecutor = null;
        }
    }

    @Override
    public void setEvictionListener(EvictionListener listener) {
        this.evictionListener = listener;
    }

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        CachedCredential cached = new CachedCredential(credential, expiresAt);

        storeLock.lock();
        try {
            // If updating an existing entry, always allow it (updates access order too)
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

            // Still full: evict the least-recently-used entry
            evictLru();
            entries.put(tokenId, cached);
        } finally {
            storeLock.unlock();
        }
    }

    @Override
    public L402Credential get(String tokenId) {
        storeLock.lock();
        try {
            CachedCredential cached = entries.get(tokenId);
            if (cached == null) {
                return null;
            }
            if (cached.isExpired()) {
                entries.remove(tokenId);
                notifyListener(tokenId, EvictionReason.EXPIRED);
                return null;
            }
            // LinkedHashMap.get() already updated access order under lock
            return cached.credential();
        } finally {
            storeLock.unlock();
        }
    }

    @Override
    public void revoke(String tokenId) {
        storeLock.lock();
        try {
            CachedCredential removed = entries.remove(tokenId);
            if (removed != null) {
                notifyListener(tokenId, EvictionReason.REVOKED);
            }
        } finally {
            storeLock.unlock();
        }
    }

    @Override
    public long activeCount() {
        storeLock.lock();
        try {
            return entries.values().stream()
                    .filter(cached -> !cached.isExpired())
                    .count();
        } finally {
            storeLock.unlock();
        }
    }

    @Override
    public void close() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void scheduledCleanup() {
        try {
            storeLock.lock();
            try {
                evictExpired();
            } finally {
                storeLock.unlock();
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Scheduled credential cleanup failed", e);
        }
    }

    private void evictExpired() {
        // Must be called under storeLock
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedCredential> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                String tokenId = entry.getKey();
                iterator.remove();
                notifyListener(tokenId, EvictionReason.EXPIRED);
            }
        }
    }

    private void evictLru() {
        // Must be called under storeLock
        // In access-ordered LinkedHashMap, the first entry is the least-recently-used
        var iterator = entries.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<String, CachedCredential> eldest = iterator.next();
            String tokenId = eldest.getKey();
            iterator.remove();
            notifyListener(tokenId, EvictionReason.CAPACITY);
        }
    }

    private void notifyListener(String tokenId, EvictionReason reason) {
        EvictionListener listener = this.evictionListener;
        if (listener != null) {
            try {
                listener.onEviction(tokenId, reason);
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "Eviction listener threw exception", e);
            }
        }
    }
}
