package com.greenharborlabs.l402.core.macaroon;

@FunctionalInterface
public interface RootKeyRevocationListener {
    void onRootKeyRevoked(byte[] keyId);
}
