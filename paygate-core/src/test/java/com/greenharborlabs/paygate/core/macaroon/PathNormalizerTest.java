package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathNormalizer")
class PathNormalizerTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("happy path — clean path unchanged")
        void happyPath() {
            assertThat(PathNormalizer.normalize("/api/products/123"))
                    .isEqualTo("/api/products/123");
        }

        @Test
        @DisplayName("strips query string")
        void queryStrip() {
            assertThat(PathNormalizer.normalize("/api/products?foo=bar"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("iterative decode resolves double-encoded traversal")
        void iterativeDecode() {
            assertThat(PathNormalizer.normalize("/api/%252e%252e/secret"))
                    .isEqualTo("/secret");
        }

        @Test
        @DisplayName("single-pass decode would fail — iterative catches it")
        void singlePassWouldFail() {
            assertThat(PathNormalizer.normalize("/api/public/%252e%252e/protected"))
                    .isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("reserved delimiter %2F preserved")
        void reservedSlash() {
            assertThat(PathNormalizer.normalize("/api/v1%2Fbypass"))
                    .isEqualTo("/api/v1%2Fbypass");
        }

        @Test
        @DisplayName("reserved delimiter %3F preserved")
        void reservedQuery() {
            assertThat(PathNormalizer.normalize("/api/a%3Fb"))
                    .isEqualTo("/api/a%3Fb");
        }

        @Test
        @DisplayName("reserved delimiter %23 preserved")
        void reservedHash() {
            assertThat(PathNormalizer.normalize("/api/a%23b"))
                    .isEqualTo("/api/a%23b");
        }

        @Test
        @DisplayName("reserved delimiter %3A preserved")
        void reservedColon() {
            assertThat(PathNormalizer.normalize("/api/a%3Ab"))
                    .isEqualTo("/api/a%3Ab");
        }

        @Test
        @DisplayName("reserved delimiter %3a lowercase uppercased")
        void reservedColonLowercaseUppercased() {
            assertThat(PathNormalizer.normalize("/api/a%3ab"))
                    .isEqualTo("/api/a%3Ab");
        }

        @Test
        @DisplayName("hex digits uppercased for reserved delimiters")
        void hexUppercased() {
            assertThat(PathNormalizer.normalize("/api/a%2fb"))
                    .isEqualTo("/api/a%2Fb");
        }

        @Test
        @DisplayName("collapses consecutive slashes")
        void slashCollapse() {
            assertThat(PathNormalizer.normalize("/api///products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("strips trailing slash")
        void trailingSlash() {
            assertThat(PathNormalizer.normalize("/api/products/"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("root preserved")
        void rootPreserved() {
            assertThat(PathNormalizer.normalize("/"))
                    .isEqualTo("/");
        }

        @Test
        @DisplayName("null returns root")
        void nullReturnsRoot() {
            assertThat(PathNormalizer.normalize(null))
                    .isEqualTo("/");
        }

        @Test
        @DisplayName("empty returns root")
        void emptyReturnsRoot() {
            assertThat(PathNormalizer.normalize(""))
                    .isEqualTo("/");
        }

        @Test
        @DisplayName("plus sign treated as literal")
        void plusLiteral() {
            assertThat(PathNormalizer.normalize("/api/a+b"))
                    .isEqualTo("/api/a+b");
        }

        @Test
        @DisplayName("prepends slash if missing")
        void prependSlash() {
            assertThat(PathNormalizer.normalize("api/products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("resolves single dot segment")
        void dotSegment() {
            assertThat(PathNormalizer.normalize("/api/./products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("resolves parent dot segment")
        void parentDotSegment() {
            assertThat(PathNormalizer.normalize("/api/../products"))
                    .isEqualTo("/products");
        }

        @Test
        @DisplayName("percent-encoded dot resolved")
        void percentEncodedDot() {
            assertThat(PathNormalizer.normalize("/api/%2e/products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("percent-encoded double-dot resolved")
        void percentEncodedDoubleDot() {
            assertThat(PathNormalizer.normalize("/api/public/%2e%2e/protected"))
                    .isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("five-layer encoded dot-segments resolved via cleanup pass")
        void fiveLayerEncodedDotSegmentsResolved() {
            // 5 layers: %25252525252e%25252525252e -> after 4 passes: %2e%2e -> cleanup pass: .. -> resolved
            assertThat(PathNormalizer.normalize("/api/public/%252525252e%252525252e/secret"))
                    .isEqualTo("/api/secret");
        }

        @Test
        @DisplayName("traversal above root clamped")
        void traversalAboveRootClamped() {
            assertThat(PathNormalizer.normalize("/../../../api"))
                    .isEqualTo("/api");
        }
    }

    @Nested
    @DisplayName("percentDecode")
    class PercentDecode {

        @Test
        @DisplayName("invalid hex digits pass through unchanged")
        void invalidHex() {
            assertThat(PathNormalizer.percentDecode("%ZZ"))
                    .isEqualTo("%ZZ");
        }

        @Test
        @DisplayName("incomplete percent at end passes through unchanged")
        void incompletePercent() {
            assertThat(PathNormalizer.percentDecode("abc%"))
                    .isEqualTo("abc%");
        }
    }
}
