package com.greenharborlabs.paygate.protocol.l402;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Client-address binding mints a {@code client_ip} caveat that is enforced only by a registered
 * {@link ClientIpCaveatVerifier}; unknown caveats are skipped. Binding must therefore refuse a
 * validator without that verifier instead of issuing an unenforced restriction.
 */
@DisplayName("L402Protocol client-address binding verifier requirement")
class L402ProtocolClientAddressBindingVerifierTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE = "svc";
  private static final String ROUTE = "/api/report/{id}";
  private static final String BOUND_ADDRESS = "203.0.113.5";

  @Test
  @DisplayName("binding with a registered client_ip verifier rejects a replay from another address")
  void bindingEnforcedWhenClientIpVerifierRegistered() throws Exception {
    try (Fixture f = new Fixture()) {
      assertThatCode(() -> f.protocol.validate(f.credential(), f.request(BOUND_ADDRESS)))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> f.protocol.validate(f.credential(), f.request("198.51.100.9")))
          .isInstanceOf(PaymentValidationException.class);
    }
  }

  @Test
  @DisplayName("binding with a verifier list lacking client_ip fails construction")
  void bindingRejectsValidatorWithoutClientIpVerifier() {
    try (var store = new InMemoryCredentialStore(10, 0)) {
      L402Validator validator =
          new L402Validator(new EmptyRootKeyStore(), store, boundaryVerifiers(), SERVICE);

      assertThatThrownBy(() -> new L402Protocol(validator, SERVICE, Clock.systemUTC(), true))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ClientIpCaveatVerifier")
          .hasMessageContaining("client_ip");
      assertThatCode(() -> new L402Protocol(validator, SERVICE, Clock.systemUTC(), false))
          .doesNotThrowAnyException();
    }
  }

  private static List<CaveatVerifier> boundaryVerifiers() {
    return new ArrayList<>(
        List.of(
            new ServicesCaveatVerifier(50),
            new RouteCaveatVerifier(50),
            new MethodCaveatVerifier(50),
            new CapabilitiesCaveatVerifier(SERVICE, 50),
            new ValidUntilCaveatVerifier(SERVICE)));
  }

  private static final class EmptyRootKeyStore implements RootKeyStore {
    @Override
    public GenerationResult generateRootKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      return null;
    }

    @Override
    public void revokeRootKey(byte[] keyId) {}
  }

  private static final class Fixture implements AutoCloseable {
    final byte[] rootKey = random(32);
    final byte[] preimage = random(32);
    final byte[] tokenId = random(32);
    final InMemoryCredentialStore store = new InMemoryCredentialStore(10, 0);
    final L402Protocol protocol;
    final String header;

    Fixture() throws Exception {
      RootKeyStore keys =
          new RootKeyStore() {
            @Override
            public GenerationResult generateRootKey() {
              throw new UnsupportedOperationException();
            }

            @Override
            public SensitiveBytes getRootKey(byte[] keyId) {
              return Arrays.equals(keyId, tokenId) ? new SensitiveBytes(rootKey.clone()) : null;
            }

            @Override
            public void revokeRootKey(byte[] keyId) {}
          };
      List<CaveatVerifier> verifiers = boundaryVerifiers();
      verifiers.add(new ClientIpCaveatVerifier(50));
      L402Validator validator = new L402Validator(keys, store, verifiers, SERVICE);
      protocol = new L402Protocol(validator, SERVICE, Clock.systemUTC(), true);

      byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
      ChallengeResponse challenge =
          protocol.formatChallenge(
              new ChallengeContext(
                  paymentHash,
                  HEX.formatHex(tokenId),
                  "lnbcrt10test",
                  10L,
                  null,
                  SERVICE,
                  600L,
                  null,
                  rootKey,
                  null,
                  null,
                  ROUTE,
                  "GET",
                  null,
                  false,
                  BOUND_ADDRESS));
      Matcher m = Pattern.compile("token=\"([^\"]+)\"").matcher(challenge.wwwAuthenticateHeader());
      if (!m.find()) {
        throw new AssertionError("no token in challenge");
      }
      header = "L402 " + m.group(1) + ":" + HEX.formatHex(preimage);
    }

    PaymentCredential credential() {
      return protocol.parseCredential(header);
    }

    Map<String, String> request(String clientIp) {
      return Map.of(
          VerificationContextKeys.REQUEST_ROUTE, ROUTE,
          VerificationContextKeys.REQUEST_METHOD, "GET",
          VerificationContextKeys.REQUEST_CLIENT_IP, clientIp,
          VerificationContextKeys.CURRENT_PRICE_SATS, "10",
          VerificationContextKeys.PRICING_STABILITY, "REQUEST_DEPENDENT");
    }

    @Override
    public void close() {
      store.close();
    }
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }
}
