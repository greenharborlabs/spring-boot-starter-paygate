package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CrossServiceTest — T069: macaroon minted for service A rejected by service B")
class CrossServiceTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String REQUEST_ROUTE = "/paid-resource";
  private static final String REQUEST_METHOD = "GET";

  private InMemoryRootKeyStore rootKeyStore;
  private InMemoryCredentialStore credentialStore;
  private byte[] rootKey;
  private byte[] tokenIdBytes;
  private byte[] preimageBytes;
  private byte[] paymentHash;

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException {
    rootKeyStore = new InMemoryRootKeyStore();
    credentialStore = new InMemoryCredentialStore();

    RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
    rootKey = genResult.rootKey().value();
    tokenIdBytes = genResult.tokenId();

    preimageBytes = new byte[32];
    RANDOM.nextBytes(preimageBytes);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);
  }

  private String buildAuthHeader(List<Caveat> caveats) {
    return buildAuthHeader(1, caveats);
  }

  private String buildAuthHeader(int identifierVersion, List<Caveat> caveats) {
    MacaroonIdentifier identifier =
        new MacaroonIdentifier(identifierVersion, paymentHash, tokenIdBytes);
    List<Caveat> boundedCaveats = new ArrayList<>(caveats);
    boundedCaveats.add(new Caveat("route", REQUEST_ROUTE));
    boundedCaveats.add(new Caveat("method", REQUEST_METHOD));
    for (Caveat caveat : caveats) {
      if (!caveat.key().equals("services")) {
        continue;
      }
      for (String service : caveat.value().split(",")) {
        boundedCaveats.add(
            new Caveat(
                service.trim() + "_valid_until",
                String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond())));
      }
    }
    Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, boundedCaveats);
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimageBytes);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  private List<CaveatVerifier> verifiers(String serviceName) {
    return List.of(
        new RouteCaveatVerifier(10),
        new MethodCaveatVerifier(10),
        new ServicesCaveatVerifier(50),
        new CapabilitiesCaveatVerifier(serviceName, 50),
        new ValidUntilCaveatVerifier(serviceName));
  }

  private L402VerificationContext context(String serviceName) {
    return L402VerificationContext.builder()
        .serviceName(serviceName)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE,
                REQUEST_ROUTE,
                VerificationContextKeys.REQUEST_METHOD,
                REQUEST_METHOD))
        .build();
  }

  @Nested
  @DisplayName("cross-service rejection")
  class CrossServiceRejection {

    @Test
    @DisplayName("macaroon minted for serviceA is rejected when validated by serviceB")
    void macaroonForServiceAIsRejectedByServiceB() {
      String header =
          buildAuthHeader(
              List.of(
                  new Caveat("services", "serviceA"), new Caveat("serviceA_capabilities", "~")));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceB"), "serviceB");

      assertThatThrownBy(() -> validator.validate(header, context("serviceB")))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
              });
    }

    @Test
    @DisplayName("macaroon minted for serviceA is accepted when validated by serviceA")
    void macaroonForServiceAIsAcceptedByServiceA() {
      String header =
          buildAuthHeader(
              List.of(
                  new Caveat("services", "serviceA"), new Caveat("serviceA_capabilities", "~")));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceA"), "serviceA");

      assertThatCode(() -> validator.validate(header, context("serviceA")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("macaroon with multi-service caveat rejects unlisted service")
    void multiServiceCaveatRejectsUnlistedService() {
      String header =
          buildAuthHeader(
              List.of(
                  new Caveat("services", "serviceA,serviceB"),
                  new Caveat("serviceA_capabilities", "~"),
                  new Caveat("serviceB_capabilities", "~")));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceC"), "serviceC");

      assertThatThrownBy(() -> validator.validate(header, context("serviceC")))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
              });
    }

    @Test
    @DisplayName("version 1 multi-service caveats validate independently in flexible order")
    void multiServiceCaveatAcceptsEachListedService() {
      String header =
          buildAuthHeader(
              List.of(
                  new Caveat("serviceB_capabilities", "~"),
                  new Caveat("services", "serviceA,serviceB"),
                  new Caveat("serviceA_capabilities", "~")));

      L402Validator serviceAValidator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceA"), "serviceA");
      L402Validator serviceBValidator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceB"), "serviceB");

      assertThatCode(() -> serviceAValidator.validate(header, context("serviceA")))
          .doesNotThrowAnyException();
      assertThatCode(() -> serviceBValidator.validate(header, context("serviceB")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("each active service requires its own ceiling and identifier version 1")
    void multiServiceRequiresPerServiceCeilingAndVersionOne() {
      List<Caveat> bothCeilings =
          List.of(
              new Caveat("services", "serviceA,serviceB"),
              new Caveat("serviceB_capabilities", "~"),
              new Caveat("serviceA_capabilities", "~"));
      L402Validator serviceAValidator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceA"), "serviceA");
      L402Validator serviceBValidator =
          new L402Validator(rootKeyStore, credentialStore, verifiers("serviceB"), "serviceB");

      String missingServiceB =
          buildAuthHeader(
              List.of(
                  new Caveat("services", "serviceA,serviceB"),
                  new Caveat("serviceA_capabilities", "~")));
      assertThatCode(() -> serviceAValidator.validate(missingServiceB, context("serviceA")))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> serviceBValidator.validate(missingServiceB, context("serviceB")))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);

      String legacyV0 = buildAuthHeader(0, bothCeilings);
      assertThatThrownBy(() -> serviceAValidator.validate(legacyV0, context("serviceA")))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThatThrownBy(() -> serviceBValidator.validate(legacyV0, context("serviceB")))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
    }
  }
}
