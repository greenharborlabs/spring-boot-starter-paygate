package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for {@link PaygateLightningHealthIndicator}.
 *
 * <p>Verifies healthy/unhealthy mapping, exception handling, and TTL-based caching.
 */
@DisplayName("PaygateLightningHealthIndicator")
class PaygateLightningHealthIndicatorTest {

  @Nested
  @DisplayName("health status mapping")
  class HealthStatusMapping {

    @Test
    @DisplayName("returns UP when backend is healthy")
    void returnsUpWhenHealthy() {
      var indicator = new PaygateLightningHealthIndicator(new ControllableBackend(true), 10_000);

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("returns DOWN when backend is unhealthy")
    void returnsDownWhenUnhealthy() {
      var indicator = new PaygateLightningHealthIndicator(new ControllableBackend(false), 10_000);

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("returns DOWN without exception detail when backend throws")
    void returnsDownOnException() {
      var backend =
          new ControllableBackend(true) {
            @Override
            public boolean isHealthy() {
              throw new RuntimeException("connection refused");
            }
          };
      var indicator = new PaygateLightningHealthIndicator(backend, 10_000);

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).isEmpty();
    }
  }

  @Nested
  @DisplayName("caching behavior")
  class CachingBehavior {

    @Test
    @DisplayName("returns cached result within TTL window")
    void returnsCachedWithinTtl() {
      var backend = new CountingBackend(true);
      var indicator = new PaygateLightningHealthIndicator(backend, 60_000);

      indicator.health();
      indicator.health();
      indicator.health();

      assertThat(backend.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("re-checks backend after TTL expires")
    void reChecksAfterTtlExpires() {
      var backend = new CountingBackend(true);
      // TTL of 0 means every call re-checks
      var indicator = new PaygateLightningHealthIndicator(backend, 0);

      indicator.health();
      indicator.health();

      assertThat(backend.callCount).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent callers use stale UP while one backend refresh is held")
    void concurrentCallersUseStaleResultDuringRefresh() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      var backend = new HeldRefreshBackend(deadline);
      var indicator = new PaygateLightningHealthIndicator(backend, 0);
      assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
      assertThat(backend.callCount.get()).isEqualTo(1);

      var workers = new ArrayList<Thread>();
      var tasks = new ArrayList<FutureTask<Health>>();
      try {
        var refresh = new FutureTask<>(indicator::health);
        tasks.add(refresh);
        workers.add(Thread.ofVirtual().start(refresh));
        assertThat(backend.refreshEntered.await(remainingNanos(deadline), TimeUnit.NANOSECONDS))
            .isTrue();

        for (int i = 0; i < 10; i++) {
          var caller = new FutureTask<>(indicator::health);
          tasks.add(caller);
          workers.add(Thread.ofVirtual().start(caller));
        }
        for (int i = 1; i < tasks.size(); i++) {
          assertThat(tasks.get(i).get(remainingNanos(deadline), TimeUnit.NANOSECONDS).getStatus())
              .isEqualTo(Status.UP);
        }
        assertThat(backend.callCount.get()).isEqualTo(2);
        assertThat(refresh.isDone()).isFalse();

        backend.releaseRefresh.countDown();
        assertThat(refresh.get(remainingNanos(deadline), TimeUnit.NANOSECONDS).getStatus())
            .isEqualTo(Status.DOWN);
        assertThat(backend.callCount.get()).isEqualTo(2);
        assertThat(backend.coordinationFailure.get()).isNull();
      } finally {
        backend.releaseRefresh.countDown();
        long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var task : tasks) {
          if (!task.isDone()) {
            task.cancel(true);
          }
        }
        for (var worker : workers) {
          if (worker.isAlive()) {
            worker.interrupt();
          }
          TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos(cleanupDeadline));
          assertThat(worker.isAlive()).as("worker must stop within cleanup budget").isFalse();
        }
      }
    }

    @Test
    @DisplayName("reflects updated backend status after TTL expires")
    void reflectsUpdatedStatusAfterTtl() {
      var backend = new CountingBackend(true);
      var indicator = new PaygateLightningHealthIndicator(backend, 0);

      Health first = indicator.health();
      assertThat(first.getStatus()).isEqualTo(Status.UP);

      backend.healthy = false;
      Health second = indicator.health();
      assertThat(second.getStatus()).isEqualTo(Status.DOWN);
    }
  }

  // -----------------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------------

  static class ControllableBackend implements LightningBackend {

    private final boolean healthy;

    ControllableBackend(boolean healthy) {
      this.healthy = healthy;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isHealthy() {
      return healthy;
    }
  }

  private static long remainingNanos(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new AssertionError("coordination or cleanup deadline exceeded");
    }
    return remaining;
  }

  static class HeldRefreshBackend extends ControllableBackend {

    final AtomicInteger callCount = new AtomicInteger();
    final AtomicReference<Throwable> coordinationFailure = new AtomicReference<>();
    final CountDownLatch refreshEntered = new CountDownLatch(1);
    final CountDownLatch releaseRefresh = new CountDownLatch(1);
    private final long deadline;

    HeldRefreshBackend(long deadline) {
      super(true);
      this.deadline = deadline;
    }

    @Override
    public boolean isHealthy() {
      if (callCount.incrementAndGet() == 1) {
        return true;
      }
      refreshEntered.countDown();
      try {
        if (!releaseRefresh.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
          throw new AssertionError("backend refresh was never released");
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        var failure = new AssertionError("backend refresh interrupted", ex);
        coordinationFailure.compareAndSet(null, failure);
        throw failure;
      } catch (AssertionError failure) {
        coordinationFailure.compareAndSet(null, failure);
        throw failure;
      }
      return false;
    }
  }

  static class CountingBackend extends ControllableBackend {

    volatile int callCount;
    volatile boolean healthy;

    CountingBackend(boolean healthy) {
      super(healthy);
      this.healthy = healthy;
    }

    @Override
    public boolean isHealthy() {
      callCount++;
      return healthy;
    }
  }
}
