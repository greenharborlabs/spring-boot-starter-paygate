package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/**
 * Regression tests for the issuer-prefix client-address binding rule on the Spring Security path
 * (security review 2026-10-18, SR-12 and I-02; keel-review Attempt 1 finding 1).
 *
 * <p>In Spring Security mode, L402 credentials are validated by {@link
 * PaygateAuthenticationProvider}, not {@code L402Protocol}. With client-address binding enabled, a
 * credential minted while binding was disabled must stay rejected even when its holder appends a
 * {@code client_ip} caveat equal to the requesting address.
 */
class ProviderIssuerClientAddressBindingRegressionTest {

  private static final String SERVICE = "svc";
  private static final String ROUTE = "/widgets/{id}";
  private static final String ADDRESS = "203.0.113.66";
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void legacyUnboundCredentialCannotBeSelfBoundByAppendingClientIp() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, null);
    PaygateAuthenticationProvider provider = provider(rootKeyStore, true);

    assertRejectedAsInvalid(provider, header);

    // The holder appends client_ip=<own address>; it is after valid_until, so not issuer binding.
    String attackHeader = appendCaveat(header, new Caveat("client_ip", ADDRESS));
    assertRejectedAsInvalid(provider, attackHeader);
  }

  @Test
  void issuerBoundCredentialStillAuthenticatesAndRejectsAnAddedSecondClientIp() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, ADDRESS);
    PaygateAuthenticationProvider provider = provider(rootKeyStore, true);

    Authentication authenticated = provider.authenticate(token(header, ADDRESS));
    assertThat(authenticated.isAuthenticated()).isTrue();

    String doubled = appendCaveat(header, new Caveat("client_ip", ADDRESS));
    assertRejectedAsInvalid(provider, doubled);
  }

  @Test
  void unboundCredentialStillAuthenticatesWhenBindingIsDisabled() throws Exception {
    InMemoryRootKeyStore rootKeyStore = new InMemoryRootKeyStore();
    String header = issue(rootKeyStore, null);
    PaygateAuthenticationProvider provider = provider(rootKeyStore, false);

    assertThat(provider.authenticate(token(header, ADDRESS)).isAuthenticated()).isTrue();
  }

  private static void assertRejectedAsInvalid(
      PaygateAuthenticationProvider provider, String header) {
    assertThatThrownBy(() -> provider.authenticate(token(header, ADDRESS)))
        .isInstanceOf(BadCredentialsException.class)
        .cause()
        .isInstanceOfSatisfying(
            PaymentValidationException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.INVALID));
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** Mints and pays a credential with the caveat order used by built-in L402 issuance. */
  private static String issue(RootKeyStore rootKeyStore, String clientAddress) throws Exception {
    byte[] preimage = new byte[32];
    new SecureRandom().nextBytes(preimage);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    List<Caveat> caveats = new ArrayList<>();
    caveats.add(new Caveat("services", SERVICE + ":0"));
    caveats.add(new Caveat("route", ROUTE));
    caveats.add(new Caveat("method", "GET"));
    if (clientAddress != null) {
      caveats.add(new Caveat("client_ip", clientAddress));
    }
    caveats.add(new Caveat(SERVICE + "_price_sats", "10"));
    caveats.add(new Caveat(SERVICE + "_capabilities", "~"));
    caveats.add(
        new Caveat(
            SERVICE + "_valid_until",
            String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond())));
    try (RootKeyStore.GenerationResult gen = rootKeyStore.generateRootKey()) {
      Macaroon macaroon =
          MacaroonMinter.mint(
              gen.rootKey().value(),
              new MacaroonIdentifier(1, paymentHash, gen.tokenId()),
              null,
              caveats);
      return "L402 "
          + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
          + ":"
          + HEX.formatHex(preimage);
    }
  }

  /** Holder-side attenuation: needs only the macaroon's current signature. */
  private static String appendCaveat(String header, Caveat caveat) {
    String b64 = header.substring(5, header.indexOf(':'));
    Macaroon mac = MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(b64));
    List<Caveat> caveats = new ArrayList<>(mac.caveats());
    caveats.add(caveat);
    byte[] sig =
        MacaroonCrypto.hmac(mac.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    Macaroon attenuated = new Macaroon(mac.identifier(), mac.location(), caveats, sig);
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated))
        + header.substring(header.indexOf(':'));
  }

  private static PaygateAuthenticationToken token(String header, String clientAddress) {
    Map<String, String> metadata =
        Map.of(
            VerificationContextKeys.REQUEST_ROUTE, ROUTE,
            VerificationContextKeys.REQUEST_METHOD, "GET",
            VerificationContextKeys.REQUEST_PATH, "/widgets/1",
            VerificationContextKeys.REQUEST_CLIENT_IP, clientAddress,
            VerificationContextKeys.CURRENT_PRICE_SATS, "10",
            VerificationContextKeys.PRICING_STABILITY, "REQUEST_DEPENDENT");
    return new PaygateAuthenticationToken(L402HeaderComponents.extractOrThrow(header), metadata);
  }

  private static PaygateAuthenticationProvider provider(RootKeyStore store, boolean binding) {
    List<CaveatVerifier> verifiers =
        List.of(
            new ServicesCaveatVerifier(50),
            new RouteCaveatVerifier(50),
            new MethodCaveatVerifier(50),
            new CapabilitiesCaveatVerifier(SERVICE, 50),
            new ValidUntilCaveatVerifier(SERVICE),
            new ClientIpCaveatVerifier(50));
    L402Validator validator =
        new L402Validator(store, new InMemoryCredentialStore(100), verifiers, SERVICE);
    return new PaygateAuthenticationProvider(validator, List.of(), SERVICE, _ -> Set.of(), binding);
  }
}
