package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for SR-2026-10-18-18: a realm-bound {@link MppProtocol} rejects a credential
 * whose HMAC-valid challenge was issued for another service's realm under a shared binding secret.
 */
@DisplayName("MPP realm binding")
class MppRealmBindingTest {

  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String DIGEST = "sha-256=:cmVhbG0tYmluZGluZy1kaWdlc3Q=:";
  private static final String ROUTE = "/api/report/{id}";
  private static final String METHOD = "GET";
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) (0x20 + i);
    }
  }

  @Test
  @DisplayName("credential issued by another service sharing the secret is INVALID")
  void rejectsCrossRealmCredentialUnderSharedSecret() {
    try (MppProtocol serviceA = realmBound(secret(1), null, "service-a");
        MppProtocol serviceB = realmBound(secret(1), null, "service-b");
        PaymentCredential credential = issue(serviceA, "service-a")) {
      assertInvalid(serviceB, credential);
    }
  }

  @Test
  @DisplayName("credential issued for the same realm still validates")
  void acceptsSameRealmCredential() {
    try (MppProtocol serviceA = realmBound(secret(1), null, "service-a");
        PaymentCredential credential = issue(serviceA, "service-a")) {
      assertThat(serviceA.validateAndResolveCapabilities(credential, requestContext())).isEmpty();
    }
  }

  @Test
  @DisplayName("realm comparison is exact, not case-insensitive")
  void rejectsRealmDifferingOnlyByCase() {
    try (MppProtocol issuer = realmBound(secret(1), null, "Service-A");
        MppProtocol verifier = realmBound(secret(1), null, "service-a");
        PaymentCredential credential = issue(issuer, "Service-A")) {
      assertInvalid(verifier, credential);
    }
  }

  @Test
  @DisplayName("cross-realm credential signed with the previous secret is INVALID after rotation")
  void rejectsCrossRealmCredentialSignedWithPreviousSecret() {
    PaymentCredential credential;
    try (MppProtocol oldServiceA = realmBound(secret(1), null, "service-a")) {
      credential = issue(oldServiceA, "service-a");
    }
    try (MppProtocol rotatedServiceB = realmBound(secret(2), secret(1), "service-b");
        MppProtocol rotatedServiceA = realmBound(secret(2), secret(1), "service-a");
        credential) {
      assertInvalid(rotatedServiceB, credential);
      assertThat(rotatedServiceA.validateAndResolveCapabilities(credential, requestContext()))
          .isEmpty();
    }
  }

  @Test
  @DisplayName("legacy constructors keep accepting any realm under the shared secret")
  void legacyConstructorDoesNotCheckRealm() {
    try (MppProtocol serviceA = realmBound(secret(1), null, "service-a");
        MppProtocol legacy = new MppProtocol(secret(1), CLOCK);
        PaymentCredential credential = issue(serviceA, "service-a")) {
      assertThat(legacy.validateAndResolveCapabilities(credential, requestContext())).isEmpty();
    }
  }

  @Test
  @DisplayName("realm-bound constructor rejects a null service name")
  void rejectsNullServiceName() {
    assertThatThrownBy(() -> realmBound(secret(1), null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("serviceName");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  @DisplayName("realm-bound constructor rejects a blank service name")
  void rejectsBlankServiceName(String serviceName) {
    assertThatThrownBy(() -> realmBound(secret(1), null, serviceName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("serviceName");
  }

  // ---------- helpers ----------

  private static MppProtocol realmBound(
      SensitiveBytes secret, SensitiveBytes previousSecret, String serviceName) {
    return new MppProtocol(secret, MppParserLimits.defaults(), previousSecret, CLOCK, serviceName);
  }

  private static void assertInvalid(MppProtocol protocol, PaymentCredential credential) {
    assertThatThrownBy(() -> protocol.validate(credential, requestContext()))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID));
  }

  private static Map<String, String> requestContext() {
    Map<String, String> context = new HashMap<>();
    context.put("request.digest", DIGEST);
    context.put("request.route", ROUTE);
    context.put("request.method", METHOD);
    context.put("request.current_price_sats", "10");
    return context;
  }

  private static PaymentCredential issue(MppProtocol issuer, String realm) {
    ChallengeContext context =
        new ChallengeContext(
            sha256(PREIMAGE),
            "00".repeat(32),
            "lnbcrt1realm",
            10L,
            "realm test",
            realm,
            600,
            null,
            null,
            null,
            DIGEST,
            ROUTE,
            METHOD);
    Map<String, String> challenge = new TreeMap<>();
    issuer
        .formatChallenge(context)
        .bodyData()
        .forEach((key, value) -> challenge.put(key, (String) value));
    assertThat(challenge).containsEntry("realm", realm);

    Map<String, Object> root = new TreeMap<>();
    root.put("challenge", new TreeMap<String, Object>(challenge));
    root.put("payload", Map.of("preimage", HexFormat.of().formatHex(PREIMAGE)));
    String blob =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(JcsSerializer.serialize(root).getBytes(StandardCharsets.UTF_8));
    return issuer.parseCredential("Payment " + blob);
  }

  private static SensitiveBytes secret(int seed) {
    byte[] bytes = new byte[32];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (seed * 11 + i);
    }
    return new SensitiveBytes(bytes);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
