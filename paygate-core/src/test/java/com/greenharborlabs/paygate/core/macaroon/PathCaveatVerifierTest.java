package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("constructor rejects maxValuesPerCaveat < 1")
  void constructorRejectsInvalidMaxValues() {
    assertThatThrownBy(() -> new PathCaveatVerifier(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxValuesPerCaveat must be >= 1");
    assertThatThrownBy(() -> new PathCaveatVerifier(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxValuesPerCaveat must be >= 1");
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

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2: path=/products/** rejects /orders/1")
    void doubleStarRejectsUnrelatedPath() {
      Caveat caveat = new Caveat("path", "/products/**");
      L402VerificationContext context = contextWithPath("/orders/1");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("9: path=/** matches root /")
    void doubleStarMatchesRoot() {
      Caveat caveat = new Caveat("path", "/**");
      L402VerificationContext context = contextWithPath("/");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("10: path=/** matches /any/deep/path")
    void doubleStarMatchesDeepPath() {
      Caveat caveat = new Caveat("path", "/**");
      L402VerificationContext context = contextWithPath("/any/deep/path");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("13: path=/products/** matches /products (zero additional segments)")
    void doubleStarMatchesZeroAdditionalSegments() {
      Caveat caveat = new Caveat("path", "/products/**");
      L402VerificationContext context = contextWithPath("/products");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
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
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("15: path=/api/*/details matches /api/orders/details (single-segment middle)")
    void singleStarInMiddleMatches() {
      Caveat caveat = new Caveat("path", "/api/*/details");
      L402VerificationContext context = contextWithPath("/api/orders/details");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("16: path=/api/*/details rejects /api/orders/123/details (multi-segment middle)")
    void singleStarInMiddleRejectsMultiSegment() {
      Caveat caveat = new Caveat("path", "/api/*/details");
      L402VerificationContext context = contextWithPath("/api/orders/123/details");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("17: path=/api/*/details rejects /api/details (star requires one segment)")
    void singleStarRequiresOneSegment() {
      Caveat caveat = new Caveat("path", "/api/*/details");
      L402VerificationContext context = contextWithPath("/api/details");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
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

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
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

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("6: path=/products/* matches /products/123?format=json (query string excluded)")
    void queryStringExcluded() {
      Caveat caveat = new Caveat("path", "/products/*");
      L402VerificationContext context = contextWithPath("/products/123?format=json");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("7: path=/products/* matches /products/my%20item (percent-encoded decoded)")
    void percentEncodedDecoded() {
      Caveat caveat = new Caveat("path", "/products/*");
      L402VerificationContext context = contextWithPath("/products/my%20item");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
        "8: path=/products/* rejects request with %2F in segment (encoded slash NOT decoded)")
    void encodedSlashNotDecoded() {
      Caveat caveat = new Caveat("path", "/products/*");
      L402VerificationContext context = contextWithPath("/products/foo%2Fbar");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName(
        "8b: path=/products/** rejects request with %2F in segment (encoded slash rejected for double-star too)")
    void encodedSlashRejectedWithDoubleStar() {
      Caveat caveat = new Caveat("path", "/products/**");
      L402VerificationContext context = contextWithPath("/products/foo%2Fbar");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName(
        "14: /api/../products/123 with path=/products/** rejects (dot-dot traversal rejected)")
    void dotSegmentRejected() {
      Caveat caveat = new Caveat("path", "/products/**");
      L402VerificationContext context = contextWithPath("/api/../products/123");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName(
        "21: /api//products/123 with path=/api/products/** matches (consecutive slashes collapsed)")
    void consecutiveSlashesCollapsed() {
      Caveat caveat = new Caveat("path", "/api/products/**");
      L402VerificationContext context = contextWithPath("/api//products/123");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
        "regression: double-encoded traversal (%252e%252e) rejected (contains %25 double-encoding)")
    void doubleEncodedTraversalRejected() {
      // %252e contains %25 which is a double-encoding indicator — rejected
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/public/%252e%252e/api/data");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
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

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("12: path=/ rejects /products (root only does not match sub-paths)")
    void rootDoesNotMatchSubPaths() {
      Caveat caveat = new Caveat("path", "/");
      L402VerificationContext context = contextWithPath("/products");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }
  }

  @Nested
  @DisplayName("gateway rewrite")
  class GatewayRewrite {

    // Gateway rewrite behavior (stripping a prefix like /gateway) is tested at the filter level.
    // This verifier test only confirms matching against the application-relative path provided in
    // context.
    @Test
    @DisplayName("18: application-relative /api/products/123 matches path=/api/products/**")
    void applicationRelativePathMatches() {
      Caveat caveat = new Caveat("path", "/api/products/**");
      L402VerificationContext context = contextWithPath("/api/products/123");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
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
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName(
        "20: path=/api/product-* rejects /api/product-123 (product-* is literal, not wildcard)")
    void infixWildcardTreatedAsLiteral() {
      Caveat caveat = new Caveat("path", "/api/product-*");
      L402VerificationContext context = contextWithPath("/api/product-123");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }
  }

  // ---------------------------------------------------------------
  // FR-031: Malformed caveat path normalization
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("FR-031: malformed caveat path normalization")
  class MalformedCaveatPathNormalization {

    @Test
    @DisplayName("FR-031: pattern without leading slash is normalized")
    void patternWithoutLeadingSlashIsNormalized() {
      Caveat caveat = new Caveat("path", "products/**");
      L402VerificationContext context = contextWithPath("/products/123");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-031: pattern without leading slash with specific path")
    void patternWithoutLeadingSlashSpecificPath() {
      Caveat caveat = new Caveat("path", "products/123");
      L402VerificationContext context = contextWithPath("/products/123");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-031: pattern without leading slash no match")
    void patternWithoutLeadingSlashNoMatch() {
      Caveat caveat = new Caveat("path", "products/123");
      L402VerificationContext context = contextWithPath("/api/other");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }
  }

  // ---------------------------------------------------------------
  // W1-01: Encoded backslash and double-encoding bypass prevention
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("W1-01: encoded backslash and double-encoding rejection")
  class EncodedBackslashAndDoubleEncoding {

    @Test
    @DisplayName("rejects %5C (encoded backslash uppercase)")
    void rejectsEncodedBackslashUppercase() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/%5Csecret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects %5c (encoded backslash lowercase)")
    void rejectsEncodedBackslashLowercase() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/%5csecret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects %252F (double-encoded forward slash)")
    void rejectsDoubleEncodedForwardSlash() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/%252Fsecret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects %252f (double-encoded forward slash lowercase)")
    void rejectsDoubleEncodedForwardSlashLowercase() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/%252fsecret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects %255C (double-encoded backslash)")
    void rejectsDoubleEncodedBackslash() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/%255Csecret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("allows normal path (no regression)")
    void allowsNormalPath() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/normal");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allows percent-encoded space (no regression)")
    void allowsPercentEncodedSpace() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/my%20item");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }
  }

  // ---------------------------------------------------------------
  // W1-02: Dot-dot path traversal rejection
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("W1-02: dot-dot path traversal rejection")
  class DotDotTraversalRejection {

    @Test
    @DisplayName("rejects /api/../secret")
    void rejectsDotDotInMiddle() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/../secret");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects /../../../api/data")
    void rejectsDotDotAtStart() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/../../../api/data");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects /api/v1/..")
    void rejectsDotDotAtEnd() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/v1/..");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("allows path with dots that are not dot-dot segments")
    void allowsDotsInSegmentNames() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/v1.2/file.json");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allows single dot segment (current directory)")
    void allowsSingleDotSegment() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("/api/./data");

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects bare .. (dot-dot with no slashes)")
    void rejectsBareDotDotNoSlashes() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("..");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("rejects bare ../path (dot-dot without leading slash)")
    void rejectsBareDotDot() {
      Caveat caveat = new Caveat("path", "/api/**");
      L402VerificationContext context = contextWithPath("../api/data");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
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
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("missing request.path in context rejects (fail-closed)")
    void missingRequestPathRejects() {
      Caveat caveat = new Caveat("path", "/products/**");
      L402VerificationContext context =
          L402VerificationContext.builder().requestMetadata(Map.of()).build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("exceeding maxValuesPerCaveat rejects")
    void exceedingMaxValuesRejects() {
      PathCaveatVerifier restrictedVerifier = new PathCaveatVerifier(2);
      Caveat caveat = new Caveat("path", "/a/**,/b/**,/c/**");
      L402VerificationContext context = contextWithPath("/a/1");

      assertThatThrownBy(() -> restrictedVerifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }

    @Test
    @DisplayName("semantically empty value after trim (path= , , ) rejects")
    void semanticallyEmptyAfterTrimRejects() {
      Caveat caveat = new Caveat("path", " , , ");
      L402VerificationContext context = contextWithPath("/products/123");

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .extracting(e -> ((MacaroonVerificationException) e).getReason())
          .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    }
  }

  // ---------------------------------------------------------------
  // Monotonic restriction (US4)
  // ---------------------------------------------------------------

  @Nested
  @DisplayName("monotonic restriction (US4)")
  class MonotonicRestriction {

    @Test
    @DisplayName("US4-1: path=/api/** → path=/api/products/** is narrowing (accepted)")
    void narrowingPathIsMoreRestrictive() {
      Caveat previous = new Caveat("path", "/api/**");
      Caveat current = new Caveat("path", "/api/products/**");

      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
    }

    @Test
    @DisplayName("US4-2: path=/api/products/** → path=/api/** is broadening (rejected)")
    void broadeningPathIsNotMoreRestrictive() {
      Caveat previous = new Caveat("path", "/api/products/**");
      Caveat current = new Caveat("path", "/api/**");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("identical path patterns are accepted (not broadening)")
    void identicalPathPatternsAccepted() {
      Caveat previous = new Caveat("path", "/api/products/**");
      Caveat current = new Caveat("path", "/api/products/**");

      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
    }

    @Test
    @DisplayName("multi-pattern narrowing: subset of patterns is accepted")
    void multiPatternSubsetAccepted() {
      Caveat previous = new Caveat("path", "/api/**,/admin/**");
      Caveat current = new Caveat("path", "/api/**");

      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
    }

    @Test
    @DisplayName("multi-pattern broadening: adding a new pattern is rejected")
    void multiPatternSupersetRejected() {
      Caveat previous = new Caveat("path", "/api/**");
      Caveat current = new Caveat("path", "/api/**,/admin/**");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("rejects oversized previous caveat in isMoreRestrictive")
    void rejectsOversizedPreviousCaveat() {
      String oversized =
          IntStream.rangeClosed(1, 51)
              .mapToObj(i -> "/api/path" + i)
              .collect(Collectors.joining(","));
      Caveat previous = new Caveat("path", oversized);
      Caveat current = new Caveat("path", "/api/path1");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("rejects oversized current caveat in isMoreRestrictive")
    void rejectsOversizedCurrentCaveat() {
      String oversized =
          IntStream.rangeClosed(1, 51)
              .mapToObj(i -> "/api/path" + i)
              .collect(Collectors.joining(","));
      Caveat previous = new Caveat("path", "/api/path1");
      Caveat current = new Caveat("path", oversized);

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("accepts within-bounds caveats in isMoreRestrictive")
    void acceptsWithinBoundsCaveats() {
      String fivePatterns =
          IntStream.rangeClosed(1, 5)
              .mapToObj(i -> "/api/path" + i)
              .collect(Collectors.joining(","));
      String threePatterns =
          IntStream.rangeClosed(1, 3)
              .mapToObj(i -> "/api/path" + i)
              .collect(Collectors.joining(","));
      Caveat previous = new Caveat("path", fivePatterns);
      Caveat current = new Caveat("path", threePatterns);

      // current is a subset of previous — should be true
      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
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
