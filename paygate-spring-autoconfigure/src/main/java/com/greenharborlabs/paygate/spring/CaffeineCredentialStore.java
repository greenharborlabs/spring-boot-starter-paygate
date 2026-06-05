package com.greenharborlabs.paygate.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.EvictionReason;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caffeine-backed {@link CredentialStore} with per-entry TTL.
 *
 * <p>Each entry expires individually based on the {@code ttlSeconds} provided at store time
 * (derived from the credential's {@code valid_until} caveat). The cache is bounded by a
 * configurable maximum size.
 *
 * <p>An optional {@link EvictionListener} can be set via {@link #setEvictionListener} to receive
 * notifications when entries are removed. The listener field is volatile, so it can be set after
 * cache construction and will take effect on the next eviction.
 */
public class CaffeineCredentialStore implements CredentialStore, AutoCloseable {

  private static final System.Logger log =
      System.getLogger(CaffeineCredentialStore.class.getName());

  private static final class CacheEntry {

    private final L402Credential credential;
    private final long ttlNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean removed;

    private CacheEntry(L402Credential credential, long ttlNanos) {
      this.credential = credential;
      this.ttlNanos = ttlNanos;
    }

    private long ttlNanos() {
      return ttlNanos;
    }

    private L402Credential copyIfActive(Runnable beforeCopy) {
      lock.lock();
      try {
        if (removed) {
          return null;
        }
        beforeCopy.run();
        return credential.copy();
      } finally {
        lock.unlock();
      }
    }

    private void destroyOnce() {
      lock.lock();
      try {
        if (!removed) {
          removed = true;
          credential.destroy();
        }
      } finally {
        lock.unlock();
      }
    }

    private L402Credential credentialForTesting() {
      return credential;
    }
  }

  private volatile EvictionListener evictionListener;
  private volatile Runnable beforeCopyForTesting = () -> {};

  private final Cache<String, CacheEntry> cache;

  public CaffeineCredentialStore(int maxSize) {
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .executor(Runnable::run)
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
            .removalListener(this::handleRemoval)
            .build();
  }

  @Override
  public void setEvictionListener(EvictionListener listener) {
    this.evictionListener = listener;
  }

  @Override
  public void store(String tokenId, L402Credential credential, long ttlSeconds) {
    long ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
    L402Credential retained = credential.copy();
    try {
      cache.put(tokenId, new CacheEntry(retained, ttlNanos));
    } catch (RuntimeException | Error e) {
      retained.destroy();
      throw e;
    }
  }

  @Override
  public L402Credential get(String tokenId) {
    CacheEntry entry = cache.getIfPresent(tokenId);
    return entry != null ? entry.copyIfActive(beforeCopyForTesting) : null;
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

  @Override
  public void close() {
    cache.invalidateAll();
    cache.cleanUp();
  }

  L402Credential peekRetainedForTesting(String tokenId) {
    CacheEntry entry = cache.getIfPresent(tokenId);
    return entry != null ? entry.credentialForTesting() : null;
  }

  void setBeforeCopyForTesting(Runnable beforeCopyForTesting) {
    this.beforeCopyForTesting = beforeCopyForTesting != null ? beforeCopyForTesting : () -> {};
  }

  private void handleRemoval(String tokenId, CacheEntry entry, RemovalCause cause) {
    destroyCached(entry);

    EvictionReason reason = mapCause(cause);
    if (reason == null) {
      return;
    }

    EvictionListener listener = this.evictionListener;
    if (listener == null) {
      return;
    }

    try {
      listener.onEviction(tokenId, reason);
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Eviction listener threw for tokenId={0}, reason={1}: {2}",
          tokenId,
          reason,
          e.getMessage());
    }
  }

  private static void destroyCached(CacheEntry entry) {
    if (entry != null) {
      entry.destroyOnce();
    }
  }

  private static EvictionReason mapCause(RemovalCause cause) {
    return switch (cause) {
      case EXPIRED -> EvictionReason.EXPIRED;
      case SIZE -> EvictionReason.CAPACITY;
      case EXPLICIT -> EvictionReason.REVOKED;
      case COLLECTED, REPLACED -> null;
    };
  }
}
