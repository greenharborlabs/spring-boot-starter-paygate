package com.greenharborlabs.paygate.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-agnostic data record carrying all information needed to create a payment challenge.
 * Produced by {@code PaygateChallengeService} and consumed by protocol-specific formatters
 * (L402 macaroon minting, MPP header generation, etc.).
 *
 * <p>Defensive copies are made for all mutable fields ({@code paymentHash}, {@code rootKeyBytes},
 * {@code opaque}) both on construction and on access.
 */
public record ChallengeContext(
        byte[] paymentHash,
        String tokenId,
        String bolt11Invoice,
        long priceSats,
        String description,
        String serviceName,
        long timeoutSeconds,
        String capability,
        byte[] rootKeyBytes,
        Map<String, String> opaque,
        String digest
) {

    public ChallengeContext {
        Objects.requireNonNull(paymentHash, "paymentHash must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(bolt11Invoice, "bolt11Invoice must not be null");
        if (priceSats <= 0) {
            throw new IllegalArgumentException("priceSats must be positive, got " + priceSats);
        }

        // Defensive copies of mutable fields
        paymentHash = paymentHash.clone();
        rootKeyBytes = rootKeyBytes != null ? rootKeyBytes.clone() : null;
        opaque = opaque != null ? Map.copyOf(opaque) : null;
    }

    @Override
    public byte[] paymentHash() {
        return paymentHash.clone();
    }

    @Override
    public byte[] rootKeyBytes() {
        return rootKeyBytes != null ? rootKeyBytes.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChallengeContext that)) return false;
        return priceSats == that.priceSats
                && timeoutSeconds == that.timeoutSeconds
                && constantTimeEquals(paymentHash, that.paymentHash)
                && Objects.equals(tokenId, that.tokenId)
                && Objects.equals(bolt11Invoice, that.bolt11Invoice)
                && Objects.equals(description, that.description)
                && Objects.equals(serviceName, that.serviceName)
                && Objects.equals(capability, that.capability)
                && constantTimeEquals(rootKeyBytes, that.rootKeyBytes)
                && Objects.equals(opaque, that.opaque)
                && Objects.equals(digest, that.digest);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(tokenId, bolt11Invoice, priceSats, description,
                serviceName, timeoutSeconds, capability, opaque, digest);
        result = 31 * result + Arrays.hashCode(paymentHash);
        result = 31 * result + Arrays.hashCode(rootKeyBytes);
        return result;
    }

    @Override
    public String toString() {
        return "ChallengeContext[tokenId=" + tokenId
                + ", priceSats=" + priceSats
                + ", serviceName=" + serviceName + "]";
    }
}
