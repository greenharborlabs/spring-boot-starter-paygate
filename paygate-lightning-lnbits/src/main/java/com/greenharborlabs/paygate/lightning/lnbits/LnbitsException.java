package com.greenharborlabs.paygate.lightning.lnbits;

import com.greenharborlabs.paygate.core.lightning.LightningException;

/** Thrown when an LNbits API call fails. */
public class LnbitsException extends LightningException {

  public LnbitsException(String message) {
    super(message);
  }

  public LnbitsException(String message, Throwable cause) {
    super(message, cause);
  }
}
