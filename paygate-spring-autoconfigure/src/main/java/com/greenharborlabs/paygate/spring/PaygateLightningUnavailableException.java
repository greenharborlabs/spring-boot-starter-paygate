package com.greenharborlabs.paygate.spring;

/**
 * Checked exception thrown when the Lightning backend is unhealthy or
 * a Lightning operation (invoice creation, key generation) fails.
 * Callers should respond with HTTP 503 Service Unavailable.
 */
public class PaygateLightningUnavailableException extends Exception {

    public PaygateLightningUnavailableException(String message) {
        super(message);
    }

    public PaygateLightningUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
