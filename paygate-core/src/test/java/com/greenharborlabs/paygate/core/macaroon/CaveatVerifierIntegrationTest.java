package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CaveatVerifierIntegrationTest — conjunctive multi-caveat evaluation")
class CaveatVerifierIntegrationTest {

  private List<CaveatVerifier> allVerifiers;

  @BeforeEach
  void setUp() {
    allVerifiers =
        List.of(
            new PathCaveatVerifier(50),
            new MethodCaveatVerifier(50),
            new ClientIpCaveatVerifier(50));
  }

  // ---------------------------------------------------------------
  // Cross-key conjunctive evaluation (US4-6, US4-7)
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("cross-key conjunctive evaluation")
  class CrossKeyConjunctive {

    private final List<Caveat> caveats =
        List.of(
            new Caveat("path", "/api/**"),
            new Caveat("method", "GET"),
            new Caveat("client_ip", "203.0.113.42"));

    @Test
    @DisplayName("US4-6: POST to /api/products/123 from matching IP — rejected (method fails)")
    void postRejectedWhenMethodCaveatIsGet() {
      L402VerificationContext context =
          L402VerificationContext.builder()
              .requestMetadata(
                  Map.of(
                      VerificationContextKeys.REQUEST_PATH, "/api/products/123",
                      VerificationContextKeys.REQUEST_METHOD, "POST",
                      VerificationContextKeys.REQUEST_CLIENT_IP, "203.0.113.42"))
              .build();

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
          .isInstanceOf(MacaroonVerificationException.class);
    }

    @Test
    @DisplayName(
        "US4-7: GET to /api/products/123 from matching IP — authorized (all caveats satisfied)")
    void getAllCaveatsSatisfied() {
      L402VerificationContext context =
          L402VerificationContext.builder()
              .requestMetadata(
                  Map.of(
                      VerificationContextKeys.REQUEST_PATH, "/api/products/123",
                      VerificationContextKeys.REQUEST_METHOD, "GET",
                      VerificationContextKeys.REQUEST_CLIENT_IP, "203.0.113.42"))
              .build();

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
          .doesNotThrowAnyException();
    }
  }

  // ---------------------------------------------------------------
  // Same-key conjunctive evaluation (US4-8, US4-8b)
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("same-key conjunctive evaluation (delegation chain)")
  class SameKeyConjunctive {

    private final List<Caveat> caveats =
        List.of(new Caveat("path", "/api/**"), new Caveat("path", "/api/products/**"));

    @Test
    @DisplayName("US4-8: request to /api/users/1 — rejected (second path caveat fails)")
    void requestOutsideNarrowedPathRejected() {
      L402VerificationContext context =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, "/api/users/1"))
              .build();

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
          .isInstanceOf(MacaroonVerificationException.class);
    }

    @Test
    @DisplayName("US4-8b: request to /api/products/123 — authorized (both path caveats pass)")
    void requestWithinNarrowedPathAuthorized() {
      L402VerificationContext context =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, "/api/products/123"))
              .build();

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
          .doesNotThrowAnyException();
    }
  }

  // ---------------------------------------------------------------
  // Cached credential re-evaluation (FR-020)
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("cached credential re-evaluation per request context")
  class CachedCredentialReEvaluation {

    @Test
    @DisplayName("FR-020: same macaroon re-evaluated per request context — path")
    void sameMacaroonReEvaluatedPerRequestContextPath() {
      List<Caveat> caveats = List.of(new Caveat("path", "/api/products/**"));

      L402VerificationContext contextA =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, "/api/products/1"))
              .build();

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextA))
          .doesNotThrowAnyException();

      L402VerificationContext contextB =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, "/api/admin/settings"))
              .build();

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextB))
          .isInstanceOf(MacaroonVerificationException.class);
    }

    @Test
    @DisplayName("FR-020: same macaroon re-evaluated with different method")
    void sameMacaroonReEvaluatedWithDifferentMethod() {
      List<Caveat> caveats = List.of(new Caveat("method", "GET"));

      L402VerificationContext contextA =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_METHOD, "GET"))
              .build();

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextA))
          .doesNotThrowAnyException();

      L402VerificationContext contextB =
          L402VerificationContext.builder()
              .requestMetadata(Map.of(VerificationContextKeys.REQUEST_METHOD, "POST"))
              .build();

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextB))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  // ---------------------------------------------------------------
  // Trusted accepted-value provenance (US4, FR-026/027)
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("trusted accepted-value provenance")
  class TrustedAcceptedValueProvenance {

    @Test
    @DisplayName("rejects a blank verifier key before it can own trusted provenance")
    void rejectsBlankVerifierKey() {
      assertThatThrownBy(() -> MacaroonVerifier.buildVerifierMap(List.of(acceptingVerifier("  "))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects duplicate verifier ownership instead of selecting a provenance source")
    void rejectsDuplicateVerifierKey() {
      assertThatThrownBy(
              () ->
                  MacaroonVerifier.buildVerifierMap(
                      List.of(acceptingVerifier("role"), acceptingVerifier("role"))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keeps differently cased registered keys as separate case-sensitive provenance")
    void preservesCaseSensitiveVerifierKeyProvenance() {
      Map<String, String> acceptedValues =
          MacaroonVerifier.verifyCaveats(
              List.of(new Caveat("role", "viewer"), new Caveat("ROLE", "operator")),
              List.of(acceptingVerifier("role"), acceptingVerifier("ROLE")),
              new L402VerificationContext());

      assertThat(acceptedValues)
          .containsExactlyInAnyOrderEntriesOf(Map.of("role", "viewer", "ROLE", "operator"));
    }

    @Test
    @DisplayName("excludes an unknown holder-added key from trusted provenance")
    void excludesUnknownCaveatFromTrustedProvenance() {
      Map<String, String> acceptedValues =
          MacaroonVerifier.verifyCaveats(
              List.of(new Caveat("role", "viewer"), new Caveat("Role", "admin")),
              List.of(acceptingVerifier("role")),
              new L402VerificationContext());

      assertThat(acceptedValues).containsExactlyEntriesOf(Map.of("role", "viewer"));
      assertThat(acceptedValues).isUnmodifiable();
    }

    @Test
    @DisplayName("never returns provenance when a registered verifier rejects its value")
    void rejectsUnsuccessfullyVerifiedCaveatBeforeReturningProvenance() {
      assertThatThrownBy(
              () ->
                  MacaroonVerifier.verifyCaveats(
                      List.of(new Caveat("role", "admin")),
                      List.of(rejectingVerifier("role")),
                      new L402VerificationContext()))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }

  private static CaveatVerifier acceptingVerifier(String key) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext context) {
        // Accepts the configured key's value.
      }
    };
  }

  private static CaveatVerifier rejectingVerifier(String key) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext context) {
        throw new MacaroonVerificationException("caveat rejected: " + caveat.key());
      }
    };
  }
}
