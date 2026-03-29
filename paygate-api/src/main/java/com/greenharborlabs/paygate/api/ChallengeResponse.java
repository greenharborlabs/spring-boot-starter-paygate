package com.greenharborlabs.paygate.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A protocol's formatted challenge output, returned when payment is required.
 *
 * @param wwwAuthenticateHeader full WWW-Authenticate header value
 * @param protocolScheme which protocol produced this challenge
 * @param bodyData optional protocol-specific data for JSON response body
 */
public record ChallengeResponse(
    String wwwAuthenticateHeader, String protocolScheme, Map<String, Object> bodyData) {

  public ChallengeResponse {
    Objects.requireNonNull(wwwAuthenticateHeader, "wwwAuthenticateHeader must not be null");
    Objects.requireNonNull(protocolScheme, "protocolScheme must not be null");
    bodyData = bodyData == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(bodyData));
  }
}
