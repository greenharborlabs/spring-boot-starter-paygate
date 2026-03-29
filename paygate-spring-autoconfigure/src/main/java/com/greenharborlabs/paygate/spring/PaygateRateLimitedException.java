package com.greenharborlabs.paygate.spring;

/**
 * Checked exception thrown when a challenge request is rejected by the rate limiter. Callers should
 * respond with HTTP 429 Too Many Requests.
 */
public class PaygateRateLimitedException extends Exception {

  public PaygateRateLimitedException(String message) {
    super(message);
  }
}
