package com.greenharborlabs.l402.core.lightning;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A 32-byte payment preimage used in Lightning Network HTLC settlement.
 * The corresponding payment hash is SHA-256(preimage).
 */
public record PaymentPreimage(byte[] value) {

    private static final int PREIMAGE_LENGTH = 32;
    private static final int SHA256_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    public PaymentPreimage {
        if (value == null) {
            throw new IllegalArgumentException("Preimage value must not be null");
        }
        if (value.length != PREIMAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Preimage must be exactly " + PREIMAGE_LENGTH + " bytes, got " + value.length);
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    /**
     * Verifies that SHA-256(this preimage) equals the given payment hash,
     * using constant-time comparison to prevent timing side-channels.
     */
    public boolean matchesHash(byte[] paymentHash) {
        if (paymentHash == null) {
            throw new IllegalArgumentException("Payment hash must not be null");
        }
        if (paymentHash.length != SHA256_LENGTH) {
            throw new IllegalArgumentException(
                    "Payment hash must be exactly " + SHA256_LENGTH + " bytes, got " + paymentHash.length);
        }
        byte[] computed = sha256(value);
        return constantTimeEquals(computed, paymentHash);
    }

    /**
     * Returns the preimage as a 64-character lowercase hex string.
     */
    public String toHex() {
        return HEX.formatHex(value);
    }

    /**
     * Parses a 64-character hex string (case-insensitive) into a PaymentPreimage.
     */
    public static PaymentPreimage fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("Hex string must not be null or empty");
        }
        if (hex.length() != PREIMAGE_LENGTH * 2) {
            throw new IllegalArgumentException(
                    "Hex string must be exactly " + (PREIMAGE_LENGTH * 2) + " characters, got " + hex.length());
        }
        try {
            byte[] decoded = HEX.parseHex(hex.toLowerCase());
            return new PaymentPreimage(decoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid hex string: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaymentPreimage other
                && java.util.Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(value);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        int result = a.length ^ b.length;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK specification
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
