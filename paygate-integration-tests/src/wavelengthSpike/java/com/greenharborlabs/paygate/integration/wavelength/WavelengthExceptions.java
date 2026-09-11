package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;

class WavelengthException extends LightningException {
  WavelengthException(String message) {
    super(message);
  }

  WavelengthException(String message, Throwable cause) {
    super(message, cause);
  }
}

final class WavelengthTimeoutException extends LightningTimeoutException {
  WavelengthTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}

final class WavelengthProtocolException extends WavelengthException {
  WavelengthProtocolException(String message) {
    super(message);
  }

  WavelengthProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}

final class WavelengthAuthenticationException extends WavelengthException {
  WavelengthAuthenticationException() {
    super("Wavelength authentication failed");
  }
}

final class WavelengthInvoiceNotFoundException extends WavelengthException {
  WavelengthInvoiceNotFoundException() {
    super("Wavelength invoice was not found");
  }
}

final class WavelengthLookupExhaustedException extends WavelengthException {
  WavelengthLookupExhaustedException() {
    super("Wavelength invoice lookup budget was exhausted");
  }
}

final class WavelengthInspectNotFoundException extends WavelengthException {
  WavelengthInspectNotFoundException() {
    super("Wavelength activity was not found by entry identifier");
  }
}

final class WavelengthUpstreamException extends WavelengthException {
  private final WavelengthClient.FailureClassification classification;
  private final int httpStatus;
  private final Integer grpcCode;

  WavelengthUpstreamException(
      String message,
      WavelengthClient.FailureClassification classification,
      int httpStatus,
      Integer grpcCode,
      Throwable cause) {
    super(message, cause);
    this.classification = classification;
    this.httpStatus = httpStatus;
    this.grpcCode = grpcCode;
  }

  WavelengthClient.FailureClassification classification() {
    return classification;
  }

  int httpStatus() {
    return httpStatus;
  }

  Integer grpcCode() {
    return grpcCode;
  }
}
