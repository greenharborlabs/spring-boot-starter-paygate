package com.greenharborlabs.paygate.lightning.lnbits;

import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;

/**
 * Thrown when an LNbits HTTP request exceeds its configured timeout.
 */
public class LnbitsTimeoutException extends LightningTimeoutException {

    public LnbitsTimeoutException(String message) {
        super(message);
    }

    public LnbitsTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
