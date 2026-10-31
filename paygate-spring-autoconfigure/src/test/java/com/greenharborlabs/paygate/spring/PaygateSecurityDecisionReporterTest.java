package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionProtocol;
import com.greenharborlabs.paygate.api.SecurityDecisionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaygateSecurityDecisionReporterTest {

  @Test
  void recordsExactlyOneFixedDimensionCounterPerDecision() {
    var registry = new SimpleMeterRegistry();
    var reporter = new PaygateSecurityDecisionReporter(registry);
    var decision =
        new SecurityDecision(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionProtocol.L402,
            "POST",
            "/api/analyze");

    reporter.onDecision(decision);

    assertThat(
            registry
                .find("paygate.security.decisions")
                .tags(
                    "reason",
                    "insufficient_price",
                    "protocol",
                    "l402",
                    "method",
                    "POST",
                    "endpoint",
                    "/api/analyze")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void replacesControlCharactersAndBoundsMethodBeforeRecordingMetricTags() {
    var registry = new SimpleMeterRegistry();
    var reporter = new PaygateSecurityDecisionReporter(registry);

    reporter.onDecision(
        new SecurityDecision(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionProtocol.L402,
            "POST\r\nforged-entry",
            "/api/analyze\u0000"));

    assertThat(
            registry
                .find("paygate.security.decisions")
                .tags("method", "_other", "endpoint", "/api/analyze_")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void randomMethodTokensProduceAtMostNineDistinctMethodTags() {
    var registry = new SimpleMeterRegistry();
    var reporter = new PaygateSecurityDecisionReporter(registry);
    var random = new Random(20261018L);
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+-.^_`|~";

    for (int i = 0; i < 1000; i++) {
      var method = new StringBuilder();
      int length = 1 + random.nextInt(i % 100 == 0 ? 4000 : 12);
      for (int j = 0; j < length; j++) {
        method.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      reporter.onDecision(decision(method.toString()));
    }

    assertThat(methodTags(registry))
        .hasSizeLessThanOrEqualTo(9)
        .isSubsetOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "_other");
    assertThat(
            registry.find("paygate.security.decisions").counters().stream()
                .mapToDouble(Counter::count)
                .sum())
        .isEqualTo(1000.0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"})
  void standardMethodsKeepTheirOwnTag(String method) {
    var registry = new SimpleMeterRegistry();

    new PaygateSecurityDecisionReporter(registry).onDecision(decision(method));

    assertThat(methodTags(registry)).containsExactly(method);
  }

  @ParameterizedTest
  @ValueSource(strings = {"get", "Post", "CONNECT", "PROPFIND", " GET", "GET "})
  void nonStandardOrDifferentlyCasedMethodsCollapseToOther(String method) {
    var registry = new SimpleMeterRegistry();

    new PaygateSecurityDecisionReporter(registry).onDecision(decision(method));

    assertThat(methodTags(registry)).containsExactly("_other");
  }

  @Test
  void unknownMethodSentinelIsPreserved() {
    var registry = new SimpleMeterRegistry();

    new PaygateSecurityDecisionReporter(registry).onDecision(decision("_unknown"));

    assertThat(methodTags(registry)).containsExactly("_unknown");
  }

  @Test
  void noMetricSinkStillDoesNotAffectTheDecisionPath() {
    new PaygateSecurityDecisionReporter(null)
        .onDecision(
            new SecurityDecision(
                SecurityDecisionReason.COMPRESSED_BODY_REJECTED,
                SecurityDecisionProtocol.L402,
                "POST",
                "/api/analyze"));
  }

  @Test
  void capsEndpointCardinalityWithoutChangingFixedDecisionDimensions() {
    var registry = new SimpleMeterRegistry();
    registry.config().meterFilter(new PaygateMeterFilter(1, "_other"));
    var reporter = new PaygateSecurityDecisionReporter(registry);

    reporter.onDecision(
        new SecurityDecision(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionProtocol.L402,
            "POST",
            "/api/one"));
    reporter.onDecision(
        new SecurityDecision(
            SecurityDecisionReason.ROUTE_STABLE_PRICE_INCREASE,
            SecurityDecisionProtocol.L402,
            "POST",
            "/api/two"));

    assertThat(registry.find("paygate.security.decisions").tag("endpoint", "_other").counters())
        .hasSize(1);
    assertThat(registry.getMeters())
        .allSatisfy(
            meter -> assertThat(meter.getId().getName()).isEqualTo("paygate.security.decisions"));
  }

  @Test
  void throwingMetricSinkCannotEscapeTheReporter() {
    var registry = new SimpleMeterRegistry();
    registry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public io.micrometer.core.instrument.Meter.Id map(
                  io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("sentinel credential body header");
              }
            });

    new PaygateSecurityDecisionReporter(registry)
        .onDecision(
            new SecurityDecision(
                SecurityDecisionReason.MISSING_PRICE_EVIDENCE,
                SecurityDecisionProtocol.L402,
                "GET",
                "/api/widgets/{id}"));
  }

  private static SecurityDecision decision(String method) {
    return new SecurityDecision(
        SecurityDecisionReason.COMPRESSED_BODY_REJECTED,
        SecurityDecisionProtocol.UNKNOWN,
        method,
        "/api/dyn");
  }

  private static Set<String> methodTags(SimpleMeterRegistry registry) {
    return registry.find("paygate.security.decisions").counters().stream()
        .map(counter -> counter.getId().getTag("method"))
        .collect(Collectors.toUnmodifiableSet());
  }
}
