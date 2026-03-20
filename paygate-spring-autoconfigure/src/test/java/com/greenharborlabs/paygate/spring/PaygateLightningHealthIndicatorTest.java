package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
            var indicator = new PaygateLightningHealthIndicator(
                    new ControllableBackend(true), 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("backend", "reachable");
        }

        @Test
        @DisplayName("returns DOWN when backend is unhealthy")
        void returnsDownWhenUnhealthy() {
            var indicator = new PaygateLightningHealthIndicator(
                    new ControllableBackend(false), 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("backend", "unreachable");
        }

        @Test
        @DisplayName("returns DOWN with exception detail when backend throws")
        void returnsDownOnException() {
            var backend = new ControllableBackend(true) {
                @Override
                public boolean isHealthy() {
                    throw new RuntimeException("connection refused");
                }
            };
            var indicator = new PaygateLightningHealthIndicator(backend, 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("backend", "error");
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
        @DisplayName("concurrent calls do not stampede the backend")
        void concurrentCallsDoNotStampede() throws InterruptedException {
            var backend = new SlowCountingBackend(true, 50);
            // TTL of 0 means cache is always expired, maximizing stampede potential
            var indicator = new PaygateLightningHealthIndicator(backend, 0);

            int threadCount = 10;
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        indicator.health();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            // With stampede protection, far fewer than 10 threads should hit the backend
            assertThat(backend.callCount.get()).isLessThan(threadCount);
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

    static class SlowCountingBackend extends ControllableBackend {

        final AtomicInteger callCount = new AtomicInteger();
        private final long delayMillis;

        SlowCountingBackend(boolean healthy, long delayMillis) {
            super(healthy);
            this.delayMillis = delayMillis;
        }

        @Override
        public boolean isHealthy() {
            callCount.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return super.isHealthy();
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
