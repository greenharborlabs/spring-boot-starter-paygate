package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MacaroonVerifier")
class MacaroonVerifierTest {

  private static final int IDENTIFIER_LENGTH = 66;

  private byte[] rootKey;
  private byte[] identifier;
  private L402VerificationContext context;

  @BeforeEach
  void setUp() {
    SecureRandom random = new SecureRandom();
    rootKey = new byte[32];
    random.nextBytes(rootKey);
    identifier = new byte[IDENTIFIER_LENGTH];
    random.nextBytes(identifier);
    context = new L402VerificationContext();
  }

  /**
   * Builds a macaroon with a correctly computed HMAC chain signature. derivedKey =
   * HMAC-SHA256("macaroons-key-generator", rootKey) sig = HMAC-SHA256(derivedKey, identifier) for
   * each caveat: sig = HMAC-SHA256(sig, "key=value".getBytes(UTF_8))
   */
  private Macaroon createValidMacaroon(
      byte[] rootKey, byte[] identifier, String location, List<Caveat> caveats) {
    byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
    byte[] sig = MacaroonCrypto.hmac(derivedKey, identifier);
    for (Caveat c : caveats) {
      sig = MacaroonCrypto.hmac(sig, c.toString().getBytes(StandardCharsets.UTF_8));
    }
    return new Macaroon(identifier, location, caveats, sig);
  }

  /** Creates a simple CaveatVerifier that accepts any caveat with the given key. */
  private CaveatVerifier acceptingVerifier(String key) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext ctx) {
        // accepts unconditionally
      }
    };
  }

  /** Creates a CaveatVerifier that always rejects by throwing. */
  private CaveatVerifier rejectingVerifier(String key) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext ctx) {
        throw new MacaroonVerificationException("caveat rejected: " + caveat);
      }
    };
  }

  /** Creates a CaveatVerifier with a custom isMoreRestrictive implementation. */
  private CaveatVerifier monotonicVerifier(String key, boolean restrictive) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext ctx) {
        // accepts unconditionally
      }

      @Override
      public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        return restrictive;
      }
    };
  }

  @Nested
  @DisplayName("valid macaroon verification")
  class ValidMacaroon {

    @Test
    @DisplayName("succeeds for valid macaroon with no caveats")
    void validNoCaveats() {
      Macaroon macaroon =
          createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, List.of(), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("succeeds for valid macaroon with caveats and matching verifiers")
    void validWithCaveats() {
      List<Caveat> caveats = List.of(new Caveat("service", "api"), new Caveat("tier", "premium"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("service"), acceptingVerifier("tier"));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("tampered signature")
  class TamperedSignature {

    @Test
    @DisplayName("fails when a byte in the signature is flipped")
    void tamperedSignatureFails() {
      Macaroon valid = createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

      byte[] tamperedSig = valid.signature();
      tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
      Macaroon tampered =
          new Macaroon(valid.identifier(), valid.location(), valid.caveats(), tamperedSig);

      assertThatThrownBy(() -> MacaroonVerifier.verify(tampered, rootKey, List.of(), context))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  @Nested
  @DisplayName("tampered caveat")
  class TamperedCaveat {

    @Test
    @DisplayName("fails when a caveat value is changed but signature is unchanged")
    void tamperedCaveatFails() {
      List<Caveat> originalCaveats = List.of(new Caveat("service", "api"));
      Macaroon valid =
          createValidMacaroon(rootKey, identifier, "https://example.com", originalCaveats);

      // Build a tampered macaroon: different caveat value, same signature
      List<Caveat> tamperedCaveats = List.of(new Caveat("service", "admin"));
      Macaroon tampered =
          new Macaroon(valid.identifier(), valid.location(), tamperedCaveats, valid.signature());

      assertThatThrownBy(
              () ->
                  MacaroonVerifier.verify(
                      tampered, rootKey, List.of(acceptingVerifier("service")), context))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  @Nested
  @DisplayName("wrong root key")
  class WrongRootKey {

    @Test
    @DisplayName("fails when verified with a different root key")
    void wrongRootKeyFails() {
      Macaroon macaroon =
          createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

      byte[] wrongKey = new byte[32];
      System.arraycopy(rootKey, 0, wrongKey, 0, rootKey.length);
      wrongKey[0] = (byte) (wrongKey[0] ^ 0xFF);

      assertThatThrownBy(() -> MacaroonVerifier.verify(macaroon, wrongKey, List.of(), context))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  @Nested
  @DisplayName("caveat verifier rejection")
  class CaveatVerifierRejection {

    @Test
    @DisplayName("propagates exception when caveat verifier rejects")
    void verifierRejectsPropagates() {
      List<Caveat> caveats = List.of(new Caveat("service", "api"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      assertThatThrownBy(
              () ->
                  MacaroonVerifier.verify(
                      macaroon, rootKey, List.of(rejectingVerifier("service")), context))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  @Nested
  @DisplayName("unknown caveat handling")
  class UnknownCaveatHandling {

    @Test
    @DisplayName("skips unknown caveats when a verifier exists for other caveats")
    void skipsUnknownCaveatsWithKnownOnes() {
      List<Caveat> caveats =
          List.of(new Caveat("services", "foo:0"), new Caveat("custom_app_data", "xyz"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // Only register a verifier for "services" — "custom_app_data" is unknown
      List<CaveatVerifier> verifiers = List.of(acceptingVerifier("services"));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes when macaroon has only unknown caveats and signature is valid")
    void passesWithOnlyUnknownCaveats() {
      List<Caveat> caveats =
          List.of(new Caveat("custom_app_data", "xyz"), new Caveat("another_unknown", "abc"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // No verifiers registered — all caveats are unknown
      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, List.of(), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("still rejects known caveat that fails verification alongside unknown ones")
    void rejectsKnownCaveatFailureWithUnknownOnes() {
      List<Caveat> caveats =
          List.of(new Caveat("custom_app_data", "xyz"), new Caveat("service", "api"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // "service" verifier rejects, "custom_app_data" has no verifier (skipped)
      List<CaveatVerifier> verifiers = List.of(rejectingVerifier("service"));

      assertThatThrownBy(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  @Nested
  @DisplayName("monotonic restriction validation")
  class MonotonicRestriction {

    @Test
    @DisplayName("passes when repeated caveat is more restrictive")
    void passesWhenMoreRestrictive() {
      List<Caveat> caveats = List.of(new Caveat("tier", "gold"), new Caveat("tier", "silver"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // isMoreRestrictive returns true
      List<CaveatVerifier> verifiers = List.of(monotonicVerifier("tier", true));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fails when repeated caveat escalates access")
    void failsWhenEscalating() {
      List<Caveat> caveats = List.of(new Caveat("tier", "silver"), new Caveat("tier", "gold"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // isMoreRestrictive returns false (escalation)
      List<CaveatVerifier> verifiers = List.of(monotonicVerifier("tier", false));

      assertThatThrownBy(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("caveat escalation detected for key: tier");
    }

    @Test
    @DisplayName("does not check monotonicity for first occurrence of a caveat key")
    void firstOccurrenceNotChecked() {
      List<Caveat> caveats = List.of(new Caveat("tier", "gold"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      // Even with isMoreRestrictive=false, single occurrence should pass
      List<CaveatVerifier> verifiers = List.of(monotonicVerifier("tier", false));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("services caveat: subset passes monotonic check")
    void servicesSubsetPasses() {
      List<Caveat> caveats =
          List.of(new Caveat("services", "a:0,b:0"), new Caveat("services", "a:0"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      L402VerificationContext ctx = L402VerificationContext.builder().serviceName("a").build();

      List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier(50));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, ctx))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("services caveat: superset fails monotonic check (escalation)")
    void servicesSupersetFails() {
      List<Caveat> caveats =
          List.of(new Caveat("services", "a:0"), new Caveat("services", "a:0,b:0"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      L402VerificationContext ctx = L402VerificationContext.builder().serviceName("a").build();

      List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier(50));

      assertThatThrownBy(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, ctx))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("caveat escalation detected for key: services");
    }

    @Test
    @DisplayName("valid_until caveat: earlier timestamp passes monotonic check")
    void validUntilEarlierPasses() {
      List<Caveat> caveats =
          List.of(
              new Caveat("svc_valid_until", "2000000000"),
              new Caveat("svc_valid_until", "1999999000"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      L402VerificationContext ctx =
          L402VerificationContext.builder()
              .serviceName("svc")
              .currentTime(java.time.Instant.ofEpochSecond(1900000000))
              .build();

      List<CaveatVerifier> verifiers = List.of(new ValidUntilCaveatVerifier("svc"));

      assertThatCode(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, ctx))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("valid_until caveat: later timestamp fails monotonic check (escalation)")
    void validUntilLaterFails() {
      List<Caveat> caveats =
          List.of(
              new Caveat("svc_valid_until", "1999999000"),
              new Caveat("svc_valid_until", "2000000000"));
      Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

      L402VerificationContext ctx =
          L402VerificationContext.builder()
              .serviceName("svc")
              .currentTime(java.time.Instant.ofEpochSecond(1900000000))
              .build();

      List<CaveatVerifier> verifiers = List.of(new ValidUntilCaveatVerifier("svc"));

      assertThatThrownBy(() -> MacaroonVerifier.verify(macaroon, rootKey, verifiers, ctx))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("caveat escalation detected for key: svc_valid_until");
    }
  }

  @Nested
  @DisplayName("verifyCaveats direct invocation")
  class VerifyCaveatsDirect {

    @Test
    @DisplayName("throws on caveat escalation detected via verifyCaveats")
    void escalationDetected() {
      List<Caveat> caveats = List.of(new Caveat("tier", "silver"), new Caveat("tier", "gold"));
      List<CaveatVerifier> verifiers = List.of(monotonicVerifier("tier", false));

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("caveat escalation detected for key: tier");
    }

    @Test
    @DisplayName("skips unknown caveats without throwing")
    void unknownCaveatsSkipped() {
      List<Caveat> caveats =
          List.of(
              new Caveat("unknown_key", "some_value"),
              new Caveat("another_unknown", "other_value"));

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, List.of(), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes context through to verifier")
    void contextPassedThrough() {
      L402VerificationContext specificContext =
          L402VerificationContext.builder().serviceName("test-service").build();

      List<Caveat> caveats = List.of(new Caveat("service", "test-service"));

      // Verifier that captures and asserts the context it receives
      CaveatVerifier capturingVerifier =
          new CaveatVerifier() {
            @Override
            public String getKey() {
              return "service";
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext ctx) {
              if (ctx != specificContext) {
                throw new AssertionError("context object was not the same instance");
              }
            }
          };

      assertThatCode(
              () ->
                  MacaroonVerifier.verifyCaveats(
                      caveats, List.of(capturingVerifier), specificContext))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("missing capabilities caveat enforcement")
  class MissingCapabilitiesCaveatEnforcement {

    @Test
    @DisplayName("rejects macaroon missing capabilities caveat when capability is required")
    void rejectsMissingCapabilitiesCaveat() {
      L402VerificationContext ctx =
          L402VerificationContext.builder()
              .serviceName("test-service")
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", "test-service:0"),
              new Caveat("test-service_valid_until", "2000000000"));
      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("services"), acceptingVerifier("test-service_valid_until"));

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              ex -> {
                MacaroonVerificationException mve = (MacaroonVerificationException) ex;
                org.assertj.core.api.Assertions.assertThat(mve.getReason())
                    .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
              })
          .hasMessageContaining("missing required capabilities caveat")
          .hasMessageContaining("search");
    }

    @Test
    @DisplayName("passes when capabilities caveat is present and capability is required")
    void passesWithCapabilitiesCaveatPresent() {
      L402VerificationContext ctx =
          L402VerificationContext.builder()
              .serviceName("test-service")
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", "test-service:0"),
              new Caveat("test-service_capabilities", "search,analyze"));
      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("services"), acceptingVerifier("test-service_capabilities"));

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes when no capability is requested and no capabilities caveat present")
    void passesNoCapabilityRequestedNoCaveat() {
      L402VerificationContext ctx =
          L402VerificationContext.builder().serviceName("test-service").build();

      List<Caveat> caveats = List.of(new Caveat("services", "test-service:0"));
      List<CaveatVerifier> verifiers = List.of(acceptingVerifier("services"));

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes when no capability is requested but capabilities caveat is present")
    void passesNoCapabilityRequestedWithCaveat() {
      L402VerificationContext ctx =
          L402VerificationContext.builder().serviceName("test-service").build();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", "test-service:0"),
              new Caveat("test-service_capabilities", "search"));
      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("services"), acceptingVerifier("test-service_capabilities"));

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects capabilities caveat when no capability is requested (fail-closed)")
    void rejectsCapabilitiesCaveatWhenNoCapabilityRequested() {
      L402VerificationContext ctx =
          L402VerificationContext.builder().serviceName("test-service").build();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", "test-service:0"),
              new Caveat("test-service_capabilities", "search"));
      List<CaveatVerifier> verifiers =
          List.of(
              new ServicesCaveatVerifier(50), new CapabilitiesCaveatVerifier("test-service", 50));

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              ex -> {
                MacaroonVerificationException mve = (MacaroonVerificationException) ex;
                org.assertj.core.api.Assertions.assertThat(mve.getReason())
                    .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
              })
          .hasMessageContaining("no capability declared");
    }

    @Test
    @DisplayName(
        "rejects on cached path (direct verifyCaveats call) when capabilities caveat missing")
    void rejectsOnCachedPath() {
      // This simulates the cached credential path where verifyCaveats is called
      // directly without going through verify() first
      L402VerificationContext ctx =
          L402VerificationContext.builder()
              .serviceName("test-service")
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"))
              .build();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", "test-service:0"),
              new Caveat("test-service_valid_until", "2000000000"));
      List<CaveatVerifier> verifiers =
          List.of(new ServicesCaveatVerifier(50), new ValidUntilCaveatVerifier("test-service"));

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, verifiers, ctx))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              ex -> {
                MacaroonVerificationException mve = (MacaroonVerificationException) ex;
                org.assertj.core.api.Assertions.assertThat(mve.getReason())
                    .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
              })
          .hasMessageContaining("missing required capabilities caveat")
          .hasMessageContaining("write");
    }
  }
}
