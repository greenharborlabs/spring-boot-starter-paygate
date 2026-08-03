package com.greenharborlabs.paygate.spring;

/**
 * A payment-protected endpoint selected for a request.
 *
 * @param config the selected endpoint policy configuration
 * @param routePattern the exact canonical registered route pattern
 * @param policyMethod the explicit normalized HTTP method registration selected for the policy
 */
public record ResolvedEndpoint(
    PaygateEndpointConfig config, String routePattern, String policyMethod) {}
