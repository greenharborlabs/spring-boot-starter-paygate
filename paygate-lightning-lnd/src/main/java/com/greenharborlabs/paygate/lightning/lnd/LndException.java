package com.greenharborlabs.paygate.lightning.lnd;

import com.greenharborlabs.paygate.core.lightning.LightningException;

/** Thrown when an LND gRPC call fails. */
public class LndException extends LightningException {

  private static final String INVALID_BACKEND_DATA = "LND returned invalid backend data";
  private static final String REQUEST_FAILED = "LND request failed";

  public LndException(String message) {
    super(message);
  }

  public LndException(String message, Throwable cause) {
    super(message, cause);
  }

  static LndException invalidBackendData() {
    return new LndException(INVALID_BACKEND_DATA);
  }

  static LndException requestFailed(Throwable cause) {
    return new LndException(REQUEST_FAILED, cause);
  }
}
