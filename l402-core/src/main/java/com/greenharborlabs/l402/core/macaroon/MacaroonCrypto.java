package com.greenharborlabs.l402.core.macaroon;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic primitives for macaroon operations.
 * Uses only JDK classes — zero external dependencies.
 */
public final class MacaroonCrypto {

    private static final System.Logger log = System.getLogger(MacaroonCrypto.class.getName());
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final byte[] GENERATOR_KEY = "macaroons-key-generator".getBytes(StandardCharsets.UTF_8);

    private static final ThreadLocal<Mac> MAC_PROTOTYPE = ThreadLocal.withInitial(() -> {
        try {
            return Mac.getInstance(HMAC_SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("HmacSHA256 must be available", e);
        }
    });

    private MacaroonCrypto() {}

    /**
     * Derives a macaroon signing key from a root key, matching go-macaroon's key derivation.
     * Computes HMAC-SHA256(key="macaroons-key-generator", data=rootKey).
     */
    public static byte[] deriveKey(byte[] rootKey) {
        return hmac(GENERATOR_KEY, rootKey);
    }

    /**
     * Derives a macaroon signing key from a {@link SensitiveBytes} root key.
     * The result is wrapped in a new {@link SensitiveBytes} instance.
     */
    public static SensitiveBytes deriveKey(SensitiveBytes rootKey) {
        byte[] raw = rootKey.value();
        try {
            byte[] derived = deriveKey(raw);
            return new SensitiveBytes(derived);
        } finally {
            KeyMaterial.zeroize(raw);
        }
    }

    /**
     * Computes HMAC-SHA256(key, data).
     * The {@link SecretKeySpec} is destroyed in a finally block to limit key lifetime in memory.
     */
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac;
            try {
                mac = (Mac) MAC_PROTOTYPE.get().clone();
            } catch (CloneNotSupportedException e) {
                mac = Mac.getInstance(HMAC_SHA256);
            }
            SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_SHA256);
            try {
                mac.init(keySpec);
                return mac.doFinal(data);
            } finally {
                try {
                    keySpec.destroy();
                } catch (DestroyFailedException e) {
                    log.log(System.Logger.Level.WARNING, "SecretKeySpec.destroy() failed: {0}", e.getMessage());
                }
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * Computes HMAC-SHA256 using a {@link SensitiveBytes} key.
     * The key is unwrapped via {@link SensitiveBytes#value()} which returns a defensive copy;
     * the {@link SecretKeySpec} created from that copy is destroyed in the delegate method.
     */
    public static byte[] hmac(SensitiveBytes key, byte[] data) {
        byte[] raw = key.value();
        try {
            return hmac(raw, data);
        } finally {
            KeyMaterial.zeroize(raw);
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

    /**
     * Convenience method that delegates to {@link KeyMaterial#zeroize(byte[])}.
     */
    public static void zeroize(byte[] data) {
        KeyMaterial.zeroize(data);
    }
}
