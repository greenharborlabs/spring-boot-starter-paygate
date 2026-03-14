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
     * Never use {@code Arrays.equals} — it short-circuits on first mismatch.
     *
     * <p>This method is designed for fixed-length inputs such as HMAC-SHA256
     * digests (32 bytes) and fixed-layout identifiers (66 bytes). The early
     * length check is safe because a length mismatch reveals no timing
     * information about the <em>content</em> of either array — only that
     * their lengths differ, which is already evident from the inputs.
     *
     * <p>After the length guard, the XOR loop provides constant-time
     * comparison of all byte contents: every byte is visited regardless
     * of where (or whether) a difference exists.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
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
