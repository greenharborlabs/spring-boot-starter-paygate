package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses MPP {@code Authorization: Payment <base64url-nopad>} credentials into a protocol-agnostic
 * {@link PaymentCredential}.
 *
 * <p>The credential blob is base64url-nopad encoded JSON with the structure:
 *
 * <pre>{@code
 * {
 *   "challenge": { "id": "...", "realm": "...", ... },
 *   "source": "optional-payer-identity",
 *   "payload": { "preimage": "<64-char-lowercase-hex>" }
 * }
 * }</pre>
 *
 * <p>This class has zero external dependencies — all JSON parsing is handled by a minimal
 * recursive-descent parser in the same package.
 */
public final class MppCredentialParser {

  private static final String PROTOCOL_SCHEME = "Payment";
  private static final int PREIMAGE_HEX_LENGTH = 64;
  private static final HexFormat HEX = HexFormat.of();

  private MppCredentialParser() {} // utility class

  /**
   * Parses an MPP credential blob (base64url-nopad encoded JSON) using default parser limits.
   *
   * @param credentialBlob the raw credential after stripping the "Payment " prefix
   * @return the parsed {@link PaymentCredential}
   * @throws PaymentValidationException on any parse failure
   */
  public static PaymentCredential parse(String credentialBlob) {
    return parse(credentialBlob, MppParserLimits.defaults());
  }

  /**
   * Parses an MPP credential blob (base64url-nopad encoded JSON) with the given parser limits.
   *
   * @param credentialBlob the raw credential after stripping the "Payment " prefix
   * @param limits parser limits for resource exhaustion protection
   * @return the parsed {@link PaymentCredential}
   * @throws PaymentValidationException on any parse failure
   */
  @SuppressWarnings("PMD.CyclomaticComplexity") // Protocol credential parser — complexity inherent
  public static PaymentCredential parse(String credentialBlob, MppParserLimits limits) {
    // Step 0: pre-decode size check (cast to long before multiplication to prevent overflow)
    if (credentialBlob.length() > (long) limits.maxInputLength() * 2) {
      throw malformed("Credential exceeds maximum size");
    }

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
      var parser = new MinimalJsonParser(json, limits);
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
      if (entry.getValue() instanceof String value) {
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

    // Step 7: decode preimage
    byte[] preimageBytes = HEX.parseHex(preimageHex);

    // Step 7b: extract payment hash from echoed challenge's request field
    byte[] paymentHash = extractPaymentHashFromRequest(echoedChallenge, limits);

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
        paymentHash, preimageBytes, tokenId, PROTOCOL_SCHEME, source, metadata);
  }

  private static final int PAYMENT_HASH_LENGTH = 32;

  /**
   * Extracts the payment hash from the echoed challenge's {@code request} field. The request field
   * is base64url-nopad encoded JCS JSON containing {@code methodDetails.paymentHash} as a hex
   * string.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity") // Multi-step hash extraction with validation
  private static byte[] extractPaymentHashFromRequest(
      Map<String, String> echoedChallenge, MppParserLimits limits) {
    String requestB64 = echoedChallenge.get("request");
    if (requestB64 == null) {
      throw malformed("Missing 'request' in echoed challenge for payment hash extraction");
    }

    byte[] requestJsonBytes;
    try {
      requestJsonBytes = Base64.getUrlDecoder().decode(requestB64);
    } catch (IllegalArgumentException e) {
      throw malformed("Invalid base64url in echoed challenge request field", e);
    }

    String requestJson = new String(requestJsonBytes, StandardCharsets.UTF_8);

    Map<String, Object> requestMap;
    try {
      var parser = new MinimalJsonParser(requestJson, limits);
      requestMap = parser.parseObject();
    } catch (MinimalJsonParser.JsonParseException e) {
      throw malformed("Missing methodDetails.paymentHash in charge request", e);
    }

    Object methodDetailsRaw = requestMap.get("methodDetails");
    if (!(methodDetailsRaw instanceof Map<?, ?> methodDetailsMap)) {
      throw malformed("Missing methodDetails.paymentHash in charge request");
    }

    Object paymentHashRaw = methodDetailsMap.get("paymentHash");
    if (!(paymentHashRaw instanceof String paymentHashHex)) {
      throw malformed("Missing methodDetails.paymentHash in charge request");
    }

    byte[] paymentHash;
    try {
      paymentHash = HEX.parseHex(paymentHashHex);
    } catch (IllegalArgumentException e) {
      throw malformed("Invalid hex in methodDetails.paymentHash", e);
    }

    if (paymentHash.length != PAYMENT_HASH_LENGTH) {
      throw malformed("Payment hash must be 32 bytes");
    }

    return paymentHash;
  }

  private static PaymentValidationException malformed(String message) {
    return new PaymentValidationException(ErrorCode.MALFORMED_CREDENTIAL, message);
  }

  private static PaymentValidationException malformed(String message, Throwable cause) {
    return new PaymentValidationException(ErrorCode.MALFORMED_CREDENTIAL, message, cause);
  }
}
