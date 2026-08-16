package com.greenharborlabs.paygate.spring;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.util.pattern.PathPattern;

/**
 * A payment-protected endpoint selected for a request.
 *
 * @param config the selected endpoint policy configuration
 * @param routePattern the exact canonical registered route pattern
 * @param policyMethod the explicit normalized HTTP method registration selected for the policy
 */
public record ResolvedEndpoint(
    PaygateEndpointConfig config,
    String routePattern,
    String policyMethod,
    RequestMappingInfo mappingInfo,
    HandlerMethod handlerMethod,
    HandlerMapping sourceMapping,
    int sourceOrder,
    PathPattern parsedPattern) {

  /**
   * Creates a result for a manually registered endpoint.
   *
   * <p>The MVC mapping metadata is unavailable for manual registrations. This constructor is
   * retained for source compatibility with integrations that construct a resolved endpoint.
   */
  public ResolvedEndpoint(PaygateEndpointConfig config, String routePattern, String policyMethod) {
    this(config, routePattern, policyMethod, null, null, null, Integer.MAX_VALUE, null);
  }
}
