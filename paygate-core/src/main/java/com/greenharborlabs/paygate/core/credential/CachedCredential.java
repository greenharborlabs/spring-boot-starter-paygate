package com.greenharborlabs.paygate.core.credential;

import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.time.Instant;
import java.util.Objects;

record CachedCredential(L402Credential credential, Instant expiresAt) {

  /**
   * Cache-decision metadata that deliberately holds only an invalidity classification, never
   * credential or bearer material.
   */
  record Invalidity(EvictionReason.Invalidity permanence) {
    Invalidity {
      Objects.requireNonNull(permanence, "permanence must not be null");
    }

    boolean permanentlyInvalid() {
      return permanence == EvictionReason.Invalidity.PERMANENT;
    }
  }

  CachedCredential {
    Objects.requireNonNull(credential, "credential must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  boolean isExpired() {
    return Instant.now().compareTo(expiresAt) >= 0;
  }
}
