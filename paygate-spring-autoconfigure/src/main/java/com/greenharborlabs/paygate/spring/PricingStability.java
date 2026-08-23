package com.greenharborlabs.paygate.spring;

/**
 * Declares whether a protected route-and-method pair has a price independent of request input.
 *
 * <p>{@link #REQUEST_DEPENDENT} is the safe default. {@link #ROUTE_STABLE} must be selected only
 * when the endpoint price cannot vary by query, headers, body, identity, or other request data.
 */
public enum PricingStability {
  /**
   * The price can vary by request and legacy credentials without signed price evidence are unsafe.
   */
  REQUEST_DEPENDENT,

  /** The price is fixed for this route and method and supports bounded legacy evidence lookup. */
  ROUTE_STABLE
}
