package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionProtocol;
import com.greenharborlabs.paygate.api.SecurityDecisionReason;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
}
