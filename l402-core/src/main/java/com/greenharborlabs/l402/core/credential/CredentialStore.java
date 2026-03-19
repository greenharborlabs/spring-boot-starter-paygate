package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;

public interface CredentialStore {
    void store(String tokenId, L402Credential credential, long ttlSeconds);
    L402Credential get(String tokenId);
    void revoke(String tokenId);
    long activeCount();

    @FunctionalInterface
    interface EvictionListener {
        void onEviction(String tokenId, EvictionReason reason);
    }

    default void setEvictionListener(EvictionListener listener) {
        // no-op by default
    }
}
