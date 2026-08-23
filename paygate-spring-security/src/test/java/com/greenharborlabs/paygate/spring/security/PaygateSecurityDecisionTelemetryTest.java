package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionObserver;
import com.greenharborlabs.paygate.api.SecurityDecisionProtocol;
import com.greenharborlabs.paygate.api.SecurityDecisionReason;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PaygateSecurityDecisionTelemetryTest {

  @Test
  void deliversOneSanitizedDecisionForEveryFixedPriceReason() {
    var decisions = new ArrayList<SecurityDecision>();

    for (var reason :
        new SecurityDecisionReason[] {
          SecurityDecisionReason.INSUFFICIENT_PRICE,
          SecurityDecisionReason.MISSING_PRICE_EVIDENCE,
          SecurityDecisionReason.ROUTE_STABLE_PRICE_INCREASE
        }) {
      SecurityDecisionObserver.notifySafely(
          decisions::add,
          new SecurityDecision(reason, SecurityDecisionProtocol.L402, "POST", "/api/widgets/{id}"));
    }

    assertThat(decisions)
        .extracting(SecurityDecision::reason)
        .containsExactly(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionReason.MISSING_PRICE_EVIDENCE,
            SecurityDecisionReason.ROUTE_STABLE_PRICE_INCREASE);
    assertThat(decisions)
        .allSatisfy(
            decision -> {
              assertThat(decision.protocol()).isEqualTo(SecurityDecisionProtocol.L402);
              assertThat(decision.method()).isEqualTo("POST");
              assertThat(decision.endpoint()).isEqualTo("/api/widgets/{id}");
            });
  }

  @Test
  void throwingObserverDoesNotChangeTheSecurityDecisionPath() {
    var decision =
        new SecurityDecision(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionProtocol.L402,
            "POST",
            "/api/widgets/{id}");

    SecurityDecisionObserver.notifySafely(
        ignored -> {
          throw new IllegalStateException("sentinel credential header body");
        },
        decision);

    assertThat(decision.reason()).isEqualTo(SecurityDecisionReason.INSUFFICIENT_PRICE);
  }
}
