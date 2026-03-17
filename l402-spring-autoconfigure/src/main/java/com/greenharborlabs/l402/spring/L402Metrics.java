package com.greenharborlabs.l402.spring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.EvictionReason;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

/**
 * Helper class that wraps a {@link MeterRegistry} and provides convenient
 * methods for recording L402-related Micrometer metrics.
 *
 * <p>Counters are created lazily via Micrometer's deduplication.
 * Gauges for credential store size and Lightning health are registered
 * eagerly during construction so they are available before any requests.
 */
public class L402Metrics {

    private final MeterRegistry registry;

    public L402Metrics(MeterRegistry registry,
                       CredentialStore credentialStore,
                       LightningBackend lightningBackend) {
        this.registry = registry;

        // Register gauges eagerly
        Gauge.builder("l402.credentials.active", credentialStore, CredentialStore::activeCount)
                .description("Currently cached credentials")
                .register(registry);

        Gauge.builder("l402.lightning.healthy", lightningBackend, backend -> {
                    try {
                        if (backend instanceof CachingLightningBackendWrapper cached) {
                            return cached.lastKnownHealthy() ? 1.0 : 0.0;
                        }
                        return backend.isHealthy() ? 1.0 : 0.0;
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .description("1=healthy, 0=unhealthy")
                .register(registry);
    }

    /**
     * Records a 402 challenge being issued: increments {@code l402.requests}
     * with {@code result=challenged} and {@code l402.invoices.created}.
     */
    public void recordChallenge(String endpoint) {
        Counter.builder("l402.requests")
                .tag("endpoint", endpoint)
                .tag("result", "challenged")
                .description("Total protected endpoint requests")
                .register(registry)
                .increment();

        Counter.builder("l402.invoices.created")
                .tag("endpoint", endpoint)
                .description("Invoices generated")
                .register(registry)
                .increment();
    }

    /**
     * Records a successful credential validation: increments {@code l402.requests}
     * with {@code result=passed}, {@code l402.revenue.sats}, and
     * {@code l402.invoices.settled}.
     */
    public void recordPassed(String endpoint, long priceSats) {
        Counter.builder("l402.requests")
                .tag("endpoint", endpoint)
                .tag("result", "passed")
                .description("Total protected endpoint requests")
                .register(registry)
                .increment();

        Counter.builder("l402.revenue.sats")
                .tag("endpoint", endpoint)
                .description("Total sats earned")
                .register(registry)
                .increment(priceSats);

        Counter.builder("l402.invoices.settled")
                .tag("endpoint", endpoint)
                .description("Invoices paid")
                .register(registry)
                .increment();
    }

    /**
     * Records a cache eviction: increments {@code l402.cache.evictions}
     * counter tagged with the eviction reason (lowercase).
     *
     * <p>Thread-safe: may be called from Caffeine's async removal listener thread.
     * {@code Counter.builder().register()} is idempotent in Micrometer.
     */
    public void recordCacheEviction(EvictionReason reason) {
        Counter.builder("l402.cache.evictions")
                .tag("reason", reason.name().toLowerCase())
                .description("Credential cache evictions")
                .register(registry)
                .increment();
    }

    /**
     * Records a rejected credential: increments {@code l402.requests}
     * with {@code result=rejected}.
     */
    public void recordRejected(String endpoint) {
        Counter.builder("l402.requests")
                .tag("endpoint", endpoint)
                .tag("result", "rejected")
                .description("Total protected endpoint requests")
                .register(registry)
                .increment();
    }
}
