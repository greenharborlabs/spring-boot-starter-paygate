package com.greenharborlabs.l402.spring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.ConcurrentHashMap;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.EvictionReason;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

/**
 * Helper class that wraps a {@link MeterRegistry} and provides convenient
 * methods for recording L402-related Micrometer metrics.
 *
 * <p>Counters are cached per unique tag combination in {@link ConcurrentHashMap}
 * fields so that {@code Counter.builder().register()} is called at most once
 * per combination. Gauges for credential store size and Lightning health are
 * registered eagerly during construction so they are available before any requests.
 */
public class L402Metrics {

    private final MeterRegistry registry;

    // Cache key for l402.requests: "endpoint\0result"
    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> invoicesCreatedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> revenueSatsCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> invoicesSettledCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EvictionReason, Counter> evictionCounters = new ConcurrentHashMap<>();

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
        requestCounter(endpoint, "challenged").increment();
        invoicesCreatedCounter(endpoint).increment();
    }

    /**
     * Records a successful credential validation: increments {@code l402.requests}
     * with {@code result=passed}, {@code l402.revenue.sats}, and
     * {@code l402.invoices.settled}.
     */
    public void recordPassed(String endpoint, long priceSats) {
        requestCounter(endpoint, "passed").increment();
        revenueSatsCounter(endpoint).increment(priceSats);
        invoicesSettledCounter(endpoint).increment();
    }

    /**
     * Records a cache eviction: increments {@code l402.cache.evictions}
     * counter tagged with the eviction reason (lowercase).
     *
     * <p>Thread-safe: may be called from Caffeine's async removal listener thread.
     */
    public void recordCacheEviction(EvictionReason reason) {
        evictionCounters.computeIfAbsent(reason, r ->
                Counter.builder("l402.cache.evictions")
                        .tag("reason", r.name().toLowerCase())
                        .description("Credential cache evictions")
                        .register(registry)
        ).increment();
    }

    /**
     * Records a rejected credential: increments {@code l402.requests}
     * with {@code result=rejected}.
     */
    public void recordRejected(String endpoint) {
        requestCounter(endpoint, "rejected").increment();
    }

    private Counter requestCounter(String endpoint, String result) {
        String key = endpoint + '\0' + result;
        return requestCounters.computeIfAbsent(key, _ ->
                Counter.builder("l402.requests")
                        .tag("endpoint", endpoint)
                        .tag("result", result)
                        .description("Total protected endpoint requests")
                        .register(registry)
        );
    }

    private Counter invoicesCreatedCounter(String endpoint) {
        return invoicesCreatedCounters.computeIfAbsent(endpoint, ep ->
                Counter.builder("l402.invoices.created")
                        .tag("endpoint", ep)
                        .description("Invoices generated")
                        .register(registry)
        );
    }

    private Counter revenueSatsCounter(String endpoint) {
        return revenueSatsCounters.computeIfAbsent(endpoint, ep ->
                Counter.builder("l402.revenue.sats")
                        .tag("endpoint", ep)
                        .description("Total sats earned")
                        .register(registry)
        );
    }

    private Counter invoicesSettledCounter(String endpoint) {
        return invoicesSettledCounters.computeIfAbsent(endpoint, ep ->
                Counter.builder("l402.invoices.settled")
                        .tag("endpoint", ep)
                        .description("Invoices paid")
                        .register(registry)
        );
    }
}
