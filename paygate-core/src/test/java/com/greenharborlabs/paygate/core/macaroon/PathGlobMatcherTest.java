package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PathGlobMatcher")
class PathGlobMatcherTest {

    @Nested
    @DisplayName("normalizePath")
    class NormalizePath {

        @Test
        @DisplayName("prepends leading / if missing")
        void prependsLeadingSlashIfMissing() {
            assertThat(PathGlobMatcher.normalizePath("api/products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("strips trailing / except for root")
        void stripsTrailingSlashExceptRoot() {
            assertThat(PathGlobMatcher.normalizePath("/api/products/"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("preserves root /")
        void preservesRoot() {
            assertThat(PathGlobMatcher.normalizePath("/"))
                    .isEqualTo("/");
        }

        @Test
        @DisplayName("collapses consecutive slashes")
        void collapsesConsecutiveSlashes() {
            assertThat(PathGlobMatcher.normalizePath("/api//products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("resolves parent dot-segments (..)")
        void resolvesParentDotSegments() {
            assertThat(PathGlobMatcher.normalizePath("/api/../products/123"))
                    .isEqualTo("/products/123");
        }

        @Test
        @DisplayName("resolves single dot-segments (.)")
        void resolvesSingleDotSegments() {
            assertThat(PathGlobMatcher.normalizePath("/api/./products"))
                    .isEqualTo("/api/products");
        }

        @Test
        @DisplayName("strips query string")
        void stripsQueryString() {
            assertThat(PathGlobMatcher.normalizePath("/products/123?format=json"))
                    .isEqualTo("/products/123");
        }

        @Test
        @DisplayName("decodes percent-encoded unreserved chars like %7E (tilde)")
        void decodesPercentEncodedUnreservedChars() {
            assertThat(PathGlobMatcher.normalizePath("/products/my%7Eitem"))
                    .isEqualTo("/products/my~item");
        }

        @Test
        @DisplayName("decodes percent-encoded data characters like %20 (space)")
        void decodesPercentEncodedSpace() {
            assertThat(PathGlobMatcher.normalizePath("/products/my%20item"))
                    .isEqualTo("/products/my item");
        }

        @Test
        @DisplayName("does NOT decode percent-encoded reserved delimiters like %2F")
        void doesNotDecodeReservedDelimiters() {
            assertThat(PathGlobMatcher.normalizePath("/products/my%2Fitem"))
                    .isEqualTo("/products/my%2Fitem");
        }

        @Test
        @DisplayName("single-pass decode — no double-decode of %25")
        void singlePassDecodeNoDoubleDecoding() {
            assertThat(PathGlobMatcher.normalizePath("/products/%2520item"))
                    .isEqualTo("/products/%20item");
        }
    }

    @Nested
    @DisplayName("matches")
    class Matches {

        @Test
        @DisplayName("exact match returns true")
        void exactMatch() {
            assertThat(PathGlobMatcher.matches("/products", "/products"))
                    .isTrue();
        }

        @Test
        @DisplayName("exact mismatch returns false")
        void exactMismatch() {
            assertThat(PathGlobMatcher.matches("/products", "/orders"))
                    .isFalse();
        }

        @Test
        @DisplayName("* matches exactly one segment")
        void singleWildcardMatchesOneSegment() {
            assertThat(PathGlobMatcher.matches("/products/*", "/products/123"))
                    .isTrue();
        }

        @Test
        @DisplayName("* does NOT match zero segments")
        void singleWildcardDoesNotMatchZeroSegments() {
            assertThat(PathGlobMatcher.matches("/products/*", "/products"))
                    .isFalse();
        }

        @Test
        @DisplayName("* does NOT match multiple segments")
        void singleWildcardDoesNotMatchMultipleSegments() {
            assertThat(PathGlobMatcher.matches("/products/*", "/products/123/reviews"))
                    .isFalse();
        }

        @Test
        @DisplayName("* in middle position matches one segment")
        void singleWildcardInMiddleMatchesOneSegment() {
            assertThat(PathGlobMatcher.matches("/api/*/details", "/api/orders/details"))
                    .isTrue();
        }

        @Test
        @DisplayName("* in middle, too many segments returns false")
        void singleWildcardInMiddleTooManySegments() {
            assertThat(PathGlobMatcher.matches("/api/*/details", "/api/orders/123/details"))
                    .isFalse();
        }

        @Test
        @DisplayName("* in middle, too few segments returns false")
        void singleWildcardInMiddleTooFewSegments() {
            assertThat(PathGlobMatcher.matches("/api/*/details", "/api/details"))
                    .isFalse();
        }

        @Test
        @DisplayName("** terminal matches zero remaining segments")
        void doubleWildcardTerminalMatchesZeroSegments() {
            assertThat(PathGlobMatcher.matches("/products/**", "/products"))
                    .isTrue();
        }

        @Test
        @DisplayName("** terminal matches one remaining segment")
        void doubleWildcardTerminalMatchesOneSegment() {
            assertThat(PathGlobMatcher.matches("/products/**", "/products/123"))
                    .isTrue();
        }

        @Test
        @DisplayName("** terminal matches deep path")
        void doubleWildcardTerminalMatchesDeepPath() {
            assertThat(PathGlobMatcher.matches("/products/**", "/products/123/reviews/456"))
                    .isTrue();
        }

        @Test
        @DisplayName("/** matches root")
        void doubleWildcardRootMatchesRoot() {
            assertThat(PathGlobMatcher.matches("/**", "/"))
                    .isTrue();
        }

        @Test
        @DisplayName("/** matches all paths")
        void doubleWildcardRootMatchesAllPaths() {
            assertThat(PathGlobMatcher.matches("/**", "/any/deep/path"))
                    .isTrue();
        }

        @Test
        @DisplayName("root-only pattern matches root")
        void rootOnlyPatternMatchesRoot() {
            assertThat(PathGlobMatcher.matches("/", "/"))
                    .isTrue();
        }

        @Test
        @DisplayName("root-only pattern does not match sub-paths")
        void rootOnlyPatternDoesNotMatchSubPaths() {
            assertThat(PathGlobMatcher.matches("/", "/products"))
                    .isFalse();
        }

        @Test
        @DisplayName("* within segment name is treated as literal")
        void wildcardWithinSegmentNameTreatedAsLiteral() {
            // "product-*" is NOT a wildcard — it's a literal segment
            assertThat(PathGlobMatcher.matches("/api/product-*", "/api/product-123"))
                    .isFalse();
        }

        @Test
        @DisplayName("* within segment name matches literal string with asterisk")
        void wildcardWithinSegmentNameMatchesLiteralAsterisk() {
            // "product-*" as a literal must match the exact string "product-*"
            assertThat(PathGlobMatcher.matches("/api/product-*", "/api/product-*"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("validatePattern")
    class ValidatePattern {

        @Test
        @DisplayName("accepts terminal ** pattern")
        void acceptsTerminalDoubleWildcard() {
            assertThatCode(() -> PathGlobMatcher.validatePattern("/api/products/**"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts single * pattern")
        void acceptsSingleWildcard() {
            assertThatCode(() -> PathGlobMatcher.validatePattern("/api/*"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts root /** pattern")
        void acceptsRootDoubleWildcard() {
            assertThatCode(() -> PathGlobMatcher.validatePattern("/**"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects non-terminal ** segment")
        void rejectsNonTerminalDoubleWildcard() {
            assertThatThrownBy(() -> PathGlobMatcher.validatePattern("/api/**/details"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
