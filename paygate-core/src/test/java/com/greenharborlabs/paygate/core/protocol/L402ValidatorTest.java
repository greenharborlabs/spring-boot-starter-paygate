package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.macaroon.VerificationFailureReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("L402Validator")
class L402ValidatorTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE_NAME = "test-api";
  private static final String REQUEST_ROUTE = "/widgets/{id}";
  private static final String REQUEST_METHOD = "GET";

  private byte[] rootKey;
  private byte[] preimageBytes;
  private byte[] paymentHash;
  private byte[] tokenIdBytes;
  private String tokenIdHex;
  private MacaroonIdentifier identifier;
  private Macaroon macaroon;
  private String validAuthHeader;

  /** Simple in-memory RootKeyStore backed by a map keyed on hex tokenId. */
  private final Map<String, byte[]> rootKeyMap = new HashMap<>();

  private final RootKeyStore rootKeyStore =
      new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
          byte[] key = new byte[32];
          RANDOM.nextBytes(key);
          byte[] tokenId = new byte[32];
          RANDOM.nextBytes(tokenId);
          return new GenerationResult(
              new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(key.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.paygate.api.crypto.SensitiveBytes getRootKey(byte[] keyId) {
          byte[] stored = rootKeyMap.get(HEX.formatHex(keyId));
          return stored == null
              ? null
              : new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(stored.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
          rootKeyMap.remove(HEX.formatHex(keyId));
        }
      };

  /** Simple in-memory CredentialStore backed by a map keyed on tokenId. */
  private final Map<String, L402Credential> credentialMap = new HashMap<>();

  private final CredentialStore credentialStore =
      new CredentialStore() {
        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
          L402Credential previous = credentialMap.put(tokenId, credential.copy());
          if (previous != null) {
            previous.destroy();
          }
        }

        @Override
        public L402Credential get(String tokenId) {
          L402Credential credential = credentialMap.get(tokenId);
          return credential == null ? null : credential.copy();
        }

        @Override
        public void revoke(String tokenId) {
          L402Credential removed = credentialMap.remove(tokenId);
          if (removed != null) {
            removed.destroy();
          }
        }

        @Override
        public long activeCount() {
          return credentialMap.size();
        }
      };

  @Nested
  @DisplayName("mandatory boundary verifier registration")
  class MandatoryBoundaryVerifierRegistration {

    @Test
    @DisplayName("constructor rejects a missing route or method verifier")
    void constructorRejectsMissingRouteOrMethodVerifier() {
      assertThatThrownBy(
              () ->
                  new L402Validator(
                      rootKeyStore,
                      credentialStore,
                      List.of(new MethodCaveatVerifier(10)),
                      SERVICE_NAME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("route");

      assertThatThrownBy(
              () ->
                  new L402Validator(
                      rootKeyStore,
                      credentialStore,
                      List.of(new RouteCaveatVerifier(10)),
                      SERVICE_NAME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("method");
    }

    @Test
    @DisplayName("constructor rejects a missing service capability verifier")
    void constructorRejectsMissingCapabilityVerifier() {
      assertThatThrownBy(
              () ->
                  new L402Validator(
                      rootKeyStore,
                      credentialStore,
                      List.of(new RouteCaveatVerifier(10), new MethodCaveatVerifier(10)),
                      SERVICE_NAME))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(SERVICE_NAME + "_capabilities");
    }
  }

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException {
    rootKeyMap.clear();
    credentialMap.clear();

    rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);

    preimageBytes = new byte[32];
    RANDOM.nextBytes(preimageBytes);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

    tokenIdBytes = new byte[32];
    RANDOM.nextBytes(tokenIdBytes);
    tokenIdHex = HEX.formatHex(tokenIdBytes);

    identifier = new MacaroonIdentifier(1, paymentHash, tokenIdBytes);
    macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", boundaryCaveats());

    // Register the root key so the validator can look it up by tokenId
    rootKeyMap.put(tokenIdHex, rootKey);

    // Build a valid Authorization header: L402 <base64-macaroon>:<hex-preimage>
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimageBytes);
    validAuthHeader = "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  @Nested
  @DisplayName("validation result API")
  class ValidationResultApi {

    @Test
    @DisplayName("canonical constructor snapshots effective capabilities")
    void canonicalConstructorSnapshotsEffectiveCapabilities() {
      L402Credential credential =
          new L402Credential(
              macaroon, PaymentPreimage.fromHex(HEX.formatHex(preimageBytes)), tokenIdHex);
      Set<String> mutableCapabilities = new LinkedHashSet<>(List.of("search", "analyze"));

      try {
        L402Validator.ValidationResult result =
            new L402Validator.ValidationResult(credential, true, mutableCapabilities);
        mutableCapabilities.clear();

        assertThat(result.effectiveCapabilities()).containsExactlyInAnyOrder("search", "analyze");
        assertThatThrownBy(() -> result.effectiveCapabilities().add("admin"))
            .isInstanceOf(UnsupportedOperationException.class);
      } finally {
        credential.destroy();
      }
    }

    @Test
    @DisplayName("canonical constructor rejects null effective capabilities")
    void canonicalConstructorRejectsNullEffectiveCapabilities() {
      L402Credential credential =
          new L402Credential(
              macaroon, PaymentPreimage.fromHex(HEX.formatHex(preimageBytes)), tokenIdHex);

      try {
        assertThatThrownBy(() -> new L402Validator.ValidationResult(credential, true, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("effectiveCapabilities");
      } finally {
        credential.destroy();
      }
    }

    @Test
    @DisplayName("two-argument constructor defaults to immutable empty capabilities")
    void twoArgumentConstructorDefaultsToImmutableEmptyCapabilities() {
      L402Credential credential =
          new L402Credential(
              macaroon, PaymentPreimage.fromHex(HEX.formatHex(preimageBytes)), tokenIdHex);

      try {
        L402Validator.ValidationResult result =
            new L402Validator.ValidationResult(credential, false);

        assertThat(result.effectiveCapabilities()).isEmpty();
        assertThatThrownBy(() -> result.effectiveCapabilities().add("search"))
            .isInstanceOf(UnsupportedOperationException.class);
      } finally {
        credential.destroy();
      }
    }

    @Test
    @DisplayName("equality and hash code include effective capabilities")
    void equalityAndHashCodeIncludeEffectiveCapabilities() {
      L402Credential credential =
          new L402Credential(
              macaroon, PaymentPreimage.fromHex(HEX.formatHex(preimageBytes)), tokenIdHex);

      try {
        L402Validator.ValidationResult first =
            new L402Validator.ValidationResult(
                credential, true, new LinkedHashSet<>(List.of("search", "analyze")));
        L402Validator.ValidationResult equal =
            new L402Validator.ValidationResult(
                credential, true, new LinkedHashSet<>(List.of("analyze", "search")));
        L402Validator.ValidationResult different =
            new L402Validator.ValidationResult(credential, true, Set.of("search"));

        assertThat(first).isEqualTo(equal).hasSameHashCodeAs(equal).isNotEqualTo(different);
      } finally {
        credential.destroy();
      }
    }
  }

  @Nested
  @DisplayName("effective capability ceiling")
  class EffectiveCapabilityCeiling {

    @Test
    @DisplayName("holder cannot expand an issued no-capability ceiling on fresh or cached paths")
    void rejectsHolderExpansionFromNoCapabilityCeilingOnFreshAndCachedPaths() {
      Macaroon issued =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              boundaryCaveats(new Caveat(SERVICE_NAME + "_capabilities", "~")));
      Macaroon holderAttenuated =
          attenuate(issued, new Caveat(SERVICE_NAME + "_capabilities", "admin"));
      String header = authHeaderFor(holderAttenuated);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              error -> {
                L402Exception l402Exception = (L402Exception) error;
                assertThat(l402Exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                assertThat(l402Exception.getMessage())
                    .isEqualTo("Credential attenuation is invalid")
                    .doesNotContainIgnoringCase("caveat escalation")
                    .doesNotContain("admin");
              });
      assertThat(credentialStore.get(tokenIdHex)).isNull();

      try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
        credentialStore.store(
            tokenIdHex, new L402Credential(holderAttenuated, preimage, tokenIdHex), 3600);
      }

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_MACAROON);
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("holder cannot substitute capabilities outside an issued named ceiling")
    void rejectsCapabilityExpansionWithoutEffectiveAuthorities() {
      Macaroon issued =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              boundaryCaveats(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")));
      Macaroon holderAttenuated =
          attenuate(issued, new Caveat(SERVICE_NAME + "_capabilities", "analyze,admin"));
      String header = authHeaderFor(holderAttenuated);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "analyze"))
              .build();

      assertThatThrownBy(() -> validator.validate(header, context))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              error -> {
                L402Exception l402Exception = (L402Exception) error;
                assertThat(l402Exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                assertThat(l402Exception.getMessage())
                    .isEqualTo("Credential attenuation is invalid")
                    .doesNotContainIgnoringCase("caveat escalation")
                    .doesNotContain("admin");
              });
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("fresh and cached validation return only the final narrowed named ceiling")
    void acceptsRequestedCapabilityWithinFinalVerifiedCeiling() {
      String header =
          buildAuthHeader(
              List.of(
                  new Caveat(SERVICE_NAME + "_capabilities", "search,analyze,admin"),
                  new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")));
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      L402Validator.ValidationResult fresh = validator.validate(header, context);
      L402Validator.ValidationResult cached = validator.validate(header, context);

      assertThat(fresh.freshValidation()).isTrue();
      assertThat(cached.freshValidation()).isFalse();
      assertThat(fresh.effectiveCapabilities()).containsExactlyInAnyOrder("search", "analyze");
      assertThat(cached.effectiveCapabilities()).containsExactlyInAnyOrder("search", "analyze");
      assertThat(fresh.effectiveCapabilities()).doesNotContain("admin");
      assertThatThrownBy(() -> cached.effectiveCapabilities().add("admin"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("fresh and cached no-capability ceilings return immutable empty sets")
    void returnsEmptySetForFinalNoCapabilityCeiling() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Validator.ValidationResult fresh = validator.validate(validAuthHeader, boundaryContext());
      L402Validator.ValidationResult cached =
          validator.validate(validAuthHeader, boundaryContext());

      assertThat(fresh.effectiveCapabilities()).isEmpty();
      assertThat(cached.effectiveCapabilities()).isEmpty();
      assertThatThrownBy(() -> fresh.effectiveCapabilities().add("search"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("missing capability ceiling rejects fresh and exact cached legacy credentials")
    void rejectsLegacyCredentialMissingCapabilityCeilingOnFreshAndCachedPaths() {
      Macaroon legacy =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              List.of(new Caveat("route", REQUEST_ROUTE), new Caveat("method", REQUEST_METHOD)));
      String header = authHeaderFor(legacy);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThat(credentialStore.get(tokenIdHex)).isNull();

      try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
        credentialStore.store(tokenIdHex, new L402Credential(legacy, preimage, tokenIdHex), 3600);
      }
      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }
  }

  @Nested
  @DisplayName("valid credential")
  class ValidCredential {

    @Test
    @DisplayName("returns credential when macaroon signature and preimage are valid")
    void validCredentialPasses() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Validator.ValidationResult result =
          validator.validate(validAuthHeader, boundaryContext());

      assertThat(result).isNotNull();
      assertThat(result.freshValidation()).isTrue();
      assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
      assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
    }

    @Test
    @DisplayName("fresh validation result remains usable after cache revocation")
    void freshValidationResultRemainsUsableAfterCacheRevocation() {
      try (var realStore = new InMemoryCredentialStore(100, 0)) {
        L402Validator validator =
            new L402Validator(rootKeyStore, realStore, boundaryVerifiers(), SERVICE_NAME);

        L402Validator.ValidationResult result =
            validator.validate(validAuthHeader, boundaryContext());
        realStore.revoke(tokenIdHex);

        assertThat(result.freshValidation()).isTrue();
        assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
      }
    }
  }

  @Nested
  @DisplayName("issuer-authenticated identifier schema")
  class IdentifierSchema {

    static Stream<Arguments> authenticatedSchemaFailures() {
      return Stream.of(
          Arguments.of("identifier version 0", 0, boundaryCaveatsForSchemaTest()),
          Arguments.of(
              "missing route",
              1,
              List.of(
                  new Caveat("method", REQUEST_METHOD),
                  new Caveat(SERVICE_NAME + "_capabilities", "~"))),
          Arguments.of(
              "missing method",
              1,
              List.of(
                  new Caveat("route", REQUEST_ROUTE),
                  new Caveat(SERVICE_NAME + "_capabilities", "~"))),
          Arguments.of(
              "missing capability ceiling",
              1,
              List.of(new Caveat("route", REQUEST_ROUTE), new Caveat("method", REQUEST_METHOD))));
    }

    private static List<Caveat> boundaryCaveatsForSchemaTest() {
      return List.of(
          new Caveat("route", REQUEST_ROUTE),
          new Caveat("method", REQUEST_METHOD),
          new Caveat(SERVICE_NAME + "_capabilities", "~"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticatedSchemaFailures")
    @DisplayName("authenticated schema failures use the generic core contract")
    void authenticatedSchemaFailuresUseGenericCoreContract(
        String caseName, int identifierVersion, List<Caveat> caveats) {
      Macaroon signedCredential =
          MacaroonMinter.mint(
              rootKey,
              new MacaroonIdentifier(identifierVersion, paymentHash, tokenIdBytes),
              "https://example.com",
              caveats);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(
              () -> validator.validate(authHeaderFor(signedCredential), boundaryContext()))
          .as(caseName)
          .isInstanceOf(L402Exception.class)
          .satisfies(
              error -> {
                L402Exception failure = (L402Exception) error;
                assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                assertThat(failure.getErrorCode().getHttpStatus()).isEqualTo(401);
                assertThat(failure.getMessage())
                    .isEqualTo("Credential constraints were not satisfied");
              });
    }

    @Test
    @DisplayName("authentic version 0 credential shapes are rejected after signature verification")
    void rejectsAuthenticVersionZeroCredentialShapesAfterSignatureVerification() {
      record LegacyCase(
          String name, List<Caveat> issuerCaveats, List<Caveat> holderAppendedCaveats) {}

      List<Caveat> matchingBoundaries = boundaryCaveats();
      List<LegacyCase> cases =
          List.of(
              new LegacyCase("zero caveats", List.of(), List.of()),
              new LegacyCase(
                  "built-in legacy",
                  List.of(
                      new Caveat("services", SERVICE_NAME + ":0"),
                      new Caveat("route", REQUEST_ROUTE),
                      new Caveat("method", REQUEST_METHOD),
                      new Caveat(SERVICE_NAME + "_capabilities", "~"),
                      new Caveat(SERVICE_NAME + "_valid_until", "4102444800")),
                  List.of()),
              new LegacyCase("no-capability ceiling", matchingBoundaries, List.of()),
              new LegacyCase(
                  "named capability",
                  boundaryCaveats(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")),
                  List.of()),
              new LegacyCase(
                  "multi-service",
                  List.of(
                      new Caveat("services", SERVICE_NAME + ":0,other-api:0"),
                      new Caveat("other-api_capabilities", "read"),
                      new Caveat("route", REQUEST_ROUTE),
                      new Caveat("method", REQUEST_METHOD),
                      new Caveat(SERVICE_NAME + "_capabilities", "~")),
                  List.of()),
              new LegacyCase("holder-appended matching boundaries", List.of(), matchingBoundaries));
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      for (LegacyCase legacyCase : cases) {
        Macaroon authentic =
            MacaroonMinter.mint(
                rootKey,
                new MacaroonIdentifier(0, paymentHash, tokenIdBytes),
                "https://example.com",
                legacyCase.issuerCaveats());
        for (Caveat appended : legacyCase.holderAppendedCaveats()) {
          authentic = attenuate(authentic, appended);
        }

        byte[] invalidSignature = authentic.signature();
        invalidSignature[0] ^= 1;
        Macaroon tampered =
            new Macaroon(
                authentic.identifier(),
                authentic.location(),
                authentic.caveats(),
                invalidSignature);
        assertThatThrownBy(() -> validator.validate(authHeaderFor(tampered), boundaryContext()))
            .as(legacyCase.name() + " authenticates before schema rejection")
            .isInstanceOf(L402Exception.class)
            .extracting(exception -> ((L402Exception) exception).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_MACAROON);

        Macaroon signedLegacy = authentic;
        assertThatThrownBy(() -> validator.validate(authHeaderFor(signedLegacy), boundaryContext()))
            .as(legacyCase.name())
            .isInstanceOf(L402Exception.class)
            .extracting(exception -> ((L402Exception) exception).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_SERVICE);
        assertThat(credentialStore.get(tokenIdHex)).as(legacyCase.name()).isNull();
      }
    }

    @Test
    @DisplayName("correctly signed version 0 is rejected only after proof and signature checks")
    void rejectsFreshVersionZeroAfterProofAndSignatureChecks() {
      Macaroon legacy =
          MacaroonMinter.mint(
              rootKey,
              new MacaroonIdentifier(0, paymentHash, tokenIdBytes),
              "https://example.com",
              boundaryCaveats());
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(authHeaderFor(legacy), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              exception -> {
                L402Exception failure = (L402Exception) exception;
                assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                assertThat(failure.getErrorCode().getHttpStatus()).isEqualTo(401);
                assertThat(failure.getMessage())
                    .isEqualTo("Credential constraints were not satisfied");
              });

      byte[] wrongPreimage = preimageBytes.clone();
      wrongPreimage[0] ^= 1;
      assertThatThrownBy(
              () -> validator.validate(authHeaderFor(legacy, wrongPreimage), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(exception -> ((L402Exception) exception).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PREIMAGE);

      byte[] invalidSignature = legacy.signature();
      invalidSignature[0] ^= 1;
      Macaroon tampered =
          new Macaroon(legacy.identifier(), legacy.location(), legacy.caveats(), invalidSignature);
      assertThatThrownBy(() -> validator.validate(authHeaderFor(tampered), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(exception -> ((L402Exception) exception).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_MACAROON);
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("exact cached version 0 is rejected and evicted")
    void rejectsAndEvictsExactCachedVersionZero() {
      Macaroon legacy =
          MacaroonMinter.mint(
              rootKey,
              new MacaroonIdentifier(0, paymentHash, tokenIdBytes),
              "https://example.com",
              boundaryCaveats());
      String header = authHeaderFor(legacy);
      try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
        credentialStore.store(tokenIdHex, new L402Credential(legacy, preimage, tokenIdHex), 3600);
      }
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(exception -> ((L402Exception) exception).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("changed version 0 variant takes fresh path without evicting cached version 1")
    void changedVersionZeroDoesNotEvictCachedVersionOne() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      validator.validate(validAuthHeader, boundaryContext());
      Macaroon legacy =
          MacaroonMinter.mint(
              rootKey,
              new MacaroonIdentifier(0, paymentHash, tokenIdBytes),
              "https://example.com",
              boundaryCaveats());
      Macaroon changedLegacy = attenuate(legacy, new Caveat("route", REQUEST_ROUTE));

      assertThatThrownBy(() -> validator.validate(authHeaderFor(changedLegacy), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(exception -> ((L402Exception) exception).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);

      L402Credential cached = credentialStore.get(tokenIdHex);
      try {
        assertThat(cached).isNotNull();
        assertThat(cached.macaroon()).isEqualTo(macaroon);
      } finally {
        if (cached != null) {
          cached.destroy();
        }
      }
    }

    @Test
    @DisplayName("valid version 1 holder attenuation replaces the cached issued variant")
    void validVersionOneAttenuationTakesFreshPathAndReplacesCacheEntry() {
      Macaroon issued =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              boundaryCaveats(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")));
      String issuedHeader = authHeaderFor(issued);
      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      assertThat(validator.validate(issuedHeader, context).freshValidation()).isTrue();

      Macaroon attenuated = attenuate(issued, new Caveat("route", REQUEST_ROUTE));
      attenuated = attenuate(attenuated, new Caveat("method", REQUEST_METHOD));
      attenuated = attenuate(attenuated, new Caveat(SERVICE_NAME + "_capabilities", "search"));
      String attenuatedHeader = authHeaderFor(attenuated);

      L402Validator.ValidationResult fresh = validator.validate(attenuatedHeader, context);
      assertThat(fresh.freshValidation()).isTrue();
      assertThat(fresh.effectiveCapabilities()).containsExactly("search");

      L402Credential cached = credentialStore.get(tokenIdHex);
      try {
        assertThat(cached).isNotNull();
        assertThat(cached.macaroon()).isEqualTo(attenuated).isNotEqualTo(issued);
      } finally {
        if (cached != null) {
          cached.destroy();
        }
      }
      assertThat(validator.validate(attenuatedHeader, context).freshValidation()).isFalse();
    }
  }

  @Nested
  @DisplayName("invalid macaroon signature")
  class InvalidMacaroonSignature {

    @Test
    @DisplayName("throws INVALID_MACAROON when macaroon signature is tampered")
    void tamperedSignatureReturnsInvalidMacaroon() {
      // Tamper the macaroon signature by flipping a byte
      byte[] tamperedSig = macaroon.signature();
      tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
      Macaroon tampered =
          new Macaroon(macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);

      byte[] serialized = MacaroonSerializer.serializeV2(tampered);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_MACAROON);
    }
  }

  @Nested
  @DisplayName("malformed identifier")
  class MalformedIdentifier {

    @Test
    @DisplayName("unsupported identifier version is exposed as a sanitized malformed credential")
    void unsupportedIdentifierVersionIsSanitized() {
      String header = authHeaderWithIdentifierVersion(macaroon, 0x3137);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Exception exception =
          catchThrowableOfType(
              L402Exception.class, () -> validator.validate(header, boundaryContext()));

      assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
      assertThat(exception).hasMessage("Malformed L402 credential").hasNoCause();
      assertThat(exception.getTokenId()).isNull();
      assertThat(exception.getMessage()).doesNotContain("Unsupported", "version", "12599", "3137");
    }
  }

  @Nested
  @DisplayName("wrong preimage")
  class WrongPreimage {

    @Test
    @DisplayName("throws INVALID_PREIMAGE when preimage does not hash to paymentHash")
    void wrongPreimageReturnsInvalidPreimage() {
      // Use a different random preimage that won't match the payment hash
      byte[] wrongPreimage = new byte[32];
      RANDOM.nextBytes(wrongPreimage);
      String wrongPreimageHex = HEX.formatHex(wrongPreimage);

      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String header = "L402 " + macaroonBase64 + ":" + wrongPreimageHex;

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PREIMAGE);
    }
  }

  @Nested
  @DisplayName("cached credential")
  class CachedCredential {

    @Test
    @DisplayName(
        "returns cached credential when presented credential matches and caveats are valid")
    void returnsCachedWhenPresentedCredentialMatches() {
      // Pre-populate the credential store with a cached credential
      PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Root key exists — cache hit should return without full macaroon re-verification
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Validator.ValidationResult result =
          validator.validate(validAuthHeader, boundaryContext());

      assertThat(result.freshValidation()).isFalse();
      assertThat(result.credential()).isNotSameAs(cached);
      assertThat(result.credential().tokenId()).isEqualTo(cached.tokenId());
      assertThat(result.credential().macaroon()).isEqualTo(cached.macaroon());
      assertThat(result.credential().preimage().toHex()).isEqualTo(cached.preimage().toHex());
    }

    @Test
    @DisplayName("cache-hit validation result remains usable after cache revocation")
    void cacheHitValidationResultRemainsUsableAfterCacheRevocation() {
      try (var realStore = new InMemoryCredentialStore(100, 0)) {
        L402Validator validator =
            new L402Validator(rootKeyStore, realStore, boundaryVerifiers(), SERVICE_NAME);

        validator.validate(validAuthHeader, boundaryContext());
        L402Credential cachedCopy = realStore.get(tokenIdHex);
        L402Validator.ValidationResult result =
            validator.validate(validAuthHeader, boundaryContext());
        realStore.revoke(tokenIdHex);

        assertThat(result.freshValidation()).isFalse();
        assertThat(result.credential()).isNotSameAs(cachedCopy);
        assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
        cachedCopy.destroy();
      }
    }

    @Test
    @DisplayName("cached credential is not served after credential store revocation")
    void cachedCredentialNotServedAfterCredentialStoreRevocation() {
      // First validate to cache the credential
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      validator.validate(validAuthHeader, boundaryContext());

      // Revoke via credential store (simulates what revokeRootKey callers should do)
      credentialStore.revoke(tokenIdHex);
      // Also revoke the root key so full re-validation fails
      rootKeyStore.revokeRootKey(tokenIdBytes);

      // Second validate should fall through to full validation and fail
      assertThatThrownBy(() -> validator.validate(validAuthHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                assertThat(l402Ex.getMessage()).contains("No root key found");
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }

    @Test
    @DisplayName("throws INVALID_MACAROON when presented macaroon does not match cached")
    void rejectsCachedCredentialWithTamperedSignature() {
      // Pre-populate the credential store with the legitimate credential
      PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Build a header with the same tokenId but a tampered macaroon signature
      byte[] tamperedSig = macaroon.signature();
      tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
      Macaroon tampered =
          new Macaroon(macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);

      byte[] serialized = MacaroonSerializer.serializeV2(tampered);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                assertThat(l402Ex.getMessage())
                    .isEqualTo("Credential signature verification failed");
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }

    @Test
    @DisplayName("throws INVALID_PREIMAGE when presented preimage does not match cached")
    void rejectsCachedCredentialWithWrongPreimage() {
      // Pre-populate the credential store with the legitimate credential
      PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Build a header with the correct macaroon but a different preimage.
      // The attacker knows the tokenId (from a response header) but not the real preimage.
      byte[] wrongPreimageBytes = new byte[32];
      RANDOM.nextBytes(wrongPreimageBytes);
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String wrongPreimageHex = HEX.formatHex(wrongPreimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + wrongPreimageHex;

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                assertThat(l402Ex.getMessage()).contains("preimage");
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }

    @Test
    @DisplayName("wrong preimage cannot reveal whether a macaroon variant matches the cache")
    void wrongPreimageDoesNotExposeCachedMacaroonVariant() {
      try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
        credentialStore.store(tokenIdHex, new L402Credential(macaroon, preimage, tokenIdHex), 3600);
      }

      byte[] alteredSignature = macaroon.signature();
      alteredSignature[0] ^= (byte) 0x80;
      Macaroon alteredVariant =
          new Macaroon(
              macaroon.identifier(), macaroon.location(), macaroon.caveats(), alteredSignature);
      byte[] wrongPreimage = new byte[32];
      RANDOM.nextBytes(wrongPreimage);

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Exception exactFailure =
          catchThrowableOfType(
              () -> validator.validate(authHeaderFor(macaroon, wrongPreimage), boundaryContext()),
              L402Exception.class);
      L402Exception alteredFailure =
          catchThrowableOfType(
              () ->
                  validator.validate(
                      authHeaderFor(alteredVariant, wrongPreimage), boundaryContext()),
              L402Exception.class);

      assertThat(exactFailure).isNotNull();
      assertThat(alteredFailure).isNotNull();
      assertThat(exactFailure.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
      assertThat(alteredFailure.getErrorCode()).isEqualTo(exactFailure.getErrorCode());
      assertThat(alteredFailure.getMessage()).isEqualTo(exactFailure.getMessage());
      try (L402Credential stillCached = credentialStore.get(tokenIdHex)) {
        assertThat(stillCached).isNotNull();
      }
    }

    @Test
    @DisplayName(
        "throws EXPIRED_CREDENTIAL and revokes cache when cached credential has expired caveat")
    void rejectsCachedCredentialWithExpiredCaveat() {
      // Create a macaroon with a valid_until caveat set in the past
      long pastEpoch = Instant.now().minusSeconds(60).getEpochSecond();
      List<Caveat> caveats =
          boundaryCaveats(new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(pastEpoch)));
      Macaroon expiredMacaroon =
          MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);

      // Pre-populate the credential store as if this was cached before expiry
      PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(expiredMacaroon, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Build header with matching macaroon and preimage (attacker replays a real credential)
      byte[] serialized = MacaroonSerializer.serializeV2(expiredMacaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Validator validator =
          new L402Validator(
              rootKeyStore, credentialStore, boundaryVerifiers(validUntilVerifier()), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });

      // Credential should be evicted from cache after expiry detection
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("skips unknown caveats in cached credential instead of revoking")
    void skipsUnknownCaveatsInCachedCredential() {
      // Create a macaroon with an unknown caveat
      List<Caveat> caveats = boundaryCaveats(new Caveat("custom_app_data", "xyz"));
      Macaroon macWithUnknown =
          com.greenharborlabs.paygate.core.macaroon.MacaroonMinter.mint(
              rootKey, identifier, "https://example.com", caveats);

      // Pre-populate the credential store
      com.greenharborlabs.paygate.core.lightning.PaymentPreimage preimage =
          com.greenharborlabs.paygate.core.lightning.PaymentPreimage.fromHex(
              HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(macWithUnknown, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Build header with matching macaroon
      byte[] serialized =
          com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer.serializeV2(macWithUnknown);
      String macaroonBase64 = java.util.Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      // No verifiers registered — unknown caveat should be skipped, not cause rejection
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Validator.ValidationResult result = validator.validate(header, boundaryContext());

      assertThat(result.freshValidation()).isFalse();
      assertThat(result.credential()).isNotSameAs(cached);
      assertThat(result.credential().tokenId()).isEqualTo(cached.tokenId());
      assertThat(result.credential().macaroon()).isEqualTo(cached.macaroon());
      assertThat(result.credential().preimage().toHex()).isEqualTo(cached.preimage().toHex());
      // Credential should NOT be revoked
      assertThat(credentialStore.get(tokenIdHex)).isNotNull();
    }
  }

  @Nested
  @DisplayName("request boundary enforcement")
  class RequestBoundaryEnforcement {

    @Test
    @DisplayName("rejects signed credentials missing route or method on fresh and cached paths")
    void rejectsSignedCredentialMissingRouteOrMethodBoundaryOnFreshAndCachedPaths() {
      List<List<Caveat>> incompleteBoundaries =
          List.of(
              List.of(
                  new Caveat("method", REQUEST_METHOD),
                  new Caveat(SERVICE_NAME + "_capabilities", "~")),
              List.of(
                  new Caveat("route", REQUEST_ROUTE),
                  new Caveat(SERVICE_NAME + "_capabilities", "~")));
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      for (List<Caveat> caveats : incompleteBoundaries) {
        Macaroon incomplete =
            MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);
        String header = authHeaderFor(incomplete);

        assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
            .isInstanceOf(L402Exception.class)
            .satisfies(
                error -> {
                  L402Exception exception = (L402Exception) error;
                  assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                  assertThat(exception.getErrorCode().getHttpStatus()).isEqualTo(401);
                  assertThat(exception.getMessage())
                      .isEqualTo("Credential constraints were not satisfied")
                      .doesNotContain(REQUEST_ROUTE)
                      .doesNotContain(REQUEST_METHOD);
                });
        assertThat(credentialStore.get(tokenIdHex)).isNull();

        try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
          credentialStore.store(
              tokenIdHex, new L402Credential(incomplete, preimage, tokenIdHex), 3600);
        }

        assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
            .isInstanceOf(L402Exception.class)
            .extracting(error -> ((L402Exception) error).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_SERVICE);
        assertThat(credentialStore.get(tokenIdHex)).isNull();
      }
    }

    @Test
    @DisplayName("rejects tampered route or method without sensitive details")
    void rejectsTamperedRouteOrMethodWithoutSensitiveDetails() {
      List<List<Caveat>> tamperedCaveatSets =
          List.of(
              List.of(
                  new Caveat("route", "/private/accounts/secret-42"),
                  new Caveat("method", REQUEST_METHOD)),
              List.of(new Caveat("route", REQUEST_ROUTE), new Caveat("method", "DELETE-SECRET")));
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      for (List<Caveat> caveats : tamperedCaveatSets) {
        Macaroon tampered =
            new Macaroon(macaroon.identifier(), macaroon.location(), caveats, macaroon.signature());

        assertThatThrownBy(() -> validator.validate(authHeaderFor(tampered), boundaryContext()))
            .isInstanceOf(L402Exception.class)
            .satisfies(
                error -> {
                  L402Exception exception = (L402Exception) error;
                  assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                  assertThat(exception.getMessage())
                      .doesNotContain("secret-42")
                      .doesNotContain("DELETE-SECRET");
                });
      }
    }

    @Test
    @DisplayName("keeps the same path distinct across GET, HEAD, and other methods")
    void keepsSamePathGetHeadAndOtherMethodsDistinct() {
      Macaroon getAndHead =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              List.of(
                  new Caveat("route", REQUEST_ROUTE),
                  new Caveat("method", "GET,HEAD"),
                  new Caveat(SERVICE_NAME + "_capabilities", "~")));
      String header = authHeaderFor(getAndHead);
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThat(
              validator.validate(header, boundaryContext(REQUEST_ROUTE, "GET")).freshValidation())
          .isTrue();
      assertThat(
              validator.validate(header, boundaryContext(REQUEST_ROUTE, "HEAD")).freshValidation())
          .isFalse();
      assertThatThrownBy(() -> validator.validate(header, boundaryContext(REQUEST_ROUTE, "POST")))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThat(credentialStore.get(tokenIdHex)).isNotNull();
    }

    @Test
    @DisplayName("matches parameterized routes by canonical registered route identity")
    void matchesParameterizedRoutesByCanonicalRegisteredRouteIdentity() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(
              () -> validator.validate(validAuthHeader, boundaryContext("/widgets/42", "GET")))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);

      assertThat(
              validator
                  .validate(validAuthHeader, boundaryContext(REQUEST_ROUTE, "GET"))
                  .freshValidation())
          .isTrue();
    }

    @Test
    @DisplayName("falls back for same-token variants and preserves cache after variant failure")
    void sameTokenVariantFallsBackToFullVerificationWithoutUnsafeEviction() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      validator.validate(validAuthHeader, boundaryContext());

      byte[] invalidSignature = macaroon.signature();
      invalidSignature[0] ^= (byte) 0x80;
      Macaroon invalidVariant =
          new Macaroon(
              macaroon.identifier(), macaroon.location(), macaroon.caveats(), invalidSignature);

      assertThatThrownBy(() -> validator.validate(authHeaderFor(invalidVariant), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_MACAROON);
      L402Credential stillCached = credentialStore.get(tokenIdHex);
      assertThat(stillCached).isNotNull();
      try {
        assertThat(stillCached.macaroon()).isEqualTo(macaroon);
      } finally {
        stillCached.destroy();
      }

      Caveat attenuation = new Caveat("method", REQUEST_METHOD);
      List<Caveat> attenuatedCaveats = new ArrayList<>(macaroon.caveats());
      attenuatedCaveats.add(attenuation);
      byte[] attenuatedSignature =
          MacaroonCrypto.hmac(
              macaroon.signature(), attenuation.toString().getBytes(StandardCharsets.UTF_8));
      Macaroon attenuated =
          new Macaroon(
              macaroon.identifier(), macaroon.location(), attenuatedCaveats, attenuatedSignature);

      assertThat(validator.validate(authHeaderFor(attenuated), boundaryContext()).freshValidation())
          .isTrue();
    }

    @Test
    @DisplayName("rejects and evicts an exact cached variant missing a mandatory boundary")
    void rejectsAndEvictsExactCachedVariantMissingMandatoryBoundary() {
      Macaroon missingMethod =
          MacaroonMinter.mint(
              rootKey,
              identifier,
              "https://example.com",
              List.of(
                  new Caveat("route", REQUEST_ROUTE),
                  new Caveat(SERVICE_NAME + "_capabilities", "~")));
      try (PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes))) {
        credentialStore.store(
            tokenIdHex, new L402Credential(missingMethod, preimage, tokenIdHex), 3600);
      }
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(authHeaderFor(missingMethod), boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .extracting(error -> ((L402Exception) error).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_SERVICE);
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }
  }

  @Nested
  @DisplayName("expired caveat")
  class ExpiredCaveat {

    @Test
    @DisplayName("throws EXPIRED_CREDENTIAL when valid_until caveat is in the past")
    void expiredCaveatReturnsExpiredCredential() throws NoSuchAlgorithmException {
      String caveatDetailMarker = "CAVEAT-DETAIL-SECRET-a6677b41";
      // Create a macaroon with an expired valid_until caveat
      long pastEpochSeconds = Instant.now().minusSeconds(3600).getEpochSecond();
      List<Caveat> caveats =
          boundaryCaveats(
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(pastEpochSeconds)));

      // Re-mint with caveats
      Macaroon macaroonWithExpiry =
          MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);

      byte[] serialized = MacaroonSerializer.serializeV2(macaroonWithExpiry);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      // Create a valid_until caveat verifier that throws MacaroonVerificationException for past
      // timestamps
      CaveatVerifier validUntilVerifier =
          new CaveatVerifier() {
            @Override
            public String getKey() {
              return SERVICE_NAME + "_valid_until";
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext context) {
              long expiryEpoch = Long.parseLong(caveat.value());
              Instant expiry = Instant.ofEpochSecond(expiryEpoch);
              if (!expiry.isAfter(context.getCurrentTime())) {
                throw new MacaroonVerificationException(
                    VerificationFailureReason.CREDENTIAL_EXPIRED, caveatDetailMarker + expiry);
              }
            }
          };

      L402Validator validator =
          new L402Validator(
              rootKeyStore, credentialStore, boundaryVerifiers(validUntilVerifier), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Exception = (L402Exception) ex;
                assertThat(l402Exception.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
                assertThat(l402Exception.getMessage())
                    .isEqualTo("Credential has expired")
                    .doesNotContain(caveatDetailMarker);
                assertThat(l402Exception.getTokenId()).isEqualTo(tokenIdHex);
              });
    }
  }

  @Nested
  @DisplayName("cache TTL derived from valid_until caveat")
  class CacheTtlFromValidUntil {

    @Test
    @DisplayName(
        "uses remaining seconds from valid_until caveat as cache TTL when shorter than default")
    void shortValidUntilGetsShorterCacheTtl() {
      AtomicLong capturedTtl = new AtomicLong(-1);
      CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

      // Macaroon with valid_until set to 120 seconds from now
      long futureEpoch = Instant.now().plusSeconds(120).getEpochSecond();
      List<Caveat> caveats =
          List.of(new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(futureEpoch)));

      String header = buildAuthHeader(caveats);

      L402Validator validator =
          new L402Validator(
              rootKeyStore,
              ttlCapturingStore,
              boundaryVerifiers(validUntilVerifier()),
              SERVICE_NAME);

      validator.validate(header, boundaryContext());

      // TTL should be approximately 90 seconds (120 - 30s safety margin),
      // definitely not the default 3600. Allow a few seconds of tolerance for test execution time.
      assertThat(capturedTtl.get())
          .isGreaterThan(0)
          .isLessThanOrEqualTo(90)
          .isGreaterThanOrEqualTo(85);
    }

    @Test
    @DisplayName("uses default TTL when no valid_until caveat is present")
    void noValidUntilUsesDefaultTtl() {
      AtomicLong capturedTtl = new AtomicLong(-1);
      CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

      L402Validator validator =
          new L402Validator(rootKeyStore, ttlCapturingStore, boundaryVerifiers(), SERVICE_NAME);

      validator.validate(validAuthHeader, boundaryContext());

      assertThat(capturedTtl.get()).isEqualTo(3600);
    }

    @Test
    @DisplayName("uses minimum of multiple valid_until caveats as cache TTL")
    void multipleValidUntilUsesMinimum() {
      AtomicLong capturedTtl = new AtomicLong(-1);
      CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

      // Two valid_until caveats: 120s and 60s from now — TTL should be ~60s
      long laterEpoch = Instant.now().plusSeconds(120).getEpochSecond();
      long soonerEpoch = Instant.now().plusSeconds(60).getEpochSecond();
      List<Caveat> caveats =
          List.of(
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(laterEpoch)),
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(soonerEpoch)));

      String header = buildAuthHeader(caveats);

      L402Validator validator =
          new L402Validator(
              rootKeyStore,
              ttlCapturingStore,
              boundaryVerifiers(validUntilVerifier()),
              SERVICE_NAME);

      validator.validate(header, boundaryContext());

      // TTL should be approximately 30 seconds (minimum 60 - 30s safety margin),
      // not 120 or the default 3600.
      assertThat(capturedTtl.get())
          .isGreaterThan(0)
          .isLessThanOrEqualTo(30)
          .isGreaterThanOrEqualTo(25);
    }
  }

  @Nested
  @DisplayName("root key lifecycle")
  class RootKeyLifecycle {

    @Test
    @DisplayName("root key SensitiveBytes is destroyed after successful validation")
    void rootKeyIsDestroyedAfterSuccessfulValidation() {
      var issuedKeys =
          new java.util.concurrent.ConcurrentLinkedQueue<
              com.greenharborlabs.paygate.api.crypto.SensitiveBytes>();
      RootKeyStore trackingStore =
          new RootKeyStore() {
            @Override
            public GenerationResult generateRootKey() {
              return rootKeyStore.generateRootKey();
            }

            @Override
            public com.greenharborlabs.paygate.api.crypto.SensitiveBytes getRootKey(byte[] keyId) {
              var sb = rootKeyStore.getRootKey(keyId);
              if (sb != null) issuedKeys.add(sb);
              return sb;
            }

            @Override
            public void revokeRootKey(byte[] keyId) {
              rootKeyStore.revokeRootKey(keyId);
            }
          };

      L402Validator validator =
          new L402Validator(trackingStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      validator.validate(validAuthHeader, boundaryContext());

      assertThat(issuedKeys).hasSize(1);
      assertThat(issuedKeys.peek().isDestroyed()).isTrue();
    }

    @Test
    @DisplayName("root key SensitiveBytes is destroyed after failed validation")
    void rootKeyIsDestroyedAfterFailedValidation() {
      // Tamper the macaroon signature so verification fails
      byte[] tamperedSig = macaroon.signature();
      tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
      Macaroon tampered =
          new Macaroon(macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);
      byte[] serialized = MacaroonSerializer.serializeV2(tampered);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      var issuedKeys =
          new java.util.concurrent.ConcurrentLinkedQueue<
              com.greenharborlabs.paygate.api.crypto.SensitiveBytes>();
      RootKeyStore trackingStore =
          new RootKeyStore() {
            @Override
            public GenerationResult generateRootKey() {
              return rootKeyStore.generateRootKey();
            }

            @Override
            public com.greenharborlabs.paygate.api.crypto.SensitiveBytes getRootKey(byte[] keyId) {
              var sb = rootKeyStore.getRootKey(keyId);
              if (sb != null) issuedKeys.add(sb);
              return sb;
            }

            @Override
            public void revokeRootKey(byte[] keyId) {
              rootKeyStore.revokeRootKey(keyId);
            }
          };

      L402Validator validator =
          new L402Validator(trackingStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      try {
        validator.validate(header, boundaryContext());
      } catch (L402Exception expected) {
        // Expected — tampered signature
      }

      assertThat(issuedKeys).hasSize(1);
      assertThat(issuedKeys.peek().isDestroyed()).isTrue();
    }
  }

  @Nested
  @DisplayName("validate with external context")
  class ValidateWithExternalContext {

    @Test
    @DisplayName("cached credential with escalating caveats is rejected and revoked")
    void cachedEscalatingCaveatsRejectedAndRevoked() {
      // Create a macaroon with two capabilities caveats where the second EXPANDS access
      // (escalation: "search" -> "search,analyze,admin"), which should be detected
      List<Caveat> caveats =
          boundaryCaveats(
              new Caveat(SERVICE_NAME + "_capabilities", "search"),
              new Caveat(SERVICE_NAME + "_capabilities", "search,analyze,admin"));
      Macaroon escalatingMacaroon =
          MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);

      // Pre-populate the credential store as if this was cached
      PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
      L402Credential cached = new L402Credential(escalatingMacaroon, preimage, tokenIdHex);
      credentialStore.store(tokenIdHex, cached, 3600);

      // Build header with matching macaroon
      byte[] serialized = MacaroonSerializer.serializeV2(escalatingMacaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimageBytes);
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
      L402Validator validator =
          new L402Validator(
              rootKeyStore, credentialStore, boundaryVerifiers(capVerifier), SERVICE_NAME);

      // Provide a requested capability so the verifier can proceed past
      // the first caveat and detect escalation in the second caveat.
      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatThrownBy(() -> validator.validate(header, context))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                assertThat(l402Ex.getMessage())
                    .isEqualTo("Credential attenuation is invalid")
                    .doesNotContainIgnoringCase("caveat escalation")
                    .doesNotContain("search,analyze,admin");
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });

      // Credential should be revoked from cache
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("validate with context enforces requested capability — matching capability passes")
    void contextWithMatchingCapabilityPasses() {
      List<Caveat> caveats = List.of(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze"));
      String header = buildAuthHeader(caveats);

      CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
      L402Validator validator =
          new L402Validator(
              rootKeyStore, credentialStore, boundaryVerifiers(capVerifier), SERVICE_NAME);

      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      L402Validator.ValidationResult result = validator.validate(header, context);

      assertThat(result).isNotNull();
      assertThat(result.freshValidation()).isTrue();
      assertThat(result.effectiveCapabilities()).containsExactlyInAnyOrder("search", "analyze");
    }

    @Test
    @DisplayName("validate with context enforces requested capability — missing capability fails")
    void contextWithMissingCapabilityFails() {
      List<Caveat> caveats = List.of(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze"));
      String header = buildAuthHeader(caveats);

      CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
      L402Validator validator =
          new L402Validator(
              rootKeyStore, credentialStore, boundaryVerifiers(capVerifier), SERVICE_NAME);

      L402VerificationContext context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "admin"))
              .build();

      assertThatThrownBy(() -> validator.validate(header, context))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                assertThat(l402Ex.getErrorCode().getHttpStatus()).isEqualTo(401);
                assertThat(l402Ex.getMessage())
                    .isEqualTo("Credential constraints were not satisfied")
                    .doesNotContain("admin")
                    .doesNotContain("search,analyze");
                assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }

    @Test
    @DisplayName("validate(String) fails closed without a request boundary context")
    void validateStringFailsClosedWithoutRequestBoundaryContext() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(validAuthHeader))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              exception -> {
                L402Exception l402Exception = (L402Exception) exception;
                assertThat(l402Exception.getErrorCode())
                    .isEqualTo(ErrorCode.MISSING_REQUEST_CONTEXT);
                assertThat(l402Exception.getMessage())
                    .isEqualTo("Request route and method context are required");
              });
    }

    @Test
    @DisplayName("missing, null, and blank route or method fail identically on fresh validation")
    void incompleteRequestContextFailsOnFreshValidation() {
      for (Map<String, String> metadata : incompleteRequestMetadata()) {
        L402Validator validator =
            new L402Validator(
                rootKeyStore, new InMemoryCredentialStore(), boundaryVerifiers(), SERVICE_NAME);
        L402VerificationContext context =
            L402VerificationContext.builder()
                .serviceName(SERVICE_NAME)
                .currentTime(Instant.now())
                .requestMetadata(metadata)
                .build();

        assertMissingRequestContext(() -> validator.validate(validAuthHeader, context));
      }
    }

    @Test
    @DisplayName("missing request context on a cache hit does not evict the valid cache entry")
    void incompleteRequestContextFailsWithoutEvictingCachedCredential() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      L402Validator.ValidationResult fresh = validator.validate(validAuthHeader, boundaryContext());
      fresh.credential().destroy();

      for (Map<String, String> metadata : incompleteRequestMetadata()) {
        L402VerificationContext incompleteContext =
            L402VerificationContext.builder()
                .serviceName(SERVICE_NAME)
                .currentTime(Instant.now())
                .requestMetadata(metadata)
                .build();
        assertMissingRequestContext(() -> validator.validate(validAuthHeader, incompleteContext));
      }

      L402Validator.ValidationResult cached =
          validator.validate(validAuthHeader, boundaryContext());
      assertThat(cached.freshValidation()).isFalse();
      cached.credential().destroy();
    }

    @Test
    @DisplayName("external context flows through to fresh path verifier")
    void contextFlowsThroughToFreshPath() {
      // A context-capturing verifier that records the context it receives
      AtomicReference<L402VerificationContext> capturedContext = new AtomicReference<>();
      CaveatVerifier capturingVerifier =
          new CaveatVerifier() {
            @Override
            public String getKey() {
              return SERVICE_NAME + "_marker";
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext ctx) {
              capturedContext.set(ctx);
            }
          };

      List<Caveat> caveats =
          List.of(
              new Caveat(SERVICE_NAME + "_marker", "test-value"),
              new Caveat(SERVICE_NAME + "_capabilities", "custom-cap"));
      String header = buildAuthHeader(caveats);

      L402Validator validator =
          new L402Validator(
              rootKeyStore,
              credentialStore,
              boundaryVerifiers(
                  capturingVerifier, new CapabilitiesCaveatVerifier(SERVICE_NAME, 50)),
              SERVICE_NAME);

      L402VerificationContext externalContext =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .currentTime(Instant.now())
              .requestMetadata(
                  boundaryMetadata(VerificationContextKeys.REQUESTED_CAPABILITY, "custom-cap"))
              .build();

      validator.validate(header, externalContext);

      // The verifier should have received the external context, not a locally-built one
      assertThat(capturedContext.get()).isSameAs(externalContext);
      assertThat(
              capturedContext
                  .get()
                  .getRequestMetadata()
                  .get(VerificationContextKeys.REQUESTED_CAPABILITY))
          .isEqualTo("custom-cap");
    }

    @Test
    @DisplayName("validate with pre-parsed L402HeaderComponents returns fresh ValidationResult")
    void validateWithPreParsedComponentsReturnsFreshResult() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402HeaderComponents components = L402HeaderComponents.extractOrThrow(validAuthHeader);
      L402VerificationContext context = boundaryContext();

      L402Validator.ValidationResult result = validator.validate(components, context);

      assertThat(result).isNotNull();
      assertThat(result.freshValidation()).isTrue();
      assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
      assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
    }
  }

  /** Creates a CredentialStore that captures the TTL passed to store(). */
  private CredentialStore ttlCapturingStore(AtomicLong capturedTtl) {
    return new CredentialStore() {
      private final Map<String, L402Credential> map = new HashMap<>();

      @Override
      public void store(String tokenId, L402Credential credential, long ttlSeconds) {
        capturedTtl.set(ttlSeconds);
        L402Credential previous = map.put(tokenId, credential.copy());
        if (previous != null) {
          previous.destroy();
        }
      }

      @Override
      public L402Credential get(String tokenId) {
        L402Credential credential = map.get(tokenId);
        return credential == null ? null : credential.copy();
      }

      @Override
      public void revoke(String tokenId) {
        L402Credential removed = map.remove(tokenId);
        if (removed != null) {
          removed.destroy();
        }
      }

      @Override
      public long activeCount() {
        return map.size();
      }
    };
  }

  /** Creates a CaveatVerifier for valid_until caveats that rejects expired timestamps. */
  private CaveatVerifier validUntilVerifier() {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return SERVICE_NAME + "_valid_until";
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext context) {
        long expiryEpoch = Long.parseLong(caveat.value());
        Instant expiry = Instant.ofEpochSecond(expiryEpoch);
        if (!expiry.isAfter(context.getCurrentTime())) {
          throw new MacaroonVerificationException(
              VerificationFailureReason.CREDENTIAL_EXPIRED, "Credential expired at " + expiry);
        }
      }
    };
  }

  private List<CaveatVerifier> boundaryVerifiers(CaveatVerifier... additionalVerifiers) {
    List<CaveatVerifier> verifiers = new ArrayList<>(3 + additionalVerifiers.length);
    verifiers.add(new RouteCaveatVerifier(10));
    verifiers.add(new MethodCaveatVerifier(10));
    if (List.of(additionalVerifiers).stream()
        .noneMatch(verifier -> (SERVICE_NAME + "_capabilities").equals(verifier.getKey()))) {
      verifiers.add(new CapabilitiesCaveatVerifier(SERVICE_NAME, 50));
    }
    verifiers.addAll(List.of(additionalVerifiers));
    return List.copyOf(verifiers);
  }

  private List<Caveat> boundaryCaveats(Caveat... additionalCaveats) {
    List<Caveat> caveats = new ArrayList<>(3 + additionalCaveats.length);
    caveats.add(new Caveat("route", REQUEST_ROUTE));
    caveats.add(new Caveat("method", REQUEST_METHOD));
    if (List.of(additionalCaveats).stream()
        .noneMatch(caveat -> (SERVICE_NAME + "_capabilities").equals(caveat.key()))) {
      caveats.add(new Caveat(SERVICE_NAME + "_capabilities", "~"));
    }
    caveats.addAll(List.of(additionalCaveats));
    return List.copyOf(caveats);
  }

  private L402VerificationContext boundaryContext() {
    return boundaryContext(REQUEST_ROUTE, REQUEST_METHOD);
  }

  private L402VerificationContext boundaryContext(String route, String method) {
    return L402VerificationContext.builder()
        .serviceName(SERVICE_NAME)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE,
                route,
                VerificationContextKeys.REQUEST_METHOD,
                method))
        .build();
  }

  private Map<String, String> requestMetadata(
      String route, String method, boolean includeRoute, boolean includeMethod) {
    Map<String, String> metadata = new HashMap<>();
    if (includeRoute) {
      metadata.put(VerificationContextKeys.REQUEST_ROUTE, route);
    }
    if (includeMethod) {
      metadata.put(VerificationContextKeys.REQUEST_METHOD, method);
    }
    return metadata;
  }

  private List<Map<String, String>> incompleteRequestMetadata() {
    return List.of(
        requestMetadata(null, REQUEST_METHOD, false, true),
        requestMetadata(null, REQUEST_METHOD, true, true),
        requestMetadata("  ", REQUEST_METHOD, true, true),
        requestMetadata(REQUEST_ROUTE, null, true, false),
        requestMetadata(REQUEST_ROUTE, null, true, true),
        requestMetadata(REQUEST_ROUTE, "\t", true, true));
  }

  private void assertMissingRequestContext(ThrowingValidation validation) {
    assertThatThrownBy(validation::validate)
        .isInstanceOf(L402Exception.class)
        .satisfies(
            exception -> {
              L402Exception l402Exception = (L402Exception) exception;
              assertThat(l402Exception.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUEST_CONTEXT);
              assertThat(l402Exception.getMessage())
                  .isEqualTo("Request route and method context are required")
                  .doesNotContain(REQUEST_ROUTE, REQUEST_METHOD, validAuthHeader);
            });
  }

  @FunctionalInterface
  private interface ThrowingValidation {
    void validate();
  }

  private Map<String, String> boundaryMetadata(String... additionalEntry) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put(VerificationContextKeys.REQUEST_ROUTE, REQUEST_ROUTE);
    metadata.put(VerificationContextKeys.REQUEST_METHOD, REQUEST_METHOD);
    if (additionalEntry.length != 0) {
      metadata.put(additionalEntry[0], additionalEntry[1]);
    }
    return Map.copyOf(metadata);
  }

  /** Builds an L402 Authorization header from a macaroon minted with the given caveats. */
  private String buildAuthHeader(List<Caveat> caveats) {
    Macaroon mac =
        MacaroonMinter.mint(
            rootKey,
            identifier,
            "https://example.com",
            boundaryCaveats(caveats.toArray(Caveat[]::new)));
    return authHeaderFor(mac);
  }

  private String authHeaderFor(Macaroon mac) {
    return authHeaderFor(mac, preimageBytes);
  }

  private String authHeaderFor(Macaroon mac, byte[] preimage) {
    byte[] serialized = MacaroonSerializer.serializeV2(mac);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimage);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  private String authHeaderWithIdentifierVersion(Macaroon mac, int version) {
    byte[] serialized = MacaroonSerializer.serializeV2(mac);
    byte[] identifierBytes = mac.identifier();
    int identifierOffset = findSubarray(serialized, identifierBytes);
    assertThat(identifierOffset).isNotNegative();
    serialized[identifierOffset] = (byte) (version >>> 8);
    serialized[identifierOffset + 1] = (byte) version;
    return "L402 "
        + Base64.getEncoder().encodeToString(serialized)
        + ":"
        + HEX.formatHex(preimageBytes);
  }

  private static int findSubarray(byte[] bytes, byte[] target) {
    outer:
    for (int i = 0; i <= bytes.length - target.length; i++) {
      for (int j = 0; j < target.length; j++) {
        if (bytes[i + j] != target[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private Macaroon attenuate(Macaroon issued, Caveat caveat) {
    List<Caveat> caveats = new ArrayList<>(issued.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(issued.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    return new Macaroon(issued.identifier(), issued.location(), caveats, signature);
  }
}
