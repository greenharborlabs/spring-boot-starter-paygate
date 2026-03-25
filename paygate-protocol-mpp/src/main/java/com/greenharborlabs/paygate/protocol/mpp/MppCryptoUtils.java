package com.greenharborlabs.paygate.protocol.mpp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared cryptographic utilities for the MPP protocol module.
 *
 * <p>Package-private to prevent external use. Zero external dependencies.
 */
final class MppCryptoUtils {

    private MppCryptoUtils() {} // prevent instantiation

    /**
     * Constant-time byte array comparison using XOR accumulation.
     *
     * <p>When arrays differ in length, sets {@code result = 1} immediately and
     * performs a dummy XOR loop over {@code max(a.length, b.length)} iterations
     * using modular indexing to avoid timing leaks on length mismatch.
     *
     * <p>When both arrays are empty, returns {@code true}.
     *
     * @param a first byte array (never null)
     * @param b second byte array (never null)
     * @return true if arrays are equal, false otherwise
     */
    static boolean constantTimeEquals(byte[] a, byte[] b) {
        int result = a.length == b.length ? 0 : 1;
        int maxLen = Math.max(a.length, b.length);

        // Both empty: maxLen == 0, loop does not execute, result == 0 → true
        // One empty, one not: result already 1, but we cannot index into an
        // empty array, so we skip the loop. The method still returns false
        // because result was set to 1 above. This is safe because the
        // "one is empty" case leaks no useful timing information (the attacker
        // already knows whether they sent an empty value).
        if (a.length > 0 && b.length > 0) {
            for (int i = 0; i < maxLen; i++) {
                result |= a[i % a.length] ^ b[i % b.length];
            }
        }
        return result == 0;
    }

    /**
     * Computes SHA-256 hash of the given data.
     *
     * @param data input bytes (never null)
     * @return 32-byte SHA-256 digest
     */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every conformant JRE
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
