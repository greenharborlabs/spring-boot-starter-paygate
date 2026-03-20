package com.greenharborlabs.l402.core.lightning;

/**
 * Base exception for lightning network connectivity and operational failures.
 * <p>
 * Backend implementations (LND, LNbits, etc.) should extend this class
 * with backend-specific subtypes. Callers can catch {@code LightningException}
 * to uniformly handle lightning failures (e.g., returning HTTP 503).
 */
public class LightningException extends RuntimeException {

    public LightningException(String message) {
        super(message);
    }

    public LightningException(String message, Throwable cause) {
        super(message, cause);
    }
}
