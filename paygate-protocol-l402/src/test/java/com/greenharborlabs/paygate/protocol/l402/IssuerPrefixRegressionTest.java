package com.greenharborlabs.paygate.protocol.l402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.IssuerCaveatPrefix;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the issuer-prefix rule at the L402 protocol boundary (security review
 * 2026-10-18, SR-12 and I-02).
 *
 * <p>Credentials minted by {@link L402Protocol#formatChallenge} keep validating. With
 * client-address binding enabled, a still-live credential minted while binding was disabled stays
 * rejected even when its holder appends a {@code client_ip} caveat, because only a {@code
 * client_ip} before the first {@code {svc}_valid_until} counts as issuer binding.
 */
class IssuerPrefixRegressionTest {

  private static final String SERVICE = "svc";
  private static final String ROUTE = "/widgets/{id}";
  private static final String ADDRESS = "203.0.113.66";
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void legacyUnboundCredentialCannotBeSelfBoundAfterBindingIsEnabled() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, false, null);

    // Operator now enables binding.
    L402Protocol bound = protocol(rootKeyStore, true, null);
    Map<String, String> fromAttacker = request(ADDRESS, null);

    PaymentCredential legacy = bound.parseCredential(header);
    assertThatThrownBy(() -> bound.validate(legacy, fromAttacker))
        .isInstanceOf(PaymentValidationException.class);

    // The holder appends client_ip=<own address>; it is after valid_until, so not issuer binding.
    String attackHeader = appendCaveat(header, new Caveat("client_ip", ADDRESS));
    PaymentCredential attack = bound.parseCredential(attackHeader);
    assertThatThrownBy(() -> bound.validate(attack, fromAttacker))
        .isInstanceOfSatisfying(
            PaymentValidationException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.INVALID));
  }

  @Test
  void builtInBoundCredentialStillValidatesAndKeepsClientIpInIssuerPrefix() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, true, ADDRESS);
    Macaroon macaroon = decode(header);

    assertThat(macaroon.caveats().getLast().key()).isEqualTo(SERVICE + "_valid_until");
    assertThat(IssuerCaveatPrefix.of(macaroon.caveats(), SERVICE))
        .extracting(Caveat::key)
        .contains("client_ip", SERVICE + "_price_sats", SERVICE + "_capabilities");

    L402Protocol bound = protocol(rootKeyStore, true, null);
    assertThatCode(() -> bound.validate(bound.parseCredential(header), request(ADDRESS, "10")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> bound.validate(bound.parseCredential(header), request("198.51.100.9", "10")))
        .isInstanceOf(PaymentValidationException.class);

    // Holders may still not add a second client_ip, even one that matches.
    String doubled = appendCaveat(header, new Caveat("client_ip", ADDRESS));
    assertThatThrownBy(() -> bound.validate(bound.parseCredential(doubled), request(ADDRESS, "10")))
        .isInstanceOf(PaymentValidationException.class);
  }

  @Test
  void builtInUnboundCredentialStillValidatesWithAndWithoutBackend() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, false, null);

    for (LightningBackend backend : new LightningBackend[] {null, unusedBackend()}) {
      L402Protocol protocol = protocol(rootKeyStore, false, backend);
      assertThatCode(
              () -> protocol.validate(protocol.parseCredential(header), request(ADDRESS, "10")))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () -> protocol.validate(protocol.parseCredential(header), request(ADDRESS, "11")))
          .isInstanceOfSatisfying(
              PaymentValidationException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(PaymentValidationException.ErrorCode.INSUFFICIENT));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** Issues a credential through built-in {@link L402Protocol#formatChallenge} and pays it. */
  private static String issue(RootKeyStore rootKeyStore, boolean binding, String clientAddress)
      throws Exception {
    byte[] preimage = new byte[32];
    new SecureRandom().nextBytes(preimage);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    try (RootKeyStore.GenerationResult gen = rootKeyStore.generateRootKey();
        ChallengeContext ctx =
            new ChallengeContext(
                paymentHash,
                HEX.formatHex(gen.tokenId()),
                "lnbc1invoice",
                10L,
                "d",
                SERVICE,
                3600L,
                null,
                gen.rootKey().value(),
                null,
                null,
                ROUTE,
                "GET",
                null,
                false,
                clientAddress)) {
      L402Protocol issuer = protocol(rootKeyStore, binding, null);
      String www = issuer.formatChallenge(ctx).wwwAuthenticateHeader();
      Matcher m = Pattern.compile("token=\"([^\"]+)\"").matcher(www);
      assertThat(m.find()).isTrue();
      return "L402 " + m.group(1) + ":" + HEX.formatHex(preimage);
    }
  }

  private static Macaroon decode(String header) {
    String b64 = header.substring(5, header.indexOf(':'));
    return MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(b64));
  }

  /** Holder-side attenuation: needs only the macaroon's current signature. */
  private static String appendCaveat(String header, Caveat caveat) {
    Macaroon mac = decode(header);
    List<Caveat> caveats = new ArrayList<>(mac.caveats());
    caveats.add(caveat);
    byte[] sig =
        MacaroonCrypto.hmac(mac.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    Macaroon attenuated = new Macaroon(mac.identifier(), mac.location(), caveats, sig);
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated))
        + header.substring(header.indexOf(':'));
  }

  private static Map<String, String> request(String clientAddress, String currentPrice) {
    var metadata = new java.util.HashMap<String, String>();
    metadata.put(VerificationContextKeys.REQUEST_ROUTE, ROUTE);
    metadata.put(VerificationContextKeys.REQUEST_METHOD, "GET");
    metadata.put(VerificationContextKeys.REQUEST_PATH, "/widgets/1");
    metadata.put(VerificationContextKeys.REQUEST_CLIENT_IP, clientAddress);
    if (currentPrice != null) {
      metadata.put(VerificationContextKeys.CURRENT_PRICE_SATS, currentPrice);
      metadata.put(VerificationContextKeys.PRICING_STABILITY, "REQUEST_DEPENDENT");
    }
    return Map.copyOf(metadata);
  }

  private static L402Protocol protocol(
      RootKeyStore store, boolean binding, LightningBackend backend) {
    List<CaveatVerifier> verifiers =
        List.of(
            new ServicesCaveatVerifier(50),
            new RouteCaveatVerifier(50),
            new MethodCaveatVerifier(50),
            new CapabilitiesCaveatVerifier(SERVICE, 50),
            new ValidUntilCaveatVerifier(SERVICE),
            new ClientIpCaveatVerifier(50));
    L402Validator validator =
        backend == null
            ? new L402Validator(store, new InMemoryCredentialStore(100), verifiers, SERVICE)
            : new L402Validator(
                store, new InMemoryCredentialStore(100), verifiers, SERVICE, backend);
    return new L402Protocol(validator, SERVICE, Clock.systemUTC(), binding);
  }

  /** A backend that fails the test if signed-price credentials ever trigger an invoice lookup. */
  private static LightningBackend unusedBackend() {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amountSats, String memo) {
        throw new AssertionError("unexpected invoice creation");
      }

      @Override
      public Invoice lookupInvoice(byte[] paymentHash) {
        throw new AssertionError("signed-price credential must not need invoice evidence");
      }

      @Override
      public boolean isHealthy() {
        return true;
      }
    };
  }
}
