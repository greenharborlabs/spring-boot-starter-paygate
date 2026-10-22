package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

class PaidRouteSamplePathTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "/                      | /",
        "/paid                  | /paid",
        "/api/items/{id}        | /api/items/x",
        "/api/{a}/{b}/detail    | /api/x/x/detail",
        "/files/{*rest}         | /files/x",
        "/api/**                | /api/x",
        "/api/*/report          | /api/x/report",
        "/api/report-*.json     | /api/report-x.json",
        "/api/item?             | /api/itemx",
        "/api/v{version}/items  | /api/vx/items",
        "/api/{id:\\d+}         | /api/0",
        "/api/{id:[a-f]{2}}     | /api/x",
        "/api/{code:[A-Z]+}     | /api/X",
        "/api/{slug:[a-z]+\\}?} | /api/x",
        "/trailing/             | /trailing/",
        "relative/{id}          | /relative/x"
      })
  @DisplayName("derives an app-relative sample request path from a route pattern")
  void derivesSamplePath(String pattern, String expected) {
    assertThat(PaidRouteSamplePath.of(pattern)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "/paid",
        "/api/items/{id}",
        "/files/{*rest}",
        "/api/**",
        "/api/*/report",
        "/api/report-*.json",
        "/api/item?",
        "/api/{id:\\d+}",
        "/api/{code:[A-Z]+}",
        "/with space/{id}",
        "/café/{id}"
      })
  @DisplayName("the derived sample path is matched by the route pattern itself")
  void samplePathMatchesItsPattern(String pattern) {
    var parsed = PathPatternParser.defaultInstance.parse(pattern);
    var sample = PathContainer.parsePath(PaidRouteSamplePath.of(pattern));

    assertThat(parsed.matches(sample)).isTrue();
  }

  @Test
  @DisplayName("literal segments are percent-encoded so the sample is a valid request URI")
  void encodesLiteralSegments() {
    assertThat(PaidRouteSamplePath.of("/with space/{id}")).isEqualTo("/with%20space/x");
  }

  @Test
  @DisplayName("an unclosed variable is rejected")
  void rejectsUnclosedVariable() {
    assertThatThrownBy(() -> PaidRouteSamplePath.of("/api/{id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unclosed variable");
  }
}
