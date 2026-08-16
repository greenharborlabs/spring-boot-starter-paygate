package com.greenharborlabs.paygate.core.protocol;

/**
 * Stable validation failure categories.
 *
 * <p><strong>Compatibility note:</strong> New constants may be added as validation becomes more
 * precise. Downstream exhaustive switches must include a default branch or be updated when
 * upgrading.
 */
public enum ErrorCode {
  INVALID_MACAROON(401),
  INVALID_PREIMAGE(401),
  EXPIRED_CREDENTIAL(401),
  INVALID_SERVICE(401),
  MISSING_REQUEST_CONTEXT(401),
  REVOKED_CREDENTIAL(401),
  LIGHTNING_UNAVAILABLE(503),
  MALFORMED_HEADER(400);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
