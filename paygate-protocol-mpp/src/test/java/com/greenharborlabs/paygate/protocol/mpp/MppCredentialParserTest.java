package com.greenharborlabs.paygate.protocol.mpp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MppCredentialParserTest {

    private static final HexFormat HEX = HexFormat.of();

    // A valid 32-byte preimage in lowercase hex
    private static final String VALID_PREIMAGE_HEX =
            "0001020304050607080910111213141516171819202122232425262728293031";

    private static final String VALID_CHALLENGE_ID = "test-challenge-id-abc123";

    /**
     * Encodes a JSON string as base64url without padding.
     */
    private static String toBlob(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String validJson() {
        return validJson(VALID_PREIMAGE_HEX, VALID_CHALLENGE_ID, "\"did:example:alice\"");
    }

    private static String validJson(String preimageHex, String challengeId, String sourceValue) {
        return """
                {
                  "challenge": {
                    "id": "%s",
                    "realm": "my-service",
                    "method": "lightning",
                    "intent": "charge",
                    "request": "eyJhbW91bnQiOjEwfQ",
                    "expires": "2026-12-31T23:59:59Z"
                  },
                  "source": %s,
                  "payload": {
                    "preimage": "%s"
                  }
                }""".formatted(challengeId, sourceValue, preimageHex);
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
    }

    @Test
    void parsesValidCredentialWithAbsentSource() {
        // source key not present at all
        String json = """
                {
                  "challenge": {
                    "id": "%s",
                    "realm": "svc",
                    "method": "lightning",
                    "intent": "charge",
                    "request": "eyJhbW91bnQiOjEwfQ",
                    "expires": "2026-12-31T23:59:59Z"
                  },
                  "payload": {
                    "preimage": "%s"
                  }
                }""".formatted(VALID_CHALLENGE_ID, VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        PaymentCredential cred = MppCredentialParser.parse(blob);

        assertThat(cred.source()).isNull();
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
        assertThat(challenge).containsEntry("request", "eyJhbW91bnQiOjEwfQ");
        assertThat(challenge).containsEntry("expires", "2026-12-31T23:59:59Z");
        assertThat(challenge).hasSize(6);
    }

    @Test
    void metadataContainsRawJsonAndSource() {
        String json = validJson();
        String blob = toBlob(json);

        PaymentCredential cred = MppCredentialParser.parse(blob);
        MppMetadata meta = (MppMetadata) cred.metadata();

        assertThat(meta.rawCredentialJson()).isEqualTo(json);
        assertThat(meta.source()).isEqualTo("did:example:alice");
    }

    // --- Base64url error ---

    @Test
    void rejectsInvalidBase64url() {
        assertThatThrownBy(() -> MppCredentialParser.parse("!!!not-base64!!!"))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_CREDENTIAL);
                    assertThat(pve.getMessage()).contains("Invalid base64url encoding");
                });
    }

    // --- JSON errors ---

    @Test
    void rejectsInvalidJson() {
        String blob = toBlob("{not valid json");

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_CREDENTIAL);
                    assertThat(pve.getMessage()).contains("Invalid JSON");
                });
    }

    @Test
    void rejectsTrailingContent() {
        String blob = toBlob(validJson() + "extra");

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_CREDENTIAL);
                });
    }

    // --- Missing challenge ---

    @Test
    void rejectsMissingChallengeObject() {
        String json = """
                {
                  "payload": { "preimage": "%s" }
                }""".formatted(VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_CREDENTIAL);
                    assertThat(pve.getMessage()).contains("Missing 'challenge' object");
                });
    }

    @Test
    void rejectsChallengeAsString() {
        String json = """
                {
                  "challenge": "not-an-object",
                  "payload": { "preimage": "%s" }
                }""".formatted(VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getMessage()).contains("Missing 'challenge' object");
                });
    }

    // --- Missing challenge.id ---

    @Test
    void rejectsMissingChallengeId() {
        String json = """
                {
                  "challenge": {
                    "realm": "svc",
                    "method": "lightning"
                  },
                  "payload": { "preimage": "%s" }
                }""".formatted(VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getMessage()).contains("Missing 'id' in challenge");
                });
    }

    @Test
    void rejectsEmptyChallengeId() {
        String json = """
                {
                  "challenge": {
                    "id": "",
                    "realm": "svc"
                  },
                  "payload": { "preimage": "%s" }
                }""".formatted(VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getMessage()).contains("Missing 'id' in challenge");
                });
    }

    // --- Missing payload.preimage ---

    @Test
    void rejectsMissingPayloadObject() {
        String json = """
                {
                  "challenge": { "id": "abc" }
                }""";
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getMessage()).contains("Missing 'payload.preimage'");
                });
    }

    @Test
    void rejectsMissingPreimageInPayload() {
        String json = """
                {
                  "challenge": { "id": "abc" },
                  "payload": { }
                }""";
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getMessage()).contains("Missing 'payload.preimage'");
                });
    }

    // --- Invalid preimage hex ---

    @ParameterizedTest
    @ValueSource(strings = {
            // Too short
            "0001020304050607",
            // Too long (66 chars)
            "000102030405060708091011121314151617181920212223242526272829303132",
            // Uppercase hex
            "000102030405060708091011121314151617181920212223242526272829AABB",
            // Non-hex characters
            "gg01020304050607080910111213141516171819202122232425262728293031",
            // Empty
            ""
    })
    void rejectsInvalidPreimageHex(String badHex) {
        String json = """
                {
                  "challenge": { "id": "abc" },
                  "payload": { "preimage": "%s" }
                }""".formatted(badHex);
        String blob = toBlob(json);

        assertThatThrownBy(() -> MppCredentialParser.parse(blob))
                .isInstanceOf(PaymentValidationException.class)
                .satisfies(e -> {
                    PaymentValidationException pve = (PaymentValidationException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_CREDENTIAL);
                    assertThat(pve.getMessage()).contains("Invalid preimage hex");
                });
    }

    // --- Edge cases ---

    @Test
    void handlesJsonWithEscapedStrings() {
        // source contains escaped characters
        String json = """
                {
                  "challenge": { "id": "test-id" },
                  "source": "did:example:alice\\/bob",
                  "payload": { "preimage": "%s" }
                }""".formatted(VALID_PREIMAGE_HEX);
        String blob = toBlob(json);

        PaymentCredential cred = MppCredentialParser.parse(blob);

        assertThat(cred.source()).isEqualTo("did:example:alice/bob");
    }

    @Test
    void handlesMinimalValidCredential() {
        // Only required fields: challenge.id and payload.preimage
        String json = """
                {"challenge":{"id":"x"},"payload":{"preimage":"%s"}}""".formatted(VALID_PREIMAGE_HEX);
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
    void base64urlWithPaddingIsAlsoAccepted() {
        // Base64.getUrlDecoder() handles both padded and unpadded
        String json = validJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        String blob = Base64.getUrlEncoder().encodeToString(bytes);

        PaymentCredential cred = MppCredentialParser.parse(blob);
        assertThat(cred.tokenId()).isEqualTo(VALID_CHALLENGE_ID);
    }
}
