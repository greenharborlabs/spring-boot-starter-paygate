package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
