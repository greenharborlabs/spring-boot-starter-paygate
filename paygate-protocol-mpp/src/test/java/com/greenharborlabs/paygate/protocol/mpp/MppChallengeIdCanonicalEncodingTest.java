package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for 2026-10-18 security review item I-13: a challenge {@code id} verifies only
 * in the canonical unpadded base64url spelling it was issued with, so one challenge cannot yield
 * several distinct token IDs. Secrets and preimages are synthetic fixtures and are never printed.
 */
class MppChallengeIdCanonicalEncodingTest {

  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String DIGEST = "sha-256=:c2VjcmV2aWV3LWRpZ2VzdA==:";
  private static final String ROUTE = "/paid";
  private static final String METHOD = "GET";
  private static final String BASE64URL_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final Map<String, String> REQUEST_CONTEXT =
      Map.of(
          "request.digest", DIGEST,
          "request.route", ROUTE,
          "request.method", METHOD,
          "request.current_price_sats", "10");
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) (0x40 + i);
    }
  }

  @Test
  @DisplayName("padded and nonzero-trailing-bit spellings of a challenge id do not verify")
  void noncanonicalIdSpellingsDoNotVerify() {
    try (MppProtocol protocol = new MppProtocol(secret(), CLOCK)) {
      Map<String, String> challenge = challengeFields(protocol);
      String id = challenge.get("id");
      assertThat(id).hasSize(43);
      assertThat(verify(id, challenge)).as("issued id").isTrue();

      // 43 characters encode 258 bits, so the final character carries two unused bits.
      char last = id.charAt(42);
      String flipped =
          id.substring(0, 42) + BASE64URL_ALPHABET.charAt(BASE64URL_ALPHABET.indexOf(last) ^ 1);
      for (String variant : new String[] {id + "=", flipped}) {
        assertThat(variant).isNotEqualTo(id);
        assertThat(Base64.getUrlDecoder().decode(variant))
            .as("the JDK decoder maps %s to the issued HMAC", variant)
            .isEqualTo(Base64.getUrlDecoder().decode(id));
        assertThat(verify(variant, challenge)).as("variant %s", variant).isFalse();
      }
    }
  }

  @Test
  @DisplayName("a credential echoing a padded challenge id is rejected as INVALID")
  void credentialWithPaddedIdIsRejected() {
    try (MppProtocol protocol = new MppProtocol(secret(), CLOCK)) {
      Map<String, String> challenge = challengeFields(protocol);
      challenge.put("id", challenge.get("id") + "=");
      PaymentCredential credential = pay(protocol, challenge);
      try {
        assertThatThrownBy(() -> protocol.validate(credential, REQUEST_CONTEXT))
            .isInstanceOfSatisfying(
                PaymentValidationException.class,
                e ->
                    assertThat(e.getErrorCode())
                        .isEqualTo(PaymentValidationException.ErrorCode.INVALID));
      } finally {
        credential.close();
      }
    }
  }

  @Test
  @DisplayName("a credential echoing the issued challenge id is accepted")
  void credentialWithIssuedIdIsAccepted() {
    try (MppProtocol protocol = new MppProtocol(secret(), CLOCK)) {
      PaymentCredential credential = pay(protocol, challengeFields(protocol));
      try {
        assertThatCode(() -> protocol.validate(credential, REQUEST_CONTEXT))
            .doesNotThrowAnyException();
      } finally {
        credential.close();
      }
    }
  }

  private static boolean verify(String id, Map<String, String> challenge) {
    return MppChallengeBinding.verify(
        id,
        challenge.get("realm"),
        challenge.get("method"),
        challenge.get("intent"),
        challenge.get("request"),
        challenge.get("expires"),
        challenge.get("digest"),
        challenge.get("opaque"),
        secret());
  }

  private static Map<String, String> challengeFields(MppProtocol protocol) {
    var context =
        new ChallengeContext(
            sha256(PREIMAGE),
            "00".repeat(32),
            "lnbcrt1canonical",
            10L,
            "canonical",
            "svc",
            600,
            null,
            null,
            null,
            DIGEST,
            ROUTE,
            METHOD);
    ChallengeResponse response = protocol.formatChallenge(context);
    Map<String, String> fields = new TreeMap<>();
    for (var entry : response.bodyData().entrySet()) {
      fields.put(entry.getKey(), (String) entry.getValue());
    }
    return fields;
  }

  private static PaymentCredential pay(MppProtocol protocol, Map<String, String> challenge) {
    Map<String, Object> root = new TreeMap<>();
    root.put("challenge", new TreeMap<String, Object>(challenge));
    root.put("payload", Map.of("preimage", HexFormat.of().formatHex(PREIMAGE)));
    String blob =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(JcsSerializer.serialize(root).getBytes(StandardCharsets.UTF_8));
    return protocol.parseCredential("Payment " + blob);
  }

  private static SensitiveBytes secret() {
    byte[] secret = new byte[32];
    for (int i = 0; i < secret.length; i++) {
      secret[i] = (byte) (7 + i);
    }
    return new SensitiveBytes(secret);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
