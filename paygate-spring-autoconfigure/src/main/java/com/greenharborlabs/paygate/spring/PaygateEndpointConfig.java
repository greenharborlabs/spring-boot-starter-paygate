package com.greenharborlabs.paygate.spring;

/**
 * Immutable configuration for a single L402-protected endpoint.
 *
 * @param httpMethod       HTTP method (e.g. "GET", "POST")
 * @param pathPattern      URL path pattern (e.g. "/api/protected")
 * @param priceSats        price in satoshis
 * @param timeoutSeconds   credential TTL in seconds
 * @param description      human-readable description
 * @param pricingStrategy  name of the pricing strategy bean, or empty for fixed price
 * @param capability       capability required for this endpoint, or empty for no specific capability
 */
public record PaygateEndpointConfig(
        String httpMethod,
        String pathPattern,
        long priceSats,
        long timeoutSeconds,
        String description,
        String pricingStrategy,
        String capability
) {
}
