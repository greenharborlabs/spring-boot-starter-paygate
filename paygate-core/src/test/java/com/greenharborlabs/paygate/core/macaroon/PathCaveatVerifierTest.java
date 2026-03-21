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

@DisplayName("PathCaveatVerifier")
class PathCaveatVerifierTest {

    private PathCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new PathCaveatVerifier(50);
    }

    @Test
    @DisplayName("getKey returns 'path'")
    void getKeyReturnsPath() {
        assertThat(verifier.getKey()).isEqualTo("path");
    }

    // ---------------------------------------------------------------
    // Acceptance scenarios 1-21
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("double-star glob matching")
    class DoubleStarGlob {

        @Test
        @DisplayName("1: path=/products/** matches /products/123")
        void doubleStarMatchesSubPath() {
            Caveat caveat = new Caveat("path", "/products/**");
            L402VerificationContext context = contextWithPath("/products/123");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("2: path=/products/** rejects /orders/1")
        void doubleStarRejectsUnrelatedPath() {
            Caveat caveat = new Caveat("path", "/products/**");
            L402VerificationContext context = contextWithPath("/orders/1");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("9: path=/** matches root /")
        void doubleStarMatchesRoot() {
            Caveat caveat = new Caveat("path", "/**");
            L402VerificationContext context = contextWithPath("/");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("10: path=/** matches /any/deep/path")
        void doubleStarMatchesDeepPath() {
            Caveat caveat = new Caveat("path", "/**");
            L402VerificationContext context = contextWithPath("/any/deep/path");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("13: path=/products/** matches /products (zero additional segments)")
        void doubleStarMatchesZeroAdditionalSegments() {
            Caveat caveat = new Caveat("path", "/products/**");
            L402VerificationContext context = contextWithPath("/products");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("single-star glob matching")
    class SingleStarGlob {

        @Test
        @DisplayName("3: path=/products/* rejects /products/123/reviews (multi-segment)")
        void singleStarRejectsMultipleSegments() {
            Caveat caveat = new Caveat("path", "/products/*");
            L402VerificationContext context = contextWithPath("/products/123/reviews");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("15: path=/api/*/details matches /api/orders/details (single-segment middle)")
        void singleStarInMiddleMatches() {
            Caveat caveat = new Caveat("path", "/api/*/details");
            L402VerificationContext context = contextWithPath("/api/orders/details");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("16: path=/api/*/details rejects /api/orders/123/details (multi-segment middle)")
        void singleStarInMiddleRejectsMultiSegment() {
            Caveat caveat = new Caveat("path", "/api/*/details");
            L402VerificationContext context = contextWithPath("/api/orders/123/details");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("17: path=/api/*/details rejects /api/details (star requires one segment)")
        void singleStarRequiresOneSegment() {
            Caveat caveat = new Caveat("path", "/api/*/details");
            L402VerificationContext context = contextWithPath("/api/details");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    @Nested
    @DisplayName("multiple patterns")
    class MultiplePatterns {

        @Test
        @DisplayName("4: path=/products/**,/orders/** matches /orders/1")
        void multiplePatternMatchesSecond() {
            Caveat caveat = new Caveat("path", "/products/**,/orders/**");
            L402VerificationContext context = contextWithPath("/orders/1");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("path normalization")
    class PathNormalization {

        @Test
        @DisplayName("5: path=/products matches /products/ (trailing slash normalization)")
        void trailingSlashNormalized() {
            Caveat caveat = new Caveat("path", "/products");
            L402VerificationContext context = contextWithPath("/products/");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("6: path=/products/* matches /products/123?format=json (query string excluded)")
        void queryStringExcluded() {
            Caveat caveat = new Caveat("path", "/products/*");
            L402VerificationContext context = contextWithPath("/products/123?format=json");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("7: path=/products/* matches /products/my%20item (percent-encoded decoded)")
        void percentEncodedDecoded() {
            Caveat caveat = new Caveat("path", "/products/*");
            L402VerificationContext context = contextWithPath("/products/my%20item");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("8: path=/products/* rejects request with %2F in segment (encoded slash NOT decoded)")
        void encodedSlashNotDecoded() {
            Caveat caveat = new Caveat("path", "/products/*");
            L402VerificationContext context = contextWithPath("/products/foo%2Fbar");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("8b: path=/products/** rejects request with %2F in segment (encoded slash rejected for double-star too)")
        void encodedSlashRejectedWithDoubleStar() {
            Caveat caveat = new Caveat("path", "/products/**");
            L402VerificationContext context = contextWithPath("/products/foo%2Fbar");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("14: /api/../products/123 with path=/products/** matches (dot-segment resolution)")
        void dotSegmentResolution() {
            Caveat caveat = new Caveat("path", "/products/**");
            L402VerificationContext context = contextWithPath("/api/../products/123");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("21: /api//products/123 with path=/api/products/** matches (consecutive slashes collapsed)")
        void consecutiveSlashesCollapsed() {
            Caveat caveat = new Caveat("path", "/api/products/**");
            L402VerificationContext context = contextWithPath("/api//products/123");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("exact path matching")
    class ExactPathMatching {

        @Test
        @DisplayName("11: path=/ matches / (root only)")
        void rootMatchesRoot() {
            Caveat caveat = new Caveat("path", "/");
            L402VerificationContext context = contextWithPath("/");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("12: path=/ rejects /products (root only does not match sub-paths)")
        void rootDoesNotMatchSubPaths() {
            Caveat caveat = new Caveat("path", "/");
            L402VerificationContext context = contextWithPath("/products");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    @Nested
    @DisplayName("gateway rewrite")
    class GatewayRewrite {

        // Gateway rewrite behavior (stripping a prefix like /gateway) is tested at the filter level.
        // This verifier test only confirms matching against the application-relative path provided in context.
        @Test
        @DisplayName("18: application-relative /api/products/123 matches path=/api/products/**")
        void applicationRelativePathMatches() {
            Caveat caveat = new Caveat("path", "/api/products/**");
            L402VerificationContext context = contextWithPath("/api/products/123");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("invalid patterns")
    class InvalidPatterns {

        @Test
        @DisplayName("19: path=/api/**/details rejects as invalid (non-terminal **)")
        void nonTerminalDoubleStarRejected() {
            Caveat caveat = new Caveat("path", "/api/**/details");
            L402VerificationContext context = contextWithPath("/api/foo/details");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("20: path=/api/product-* rejects /api/product-123 (product-* is literal, not wildcard)")
        void infixWildcardTreatedAsLiteral() {
            Caveat caveat = new Caveat("path", "/api/product-*");
            L402VerificationContext context = contextWithPath("/api/product-123");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty caveat value (path=,,,) rejects")
        void emptyCaveatValueRejects() {
            Caveat caveat = new Caveat("path", ",,,");
            L402VerificationContext context = contextWithPath("/products/123");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("missing request.path in context rejects (fail-closed)")
        void missingRequestPathRejects() {
            Caveat caveat = new Caveat("path", "/products/**");
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
            PathCaveatVerifier restrictedVerifier = new PathCaveatVerifier(2);
            Caveat caveat = new Caveat("path", "/a/**,/b/**,/c/**");
            L402VerificationContext context = contextWithPath("/a/1");

            assertThatThrownBy(() -> restrictedVerifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("semantically empty value after trim (path= , , ) rejects")
        void semanticallyEmptyAfterTrimRejects() {
            Caveat caveat = new Caveat("path", " , , ");
            L402VerificationContext context = contextWithPath("/products/123");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    private static L402VerificationContext contextWithPath(String path) {
        return L402VerificationContext.builder()
                .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, path))
                .build();
    }
}
