package com.greenharborlabs.paygate.api;

import java.util.Objects;

/**
 * Thrown when a payment credential fails validation.
 *
 * <p>Each instance carries an {@link ErrorCode} that determines the HTTP status code and RFC 9457
 * problem type URI for the error response. The optional {@code tokenId} identifies the specific
 * credential that failed validation. Logs should use only a short token-ID prefix and must never
 * include the full credential value.
 */
public class PaymentValidationException extends RuntimeException {

  /** Classifies the validation failure and maps it to an HTTP status and problem type URI. */
  public enum ErrorCode {
    MALFORMED(400, "https://paymentauth.org/problems/malformed"),
    INVALID(402, "https://paymentauth.org/problems/invalid"),
    INSUFFICIENT(402, "https://paymentauth.org/problems/insufficient"),
    UNAVAILABLE(503, "https://paymentauth.org/problems/unavailable");

    private final int httpStatus;
    private final String problemTypeUri;

    ErrorCode(int httpStatus, String problemTypeUri) {
      this.httpStatus = httpStatus;
      this.problemTypeUri = problemTypeUri;
    }

    public int httpStatus() {
      return httpStatus;
    }

    public String problemTypeUri() {
      return problemTypeUri;
    }
  }

  private final ErrorCode errorCode;
  private final String tokenId;
  private final String problemTypeUri;
  private final int httpStatus;

  /**
   * Creates a validation exception without a token ID.
   *
   * @param errorCode the error classification, must not be {@code null}
   * @param message diagnostic detail retained for compatibility but not exposed publicly
   */
  public PaymentValidationException(ErrorCode errorCode, String message) {
    this(errorCode, message, (String) null);
  }

  /**
   * Creates a validation exception with an optional token ID.
   *
   * @param errorCode the error classification, must not be {@code null}
   * @param message diagnostic detail retained for compatibility but not exposed publicly
   * @param tokenId the token identifier, may be {@code null}
   */
  public PaymentValidationException(ErrorCode errorCode, String message, String tokenId) {
    super(publicMessage(errorCode));
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.tokenId = tokenId;
    this.problemTypeUri = errorCode.problemTypeUri();
    this.httpStatus = errorCode.httpStatus();
  }

  /**
   * Creates a validation exception with a cause.
   *
   * @param errorCode the error classification, must not be {@code null}
   * @param message diagnostic detail retained for compatibility but not exposed publicly
   * @param cause the underlying cause
   */
  public PaymentValidationException(ErrorCode errorCode, String message, Throwable cause) {
    super(publicMessage(errorCode), cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.tokenId = null;
    this.problemTypeUri = errorCode.problemTypeUri();
    this.httpStatus = errorCode.httpStatus();
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public String getTokenId() {
    return tokenId;
  }

  public String getProblemTypeUri() {
    return problemTypeUri;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  private static String publicMessage(ErrorCode errorCode) {
    return "Payment validation failed: "
        + Objects.requireNonNull(errorCode, "errorCode must not be null").name();
  }
}
