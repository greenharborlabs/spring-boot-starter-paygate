package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CaveatVerifierIntegrationTest — conjunctive multi-caveat evaluation")
class CaveatVerifierIntegrationTest {

    private List<CaveatVerifier> allVerifiers;

    @BeforeEach
    void setUp() {
        allVerifiers = List.of(
                new PathCaveatVerifier(50),
                new MethodCaveatVerifier(50),
                new ClientIpCaveatVerifier(50)
        );
    }

    // ---------------------------------------------------------------
    // Cross-key conjunctive evaluation (US4-6, US4-7)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("cross-key conjunctive evaluation")
    class CrossKeyConjunctive {

        private final List<Caveat> caveats = List.of(
                new Caveat("path", "/api/**"),
                new Caveat("method", "GET"),
                new Caveat("client_ip", "203.0.113.42")
        );

        @Test
        @DisplayName("US4-6: POST to /api/products/123 from matching IP — rejected (method fails)")
        void postRejectedWhenMethodCaveatIsGet() {
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/products/123",
                            VerificationContextKeys.REQUEST_METHOD, "POST",
                            VerificationContextKeys.REQUEST_CLIENT_IP, "203.0.113.42"
                    ))
                    .build();

            assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
                    .isInstanceOf(L402Exception.class);
        }

        @Test
        @DisplayName("US4-7: GET to /api/products/123 from matching IP — authorized (all caveats satisfied)")
        void getAllCaveatsSatisfied() {
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/products/123",
                            VerificationContextKeys.REQUEST_METHOD, "GET",
                            VerificationContextKeys.REQUEST_CLIENT_IP, "203.0.113.42"
                    ))
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

        private final List<Caveat> caveats = List.of(
                new Caveat("path", "/api/**"),
                new Caveat("path", "/api/products/**")
        );

        @Test
        @DisplayName("US4-8: request to /api/users/1 — rejected (second path caveat fails)")
        void requestOutsideNarrowedPathRejected() {
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/users/1"
                    ))
                    .build();

            assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, context))
                    .isInstanceOf(L402Exception.class);
        }

        @Test
        @DisplayName("US4-8b: request to /api/products/123 — authorized (both path caveats pass)")
        void requestWithinNarrowedPathAuthorized() {
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/products/123"
                    ))
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

            L402VerificationContext contextA = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/products/1"
                    ))
                    .build();

            assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextA))
                    .doesNotThrowAnyException();

            L402VerificationContext contextB = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_PATH, "/api/admin/settings"
                    ))
                    .build();

            assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextB))
                    .isInstanceOf(L402Exception.class);
        }

        @Test
        @DisplayName("FR-020: same macaroon re-evaluated with different method")
        void sameMacaroonReEvaluatedWithDifferentMethod() {
            List<Caveat> caveats = List.of(new Caveat("method", "GET"));

            L402VerificationContext contextA = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_METHOD, "GET"
                    ))
                    .build();

            assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextA))
                    .doesNotThrowAnyException();

            L402VerificationContext contextB = L402VerificationContext.builder()
                    .requestMetadata(Map.of(
                            VerificationContextKeys.REQUEST_METHOD, "POST"
                    ))
                    .build();

            assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, allVerifiers, contextB))
                    .isInstanceOf(L402Exception.class);
        }
    }
}
