package com.greenharborlabs.paygate.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.EvictionReason;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed {@link CredentialStore} with per-entry TTL.
 *
 * <p>Each entry expires individually based on the {@code ttlSeconds} provided at store time
 * (derived from the credential's {@code valid_until} caveat). The cache is bounded by a
 * configurable maximum size.
 *
 * <p>An optional {@link EvictionListener} can be set via {@link #setEvictionListener} to receive
 * notifications when entries are removed. The listener is invoked asynchronously on Caffeine's
 * maintenance executor thread. The listener field is volatile, so it can be set after cache
 * construction and will take effect on the next eviction.
 */
public class CaffeineCredentialStore implements CredentialStore {

  private static final System.Logger log =
      System.getLogger(CaffeineCredentialStore.class.getName());

  private record CacheEntry(L402Credential credential, long ttlNanos) {}

  private volatile EvictionListener evictionListener;

  private final Cache<String, CacheEntry> cache;

  public CaffeineCredentialStore(int maxSize) {
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
            .removalListener(
                (String key, CacheEntry value, RemovalCause cause) -> {
                  EvictionListener listener = this.evictionListener;
                  if (listener == null) {
                    return;
                  }
                  EvictionReason reason = mapCause(cause);
                  if (reason == null) {
                    return;
                  }
                  try {
                    listener.onEviction(key, reason);
                  } catch (Exception e) {
                    log.log(
                        System.Logger.Level.WARNING,
                        "Eviction listener threw for tokenId={0}, reason={1}: {2}",
                        key,
                        reason,
                        e.getMessage());
                  }
                })
            .build();
  }

  @Override
  public void setEvictionListener(EvictionListener listener) {
    this.evictionListener = listener;
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

  private static EvictionReason mapCause(RemovalCause cause) {
    return switch (cause) {
      case EXPIRED -> EvictionReason.EXPIRED;
      case SIZE -> EvictionReason.CAPACITY;
      case EXPLICIT -> EvictionReason.REVOKED;
      case COLLECTED, REPLACED -> null;
    };
  }
}
