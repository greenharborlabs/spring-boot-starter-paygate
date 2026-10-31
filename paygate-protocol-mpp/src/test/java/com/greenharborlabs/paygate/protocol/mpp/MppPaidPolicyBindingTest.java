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
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for SR-02: an MPP credential is bound to the route, method, capability, and
 * price of the paid policy that issued it, not only to the request digest.
 */
@DisplayName("MPP paid-policy binding")
class MppPaidPolicyBindingTest {

  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String REALM = "svc";
  private static final String DIGEST = "sha-256=:YmluZGluZy10ZXN0LWRpZ2VzdA==:";
  private static final String ROUTE = "/v/report/{id}";
  private static final String METHOD = "GET";
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) (0x40 + i);
    }
  }

  @Nested
  @DisplayName("price")
  class Price {

    @Test
    @DisplayName("credential paid at 1 sat is rejected where the trusted current price is 1000")
    void rejectsUnderpaidCredential() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 1L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "1000"));
      }
    }

    @Test
    @DisplayName("credential paid at exactly the current price validates")
    void acceptsEqualPrice() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 1000L, ROUTE, METHOD, null)) {
        assertThat(
                protocol.validateAndResolveCapabilities(
                    credential, requestContext(ROUTE, METHOD, null, "1000")))
            .isEmpty();
      }
    }

    @Test
    @DisplayName("credential paid above the current price validates")
    void acceptsOverpayment() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 1000L, ROUTE, METHOD, null)) {
        protocol.validate(credential, requestContext(ROUTE, METHOD, null, "999"));
      }
    }

    @Test
    @DisplayName("missing trusted current price fails closed")
    void rejectsMissingCurrentPrice() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 10L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, null));
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "+10", "010", "1.0", "1e3", " 10", "99999999999999999999"})
    @DisplayName("non-canonical trusted current price fails closed")
    void rejectsNonCanonicalCurrentPrice(String currentPrice) {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 10L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, currentPrice));
      }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"0100", "+100", "-100", "100.0", "1e2", "100 ", "9223372036854775808", ""})
    @DisplayName("HMAC-valid charge request with a non-canonical amount is rejected")
    void rejectsNonCanonicalBoundAmount(String amount) {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential =
              signedCredential(secret(1), chargeRequest(amount, "BTC"), opaque(ROUTE, "~"))) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "1"));
      }
    }

    @Test
    @DisplayName("HMAC-valid charge request in another currency is rejected")
    void rejectsUnexpectedCurrency() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential =
              signedCredential(secret(1), chargeRequest("1000", "sat"), opaque(ROUTE, "~"))) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "1"));
      }
    }
  }

  @Nested
  @DisplayName("route, method and capability")
  class PolicyIdentity {

    @Test
    @DisplayName("equal-price credential issued for another route is rejected")
    void rejectsDifferentRouteAtEqualPrice() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext("/v/report/export", METHOD, null, "5"));
      }
    }

    @Test
    @DisplayName("credential issued for another request method is rejected")
    void rejectsDifferentMethod() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, "HEAD", null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "5"));
      }
    }

    @Test
    @DisplayName("capability-free credential cannot be presented to a capability endpoint")
    void rejectsCapabilityEscalation() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, "premium", "5"));
      }
    }

    @Test
    @DisplayName("capability credential cannot be presented to an endpoint with another capability")
    void rejectsDifferentCapability() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, METHOD, "basic")) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, "premium", "5"));
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "5"));
      }
    }

    @Test
    @DisplayName("missing request route or method metadata fails closed")
    void rejectsMissingRouteOrMethodMetadata() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, METHOD, null)) {
        assertInvalid(protocol, credential, requestContext(null, METHOD, null, "5"));
        assertInvalid(protocol, credential, requestContext(ROUTE, null, null, "5"));
      }
    }

    @Test
    @DisplayName("matching credential returns the bound capabilities")
    void returnsBoundCapabilities() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential = issue(protocol, 5L, ROUTE, METHOD, "premium,export")) {
        assertThat(
                protocol.validateAndResolveCapabilities(
                    credential, requestContext(ROUTE, METHOD, "premium,export", "5")))
            .containsExactlyInAnyOrder("premium", "export");
      }
    }
  }

  @Nested
  @DisplayName("opaque integrity")
  class OpaqueIntegrity {

    @Test
    @DisplayName("credential without an opaque binding (issued before the upgrade) is rejected")
    void rejectsMissingOpaque() {
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential =
              signedCredential(secret(1), chargeRequest("10", "BTC"), null)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "10"));
      }
    }

    @Test
    @DisplayName("HMAC-valid opaque that lacks the capability field is rejected")
    void rejectsIncompleteOpaque() {
      String partial =
          encode(JcsSerializer.serialize(Map.of("paygate_route", ROUTE, "paygate_method", METHOD)));
      try (MppProtocol protocol = protocol(secret(1));
          PaymentCredential credential =
              signedCredential(secret(1), chargeRequest("10", "BTC"), partial)) {
        assertInvalid(protocol, credential, requestContext(ROUTE, METHOD, null, "10"));
      }
    }

    @Test
    @DisplayName("opaque rewritten to another route breaks the HMAC")
    void rejectsTamperedOpaque() {
      try (MppProtocol protocol = protocol(secret(1))) {
        Map<String, String> challenge = challengeFields(protocol, 5L, ROUTE, METHOD, null);
        challenge.put("opaque", opaque("/v/report/export", "~"));
        try (PaymentCredential credential = pay(protocol, challenge)) {
          assertInvalid(
              protocol, credential, requestContext("/v/report/export", METHOD, null, "5"));
        }
      }
    }

    @Test
    @DisplayName("caller-supplied opaque cannot pre-empt a reserved binding key")
    void rejectsReservedCallerOpaqueKey() {
      try (MppProtocol protocol = protocol(secret(1))) {
        ChallengeContext context =
            context(5L, ROUTE, METHOD, null, Map.of("paygate_route", "/elsewhere"));
        assertThatThrownBy(() -> protocol.formatChallenge(context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
      }
    }

    @Test
    @DisplayName("challenge cannot be issued without a route pattern or request method")
    void rejectsChallengeWithoutPolicyBoundary() {
      try (MppProtocol protocol = protocol(secret(1))) {
        assertThatThrownBy(() -> protocol.formatChallenge(context(5L, null, METHOD, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.formatChallenge(context(5L, ROUTE, " ", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }

    @Test
    @DisplayName("caller-supplied opaque entries are preserved next to the binding")
    void preservesCallerOpaque() {
      try (MppProtocol protocol = protocol(secret(1))) {
        Map<String, String> challenge =
            challengeFields(protocol, context(5L, ROUTE, METHOD, null, Map.of("session", "s1")));
        String json =
            new String(
                Base64.getUrlDecoder().decode(challenge.get("opaque")), StandardCharsets.UTF_8);
        assertThat(json)
            .isEqualTo(
                "{\"paygate_capability\":\"~\",\"paygate_method\":\"GET\","
                    + "\"paygate_route\":\"/v/report/{id}\",\"session\":\"s1\"}");
      }
    }
  }

  @Nested
  @DisplayName("secret rotation")
  class Rotation {

    @Test
    @DisplayName("bound credential signed with the previous secret still validates")
    void previousSecretStillValidates() {
      PaymentCredential credential;
      try (MppProtocol beforeRotation = protocol(secret(1))) {
        credential = issue(beforeRotation, 5L, ROUTE, METHOD, "premium");
      }
      try (MppProtocol rotated =
              new MppProtocol(secret(2), MppParserLimits.defaults(), secret(1), CLOCK);
          credential) {
        assertThat(
                rotated.validateAndResolveCapabilities(
                    credential, requestContext(ROUTE, METHOD, "premium", "5")))
            .containsExactly("premium");
        assertInvalid(rotated, credential, requestContext("/other", METHOD, "premium", "5"));
      }
    }
  }

  // ---------- helpers ----------

  private static void assertInvalid(
      MppProtocol protocol, PaymentCredential credential, Map<String, String> requestContext) {
    assertThatThrownBy(() -> protocol.validate(credential, requestContext))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID));
  }

  private static MppProtocol protocol(SensitiveBytes secret) {
    return new MppProtocol(secret, CLOCK);
  }

  private static Map<String, String> requestContext(
      String route, String method, String capability, String currentPrice) {
    Map<String, String> context = new HashMap<>();
    context.put("request.digest", DIGEST);
    putIfPresent(context, "request.route", route);
    putIfPresent(context, "request.method", method);
    putIfPresent(context, "request.capability", capability);
    putIfPresent(context, "request.current_price_sats", currentPrice);
    return context;
  }

  private static void putIfPresent(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static ChallengeContext context(
      long price, String route, String method, String capability, Map<String, String> opaque) {
    return new ChallengeContext(
        sha256(PREIMAGE),
        "00".repeat(32),
        "lnbcrt1binding",
        price,
        "binding test",
        REALM,
        600,
        capability,
        null,
        opaque,
        DIGEST,
        route,
        method);
  }

  private static PaymentCredential issue(
      MppProtocol protocol, long price, String route, String method, String capability) {
    return pay(protocol, challengeFields(protocol, price, route, method, capability));
  }

  private static Map<String, String> challengeFields(
      MppProtocol protocol, long price, String route, String method, String capability) {
    return challengeFields(protocol, context(price, route, method, capability, null));
  }

  private static Map<String, String> challengeFields(
      MppProtocol protocol, ChallengeContext context) {
    Map<String, String> fields = new TreeMap<>();
    protocol
        .formatChallenge(context)
        .bodyData()
        .forEach((key, value) -> fields.put(key, (String) value));
    return fields;
  }

  private static PaymentCredential pay(MppProtocol protocol, Map<String, String> challenge) {
    Map<String, Object> root = new TreeMap<>();
    root.put("challenge", new TreeMap<String, Object>(challenge));
    root.put("payload", Map.of("preimage", HexFormat.of().formatHex(PREIMAGE)));
    return protocol.parseCredential("Payment " + encode(JcsSerializer.serialize(root)));
  }

  /** Builds a credential whose HMAC is valid over arbitrary request and opaque slots. */
  private static PaymentCredential signedCredential(
      SensitiveBytes secret, String requestB64, String opaqueB64) {
    String expires = DateTimeFormatter.ISO_INSTANT.format(NOW.plusSeconds(600));
    String id =
        MppChallengeBinding.createId(
            REALM, "lightning", "charge", requestB64, expires, DIGEST, opaqueB64, secret);
    Map<String, String> challenge = new TreeMap<>();
    challenge.put("id", id);
    challenge.put("realm", REALM);
    challenge.put("method", "lightning");
    challenge.put("intent", "charge");
    challenge.put("request", requestB64);
    challenge.put("expires", expires);
    challenge.put("digest", DIGEST);
    if (opaqueB64 != null) {
      challenge.put("opaque", opaqueB64);
    }
    return new PaymentCredential(
        sha256(PREIMAGE), PREIMAGE, id, "Payment", null, new MppMetadata(challenge, null));
  }

  private static String chargeRequest(String amount, String currency) {
    var details =
        new LightningChargeRequest.MethodDetails(
            "lnbcrt1binding", HexFormat.of().formatHex(sha256(PREIMAGE)), "mainnet");
    return encode(
        JcsSerializer.serialize(
            new LightningChargeRequest(amount, currency, null, details).toJcsMap()));
  }

  private static String opaque(String route, String capability) {
    return encode(
        JcsSerializer.serialize(
            Map.of(
                "paygate_route",
                route,
                "paygate_method",
                METHOD,
                "paygate_capability",
                capability)));
  }

  private static String encode(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static SensitiveBytes secret(int seed) {
    byte[] bytes = new byte[32];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (seed * 7 + i);
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
