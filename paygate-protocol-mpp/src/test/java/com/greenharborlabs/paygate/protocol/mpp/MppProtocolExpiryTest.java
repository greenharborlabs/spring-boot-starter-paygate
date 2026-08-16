package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MppProtocolExpiryTest {

  private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String REALM = "expiry-test";
  private static final String DIGEST = "sha-256=:ZXhwaXJ5LXRlc3Q=:";
  private static final byte[] SECRET_BYTES = new byte[32];
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < SECRET_BYTES.length; i++) {
      SECRET_BYTES[i] = (byte) (i + 1);
      PREIMAGE[i] = (byte) (i + 32);
    }
  }

  @Test
  void rejectsHmacValidChallengeWithMissingExpiry() {
    assertRejected(buildCredential(null));
  }

  @Test
  void rejectsHmacValidChallengeWithInvalidExpiry() {
    assertRejected(buildCredential("not-an-rfc-3339-timestamp"));
  }

  @Test
  void rejectsHmacValidExpiredChallengeUsingInjectedClock() {
    assertRejected(buildCredential(timestamp(NOW.minusNanos(1))));
  }

  @Test
  void acceptsExpiryAtOneSecondLowerBoundary() {
    assertThatCode(
            () ->
                protocol()
                    .validate(buildCredential(timestamp(NOW.plusSeconds(1))), requestContext()))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsExpiryAtMaximumTwentyFourHourBoundary() {
    assertThatCode(
            () ->
                protocol()
                    .validate(
                        buildCredential(timestamp(NOW.plusSeconds(24 * 60 * 60))),
                        requestContext()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsHmacValidExpiryBeyondMaximumTwentyFourHours() {
    assertRejected(buildCredential(timestamp(NOW.plusSeconds(24 * 60 * 60).plusNanos(1))));
  }

  private static void assertRejected(PaymentCredential credential) {
    assertThatThrownBy(() -> protocol().validate(credential, requestContext()))
        .isInstanceOf(PaymentValidationException.class);
  }

  private static MppProtocol protocol() {
    return new MppProtocol(secret(), CLOCK);
  }

  private static PaymentCredential buildCredential(String expires) {
    byte[] paymentHash = sha256(PREIMAGE);
    String request = request();
    String id =
        MppChallengeBinding.createId(
            REALM, "lightning", "charge", request, expires, DIGEST, null, secret());
    Map<String, String> challenge = new LinkedHashMap<>();
    challenge.put("id", id);
    challenge.put("realm", REALM);
    challenge.put("method", "lightning");
    challenge.put("intent", "charge");
    challenge.put("request", request);
    if (expires != null) {
      challenge.put("expires", expires);
    }
    challenge.put("digest", DIGEST);
    return new PaymentCredential(
        paymentHash, PREIMAGE, id, "Payment", null, new MppMetadata(challenge, null));
  }

  private static Map<String, String> requestContext() {
    return Map.of("request.digest", DIGEST);
  }

  private static String request() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString("{\"amount\":\"1\"}".getBytes(StandardCharsets.UTF_8));
  }

  private static String timestamp(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }

  private static SensitiveBytes secret() {
    return new SensitiveBytes(SECRET_BYTES.clone());
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
