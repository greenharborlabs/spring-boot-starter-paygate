package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.SecurityBounds;

/**
 * Immutable configuration for a single L402-protected endpoint.
 *
 * @param httpMethod HTTP method (e.g. "GET", "POST")
 * @param pathPattern URL path pattern (e.g. "/api/protected")
 * @param priceSats price in satoshis
 * @param timeoutSeconds credential TTL in seconds
 * @param description human-readable description
 * @param pricingStrategy name of the pricing strategy bean, or empty for fixed price
 * @param capability any-of (OR) capability requirement; for example, {@code "search,analyze"}
 *     accepts either name, while a null or wholly blank value means no named capability
 */
public record PaygateEndpointConfig(
    String httpMethod,
    String pathPattern,
    long priceSats,
    long timeoutSeconds,
    String description,
    String pricingStrategy,
    String capability) {

  /**
   * Rejects invalid configured prices while endpoints are being registered.
   *
   * <p>The direct inclusive bounds check is overflow-safe for all {@code long} values. Dynamic
   * pricing is validated again by {@link PaygateChallengeService} after the strategy runs.
   */
  public PaygateEndpointConfig {
    if (!SecurityBounds.isValidPrice(priceSats)) {
      throw new IllegalArgumentException(
          "priceSats must be between "
              + SecurityBounds.MIN_PRICE_SATS
              + " and "
              + SecurityBounds.MAX_PRICE_SATS
              + ", got "
              + priceSats);
    }
  }
}
