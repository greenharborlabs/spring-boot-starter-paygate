package com.greenharborlabs.paygate.api;

/**
 * Fixed protocol classification for a {@link SecurityDecision}.
 *
 * <p>The classification is deliberately separate from a client-provided authentication scheme so
 * observer implementations can retain bounded dimensions.
 */
public enum SecurityDecisionProtocol {
  /** The L402 macaroon-and-Lightning protocol. */
  L402,

  /** The Modern Payment Protocol. */
  MPP,

  /** A framework-level decision not attributable to a specific payment protocol. */
  UNKNOWN
}
