package com.greenharborlabs.paygate.lightning.lnbits;

import com.greenharborlabs.paygate.core.lightning.LightningException;

/** Thrown when an LNbits API call fails. */
public class LnbitsException extends LightningException {

  private static final String INVALID_BACKEND_DATA = "LNbits returned invalid backend data";
  private static final String REQUEST_FAILED = "LNbits request failed";

  public LnbitsException(String message) {
    super(message);
  }

  public LnbitsException(String message, Throwable cause) {
    super(message, cause);
  }

  static LnbitsException invalidBackendData() {
    return new LnbitsException(INVALID_BACKEND_DATA);
  }

  static LnbitsException invalidBackendData(Throwable cause) {
    return new LnbitsException(INVALID_BACKEND_DATA, cause);
  }

  static LnbitsException requestFailed() {
    return new LnbitsException(REQUEST_FAILED);
  }

  static LnbitsException requestFailed(Throwable cause) {
    return new LnbitsException(REQUEST_FAILED, cause);
  }
}
