package com.greenharborlabs.paygate.api;

import java.util.Objects;

/**
 * Optional observer for sanitized {@link SecurityDecision security decisions}.
 *
 * <p>Implementations must not affect enforcement. Call sites should use {@link
 * #notifySafely(SecurityDecisionObserver, SecurityDecision)} so an unavailable telemetry sink is
 * isolated from the protected request.
 */
@FunctionalInterface
public interface SecurityDecisionObserver {

  /** A no-op observer suitable as the dependency-free default. */
  SecurityDecisionObserver NOOP = ignored -> {};

  /**
   * Observes one sanitized security decision.
   *
   * @param decision the fixed-taxonomy decision to observe
   */
  void onDecision(SecurityDecision decision);

  /**
   * Delivers a decision without allowing an observer failure to affect enforcement.
   *
   * @param observer observer to call; {@code null} is treated as no observer
   * @param decision sanitized decision to deliver
   */
  static void notifySafely(SecurityDecisionObserver observer, SecurityDecision decision) {
    Objects.requireNonNull(decision, "decision");
    if (observer == null) {
      return;
    }
    try {
      observer.onDecision(decision);
    } catch (RuntimeException ignored) {
      // Observability is strictly best effort and must never alter enforcement.
    }
  }
}
