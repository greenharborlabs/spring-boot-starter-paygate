package com.greenharborlabs.paygate.spring;

/**
 * A thread-safe, identity-independent allowance for invoice creation.
 *
 * <p>Applications running more than one instance may replace the default local implementation with
 * a shared limiter. Implementations must return {@code false} when no allowance remains and may
 * throw to signal that an allowance cannot be determined; callers fail closed in that case.
 */
@FunctionalInterface
public interface AggregateInvoiceRateLimiter {

  /** Attempts to reserve capacity for exactly one invoice creation. */
  boolean tryAcquire();
}
