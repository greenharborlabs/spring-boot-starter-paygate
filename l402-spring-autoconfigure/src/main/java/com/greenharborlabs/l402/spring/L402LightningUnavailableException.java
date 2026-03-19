package com.greenharborlabs.l402.spring;

/**
 * Checked exception thrown when the Lightning backend is unhealthy or
 * a Lightning operation (invoice creation, key generation) fails.
 * Callers should respond with HTTP 503 Service Unavailable.
 */
public class L402LightningUnavailableException extends Exception {

    public L402LightningUnavailableException(String message) {
        super(message);
    }

    public L402LightningUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
