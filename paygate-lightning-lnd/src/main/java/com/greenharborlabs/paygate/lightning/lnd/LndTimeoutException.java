package com.greenharborlabs.paygate.lightning.lnd;

import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;

/** Thrown when an LND gRPC call exceeds its configured deadline. */
public class LndTimeoutException extends LightningTimeoutException {

  public LndTimeoutException(String message) {
    super(message);
  }

  public LndTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
