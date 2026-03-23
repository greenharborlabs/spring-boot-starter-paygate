package com.greenharborlabs.paygate.protocol.mpp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 challenge binding for stateless server-side verification of MPP challenges.
 *
 * <p>The challenge ID is computed over a pipe-delimited 7-slot input string:
 * {@code realm|method|intent|request_b64url|expires_or_empty|digest_or_empty|opaque_b64url_or_empty}
 *
 * <p>Absent optional fields use empty string in their slot. The resulting HMAC is
 * encoded as base64url without padding.
 */
public final class MppChallengeBinding {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char PIPE = '|';
    private static final ThreadLocal<Mac> MAC_TL = ThreadLocal.withInitial(() -> {
        try {
            return Mac.getInstance(HMAC_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("HmacSHA256 not available", e);
        }
    });

    private MppChallengeBinding() {} // utility class

    /**
     * Creates an HMAC-bound challenge ID.
     *
     * @param realm      service name (never null)
     * @param method     payment method, e.g. "lightning" (never null)
     * @param intent     e.g. "charge" (never null)
     * @param requestB64 base64url-nopad encoded JCS request (never null)
     * @param expires    RFC 3339 timestamp or null (absent)
     * @param digest     RFC 9530 content digest or null (absent)
     * @param opaqueB64  base64url-nopad encoded JCS opaque or null (absent)
     * @param secret     server HMAC secret key bytes (never null)
     * @return base64url-nopad encoded HMAC-SHA256
     */
    public static String createId(String realm, String method, String intent,
                                   String requestB64, String expires, String digest,
                                   String opaqueB64, byte[] secret) {
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(requestB64, "requestB64 must not be null");
        Objects.requireNonNull(secret, "secret must not be null");

        byte[] hmacBytes = computeHmac(realm, method, intent, requestB64, expires, digest, opaqueB64, secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
    }

    /**
     * Constant-time verifies a challenge ID against the expected HMAC.
     * Returns true if valid, false if tampered.
     *
     * @param id         the challenge ID to verify (base64url-nopad encoded HMAC)
     * @param realm      service name (never null)
     * @param method     payment method (never null)
     * @param intent     e.g. "charge" (never null)
     * @param requestB64 base64url-nopad encoded JCS request (never null)
     * @param expires    RFC 3339 timestamp or null (absent)
     * @param digest     RFC 9530 content digest or null (absent)
     * @param opaqueB64  base64url-nopad encoded JCS opaque or null (absent)
     * @param secret     server HMAC secret key bytes (never null)
     * @return true if the ID matches the expected HMAC, false otherwise
     */
    public static boolean verify(String id, String realm, String method, String intent,
                                  String requestB64, String expires, String digest,
                                  String opaqueB64, byte[] secret) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(requestB64, "requestB64 must not be null");
        Objects.requireNonNull(secret, "secret must not be null");

        byte[] presentedBytes;
        try {
            presentedBytes = Base64.getUrlDecoder().decode(id);
        } catch (IllegalArgumentException _) {
            // Malformed base64 input — reject without timing leak
            return false;
        }

        byte[] expectedBytes = computeHmac(realm, method, intent, requestB64, expires, digest, opaqueB64, secret);
        return constantTimeEquals(presentedBytes, expectedBytes);
    }

    /**
     * Builds the pipe-delimited input and computes HMAC-SHA256.
     */
    private static byte[] computeHmac(String realm, String method, String intent,
                                       String requestB64, String expires, String digest,
                                       String opaqueB64, byte[] secret) {
        String input = new StringBuilder()
                .append(realm).append(PIPE)
                .append(method).append(PIPE)
                .append(intent).append(PIPE)
                .append(requestB64).append(PIPE)
                .append(nullToEmpty(expires)).append(PIPE)
                .append(nullToEmpty(digest)).append(PIPE)
                .append(nullToEmpty(opaqueB64))
                .toString();

        try {
            Mac mac = MAC_TL.get();
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid HMAC secret key", e);
        }
    }

    /**
     * Constant-time byte array comparison using XOR accumulation.
     * Never use {@code Arrays.equals} or {@code MessageDigest.isEqual} — this implementation
     * guarantees no early return on mismatch.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
