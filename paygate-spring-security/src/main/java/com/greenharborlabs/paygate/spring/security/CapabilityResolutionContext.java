package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable context carrying all information needed for multi-strategy capability resolution.
 *
 * @param tokenId the token identifier, or {@code null} if unavailable
 * @param serviceName the configured service name, or {@code null} if not set
 * @param l402Credential the parsed L402 credential, or {@code null} for non-L402 paths (e.g. MPP)
 * @param requestMetadata request-scoped metadata (path, method, capability, etc.); never {@code
 *     null}
 */
public record CapabilityResolutionContext(
    String tokenId,
    String serviceName,
    L402Credential l402Credential,
    Map<String, String> requestMetadata) {

  public CapabilityResolutionContext {
    Objects.requireNonNull(requestMetadata, "requestMetadata must not be null");
    requestMetadata = Map.copyOf(requestMetadata);
  }
}
