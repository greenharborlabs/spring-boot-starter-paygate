package com.greenharborlabs.paygate.core.macaroon;

@FunctionalInterface
public interface RootKeyRevocationListener {
    void onRootKeyRevoked(byte[] keyId);
}
