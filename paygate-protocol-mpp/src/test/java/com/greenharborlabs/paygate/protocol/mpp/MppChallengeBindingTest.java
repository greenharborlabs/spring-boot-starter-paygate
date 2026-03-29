package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MppChallengeBindingTest {

  private static final byte[] SECRET_BYTES =
      "test-hmac-secret-key-32bytes!!!!".getBytes(StandardCharsets.UTF_8);

  /** Creates a fresh SensitiveBytes wrapping a clone of SECRET_BYTES (safe for repeated use). */
  private static SensitiveBytes secret() {
    return new SensitiveBytes(SECRET_BYTES.clone());
  }

  private static final String REALM = "api.example.com";
  private static final String METHOD = "lightning";
  private static final String INTENT = "charge";
  private static final String REQUEST_B64 =
      Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString("{\"amount\":100}".getBytes(StandardCharsets.UTF_8));
  private static final String EXPIRES = "2026-12-31T23:59:59Z";
  private static final String DIGEST = "sha-256=:abc123:";
  private static final String OPAQUE_B64 =
      Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString("{\"tracking\":\"xyz\"}".getBytes(StandardCharsets.UTF_8));

  /** Base64url-nopad: only [A-Za-z0-9_-], no '=' padding. */
  private static final Pattern BASE64URL_NOPAD = Pattern.compile("^[A-Za-z0-9_-]+$");

  // ---- Round-trip: all 7 slots filled ----

  @Test
  void roundTrip_allSlotsFilled_verifyReturnsTrue() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    boolean result =
        MppChallengeBinding.verify(
            id, REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(result).isTrue();
  }

  // ---- Round-trip: absent optional slots ----

  @Test
  void roundTrip_nullOptionalSlots_verifyReturnsTrue() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, null, null, null, secret());

    boolean result =
        MppChallengeBinding.verify(
            id, REALM, METHOD, INTENT, REQUEST_B64, null, null, null, secret());

    assertThat(result).isTrue();
  }

  @Test
  void roundTrip_onlyExpiresNull_verifyReturnsTrue() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, null, DIGEST, OPAQUE_B64, secret());

    boolean result =
        MppChallengeBinding.verify(
            id, REALM, METHOD, INTENT, REQUEST_B64, null, DIGEST, OPAQUE_B64, secret());

    assertThat(result).isTrue();
  }

  // ---- Tampered ID ----

  @Test
  void verify_tamperedId_oneByteFlipped_returnsFalse() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    // Decode, flip one byte, re-encode
    byte[] decoded = Base64.getUrlDecoder().decode(id);
    decoded[0] ^= 0x01;
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);

    boolean result =
        MppChallengeBinding.verify(
            tampered, REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(result).isFalse();
  }

  // ---- Different field values ----

  @Test
  void verify_differentRealm_returnsFalse() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(
            MppChallengeBinding.verify(
                id,
                "other.example.com",
                METHOD,
                INTENT,
                REQUEST_B64,
                EXPIRES,
                DIGEST,
                OPAQUE_B64,
                secret()))
        .isFalse();
  }

  @Test
  void verify_differentMethod_returnsFalse() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(
            MppChallengeBinding.verify(
                id, REALM, "onchain", INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isFalse();
  }

  @Test
  void verify_differentRequest_returnsFalse() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    String otherRequest =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"amount\":999}".getBytes(StandardCharsets.UTF_8));

    assertThat(
            MppChallengeBinding.verify(
                id, REALM, METHOD, INTENT, otherRequest, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isFalse();
  }

  @Test
  void verify_differentSecret_returnsFalse() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    SensitiveBytes otherSecret =
        new SensitiveBytes("different-secret-key-32bytes!!!!!".getBytes(StandardCharsets.UTF_8));

    assertThat(
            MppChallengeBinding.verify(
                id, REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, otherSecret))
        .isFalse();
  }

  // ---- Malformed / empty ID ----

  @Test
  void verify_malformedBase64Id_returnsFalse() {
    // '!!!' is not valid base64url
    assertThat(
            MppChallengeBinding.verify(
                "!!!not-valid-base64!!!",
                REALM,
                METHOD,
                INTENT,
                REQUEST_B64,
                EXPIRES,
                DIGEST,
                OPAQUE_B64,
                secret()))
        .isFalse();
  }

  @Test
  void verify_emptyId_returnsFalse() {
    // Empty string decodes to zero-length byte array, which differs in length from 32-byte HMAC
    assertThat(
            MppChallengeBinding.verify(
                "", REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isFalse();
  }

  // ---- Null required parameters ----

  @Test
  void createId_nullRealm_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    null, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("realm");
  }

  @Test
  void createId_nullMethod_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, null, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("method");
  }

  @Test
  void createId_nullIntent_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, null, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("intent");
  }

  @Test
  void createId_nullRequestB64_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, INTENT, null, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("requestB64");
  }

  @Test
  void createId_nullSecret_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("secret");
  }

  @Test
  void verify_nullId_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.verify(
                    null,
                    REALM,
                    METHOD,
                    INTENT,
                    REQUEST_B64,
                    EXPIRES,
                    DIGEST,
                    OPAQUE_B64,
                    secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id");
  }

  @Test
  void verify_nullRealm_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.verify(
                    "dummy",
                    null,
                    METHOD,
                    INTENT,
                    REQUEST_B64,
                    EXPIRES,
                    DIGEST,
                    OPAQUE_B64,
                    secret()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("realm");
  }

  @Test
  void verify_nullSecret_throwsNpe() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.verify(
                    "dummy", REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("secret");
  }

  // ---- Output format: base64url without padding ----

  @Test
  void createId_outputIsValidBase64urlNoPad() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(id).matches(BASE64URL_NOPAD);
    assertThat(id).doesNotContain("=");
    assertThat(id).doesNotContain("+");
    assertThat(id).doesNotContain("/");
  }

  @Test
  void createId_outputDecodesToExactly32Bytes() {
    String id =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    byte[] decoded = Base64.getUrlDecoder().decode(id);
    // HMAC-SHA256 always produces 32 bytes
    assertThat(decoded).hasSize(32);
  }

  // ---- Determinism ----

  @Test
  void createId_sameInputs_producesSameOutput() {
    String id1 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());
    String id2 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThat(id1).isEqualTo(id2);
  }

  // ---- Differentiation: null vs empty string in optional slots ----

  @Test
  void nullOptionalField_differsFromEmptyStringOptionalField() {
    // null expires -> empty string slot, but if caller explicitly passes ""
    // both should produce the same ID since nullToEmpty("") == ""
    String idWithNull =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, null, DIGEST, OPAQUE_B64, secret());
    String idWithEmpty =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, "", DIGEST, OPAQUE_B64, secret());

    assertThat(idWithNull).isEqualTo(idWithEmpty);
  }

  // ---- Slot isolation: changing one optional slot changes the ID ----

  @Test
  void differentExpires_producesDifferentId() {
    String id1 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());
    String id2 =
        MppChallengeBinding.createId(
            REALM,
            METHOD,
            INTENT,
            REQUEST_B64,
            "2027-01-01T00:00:00Z",
            DIGEST,
            OPAQUE_B64,
            secret());

    assertThat(id1).isNotEqualTo(id2);
  }

  @Test
  void differentDigest_producesDifferentId() {
    String id1 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());
    String id2 =
        MppChallengeBinding.createId(
            REALM,
            METHOD,
            INTENT,
            REQUEST_B64,
            EXPIRES,
            "sha-256=:different:",
            OPAQUE_B64,
            secret());

    assertThat(id1).isNotEqualTo(id2);
  }

  // ---- Pipe delimiter injection ----

  @Test
  void createId_pipeInRealm_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    "evil|lightning",
                    METHOD,
                    INTENT,
                    REQUEST_B64,
                    EXPIRES,
                    DIGEST,
                    OPAQUE_B64,
                    secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm");
  }

  @Test
  void createId_pipeInMethod_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, "x|y", INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("method");
  }

  @Test
  void createId_pipeInIntent_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, "a|b", REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intent");
  }

  @Test
  void createId_pipeInExpires_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, INTENT, REQUEST_B64, "2026|01", DIGEST, OPAQUE_B64, secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expires");
  }

  @Test
  void createId_pipeInDigest_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                MppChallengeBinding.createId(
                    REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, "sha|256", OPAQUE_B64, secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void verify_pipeInRealm_throwsIllegalArgument() {
    // Use a valid base64url ID so the decode step succeeds before computeHmac is reached
    String validId =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());

    assertThatThrownBy(
            () ->
                MppChallengeBinding.verify(
                    validId,
                    "evil|x",
                    METHOD,
                    INTENT,
                    REQUEST_B64,
                    EXPIRES,
                    DIGEST,
                    OPAQUE_B64,
                    secret()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm");
  }

  @Test
  void differentOpaque_producesDifferentId() {
    String id1 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, OPAQUE_B64, secret());
    String otherOpaque =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"other\":true}".getBytes(StandardCharsets.UTF_8));
    String id2 =
        MppChallengeBinding.createId(
            REALM, METHOD, INTENT, REQUEST_B64, EXPIRES, DIGEST, otherOpaque, secret());

    assertThat(id1).isNotEqualTo(id2);
  }
}
