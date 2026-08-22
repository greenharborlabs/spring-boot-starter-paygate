package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteOverlapAnalyzerTest {

  @Test
  void identifiesHeadAsAnIntersectionWithGet() {
    var finding = RouteOverlapAnalyzer.analyze("HEAD", "/orders/{id}", "GET", "/orders/*");

    assertThat(finding)
        .isNotNull()
        .extracting(
            RouteOverlapAnalyzer.Finding::method, RouteOverlapAnalyzer.Finding::classification)
        .containsExactly("HEAD", RouteOverlapAnalyzer.Classification.POSSIBLE_OVERLAP);
  }

  @Test
  void rejectsDisjointLiteralPaths() {
    assertThat(RouteOverlapAnalyzer.analyze("GET", "/paid/orders", "GET", "/free/orders")).isNull();
  }

  @Test
  void normalizesTrailingSeparators() {
    assertThat(RouteOverlapAnalyzer.analyze("GET", "/orders/", "GET", "/orders"))
        .extracting(RouteOverlapAnalyzer.Finding::classification)
        .isEqualTo(RouteOverlapAnalyzer.Classification.EXACT);
  }

  @ParameterizedTest(name = "{0} intersects {1}")
  @CsvSource({
    "/orders/{id}, /orders/42",
    "/orders/*, /orders/42",
    "/orders/**, /orders/42/items",
    "/orders/{*path}, /orders/42/items",
    "/orders/{id:[0-9]+}, /orders/42"
  })
  void classifiesVariableWildcardCatchAllAndRegexPatternsConservatively(
      String paid, String unprotected) {
    assertThat(RouteOverlapAnalyzer.analyze("GET", paid, "GET", unprotected))
        .isNotNull()
        .extracting(RouteOverlapAnalyzer.Finding::classification)
        .isEqualTo(RouteOverlapAnalyzer.Classification.POSSIBLE_OVERLAP);
  }

  @Test
  void keepsMethodsWithoutAnIntersectionSeparate() {
    assertThat(RouteOverlapAnalyzer.analyze("POST", "/orders/{id}", "GET", "/orders/*")).isNull();
  }
}
