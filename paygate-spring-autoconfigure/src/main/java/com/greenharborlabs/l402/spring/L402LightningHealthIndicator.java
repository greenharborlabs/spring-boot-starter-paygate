package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.LightningBackend;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Spring Boot Health Indicator for the L402 Lightning backend.
 *
 * <p>Caches the health result for a configurable duration to avoid
 * hammering the Lightning node on every actuator scrape.
 */
public class L402LightningHealthIndicator implements HealthIndicator {

    private record CacheEntry(Health health, long timestamp) {}

    private final LightningBackend backend;
    private final long cacheTtlMillis;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile CacheEntry cached;

    public L402LightningHealthIndicator(LightningBackend backend, long cacheTtlMillis) {
        this.backend = backend;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        CacheEntry entry = this.cached;
        if (entry != null && (now - entry.timestamp()) < cacheTtlMillis) {
            return entry.health();
        }
        // Stampede protection: only one thread refreshes; others return stale value
        if (refreshLock.tryLock()) {
            try {
                // Double-check after acquiring lock
                entry = this.cached;
                if (entry != null && (System.currentTimeMillis() - entry.timestamp()) < cacheTtlMillis) {
                    return entry.health();
                }
                Health result = checkHealth();
                this.cached = new CacheEntry(result, System.currentTimeMillis());
                return result;
            } finally {
                refreshLock.unlock();
            }
        }
        // Lock not acquired: return stale value if available, otherwise block and refresh
        if (entry != null) {
            return entry.health();
        }
        // First call ever with contention — must block to produce an initial value
        refreshLock.lock();
        try {
            entry = this.cached;
            if (entry != null) {
                return entry.health();
            }
            Health result = checkHealth();
            this.cached = new CacheEntry(result, System.currentTimeMillis());
            return result;
        } finally {
            refreshLock.unlock();
        }
    }

    private Health checkHealth() {
        try {
            if (backend.isHealthy()) {
                return Health.up().withDetail("backend", "reachable").build();
            }
            return Health.down().withDetail("backend", "unreachable").build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("backend", "error").build();
        }
    }
}
