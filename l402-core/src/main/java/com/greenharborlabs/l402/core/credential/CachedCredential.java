package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;

import java.time.Instant;
import java.util.Objects;

record CachedCredential(L402Credential credential, Instant expiresAt) {

    CachedCredential {
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    boolean isExpired() {
        return Instant.now().compareTo(expiresAt) >= 0;
    }
}
