package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenBucketAggregateInvoiceRateLimiter")
class AggregateInvoiceRateLimiterTest {

  @Test
  @DisplayName("enforces burst capacity and refills from monotonic time")
  void enforcesBurstAndRefills() {
    var clock = new AtomicLong();
    var limiter = new TokenBucketAggregateInvoiceRateLimiter(2.0d, 2, clock::get);

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();

    clock.addAndGet(500_000_000L);
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();
  }

  @Test
  @DisplayName("rejects non-finite and non-positive settings")
  void rejectsInvalidSettings() {
    assertThatThrownBy(() -> new TokenBucketAggregateInvoiceRateLimiter(Double.NaN, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketAggregateInvoiceRateLimiter(1.0d, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
