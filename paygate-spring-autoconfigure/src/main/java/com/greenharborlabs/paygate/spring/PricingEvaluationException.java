package com.greenharborlabs.paygate.spring;

/** Indicates that trusted request-price evaluation could not complete safely. */
public final class PricingEvaluationException extends RuntimeException {

  /** Creates a failure with a stable, non-sensitive message. */
  public PricingEvaluationException(String message) {
    super(message);
  }

  /** Creates a failure without exposing the cause message to callers. */
  public PricingEvaluationException(String message, Throwable cause) {
    super(message, cause);
  }
}
