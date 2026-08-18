package com.greenharborlabs.paygate.api;

/**
 * Fixed, low-cardinality reasons for security decisions that may be observed operationally.
 *
 * <p>The values are intentionally closed so observers cannot accidentally publish sensitive or
 * attacker-controlled detail as a metric or log dimension.
 */
public enum SecurityDecisionReason {
  /** A credential's integrity-protected payment amount does not cover the current request. */
  INSUFFICIENT_PRICE,

  /** A credential lacks payment-price evidence required by the endpoint policy. */
  MISSING_PRICE_EVIDENCE,

  /** A route-stable legacy credential predates an increase in the configured route price. */
  ROUTE_STABLE_PRICE_INCREASE,

  /** A size-priced endpoint rejected a request body with a non-identity content encoding. */
  COMPRESSED_BODY_REJECTED,

  /** A signed caveat key was padded but resolved to a registered canonical verifier key. */
  PADDED_CAVEAT_KEY_NORMALIZED
}
