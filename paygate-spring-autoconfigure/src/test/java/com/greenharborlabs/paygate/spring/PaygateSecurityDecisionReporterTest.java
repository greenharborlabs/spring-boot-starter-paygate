package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionProtocol;
import com.greenharborlabs.paygate.api.SecurityDecisionReason;
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
}
