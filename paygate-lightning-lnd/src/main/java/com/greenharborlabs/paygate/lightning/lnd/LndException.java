package com.greenharborlabs.paygate.lightning.lnd;

import com.greenharborlabs.paygate.core.lightning.LightningException;

/**
 * Thrown when an LND gRPC call fails.
 */
public class LndException extends LightningException {

    public LndException(String message) {
        super(message);
    }

    public LndException(String message, Throwable cause) {
        super(message, cause);
    }
}
