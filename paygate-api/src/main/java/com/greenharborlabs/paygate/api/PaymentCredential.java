package com.greenharborlabs.paygate.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Protocol-agnostic representation of a parsed payment credential.
 *
 * @param paymentHash           SHA-256 hash of the preimage (defensively copied)
 * @param preimage              32-byte preimage proving payment (defensively copied)
 * @param tokenId               token/challenge identifier
 * @param sourceProtocolScheme  protocol that parsed this credential ("L402" or "Payment")
 * @param source                optional payer identity (DID format, from MPP); may be null
 * @param metadata              protocol-specific metadata
 */
public record PaymentCredential(
        byte[] paymentHash,
        byte[] preimage,
        String tokenId,
        String sourceProtocolScheme,
        String source,
        ProtocolMetadata metadata
) {

    public PaymentCredential {
        Objects.requireNonNull(paymentHash, "paymentHash must not be null");
        Objects.requireNonNull(preimage, "preimage must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(sourceProtocolScheme, "sourceProtocolScheme must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");

        paymentHash = paymentHash.clone();
        preimage = preimage.clone();
    }

    @Override
    public byte[] paymentHash() {
        return paymentHash.clone();
    }

    @Override
    public byte[] preimage() {
        return preimage.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentCredential that)) return false;
        return constantTimeEquals(paymentHash, that.paymentHash)
                && constantTimeEquals(preimage, that.preimage)
                && Objects.equals(tokenId, that.tokenId)
                && Objects.equals(sourceProtocolScheme, that.sourceProtocolScheme)
                && Objects.equals(source, that.source)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(tokenId, sourceProtocolScheme, source, metadata);
        result = 31 * result + Arrays.hashCode(paymentHash);
        result = 31 * result + Arrays.hashCode(preimage);
        return result;
    }

    @Override
    public String toString() {
        return "PaymentCredential[tokenId=" + tokenId
                + ", sourceProtocolScheme=" + sourceProtocolScheme
                + ", source=" + source + "]";
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
}
