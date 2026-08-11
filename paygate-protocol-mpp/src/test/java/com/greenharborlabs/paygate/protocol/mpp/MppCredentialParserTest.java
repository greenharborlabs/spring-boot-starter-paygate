package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MppCredentialParserTest {

  private static final HexFormat HEX = HexFormat.of();

  // A valid 32-byte preimage in lowercase hex
  private static final String VALID_PREIMAGE_HEX =
      "0001020304050607080910111213141516171819202122232425262728293031";

  private static final String VALID_CHALLENGE_ID = "test-challenge-id-abc123";

  /** Encodes a JSON string as base64url without padding. */
  private static String toBlob(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String validJson() {
    return validJson(VALID_PREIMAGE_HEX, VALID_CHALLENGE_ID, "\"did:example:alice\"");
  }

  /**
   * Builds a base64url-nopad request field containing the payment hash for the given preimage hex.
   */
  private static String buildRequestB64(String preimageHex) {
    byte[] preimageBytes = HEX.parseHex(preimageHex);
    byte[] paymentHash;
    try {
      paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);
    } catch (Exception e) {
      throw new AssertionError("SHA-256 not available", e);
    }
    String paymentHashHex = HEX.formatHex(paymentHash);
    // JCS: keys sorted lexicographically, no whitespace
    String requestJson =
        "{\"amount\":\"100\",\"currency\":\"BTC\",\"methodDetails\":"
            + "{\"invoice\":\"lnbc100n1p0test\",\"network\":\"mainnet\","
            + "\"paymentHash\":\"%s\"}}".formatted(paymentHashHex);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(requestJson.getBytes(StandardCharsets.UTF_8));
  }

  private static final String VALID_REQUEST_B64 = buildRequestB64(VALID_PREIMAGE_HEX);

  private static String validJson(String preimageHex, String challengeId, String sourceValue) {
    String requestB64 = buildRequestB64(preimageHex);
    return """
                {
                  "challenge": {
                    "id": "%s",
                    "realm": "my-service",
                    "method": "lightning",
                    "intent": "charge",
                    "request": "%s",
                    "expires": "2026-12-31T23:59:59Z"
                  },
                  "source": %s,
                  "payload": {
                    "preimage": "%s"
                  }
                }"""
        .formatted(challengeId, requestB64, sourceValue, preimageHex);
  }

  /** Returns an alternative final base64url character that decodes to the same bytes. */
  private static String nonCanonicalBase64url(String canonical) {
    int remainder = canonical.length() % 4;
    if (remainder == 0) {
      throw new IllegalArgumentException("Base64url input must have unused trailing bits");
    }

    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    int lastIndex = alphabet.indexOf(canonical.charAt(canonical.length() - 1));
    int replacement = lastIndex + 1;
    return canonical.substring(0, canonical.length() - 1) + alphabet.charAt(replacement);
  }

  private static void assertMalformed(String credentialBlob) {
    assertThatThrownBy(() -> MppCredentialParser.parse(credentialBlob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED));
  }

  // --- Happy path ---

  @Test
  void parsesValidCredentialWithSource() throws Exception {
    String blob = toBlob(validJson());

    PaymentCredential cred = MppCredentialParser.parse(blob);

    // paymentHash = SHA-256 of the decoded preimage bytes
    byte[] expectedPreimage = HEX.parseHex(VALID_PREIMAGE_HEX);
    byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(expectedPreimage);

    assertThat(cred.paymentHash()).isEqualTo(expectedHash);
    assertThat(cred.preimage()).isEqualTo(expectedPreimage);
    assertThat(cred.tokenId()).isEqualTo(VALID_CHALLENGE_ID);
    assertThat(cred.sourceProtocolScheme()).isEqualTo("Payment");
    assertThat(cred.source()).isEqualTo("did:example:alice");
  }

  @Test
  void parsesValidCredentialWithNullSource() {
    String json = validJson(VALID_PREIMAGE_HEX, VALID_CHALLENGE_ID, "null");
    String blob = toBlob(json);

    PaymentCredential cred = MppCredentialParser.parse(blob);

    assertThat(cred.source()).isNull();
    assertThat(cred.tokenId()).isEqualTo(VALID_CHALLENGE_ID);
    assertThat(((MppMetadata) cred.metadata()).source()).isNull();
  }

  @Test
  void parsesValidCredentialWithAbsentSource() {
    // source key not present at all
    String json =
        """
                {
                  "challenge": {
                    "id": "%s",
                    "realm": "svc",
                    "method": "lightning",
                    "intent": "charge",
                    "request": "%s",
                    "expires": "2026-12-31T23:59:59Z"
                  },
                  "payload": {
                    "preimage": "%s"
                  }
                }"""
            .formatted(VALID_CHALLENGE_ID, VALID_REQUEST_B64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    PaymentCredential cred = MppCredentialParser.parse(blob);

    assertThat(cred.source()).isNull();
    assertThat(((MppMetadata) cred.metadata()).source()).isNull();
  }

  @Test
  void extractsEchoedChallengeAsMap() {
    String blob = toBlob(validJson());

    PaymentCredential cred = MppCredentialParser.parse(blob);

    assertThat(cred.metadata()).isInstanceOf(MppMetadata.class);
    MppMetadata meta = (MppMetadata) cred.metadata();

    Map<String, String> challenge = meta.echoedChallenge();
    assertThat(challenge).containsEntry("id", VALID_CHALLENGE_ID);
    assertThat(challenge).containsEntry("realm", "my-service");
    assertThat(challenge).containsEntry("method", "lightning");
    assertThat(challenge).containsEntry("intent", "charge");
    assertThat(challenge).containsEntry("request", VALID_REQUEST_B64);
    assertThat(challenge).containsEntry("expires", "2026-12-31T23:59:59Z");
    assertThat(challenge).hasSize(6);
  }

  @Test
  void metadataContainsEchoedChallengeAndSource() {
    String blob = toBlob(validJson());

    PaymentCredential cred = MppCredentialParser.parse(blob);
    MppMetadata meta = (MppMetadata) cred.metadata();

    assertThat(meta.echoedChallenge()).containsEntry("id", VALID_CHALLENGE_ID);
    assertThat(meta.echoedChallenge()).containsEntry("request", VALID_REQUEST_B64);
    assertThatThrownBy(() -> meta.echoedChallenge().put("id", "tampered"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(meta.source()).isEqualTo("did:example:alice");
  }

  // --- Base64url error ---

  @Test
  void rejectsInvalidBase64url() {
    assertThatThrownBy(() -> MppCredentialParser.parse("!!!not-base64!!!"))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- JSON errors ---

  @Test
  void rejectsInvalidJson() {
    String blob = toBlob("{not valid json");

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsTrailingContent() {
    String blob = toBlob(validJson() + "extra");

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
            });
  }

  // --- Missing challenge ---

  @Test
  void rejectsMissingChallengeObject() {
    String json =
        """
                {
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsChallengeAsString() {
    String json =
        """
                {
                  "challenge": "not-an-object",
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- Missing challenge.id ---

  @Test
  void rejectsMissingChallengeId() {
    String json =
        """
                {
                  "challenge": {
                    "realm": "svc",
                    "method": "lightning"
                  },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsEmptyChallengeId() {
    String json =
        """
                {
                  "challenge": {
                    "id": "",
                    "realm": "svc"
                  },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- Missing payload.preimage ---

  @Test
  void rejectsMissingPayloadObject() {
    String json =
        """
                {
                  "challenge": { "id": "abc" }
                }""";
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsMissingPreimageInPayload() {
    String json =
        """
                {
                  "challenge": { "id": "abc" },
                  "payload": { }
                }""";
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- Invalid preimage hex ---

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Too short
        "0001020304050607",
        // Too long (66 chars)
        "000102030405060708091011121314151617181920212223242526272829303132",
        // Non-hex characters
        "gg01020304050607080910111213141516171819202122232425262728293031",
        // Empty
        ""
      })
  void rejectsInvalidPreimageHex(String badHex) {
    String json =
        """
                {
                  "challenge": { "id": "abc" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(badHex);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsUppercasePreimageWithClearMessage() {
    String uppercaseHex = "000102030405060708091011121314151617181920212223242526272829AABB";
    String json =
        """
                {
                  "challenge": { "id": "abc" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(uppercaseHex);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- Edge cases ---

  @Test
  void handlesJsonWithEscapedStrings() {
    // source contains escaped characters
    String json =
        """
                {
                  "challenge": { "id": "test-id", "request": "%s" },
                  "source": "did:example:alice\\/bob",
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_REQUEST_B64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    PaymentCredential cred = MppCredentialParser.parse(blob);

    assertThat(cred.source()).isEqualTo("did:example:alice/bob");
  }

  @Test
  void handlesMinimalValidCredential() {
    // Required fields: challenge.id, challenge.request, and payload.preimage
    String json =
        """
                {"challenge":{"id":"x","request":"%s"},"payload":{"preimage":"%s"}}"""
            .formatted(VALID_REQUEST_B64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    PaymentCredential cred = MppCredentialParser.parse(blob);

    assertThat(cred.tokenId()).isEqualTo("x");
    assertThat(cred.source()).isNull();
    assertThat(cred.sourceProtocolScheme()).isEqualTo("Payment");
  }

  @Test
  void base64urlWithoutPaddingIsAccepted() {
    // Verify that base64url encoding without padding works
    String json = validJson();
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

    // Encode without padding (standard MPP format)
    String blob = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    PaymentCredential cred = MppCredentialParser.parse(blob);
    assertThat(cred.tokenId()).isEqualTo(VALID_CHALLENGE_ID);
  }

  @Test
  void rejectsPaddedOuterCredentialBlob() {
    String json = validJson() + " ";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    String blob = Base64.getUrlEncoder().encodeToString(bytes);

    assertMalformed(blob);
  }

  @Test
  void rejectsNonUrlAlphabetInOuterCredentialBlob() {
    assertMalformed("++++");
  }

  @Test
  void rejectsNonCanonicalOuterCredentialBlob() {
    String blob = toBlob(validJson() + " ");
    assertThat(blob.length() % 4).isNotZero();

    assertMalformed(nonCanonicalBase64url(blob));
  }

  // --- Request field extraction errors ---

  @Test
  void rejectsMissingRequestField() {
    String json =
        """
                {
                  "challenge": { "id": "test-id", "realm": "svc" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsMalformedRequestBase64url() {
    String json =
        """
                {
                  "challenge": { "id": "test-id", "request": "!!!notbase64" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsPaddedRequestField() {
    String paddedRequest =
        Base64.getUrlEncoder()
            .encodeToString(
                (new String(
                        Base64.getUrlDecoder().decode(VALID_REQUEST_B64), StandardCharsets.UTF_8))
                    .concat(" ")
                    .getBytes(StandardCharsets.UTF_8));
    String json =
        """
                {"challenge":{"id":"test-id","request":"%s"},"payload":{"preimage":"%s"}}"""
            .formatted(paddedRequest, VALID_PREIMAGE_HEX);

    assertMalformed(toBlob(json));
  }

  @Test
  void rejectsNonUrlAlphabetInRequestField() {
    String json =
        """
                {"challenge":{"id":"test-id","request":"////"},"payload":{"preimage":"%s"}}"""
            .formatted(VALID_PREIMAGE_HEX);

    assertMalformed(toBlob(json));
  }

  @Test
  void rejectsNonCanonicalRequestField() {
    String requestJson =
        new String(Base64.getUrlDecoder().decode(VALID_REQUEST_B64), StandardCharsets.UTF_8) + " ";
    String request = toBlob(requestJson);
    assertThat(request.length() % 4).isNotZero();
    String json =
        """
                {"challenge":{"id":"test-id","request":"%s"},"payload":{"preimage":"%s"}}"""
            .formatted(nonCanonicalBase64url(request), VALID_PREIMAGE_HEX);

    assertMalformed(toBlob(json));
  }

  @Test
  void rejectsTrailingContentInRequestJson() {
    String requestJson =
        new String(Base64.getUrlDecoder().decode(VALID_REQUEST_B64), StandardCharsets.UTF_8) + "{}";
    String requestB64 = toBlob(requestJson);

    assertMalformed(toBlob(validJson().replace(VALID_REQUEST_B64, requestB64)));
  }

  @Test
  void rejectsMissingMethodDetailsPaymentHash() {
    // Request JSON without methodDetails
    String requestJson = "{\"amount\":\"100\"}";
    String requestB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(requestJson.getBytes(StandardCharsets.UTF_8));
    String json =
        """
                {
                  "challenge": { "id": "test-id", "request": "%s" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(requestB64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsInvalidHexInPaymentHash() {
    // Request JSON with invalid hex in paymentHash
    String requestJson = "{\"methodDetails\":{\"paymentHash\":\"zzzz\"}}";
    String requestB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(requestJson.getBytes(StandardCharsets.UTF_8));
    String json =
        """
                {
                  "challenge": { "id": "test-id", "request": "%s" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(requestB64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  // --- Parser limits ---

  @Test
  void rejectsOversizedCredentialBeforeDecode() {
    // maxInputLength default is 65_536, so base64url blob > 65_536 * 2 = 131_072 chars
    // should be rejected before base64 decode even happens
    int oversizedLength = 65_536 * 2 + 1;
    String oversized = "A".repeat(oversizedLength);

    assertThatThrownBy(() -> MppCredentialParser.parse(oversized))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsOversizedCredentialWithCustomLimits() {
    MppParserLimits tinyLimits = new MppParserLimits(5, 8192, 32, 100);
    // 100 * 2 = 200 char limit; create a 201-char blob
    String oversized = "A".repeat(201);

    assertThatThrownBy(() -> MppCredentialParser.parse(oversized, tinyLimits))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }

  @Test
  void rejectsDeeplyNestedCredential() {
    // Default maxDepth is 5; create a credential with depth > 5
    // Build JSON with nesting: {"a":{"a":{"a":{"a":{"a":{"a":"x"}}}}}}
    String nested = "{\"a\":".repeat(6) + "\"x\"" + "}".repeat(6);
    String blob = toBlob(nested);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
            });
  }

  @Test
  void parseWithDefaultLimitsDelegatesToOverload() {
    // Verify parse(String) produces same result as parse(String, defaults())
    String blob = toBlob(validJson());

    PaymentCredential cred1 = MppCredentialParser.parse(blob);
    PaymentCredential cred2 = MppCredentialParser.parse(blob, MppParserLimits.defaults());

    assertThat(cred1.tokenId()).isEqualTo(cred2.tokenId());
    assertThat(cred1.paymentHash()).isEqualTo(cred2.paymentHash());
    assertThat(cred1.preimage()).isEqualTo(cred2.preimage());
  }

  @Test
  void rejectsWrongLengthPaymentHash() {
    // 16-byte payment hash (32 hex chars) instead of 32 bytes (64 hex chars)
    String shortHash =
        "0102030405060708091011121314151617181920212223242526272829303132".substring(0, 32);
    String requestJson = "{\"methodDetails\":{\"paymentHash\":\"%s\"}}".formatted(shortHash);
    String requestB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(requestJson.getBytes(StandardCharsets.UTF_8));
    String json =
        """
                {
                  "challenge": { "id": "test-id", "request": "%s" },
                  "payload": { "preimage": "%s" }
                }"""
            .formatted(requestB64, VALID_PREIMAGE_HEX);
    String blob = toBlob(json);

    assertThatThrownBy(() -> MppCredentialParser.parse(blob))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e -> {
              PaymentValidationException pve = (PaymentValidationException) e;
              assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
              assertThat(pve.getMessage()).isEqualTo("Payment validation failed: MALFORMED");
            });
  }
}
