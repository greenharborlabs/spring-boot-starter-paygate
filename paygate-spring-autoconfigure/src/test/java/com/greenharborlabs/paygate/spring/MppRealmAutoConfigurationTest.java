package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.protocol.mpp.JcsSerializer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * Verifies that the auto-configured MPP protocol is bound to {@code paygate.service-name}
 * (SR-2026-10-18-18): it rejects a challenge minted under the shared secret for another realm.
 */
@DisplayName("Auto-configured MPP realm binding")
class MppRealmAutoConfigurationTest {

  private static final String SECRET = "abcdefghijklmnopqrstuvwxyz012345";
  private static final String DIGEST = "sha-256=:YXV0b2NvbmZpZy1yZWFsbS1kaWdlc3Q=:";
  private static final String ROUTE = "/api/realm";
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) (0x60 + i);
    }
  }

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.protocols.mpp.challenge-binding-secret=" + SECRET)
          .withBean(LightningBackend.class, AutoConfigurationTest.StubLightningBackend::new);

  @Test
  @DisplayName("accepts its own realm and rejects another realm under the same secret")
  void rejectsCredentialForAnotherRealm() {
    contextRunner
        .withPropertyValues("paygate.service-name=service-a")
        .run(
            context -> {
              PaymentProtocol mpp = context.getBean("mppProtocol", PaymentProtocol.class);

              try (PaymentCredential own = issue(mpp, "service-a")) {
                assertThat(mpp.validateAndResolveCapabilities(own, requestContext())).isEmpty();
              }
              try (PaymentCredential foreign = issue(mpp, "service-b")) {
                assertThatThrownBy(() -> mpp.validate(foreign, requestContext()))
                    .isInstanceOfSatisfying(
                        PaymentValidationException.class,
                        e ->
                            assertThat(e.getErrorCode())
                                .isEqualTo(PaymentValidationException.ErrorCode.INVALID));
              }
            });
  }

  @Test
  @DisplayName("an unset service name binds the realm to the challenge default, 'default'")
  void unsetServiceNameUsesDefaultRealm() {
    contextRunner.run(
        context -> {
          PaymentProtocol mpp = context.getBean("mppProtocol", PaymentProtocol.class);

          try (PaymentCredential own = issue(mpp, "default")) {
            assertThat(mpp.validateAndResolveCapabilities(own, requestContext())).isEmpty();
          }
          try (PaymentCredential foreign = issue(mpp, "service-b")) {
            assertThatThrownBy(() -> mpp.validate(foreign, requestContext()))
                .isInstanceOf(PaymentValidationException.class);
          }
        });
  }

  private static Map<String, String> requestContext() {
    return Map.of(
        "request.digest",
        DIGEST,
        "request.route",
        ROUTE,
        "request.method",
        "GET",
        "request.current_price_sats",
        "10");
  }

  /** Mints a challenge for {@code realm} with the shared secret and returns its paid credential. */
  private static PaymentCredential issue(PaymentProtocol mpp, String realm) {
    ChallengeContext context =
        new ChallengeContext(
            sha256(PREIMAGE),
            "00".repeat(32),
            "lnbcrt1realm",
            10L,
            "realm",
            realm,
            600,
            null,
            null,
            null,
            DIGEST,
            ROUTE,
            "GET");
    Map<String, Object> challenge = new TreeMap<>();
    mpp.formatChallenge(context).bodyData().forEach(challenge::put);
    Map<String, Object> root = new TreeMap<>();
    root.put("challenge", challenge);
    root.put("payload", Map.of("preimage", HexFormat.of().formatHex(PREIMAGE)));
    String blob =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(JcsSerializer.serialize(root).getBytes(StandardCharsets.UTF_8));
    return mpp.parseCredential("Payment " + blob);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
