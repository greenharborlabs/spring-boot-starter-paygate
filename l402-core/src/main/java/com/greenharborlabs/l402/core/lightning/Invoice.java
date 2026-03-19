package com.greenharborlabs.l402.core.lightning;

import com.greenharborlabs.l402.core.macaroon.MacaroonCrypto;

import java.time.Instant;
import java.util.Arrays;

/**
 * Immutable representation of a Lightning Network invoice.
 *
 * <p>Byte array fields ({@code paymentHash}, {@code preimage}) are defensively copied
 * on construction and on access to preserve immutability.
 */
public record Invoice(
        byte[] paymentHash,
        String bolt11,
        long amountSats,
        String memo,
        InvoiceStatus status,
        byte[] preimage,
        Instant createdAt,
        Instant expiresAt
) {

    private static final int HASH_BYTES = 32;

    /**
     * Compact constructor -- validates all fields and makes defensive copies of byte arrays.
     */
    public Invoice {
        if (paymentHash == null) {
            throw new IllegalArgumentException("paymentHash must not be null");
        }
        if (paymentHash.length != HASH_BYTES) {
            throw new IllegalArgumentException(
                    "paymentHash must be exactly 32 bytes, got: " + paymentHash.length);
        }
        if (bolt11 == null || bolt11.isEmpty()) {
            throw new IllegalArgumentException("bolt11 must not be null or empty");
        }
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be > 0, got: " + amountSats);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        if (preimage != null && preimage.length != HASH_BYTES) {
            throw new IllegalArgumentException(
                    "preimage must be exactly 32 bytes when present, got: " + preimage.length);
        }
        paymentHash = paymentHash.clone();
        preimage = preimage != null ? preimage.clone() : null;
    }

    @Override
    public byte[] paymentHash() {
        return paymentHash.clone();
    }

    @Override
    public byte[] preimage() {
        return preimage != null ? preimage.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Invoice other
                && MacaroonCrypto.constantTimeEquals(paymentHash, other.paymentHash)
                && bolt11.equals(other.bolt11)
                && amountSats == other.amountSats
                && java.util.Objects.equals(memo, other.memo)
                && status == other.status
                && constantTimePreimageEquals(preimage, other.preimage)
                && createdAt.equals(other.createdAt)
                && expiresAt.equals(other.expiresAt);
    }

    private static boolean constantTimePreimageEquals(byte[] a, byte[] b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return MacaroonCrypto.constantTimeEquals(a, b);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(paymentHash);
        result = 31 * result + bolt11.hashCode();
        result = 31 * result + Long.hashCode(amountSats);
        result = 31 * result + java.util.Objects.hashCode(memo);
        result = 31 * result + status.hashCode();
        result = 31 * result + Arrays.hashCode(preimage);
        result = 31 * result + createdAt.hashCode();
        result = 31 * result + expiresAt.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Invoice[bolt11=" + bolt11 + ", amountSats=" + amountSats
                + ", status=" + status + "]";
    }
}
