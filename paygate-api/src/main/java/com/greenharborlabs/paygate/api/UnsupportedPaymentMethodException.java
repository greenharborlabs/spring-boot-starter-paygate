package com.greenharborlabs.paygate.api;

/**
 * Signals that a presented credential uses a payment method the protocol does not support.
 *
 * <p>This is a classified {@link PaymentValidationException} so boundary code can distinguish an
 * unsupported method from other invalid credentials without examining diagnostic text.
 */
public final class UnsupportedPaymentMethodException extends PaymentValidationException {

  /**
   * Creates an unsupported-payment-method failure.
   *
   * @param tokenId the presented credential token identifier, which remains diagnostic-only
   */
  public UnsupportedPaymentMethodException(String tokenId) {
    super(ErrorCode.INVALID, "Unsupported payment method", tokenId);
  }
}
