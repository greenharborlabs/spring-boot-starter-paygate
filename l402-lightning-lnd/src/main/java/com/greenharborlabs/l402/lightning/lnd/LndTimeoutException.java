package com.greenharborlabs.l402.lightning.lnd;

import com.greenharborlabs.l402.core.lightning.LightningTimeoutException;

/**
 * Thrown when an LND gRPC call exceeds its configured deadline.
 */
public class LndTimeoutException extends LightningTimeoutException {

    public LndTimeoutException(String message) {
        super(message);
    }

    public LndTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
