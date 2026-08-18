package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecurityDecisionObserverTest {

  private static final SecurityDecision DECISION =
      new SecurityDecision(
          SecurityDecisionReason.INSUFFICIENT_PRICE,
          SecurityDecisionProtocol.L402,
          "POST",
          "/api/analyze");

  @Test
  void taxonomyIsClosedAndNoopObserverAcceptsEveryDecision() {
    assertThat(SecurityDecisionReason.values())
        .containsExactly(
            SecurityDecisionReason.INSUFFICIENT_PRICE,
            SecurityDecisionReason.MISSING_PRICE_EVIDENCE,
            SecurityDecisionReason.ROUTE_STABLE_PRICE_INCREASE,
            SecurityDecisionReason.COMPRESSED_BODY_REJECTED,
            SecurityDecisionReason.PADDED_CAVEAT_KEY_NORMALIZED);

    SecurityDecisionObserver.NOOP.onDecision(DECISION);
  }

  @Test
  void safeNotificationDeliversTheTypedDecision() {
    var observed = new AtomicReference<SecurityDecision>();

    SecurityDecisionObserver.notifySafely(observed::set, DECISION);

    assertThat(observed).hasValue(DECISION);
  }

  @Test
  void safeNotificationIsolatesObserverFailures() {
    SecurityDecisionObserver.notifySafely(
        ignored -> {
          throw new IllegalStateException("sink unavailable");
        },
        DECISION);
  }
}
