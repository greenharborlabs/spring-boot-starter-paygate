package com.greenharborlabs.paygate.core.credential;

import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class InMemoryCredentialStore implements CredentialStore, AutoCloseable {

  private static final System.Logger log =
      System.getLogger(InMemoryCredentialStore.class.getName());
  private static final int DEFAULT_MAX_SIZE = 10_000;
  private static final long DEFAULT_CLEANUP_INTERVAL_SECONDS = 60;

  private final LinkedHashMap<String, CachedCredential> entries;
  private final ReentrantLock storeLock = new ReentrantLock();
  private final int maxSize;
  private final ScheduledExecutorService cleanupExecutor;
  private volatile EvictionListener evictionListener;
  private boolean closed;

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
      throw new IllegalArgumentException(
          "cleanupIntervalSeconds must be non-negative, got: " + cleanupIntervalSeconds);
    }
    this.maxSize = maxSize;
    this.entries = new LinkedHashMap<>(Math.min(maxSize, 256), 0.75f, true);

    if (cleanupIntervalSeconds > 0) {
      this.cleanupExecutor =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "l402-credential-cleanup");
                t.setDaemon(true);
                return t;
              });
      this.cleanupExecutor.scheduleAtFixedRate(
          this::scheduledCleanup, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
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
    Instant expiresAt = expirationFor(ttlSeconds);
    CachedCredential cached = new CachedCredential(credential.copy(), expiresAt);
    var evictionEvents = new ArrayList<EvictionEvent>();

    storeLock.lock();
    try {
      if (closed) {
        destroyCached(cached);
        throw new IllegalStateException("Credential store is closed");
      }
      // If updating an existing entry, always allow it (updates access order too)
      if (entries.containsKey(tokenId)) {
        destroyCached(entries.put(tokenId, cached));
      } else if (entries.size() < maxSize) {
        // If under capacity, store directly.
        entries.put(tokenId, cached);
      } else {
        // At capacity: evict expired entries first, then the least-recently-used entry.
        evictExpired(evictionEvents);
        if (entries.size() == maxSize) {
          evictLru(evictionEvents);
        }
        entries.put(tokenId, cached);
      }
    } finally {
      storeLock.unlock();
    }
    notifyListeners(evictionEvents);
  }

  @Override
  public L402Credential get(String tokenId) {
    EvictionEvent evictionEvent = null;
    L402Credential result;
    storeLock.lock();
    try {
      if (closed) {
        return null;
      }
      CachedCredential cached = entries.get(tokenId);
      if (cached == null) {
        return null;
      }
      if (cached.isExpired()) {
        destroyCached(entries.remove(tokenId));
        evictionEvent = new EvictionEvent(tokenId, EvictionReason.EXPIRED);
        result = null;
      } else {
        // LinkedHashMap.get() already updated access order under lock
        result = cached.credential().copy();
      }
    } finally {
      storeLock.unlock();
    }
    if (evictionEvent != null) {
      notifyListener(evictionEvent);
    }
    return result;
  }

  @Override
  public void revoke(String tokenId) {
    EvictionEvent evictionEvent = null;
    storeLock.lock();
    try {
      if (closed) {
        return;
      }
      CachedCredential removed = entries.remove(tokenId);
      if (removed != null) {
        destroyCached(removed);
        evictionEvent = new EvictionEvent(tokenId, EvictionReason.REVOKED);
      }
    } finally {
      storeLock.unlock();
    }
    if (evictionEvent != null) {
      notifyListener(evictionEvent);
    }
  }

  @Override
  public long activeCount() {
    storeLock.lock();
    try {
      if (closed) {
        return 0;
      }
      return entries.values().stream().filter(cached -> !cached.isExpired()).count();
    } finally {
      storeLock.unlock();
    }
  }

  @Override
  public void close() {
    storeLock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      entries.values().forEach(InMemoryCredentialStore::destroyCached);
      entries.clear();
    } finally {
      storeLock.unlock();
    }
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
      var evictionEvents = new ArrayList<EvictionEvent>();
      storeLock.lock();
      try {
        if (closed) {
          return;
        }
        evictExpired(evictionEvents);
      } finally {
        storeLock.unlock();
      }
      notifyListeners(evictionEvents);
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "Scheduled credential cleanup failed", e);
    }
  }

  /**
   * Test support only: returns the retained cache-owned entry without copying it.
   *
   * <p>This is intentionally package-private and must not be added to {@link CredentialStore}.
   */
  CachedCredential peekRetainedForTesting(String tokenId) {
    storeLock.lock();
    try {
      if (closed) {
        return null;
      }
      return entries.get(tokenId);
    } finally {
      storeLock.unlock();
    }
  }

  private void evictExpired(List<EvictionEvent> evictionEvents) {
    // Must be called under storeLock
    var iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, CachedCredential> entry = iterator.next();
      if (entry.getValue().isExpired()) {
        String tokenId = entry.getKey();
        CachedCredential cached = entry.getValue();
        iterator.remove();
        destroyCached(cached);
        evictionEvents.add(new EvictionEvent(tokenId, EvictionReason.EXPIRED));
      }
    }
  }

  private void evictLru(List<EvictionEvent> evictionEvents) {
    // Must be called under storeLock
    // In access-ordered LinkedHashMap, the first entry is the least-recently-used
    var iterator = entries.entrySet().iterator();
    if (iterator.hasNext()) {
      Map.Entry<String, CachedCredential> eldest = iterator.next();
      String tokenId = eldest.getKey();
      CachedCredential cached = eldest.getValue();
      iterator.remove();
      destroyCached(cached);
      evictionEvents.add(new EvictionEvent(tokenId, EvictionReason.CAPACITY));
    }
  }

  private static void destroyCached(CachedCredential cached) {
    if (cached != null) {
      cached.credential().destroy();
    }
  }

  private static Instant expirationFor(long ttlSeconds) {
    try {
      Instant now = Instant.now();
      long expirationEpochSecond = Math.addExact(now.getEpochSecond(), ttlSeconds);
      return Instant.ofEpochSecond(expirationEpochSecond, now.getNano());
    } catch (ArithmeticException | java.time.DateTimeException e) {
      throw new IllegalArgumentException(
          "ttlSeconds produces an invalid expiration: " + ttlSeconds, e);
    }
  }

  private void notifyListeners(List<EvictionEvent> evictionEvents) {
    evictionEvents.forEach(this::notifyListener);
  }

  private void notifyListener(EvictionEvent evictionEvent) {
    EvictionListener listener = this.evictionListener;
    if (listener != null) {
      try {
        listener.onEviction(evictionEvent.tokenId(), evictionEvent.reason());
      } catch (Exception e) {
        log.log(System.Logger.Level.WARNING, "Eviction listener threw exception", e);
      }
    }
  }

  private record EvictionEvent(String tokenId, EvictionReason reason) {}
}
