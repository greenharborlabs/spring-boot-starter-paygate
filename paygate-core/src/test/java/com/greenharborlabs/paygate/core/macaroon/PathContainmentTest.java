package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PathGlobMatcher.isContainedIn")
class PathContainmentTest {

  @Nested
  @DisplayName("double-star containment")
  class DoubleStarContainment {

    @Test
    @DisplayName("/api/** contains /api/products/** — narrower glob is contained")
    void doubleStarContainsNarrowerDoublestar() {
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/api/products/**")).isTrue();
    }

    @Test
    @DisplayName("/api/products/** does NOT contain /api/** — broader glob is not contained")
    void narrowerDoublestarDoesNotContainBroader() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products/**", "/api/**")).isFalse();
    }

    @Test
    @DisplayName("/api/** contains /api/products — literal narrower than glob")
    void doubleStarContainsLiteral() {
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/api/products")).isTrue();
    }

    @Test
    @DisplayName("/** contains everything")
    void rootDoubleStarContainsEverything() {
      assertThat(PathGlobMatcher.isContainedIn("/**", "/api/products/**")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/**", "/api/products")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/**", "/")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/**", "/**")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/**", "/api/*")).isTrue();
    }

    @Test
    @DisplayName("existing ends with ** but proposed doesn't match prefix — false")
    void doubleStarPrefixMismatch() {
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/orders/products")).isFalse();
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/orders/**")).isFalse();
    }
  }

  @Nested
  @DisplayName("single-star containment")
  class SingleStarContainment {

    @Test
    @DisplayName("/api/* contains /api/products — literal narrower than wildcard")
    void singleStarContainsLiteral() {
      assertThat(PathGlobMatcher.isContainedIn("/api/*", "/api/products")).isTrue();
    }

    @Test
    @DisplayName("/api/products does NOT contain /api/* — wildcard broader than literal")
    void literalDoesNotContainSingleStar() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products", "/api/*")).isFalse();
    }

    @Test
    @DisplayName("/api/*/details contains /api/orders/details — * in middle matches literal")
    void singleStarInMiddleContainsLiteral() {
      assertThat(PathGlobMatcher.isContainedIn("/api/*/details", "/api/orders/details")).isTrue();
    }
  }

  @Nested
  @DisplayName("identical patterns")
  class IdenticalPatterns {

    @Test
    @DisplayName("identical literal patterns are contained")
    void identicalLiterals() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products", "/api/products")).isTrue();
    }

    @Test
    @DisplayName("identical glob patterns are contained")
    void identicalGlobs() {
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/api/**")).isTrue();
    }

    @Test
    @DisplayName("identical single-star patterns are contained")
    void identicalSingleStar() {
      assertThat(PathGlobMatcher.isContainedIn("/api/*", "/api/*")).isTrue();
    }
  }

  @Nested
  @DisplayName("disjoint patterns")
  class DisjointPatterns {

    @Test
    @DisplayName("completely disjoint patterns are not contained")
    void disjointPatterns() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products", "/orders/items")).isFalse();
    }

    @Test
    @DisplayName("partially overlapping but not contained")
    void partialOverlap() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products", "/api/orders")).isFalse();
    }
  }

  @Nested
  @DisplayName("segment count mismatches")
  class SegmentCountMismatches {

    @Test
    @DisplayName("proposed has fewer segments than existing with no ** — false")
    void proposedFewerSegmentsNoDoublestar() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products/details", "/api/products")).isFalse();
    }

    @Test
    @DisplayName("proposed has more segments than existing with no ** — false")
    void proposedMoreSegmentsNoDoublestar() {
      assertThat(PathGlobMatcher.isContainedIn("/api/products", "/api/products/details")).isFalse();
    }
  }

  @Nested
  @DisplayName("conservative rejection for complex cases")
  class ConservativeRejection {

    @Test
    @DisplayName("ambiguous wildcard patterns conservatively rejected")
    void ambiguousWildcardPatternsRejected() {
      // /api/*/items does NOT contain /api/*/details — different fixed segments
      assertThat(PathGlobMatcher.isContainedIn("/api/*/items", "/api/*/details")).isFalse();
    }

    @Test
    @DisplayName("single-star proposed vs literal existing — conservatively rejected")
    void singleStarProposedVsLiteralExisting() {
      // /api/orders does NOT contain /api/* — wildcard is broader
      assertThat(PathGlobMatcher.isContainedIn("/api/orders", "/api/*")).isFalse();
    }
  }

  @Nested
  @DisplayName("comma-separated containment")
  class CommaSeparatedContainment {

    @Test
    @DisplayName("each proposed pattern must be contained in at least one existing pattern")
    void eachProposedContainedInAtLeastOneExisting() {
      // This tests the conceptual behavior: if the verifier splits on commas,
      // each proposed value must be contained in at least one existing value.
      // Individual isContainedIn calls validate each pair.
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/api/products")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/api/orders")).isTrue();
      assertThat(PathGlobMatcher.isContainedIn("/api/**", "/billing/invoices")).isFalse();
    }

    @Test
    @DisplayName("proposed literal contained in one of multiple existing patterns")
    void proposedContainedInOneOfMultiple() {
      // Simulating checking against multiple existing patterns
      boolean contained =
          PathGlobMatcher.isContainedIn("/api/**", "/billing/invoices")
              || PathGlobMatcher.isContainedIn("/billing/**", "/billing/invoices");
      assertThat(contained).isTrue();
    }

    @Test
    @DisplayName("proposed pattern not contained in any existing pattern — rejected")
    void proposedNotContainedInAny() {
      boolean contained =
          PathGlobMatcher.isContainedIn("/api/**", "/admin/settings")
              || PathGlobMatcher.isContainedIn("/billing/**", "/admin/settings");
      assertThat(contained).isFalse();
    }
  }
}
