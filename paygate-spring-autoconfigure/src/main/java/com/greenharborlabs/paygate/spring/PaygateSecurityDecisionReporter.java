package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Best-effort Spring observer that emits one sanitized counter and structured log event per
 * decision.
 *
 * <p>Only the fixed decision taxonomy and sanitized fields from {@link SecurityDecision} are used.
 * Micrometer registration/increment and logger failures are independently isolated so observation
 * cannot influence payment enforcement.
 */
public final class PaygateSecurityDecisionReporter implements SecurityDecisionObserver {

  private static final System.Logger LOG =
      System.getLogger(PaygateSecurityDecisionReporter.class.getName());

  private final MeterRegistry meterRegistry;

  /**
   * Creates a reporter with an optional Micrometer registry; a {@code null} registry keeps only
   * structured logging enabled.
   */
  public PaygateSecurityDecisionReporter(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Emits the fixed decision counter and structured event without affecting enforcement. */
  @Override
  public void onDecision(SecurityDecision decision) {
    if (meterRegistry != null) {
      try {
        Counter.builder("paygate.security.decisions")
            .tag("reason", lower(decision.reason().name()))
            .tag("protocol", lower(decision.protocol().name()))
            .tag("method", decision.method())
            .tag("endpoint", decision.endpoint())
            .description("Sanitized payment-gateway security decisions")
            .register(meterRegistry)
            .increment();
      } catch (RuntimeException ignored) {
        // A metric sink outage must not affect enforcement or the log sink.
      }
    }
    try {
      LOG.log(
          System.Logger.Level.INFO,
          "paygate_security_decision reason={0} protocol={1} method={2} endpoint={3}",
          lower(decision.reason().name()),
          lower(decision.protocol().name()),
          decision.method(),
          decision.endpoint());
    } catch (RuntimeException ignored) {
      // Logging is likewise best effort.
    }
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
