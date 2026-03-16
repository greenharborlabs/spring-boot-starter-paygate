package com.greenharborlabs.l402.core.lightning;

/**
 * Signals that a lightning backend operation exceeded its configured timeout.
 * <p>
 * This is a subtype of {@link LightningException} so callers catching the base
 * type will still handle timeouts uniformly. Code that needs to distinguish
 * timeouts from other failures (e.g., for retry logic) can catch this type
 * specifically.
 */
public class LightningTimeoutException extends LightningException {

    public LightningTimeoutException(String message) {
        super(message);
    }

    public LightningTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
