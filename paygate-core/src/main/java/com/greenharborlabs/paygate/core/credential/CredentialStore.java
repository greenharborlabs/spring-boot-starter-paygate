package com.greenharborlabs.paygate.core.credential;

import com.greenharborlabs.paygate.core.protocol.L402Credential;

/**
 * Cache for validated L402 credentials.
 *
 * <p>Ownership rules:
 *
 * <ul>
 *   <li>{@link #store(String, L402Credential, long)} callers keep ownership of the credential they
 *       pass in. Retaining implementations must store a private copy and must not destroy the
 *       caller-owned object.
 *   <li>{@link #get(String)} returns a caller-owned credential. The returned credential must remain
 *       usable independently of later cache eviction, expiry, capacity removal, or revocation.
 *   <li>Removal paths such as {@link #revoke(String)}, expiry cleanup, capacity eviction, and
 *       implementation shutdown must destroy only private cache-owned credentials retained by the
 *       implementation.
 * </ul>
 */
public interface CredentialStore {
  /**
   * Stores a validated credential for up to {@code ttlSeconds}.
   *
   * <p>The caller keeps ownership of {@code credential}. Implementations that retain the value must
   * retain a private {@link L402Credential#copy()} and destroy only that private copy on removal.
   */
  void store(String tokenId, L402Credential credential, long ttlSeconds);

  /**
   * Returns a caller-owned credential copy, or {@code null} when absent or expired.
   *
   * <p>The returned credential remains valid independently of later cache eviction. Callers are
   * responsible for destroying it when no longer needed.
   */
  L402Credential get(String tokenId);

  /**
   * Removes a credential from the store.
   *
   * <p>Implementations must destroy only private cache-owned retained credentials, never
   * caller-owned credentials previously passed to {@link #store(String, L402Credential, long)} or
   * returned by {@link #get(String)}.
   */
  void revoke(String tokenId);

  long activeCount();

  @FunctionalInterface
  interface EvictionListener {
    void onEviction(String tokenId, EvictionReason reason);
  }

  default void setEvictionListener(EvictionListener listener) {
    // no-op by default
  }
}
