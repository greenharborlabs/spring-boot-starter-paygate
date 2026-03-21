package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MethodCaveatVerifier")
class MethodCaveatVerifierTest {

    private MethodCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new MethodCaveatVerifier(50);
    }

    @Test
    @DisplayName("getKey returns 'method'")
    void getKeyReturnsMethod() {
        assertThat(verifier.getKey()).isEqualTo("method");
    }

    // ---------------------------------------------------------------
    // Acceptance scenarios 1-6
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("single method matching")
    class SingleMethodMatching {

        @Test
        @DisplayName("1: method=GET allows GET request")
        void getMethodAllowsGetRequest() {
            Caveat caveat = new Caveat("method", "GET");
            L402VerificationContext context = contextWithMethod("GET");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("2: method=GET rejects POST request")
        void getMethodRejectsPostRequest() {
            Caveat caveat = new Caveat("method", "GET");
            L402VerificationContext context = contextWithMethod("POST");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("5: method=GET rejects HEAD (no implicit HEAD for GET)")
        void getMethodRejectsHead() {
            Caveat caveat = new Caveat("method", "GET");
            L402VerificationContext context = contextWithMethod("HEAD");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    @Nested
    @DisplayName("multiple methods")
    class MultipleMethods {

        @Test
        @DisplayName("3: method=GET,HEAD allows HEAD request")
        void getAndHeadAllowsHead() {
            Caveat caveat = new Caveat("method", "GET,HEAD");
            L402VerificationContext context = contextWithMethod("HEAD");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("6: method=GET,HEAD allows HEAD (explicitly included)")
        void getAndHeadExplicitlyAllowsHead() {
            Caveat caveat = new Caveat("method", "GET,HEAD");
            L402VerificationContext context = contextWithMethod("HEAD");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("case insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("4: method=get allows GET request (case-insensitive)")
        void lowercaseCaveatMatchesUppercaseRequest() {
            Caveat caveat = new Caveat("method", "get");
            L402VerificationContext context = contextWithMethod("GET");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("method=GET allows get request (case-insensitive)")
        void uppercaseCaveatMatchesLowercaseRequest() {
            Caveat caveat = new Caveat("method", "GET");
            L402VerificationContext context = contextWithMethod("get");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("method=Get,Post allows POST request (mixed case)")
        void mixedCaseCaveatMatchesMixedCaseRequest() {
            Caveat caveat = new Caveat("method", "Get,Post");
            L402VerificationContext context = contextWithMethod("POST");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty caveat value (method=,,,) rejects")
        void emptyCaveatValueRejects() {
            Caveat caveat = new Caveat("method", ",,,");
            L402VerificationContext context = contextWithMethod("GET");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("missing request.method in context rejects (fail-closed)")
        void missingRequestMethodRejects() {
            Caveat caveat = new Caveat("method", "GET");
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of())
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("exceeding maxValuesPerCaveat rejects")
        void exceedingMaxValuesRejects() {
            MethodCaveatVerifier restrictedVerifier = new MethodCaveatVerifier(2);
            Caveat caveat = new Caveat("method", "GET,POST,DELETE");
            L402VerificationContext context = contextWithMethod("GET");

            assertThatThrownBy(() -> restrictedVerifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("semantically empty value after trim (method= , , ) rejects")
        void semanticallyEmptyAfterTrimRejects() {
            Caveat caveat = new Caveat("method", " , , ");
            L402VerificationContext context = contextWithMethod("GET");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("whitespace around comma-separated values is trimmed")
        void whitespaceTrimmed() {
            Caveat caveat = new Caveat("method", " GET , POST ");
            L402VerificationContext context = contextWithMethod("POST");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("custom HTTP methods")
    class CustomMethods {

        @Test
        @DisplayName("method=PURGE allows PURGE request")
        void purgeMethodAllowed() {
            Caveat caveat = new Caveat("method", "PURGE");
            L402VerificationContext context = contextWithMethod("PURGE");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("method=PROPFIND allows PROPFIND request (WebDAV)")
        void propfindMethodAllowed() {
            Caveat caveat = new Caveat("method", "PROPFIND");
            L402VerificationContext context = contextWithMethod("PROPFIND");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("method=PATCH,DELETE allows DELETE request")
        void customMethodListAllowsMatch() {
            Caveat caveat = new Caveat("method", "PATCH,DELETE");
            L402VerificationContext context = contextWithMethod("DELETE");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    private static L402VerificationContext contextWithMethod(String method) {
        return L402VerificationContext.builder()
                .requestMetadata(Map.of(VerificationContextKeys.REQUEST_METHOD, method))
                .build();
    }
}
