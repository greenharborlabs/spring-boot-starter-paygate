package com.greenharborlabs.paygate.api;

import java.util.Objects;

/**
 * Sanitized, bounded context for an operational security decision.
 *
 * <p>Callers must supply only normalized HTTP methods and registry-owned endpoint identities. The
 * record intentionally has no field for credentials, caveats, request bodies, headers, exception
 * messages, or other untrusted data.
 *
 * @param reason fixed decision reason
 * @param protocol fixed protocol classification
 * @param method normalized HTTP method, or {@code "_unknown"}
 * @param endpoint canonical registry-owned endpoint identity, or {@code "_unknown"}
 */
public record SecurityDecision(
    SecurityDecisionReason reason,
    SecurityDecisionProtocol protocol,
    String method,
    String endpoint) {

  /** Validates the required, already-sanitized decision fields. */
  public SecurityDecision {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(endpoint, "endpoint");
  }
}
