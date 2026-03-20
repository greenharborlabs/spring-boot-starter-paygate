package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;

import java.time.Duration;

/**
 * Decorates a {@link LightningBackend} to cache the result of {@link #isHealthy()}
 * for a configurable TTL. All other methods delegate directly to the wrapped backend.
 *
 * <p>Thread safety is achieved via a single {@code volatile} snapshot record;
 * the slight risk of duplicate evaluation on concurrent cache miss is acceptable
 * for a health check.
 */
public class CachingLightningBackendWrapper implements LightningBackend {

    private record HealthSnapshot(boolean healthy, long atNanos) {}

    private final LightningBackend delegate;
    private final long ttlNanos;

    private volatile HealthSnapshot snapshot;

    public CachingLightningBackendWrapper(LightningBackend delegate, Duration ttl) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be null or negative");
        }
        this.delegate = delegate;
        this.ttlNanos = ttl.toNanos();
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        return delegate.createInvoice(amountSats, memo);
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        return delegate.lookupInvoice(paymentHash);
    }

    @Override
    public boolean isHealthy() {
        long now = System.nanoTime();
        HealthSnapshot current = snapshot;
        // nanoTime can wrap, so use subtraction for correct elapsed calculation
        if (current != null && (now - current.atNanos()) < ttlNanos) {
            return current.healthy();
        }
        boolean healthy = delegate.isHealthy();
        snapshot = new HealthSnapshot(healthy, now);
        return healthy;
    }

    /**
     * Returns the last cached health value without triggering a refresh call to the
     * delegate. Returns {@code false} if no health check has been performed yet.
     *
     * <p>Intended for use by metrics gauges that must not block on a real backend call.
     */
    public boolean lastKnownHealthy() {
        HealthSnapshot current = snapshot;
        return current != null && current.healthy();
    }

    /**
     * Returns the wrapped backend, useful for unwrapping in tests or diagnostics.
     */
    public LightningBackend getDelegate() {
        return delegate;
    }
}
