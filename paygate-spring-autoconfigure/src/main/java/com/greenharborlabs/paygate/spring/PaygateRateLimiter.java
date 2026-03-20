package com.greenharborlabs.paygate.spring;

/**
 * Rate limiter for L402 challenge issuance.
 *
 * <p>Called before creating an invoice for unauthenticated requests to prevent
 * invoice flooding attacks. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface PaygateRateLimiter {

    /**
     * Attempts to acquire permission to issue a challenge for the given key
     * (typically the client IP address).
     *
     * @param key the rate-limiting key (e.g., remote IP address)
     * @return {@code true} if the request is allowed, {@code false} if rate-limited
     */
    boolean tryAcquire(String key);
}
