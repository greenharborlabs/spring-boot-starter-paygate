package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("L402 mandatory boundaries")
class L402MandatoryBoundariesTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE_NAME = "catalog-api";
  private static final String ROUTE = "/catalog/{id}";
  private static final String METHOD = "GET";

  private final Map<String, byte[]> rootKeys = new HashMap<>();
  private final Map<String, L402Credential> cachedCredentials = new HashMap<>();
  private byte[] rootKey;
  private byte[] preimage;
  private byte[] paymentHash;
  private byte[] tokenId;
  private String tokenIdHex;

  @BeforeEach
  void setUp() throws Exception {
    rootKeys.clear();
    cachedCredentials.clear();
    rootKey = randomBytes();
    preimage = randomBytes();
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    tokenId = randomBytes();
    tokenIdHex = HEX.formatHex(tokenId);
    rootKeys.put(tokenIdHex, rootKey.clone());
  }

  @ParameterizedTest(name = "missing {0} rejects fresh authorization")
  @MethodSource("mandatoryCaveatKeys")
  void missingMandatoryCaveatRejectsFreshAuthorization(String missingKey) {
    L402Validator validator = newValidator();
    String header = authorizationHeader(mint(caveatsWithout(missingKey)));

    assertBoundaryRejected(() -> validator.validate(header, context()));
  }

  @ParameterizedTest(name = "missing {0} rejects cached authorization")
  @MethodSource("mandatoryCaveatKeys")
  void missingMandatoryCaveatRejectsCachedAuthorization(String missingKey) {
    L402Validator validator = newValidator();
    Macaroon macaroon = mint(caveatsWithout(missingKey));
    cache(macaroon);

    assertBoundaryRejected(() -> validator.validate(authorizationHeader(macaroon), context()));
  }

  @Test
  @DisplayName("complete issuer boundaries retain fresh and cached capability behavior")
  void completeIssuerBoundariesRetainFreshAndCachedCapabilityBehavior() {
    L402Validator validator = newValidator();
    Macaroon macaroon = mint(mandatoryCaveats());
    String header = authorizationHeader(macaroon);

    L402Validator.ValidationResult fresh = validator.validate(header, context());
    assertThat(fresh.freshValidation()).isTrue();
    assertThat(fresh.effectiveCapabilities()).containsExactly("read");
    fresh.credential().destroy();

    L402Validator.ValidationResult cached = validator.validate(header, context());
    assertThat(cached.freshValidation()).isFalse();
    assertThat(cached.effectiveCapabilities()).containsExactly("read");
    cached.credential().destroy();
  }

  @ParameterizedTest(name = "incomplete verifier profile without {0} is rejected at construction")
  @MethodSource("mandatoryCaveatKeys")
  void incompleteVerifierProfileIsRejectedAtConstruction(String omittedVerifierKey) {
    List<CaveatVerifier> verifiers = new ArrayList<>(boundaryVerifiers());
    verifiers.removeIf(verifier -> verifier.getKey().equals(omittedVerifierKey));

    assertThatThrownBy(
            () -> new L402Validator(rootKeyStore(), credentialStore(), verifiers, SERVICE_NAME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(omittedVerifierKey);
  }

  private static Stream<String> mandatoryCaveatKeys() {
    return Stream.of(
        "services",
        "route",
        "method",
        SERVICE_NAME + "_capabilities",
        SERVICE_NAME + "_valid_until");
  }

  private L402Validator newValidator() {
    return new L402Validator(rootKeyStore(), credentialStore(), boundaryVerifiers(), SERVICE_NAME);
  }

  private RootKeyStore rootKeyStore() {
    return new RootKeyStore() {
      @Override
      public GenerationResult generateRootKey() {
        throw new UnsupportedOperationException("not used by validation");
      }

      @Override
      public SensitiveBytes getRootKey(byte[] keyId) {
        byte[] stored = rootKeys.get(HEX.formatHex(keyId));
        return stored == null ? null : new SensitiveBytes(stored.clone());
      }

      @Override
      public void revokeRootKey(byte[] keyId) {
        rootKeys.remove(HEX.formatHex(keyId));
      }
    };
  }

  private CredentialStore credentialStore() {
    return new CredentialStore() {
      @Override
      public void store(String id, L402Credential credential, long ttlSeconds) {
        L402Credential previous = cachedCredentials.put(id, credential.copy());
        if (previous != null) {
          previous.destroy();
        }
      }

      @Override
      public L402Credential get(String id) {
        L402Credential credential = cachedCredentials.get(id);
        return credential == null ? null : credential.copy();
      }

      @Override
      public void revoke(String id) {
        L402Credential removed = cachedCredentials.remove(id);
        if (removed != null) {
          removed.destroy();
        }
      }

      @Override
      public long activeCount() {
        return cachedCredentials.size();
      }
    };
  }

  private List<CaveatVerifier> boundaryVerifiers() {
    return List.of(
        new ServicesCaveatVerifier(10),
        new RouteCaveatVerifier(10),
        new MethodCaveatVerifier(10),
        new CapabilitiesCaveatVerifier(SERVICE_NAME, 10),
        new ValidUntilCaveatVerifier(SERVICE_NAME));
  }

  private List<Caveat> mandatoryCaveats() {
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", ROUTE),
        new Caveat("method", METHOD),
        new Caveat(SERVICE_NAME + "_capabilities", "read"),
        new Caveat(
            SERVICE_NAME + "_valid_until",
            Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())));
  }

  private List<Caveat> caveatsWithout(String missingKey) {
    List<Caveat> caveats = new ArrayList<>(mandatoryCaveats());
    caveats.removeIf(caveat -> caveat.key().equals(missingKey));
    return caveats;
  }

  private Macaroon mint(List<Caveat> caveats) {
    return MacaroonMinter.mint(
        rootKey,
        new MacaroonIdentifier(1, paymentHash, tokenId),
        "https://issuer.example",
        caveats);
  }

  private void cache(Macaroon macaroon) {
    credentialStore()
        .store(
            tokenIdHex,
            new L402Credential(
                macaroon, PaymentPreimage.fromHex(HEX.formatHex(preimage)), tokenIdHex),
            3600);
  }

  private String authorizationHeader(Macaroon macaroon) {
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(preimage);
  }

  private L402VerificationContext context() {
    return L402VerificationContext.builder()
        .serviceName(SERVICE_NAME)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE, ROUTE,
                VerificationContextKeys.REQUEST_METHOD, METHOD,
                VerificationContextKeys.REQUESTED_CAPABILITY, "read"))
        .build();
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private static void assertBoundaryRejected(ThrowingValidation validation) {
    assertThatThrownBy(validation::validate)
        .isInstanceOf(L402Exception.class)
        .extracting(error -> ((L402Exception) error).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SERVICE);
  }

  @FunctionalInterface
  private interface ThrowingValidation {
    void validate();
  }
}
