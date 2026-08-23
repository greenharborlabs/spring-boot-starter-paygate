package com.greenharborlabs.paygate.spring;

import java.util.Objects;
import java.util.function.LongSupplier;

/** A bounded, monotonic-time token bucket for the default per-instance invoice ceiling. */
public final class TokenBucketAggregateInvoiceRateLimiter implements AggregateInvoiceRateLimiter {

  private final double requestsPerSecond;
  private final int burstSize;
  private final LongSupplier nanoTime;
  private double tokens;
  private long lastRefillNanos;

  /** Creates a limiter using {@link System#nanoTime()}. */
  public TokenBucketAggregateInvoiceRateLimiter(double requestsPerSecond, int burstSize) {
    this(requestsPerSecond, burstSize, System::nanoTime);
  }

  /** Creates a limiter with an injectable monotonic clock, primarily for deterministic tests. */
  TokenBucketAggregateInvoiceRateLimiter(
      double requestsPerSecond, int burstSize, LongSupplier nanoTime) {
    if (!Double.isFinite(requestsPerSecond) || requestsPerSecond <= 0.0d) {
      throw new IllegalArgumentException("requestsPerSecond must be finite and positive");
    }
    if (burstSize <= 0) {
      throw new IllegalArgumentException("burstSize must be positive");
    }
    this.requestsPerSecond = requestsPerSecond;
    this.burstSize = burstSize;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    this.tokens = burstSize;
    this.lastRefillNanos = nanoTime.getAsLong();
  }

  @Override
  public synchronized boolean tryAcquire() {
    long now = nanoTime.getAsLong();
    long elapsed = Math.max(0L, now - lastRefillNanos);
    if (elapsed > 0L) {
      double replenished = elapsed * requestsPerSecond / 1_000_000_000.0d;
      tokens = Math.min(burstSize, tokens + replenished);
      lastRefillNanos = now;
    }
    if (tokens < 1.0d) {
      return false;
    }
    tokens -= 1.0d;
    return true;
  }
}
