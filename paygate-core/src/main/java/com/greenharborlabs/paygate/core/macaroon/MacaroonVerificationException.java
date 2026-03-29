package com.greenharborlabs.paygate.core.macaroon;

public class MacaroonVerificationException extends RuntimeException {

  private final VerificationFailureReason reason;

  public MacaroonVerificationException(String message) {
    super(message);
    this.reason = VerificationFailureReason.SIGNATURE_INVALID;
  }

  public MacaroonVerificationException(VerificationFailureReason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Returns the semantic reason for verification failure.
   *
   * <p>All internal code paths produce a non-null reason. This method may return {@code null} only
   * if an external subclass bypasses the provided constructors.
   *
   * @return the failure reason, or {@code null} defensively for external subclassers
   */
  public VerificationFailureReason getReason() {
    return reason;
  }
}
