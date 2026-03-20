package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link L402SecurityFilter#normalizePath(String)} and
 * {@link L402PathUtils#percentDecodePath(String)}.
 * No Spring context needed.
 */
@DisplayName("L402SecurityFilter path normalization")
class L402SecurityFilterPathNormalizationTest {

    @Nested
    @DisplayName("normalizePath")
    class NormalizePath {

        @Test
        @DisplayName("null returns /")
        void nullReturnsRoot() {
            assertThat(L402SecurityFilter.normalizePath(null)).isEqualTo("/");
        }

        @Test
        @DisplayName("empty string returns /")
        void emptyReturnsRoot() {
            assertThat(L402SecurityFilter.normalizePath("")).isEqualTo("/");
        }

        @Test
        @DisplayName("root path returns /")
        void rootReturnsRoot() {
            assertThat(L402SecurityFilter.normalizePath("/")).isEqualTo("/");
        }

        @Test
        @DisplayName("simple path passes through")
        void simplePathPassesThrough() {
            assertThat(L402SecurityFilter.normalizePath("/api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("dot-dot segments are collapsed")
        void dotDotCollapsed() {
            assertThat(L402SecurityFilter.normalizePath("/api/public/../protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("single dot segments are removed")
        void singleDotRemoved() {
            assertThat(L402SecurityFilter.normalizePath("/api/./protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("percent-encoded dot-dot (%2e%2e) is decoded and collapsed")
        void percentEncodedDotDot() {
            assertThat(L402SecurityFilter.normalizePath("/api/public/%2e%2e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("uppercase percent-encoded dot-dot (%2E%2E) is decoded and collapsed")
        void uppercasePercentEncodedDotDot() {
            assertThat(L402SecurityFilter.normalizePath("/api/public/%2E%2E/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("double-encoded dot-dot (%252e%252e) is decoded and collapsed")
        void doubleEncodedDotDot() {
            assertThat(L402SecurityFilter.normalizePath("/api/public/%252e%252e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("percent-encoded single dot (%2e) is decoded and removed")
        void percentEncodedSingleDot() {
            assertThat(L402SecurityFilter.normalizePath("/api/%2e/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("plus sign is treated as literal, not as space")
        void plusIsLiteral() {
            assertThat(L402SecurityFilter.normalizePath("/api/c++/resource")).isEqualTo("/api/c++/resource");
        }

        @Test
        @DisplayName("traversal above root is clamped")
        void traversalAboveRootClamped() {
            assertThat(L402SecurityFilter.normalizePath("/../../../api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("empty segments from multiple slashes are collapsed")
        void emptySegmentsCollapsed() {
            assertThat(L402SecurityFilter.normalizePath("/api///protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("multiple dot-dot segments resolve correctly")
        void multipleDotDots() {
            assertThat(L402SecurityFilter.normalizePath("/api/foo/bar/../../protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("all dot-dot resolves to root")
        void allDotDotsToRoot() {
            assertThat(L402SecurityFilter.normalizePath("/a/b/../../..")).isEqualTo("/");
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
            assertThat(L402SecurityFilter.normalizePath(input)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("percentDecodePath")
    class PercentDecodePath {

        @Test
        @DisplayName("null returns null")
        void nullReturnsNull() {
            assertThat(L402PathUtils.percentDecodePath(null)).isNull();
        }

        @Test
        @DisplayName("empty returns empty")
        void emptyReturnsEmpty() {
            assertThat(L402PathUtils.percentDecodePath("")).isEmpty();
        }

        @Test
        @DisplayName("no percent sequences passes through")
        void noPercentPassesThrough() {
            assertThat(L402PathUtils.percentDecodePath("/api/protected")).isEqualTo("/api/protected");
        }

        @Test
        @DisplayName("decodes %2e to dot")
        void decodesPercentToDot() {
            assertThat(L402PathUtils.percentDecodePath("%2e")).isEqualTo(".");
        }

        @Test
        @DisplayName("decodes uppercase %2E to dot")
        void decodesUppercasePercentToDot() {
            assertThat(L402PathUtils.percentDecodePath("%2E")).isEqualTo(".");
        }

        @Test
        @DisplayName("decodes %25 to percent")
        void decodesPercentToPercent() {
            assertThat(L402PathUtils.percentDecodePath("%25")).isEqualTo("%");
        }

        @Test
        @DisplayName("plus sign is not treated as space")
        void plusIsNotSpace() {
            assertThat(L402PathUtils.percentDecodePath("a+b")).isEqualTo("a+b");
        }

        @Test
        @DisplayName("incomplete percent at end passes through")
        void incompletePercentAtEnd() {
            assertThat(L402PathUtils.percentDecodePath("abc%")).isEqualTo("abc%");
        }

        @Test
        @DisplayName("incomplete percent with one hex char passes through")
        void incompletePercentOneHex() {
            assertThat(L402PathUtils.percentDecodePath("abc%2")).isEqualTo("abc%2");
        }

        @Test
        @DisplayName("invalid hex digits after percent passes through")
        void invalidHexAfterPercent() {
            assertThat(L402PathUtils.percentDecodePath("%ZZ")).isEqualTo("%ZZ");
        }

        @Test
        @DisplayName("decodes multi-byte UTF-8 sequence")
        void decodesMultiByteUtf8() {
            // U+00E9 (e-acute) = UTF-8 bytes C3 A9
            assertThat(L402PathUtils.percentDecodePath("%C3%A9")).isEqualTo("\u00e9");
        }
    }
}
