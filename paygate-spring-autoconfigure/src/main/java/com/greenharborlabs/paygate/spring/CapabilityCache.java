package com.greenharborlabs.paygate.spring;

/**
 * Cache for storing per-token capability strings with individual TTLs.
 *
 * <p>Used to remember which capability a token was issued for, so that
 * subsequent requests carrying the same token can be checked against
 * the endpoint's required capability.
 */
public interface CapabilityCache {

    /**
     * Stores a capability for the given token ID.
     *
     * <p>If {@code capability} is {@code null} or empty, this method is a no-op
     * (endpoints without capabilities should not pollute the cache).
     *
     * @param tokenId    the token identifier (must not be null)
     * @param capability the capability string, or null/empty to skip
     * @param ttlSeconds time-to-live in seconds (must be non-negative)
     * @throws NullPointerException     if tokenId is null
     * @throws IllegalArgumentException if ttlSeconds is negative
     */
    void store(String tokenId, String capability, long ttlSeconds);

    /**
     * Retrieves the capability for the given token ID.
     *
     * @param tokenId the token identifier
     * @return the capability string, or {@code null} if not found or expired
     */
    String get(String tokenId);
}
