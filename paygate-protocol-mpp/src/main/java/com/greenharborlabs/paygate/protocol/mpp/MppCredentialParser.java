package com.greenharborlabs.paygate.protocol.mpp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;

/**
 * Parses MPP {@code Authorization: Payment <base64url-nopad>} credentials into
 * a protocol-agnostic {@link PaymentCredential}.
 *
 * <p>The credential blob is base64url-nopad encoded JSON with the structure:
 * <pre>{@code
 * {
 *   "challenge": { "id": "...", "realm": "...", ... },
 *   "source": "optional-payer-identity",
 *   "payload": { "preimage": "<64-char-lowercase-hex>" }
 * }
 * }</pre>
 *
 * <p>This class has zero external dependencies — all JSON parsing is handled
 * by a minimal recursive-descent parser in the same package.
 */
public final class MppCredentialParser {

    private static final String PROTOCOL_SCHEME = "Payment";
    private static final int PREIMAGE_HEX_LENGTH = 64;
    private static final HexFormat HEX = HexFormat.of();

    private MppCredentialParser() {} // utility class

    /**
     * Parses an MPP credential blob (base64url-nopad encoded JSON).
     *
     * @param credentialBlob the raw credential after stripping the "Payment " prefix
     * @return the parsed {@link PaymentCredential}
     * @throws PaymentValidationException on any parse failure
     */
    public static PaymentCredential parse(String credentialBlob) {
        // Step 1: base64url decode
        byte[] jsonBytes;
        try {
            jsonBytes = Base64.getUrlDecoder().decode(credentialBlob);
        } catch (IllegalArgumentException e) {
            throw malformed("Invalid base64url encoding", e);
        }

        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        // Step 2: parse JSON
        Map<String, Object> root;
        try {
            var parser = new MinimalJsonParser(json);
            root = parser.parseObject();
            parser.expectEnd();
        } catch (MinimalJsonParser.JsonParseException e) {
            throw malformed("Invalid JSON in credential: " + e.getMessage(), e);
        }

        // Step 3: extract challenge object
        Object challengeRaw = root.get("challenge");
        if (!(challengeRaw instanceof Map<?, ?> challengeMap)) {
            throw malformed("Missing 'challenge' object in credential");
        }

        Map<String, String> echoedChallenge = new LinkedHashMap<>();
        for (var entry : challengeMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("Non-string key in challenge object");
            }
            if (entry.getValue() == null) {
                // null JSON values in challenge are treated as absent -- skip
                continue;
            } else if (entry.getValue() instanceof String value) {
                echoedChallenge.put(key, value);
            } else {
                throw malformed("Non-string value for key '%s' in challenge object".formatted(key));
            }
        }

        // Step 4: extract challenge.id (tokenId)
        String tokenId = echoedChallenge.get("id");
        if (tokenId == null || tokenId.isEmpty()) {
            throw malformed("Missing 'id' in challenge");
        }

        // Step 5: extract payload.preimage
        Object payloadRaw = root.get("payload");
        if (!(payloadRaw instanceof Map<?, ?> payloadMap)) {
            throw malformed("Missing 'payload.preimage' in credential");
        }

        Object preimageRaw = payloadMap.get("preimage");
        if (!(preimageRaw instanceof String preimageHex)) {
            throw malformed("Missing 'payload.preimage' in credential");
        }

        // Step 6: validate preimage hex format
        if (preimageHex.length() != PREIMAGE_HEX_LENGTH) {
            throw malformed("Invalid preimage hex");
        }
        for (int i = 0; i < preimageHex.length(); i++) {
            char c = preimageHex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw malformed("Invalid preimage hex");
            }
        }

        // Step 7: decode preimage and compute payment hash
        byte[] preimageBytes = HEX.parseHex(preimageHex);
        byte[] paymentHash = sha256(preimageBytes);

        // Step 8: extract optional source
        Object sourceRaw = root.get("source");
        String source = null;
        if (sourceRaw instanceof String s) {
            source = s;
        }
        // null or absent → source stays null

        // Step 9: build metadata and credential
        var metadata = new MppMetadata(echoedChallenge, source, json);

        return new PaymentCredential(
                paymentHash,
                preimageBytes,
                tokenId,
                PROTOCOL_SCHEME,
                source,
                metadata
        );
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every conformant JRE
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static PaymentValidationException malformed(String message) {
        return new PaymentValidationException(ErrorCode.MALFORMED_CREDENTIAL, message);
    }

    private static PaymentValidationException malformed(String message, Throwable cause) {
        return new PaymentValidationException(ErrorCode.MALFORMED_CREDENTIAL, message, cause);
    }
}
