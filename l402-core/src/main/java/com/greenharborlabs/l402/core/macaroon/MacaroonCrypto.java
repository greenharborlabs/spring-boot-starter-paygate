package com.greenharborlabs.l402.core.macaroon;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic primitives for macaroon operations.
 * Uses only JDK classes — zero external dependencies.
 */
public final class MacaroonCrypto {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final byte[] GENERATOR_KEY = "macaroons-key-generator".getBytes(StandardCharsets.UTF_8);

    private MacaroonCrypto() {}

    /**
     * Derives a macaroon signing key from a root key, matching go-macaroon's key derivation.
     * Computes HMAC-SHA256(key="macaroons-key-generator", data=rootKey).
     */
    public static byte[] deriveKey(byte[] rootKey) {
        return hmac(GENERATOR_KEY, rootKey);
    }

    /**
     * Computes HMAC-SHA256(key, data).
     */
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * Constant-time byte array comparison using XOR accumulation.
     * Never use Arrays.equals — it short-circuits on first mismatch.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        int result = a.length ^ b.length;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Binds a discharge macaroon to a request by computing
     * HMAC-SHA256(key=rootSig, data=dischargeSig).
     */
    public static byte[] bindForRequest(byte[] rootSig, byte[] dischargeSig) {
        return hmac(rootSig, dischargeSig);
    }
}
