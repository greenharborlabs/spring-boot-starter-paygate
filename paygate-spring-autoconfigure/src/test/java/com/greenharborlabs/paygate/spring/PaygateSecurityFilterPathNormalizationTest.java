package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link PaygateSecurityFilter#normalizePath(String)} and
 * {@link PaygatePathUtils#percentDecodePath(String)}.
 * No Spring context needed.
 */
@DisplayName("PaygateSecurityFilter path normalization")
class PaygateSecurityFilterPathNormalizationTest {

    @Nested
    @DisplayName("normalizePath")
    class NormalizePath {

        @Test
        @DisplayName("null returns /")
        void nullReturnsRoot() {
            assertThat(PaygateSecurityFilter.normalizePath(null)).isEqualTo("/");
        }

        @Test
        @DisplayName("empty string returns /")
        void emptyReturnsRoot() {
            assertThat(PaygateSecurityFilter.normalizePath("")).isEqualTo("/");
        }

        @Test
        @DisplayName("root path returns /")
        void rootReturnsRoot() {
            assertThat(PaygateSecurityFilter.normalizePath("/")).isEqualTo("/");
        }

        @Test
        @DisplayName("simple path passes through")
        void simplePathPassesThrough() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("dot-dot segments are collapsed")
        void dotDotCollapsed() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/public/../protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("single dot segments are removed")
        void singleDotRemoved() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/./protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("percent-encoded dot-dot (%2e%2e) is decoded and collapsed")
        void percentEncodedDotDot() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/public/%2e%2e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("uppercase percent-encoded dot-dot (%2E%2E) is decoded and collapsed")
        void uppercasePercentEncodedDotDot() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/public/%2E%2E/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("double-encoded dot-dot (%252e%252e) is decoded and collapsed")
        void doubleEncodedDotDot() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/public/%252e%252e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("percent-encoded single dot (%2e) is decoded and removed")
        void percentEncodedSingleDot() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/%2e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("plus sign is treated as literal, not as space")
        void plusIsLiteral() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/c++/resource")).isEqualTo("/api/c++/resource");
        }

        @Test
        @DisplayName("traversal above root is clamped")
        void traversalAboveRootClamped() {
            assertThat(PaygateSecurityFilter.normalizePath("/../../../api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("empty segments from multiple slashes are collapsed")
        void emptySegmentsCollapsed() {
            assertThat(PaygateSecurityFilter.normalizePath("/api///protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("multiple dot-dot segments resolve correctly")
        void multipleDotDots() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/foo/bar/../../protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("all dot-dot resolves to root")
        void allDotDotsToRoot() {
            assertThat(PaygateSecurityFilter.normalizePath("/a/b/../../..")).isEqualTo("/");
        }

        @ParameterizedTest
        @CsvSource({
            "/api/public/%2e%2e/protected, /api/protected",
            "/api/public/%2E%2E/protected, /api/protected",
            "/api/public/%252e%252e/protected, /api/protected",
            "/api/%2e/protected, /api/protected",
            "/api/c++/resource, /api/c++/resource",
            "/../../../api/protected, /api/protected",
            "/api///protected, /api/protected",
        })
        @DisplayName("parameterized normalization cases")
        void parameterizedCases(String input, String expected) {
            assertThat(PaygateSecurityFilter.normalizePath(input)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("percentDecodePath")
    class PercentDecodePath {

        @Test
        @DisplayName("null returns null")
        void nullReturnsNull() {
            assertThat(PaygatePathUtils.percentDecodePath(null)).isNull();
        }

        @Test
        @DisplayName("empty returns empty")
        void emptyReturnsEmpty() {
            assertThat(PaygatePathUtils.percentDecodePath("")).isEmpty();
        }

        @Test
        @DisplayName("no percent sequences passes through")
        void noPercentPassesThrough() {
            assertThat(PaygatePathUtils.percentDecodePath("/api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("decodes %2e to dot")
        void decodesPercentToDot() {
            assertThat(PaygatePathUtils.percentDecodePath("%2e")).isEqualTo(".");
        }

        @Test
        @DisplayName("decodes uppercase %2E to dot")
        void decodesUppercasePercentToDot() {
            assertThat(PaygatePathUtils.percentDecodePath("%2E")).isEqualTo(".");
        }

        @Test
        @DisplayName("decodes %25 to percent")
        void decodesPercentToPercent() {
            assertThat(PaygatePathUtils.percentDecodePath("%25")).isEqualTo("%");
        }

        @Test
        @DisplayName("plus sign is not treated as space")
        void plusIsNotSpace() {
            assertThat(PaygatePathUtils.percentDecodePath("a+b")).isEqualTo("a+b");
        }

        @Test
        @DisplayName("incomplete percent at end passes through")
        void incompletePercentAtEnd() {
            assertThat(PaygatePathUtils.percentDecodePath("abc%")).isEqualTo("abc%");
        }

        @Test
        @DisplayName("incomplete percent with one hex char passes through")
        void incompletePercentOneHex() {
            assertThat(PaygatePathUtils.percentDecodePath("abc%2")).isEqualTo("abc%2");
        }

        @Test
        @DisplayName("invalid hex digits after percent passes through")
        void invalidHexAfterPercent() {
            assertThat(PaygatePathUtils.percentDecodePath("%ZZ")).isEqualTo("%ZZ");
        }

        @Test
        @DisplayName("decodes multi-byte UTF-8 sequence")
        void decodesMultiByteUtf8() {
            // U+00E9 (e-acute) = UTF-8 bytes C3 A9
            assertThat(PaygatePathUtils.percentDecodePath("%C3%A9")).isEqualTo("\u00e9");
        }

        @Test
        @DisplayName("FR-003b: preserves %2F (uppercase) encoded slash")
        void preservesUppercaseEncodedSlash() {
            assertThat(PaygatePathUtils.percentDecodePath("/api/v1%2Fbypass"))
                    .isEqualTo("/api/v1%2Fbypass");
        }

        @Test
        @DisplayName("FR-003b: preserves %2f (lowercase) encoded slash")
        void preservesLowercaseEncodedSlash() {
            assertThat(PaygatePathUtils.percentDecodePath("/api/v1%2fbypass"))
                    .isEqualTo("/api/v1%2fbypass");
        }

        @Test
        @DisplayName("FR-003b: still decodes %2E for traversal protection alongside preserved %2F")
        void decodesDotsButPreservesSlashes() {
            assertThat(PaygatePathUtils.percentDecodePath("/api/%2e%2e/v1%2Fbypass"))
                    .isEqualTo("/api/../v1%2Fbypass");
        }
    }

    @Nested
    @DisplayName("normalizePath — %2F preservation (FR-003b)")
    class NormalizePathEncodedSlash {

        @Test
        @DisplayName("FR-003b: %2F preserved through full normalization")
        void encodedSlashPreservedThroughNormalization() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/v1%2Fbypass"))
                    .isEqualTo("/api/v1%2Fbypass");
        }

        @Test
        @DisplayName("FR-003b: %2f (lowercase) preserved through full normalization")
        void lowercaseEncodedSlashPreservedThroughNormalization() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/v1%2fbypass"))
                    .isEqualTo("/api/v1%2fbypass");
        }

        @Test
        @DisplayName("FR-003b: %2E traversal still decoded and collapsed with %2F preserved")
        void traversalDecodedWhileSlashPreserved() {
            assertThat(PaygateSecurityFilter.normalizePath("/api/%2e%2e/v1%2Fbypass"))
                    .isEqualTo("/v1%2Fbypass");
        }
    }
}
