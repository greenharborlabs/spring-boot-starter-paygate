package com.greenharborlabs.l402.spring;

/**
 * Checked exception thrown when a challenge request is rejected by the
 * rate limiter. Callers should respond with HTTP 429 Too Many Requests.
 */
public class L402RateLimitedException extends Exception {

    public L402RateLimitedException(String message) {
        super(message);
    }
}
