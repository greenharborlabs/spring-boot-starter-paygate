package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.EvictionReason;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Helper class that wraps a {@link MeterRegistry} and provides convenient methods for recording
 * L402-related Micrometer metrics.
 *
 * <p>Counters are cached per unique tag combination in {@link ConcurrentHashMap} fields so that
 * {@code Counter.builder().register()} is called at most once per combination. Gauges for
 * credential store size and Lightning health are registered eagerly during construction so they are
 * available before any requests.
 */
public class PaygateMetrics {
  private static final String CAVEAT_TYPE_OTHER = "_other";
  private static final Set<String> KNOWN_CAVEAT_TYPES =
      Set.of(
          "path", "method", "client_ip", "services", "capabilities", "valid_until", "escalation");

  private final MeterRegistry registry;
  private final Supplier<Boolean> lightningHealthySupplier;

  // Cache key for paygate.requests: "endpoint\0result"
  private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> invoicesCreatedCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> revenueSatsCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> invoicesSettledCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<EvictionReason, Counter> evictionCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> caveatRejectedCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> rateLimiterEvictionCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> rateLimitRejectionCounters =
      new ConcurrentHashMap<>();
  private final Timer caveatVerifyTimer;

  public PaygateMetrics(
      MeterRegistry registry, CredentialStore credentialStore, LightningBackend lightningBackend) {
    this(registry, credentialStore, healthSupplierFor(lightningBackend));
  }

  public PaygateMetrics(
      MeterRegistry registry,
      CredentialStore credentialStore,
      Supplier<Boolean> lightningHealthySupplier) {
    this.registry = registry;
    this.lightningHealthySupplier = lightningHealthySupplier;

    // Register gauges eagerly
    Gauge.builder("paygate.credentials.active", credentialStore, CredentialStore::activeCount)
        .description("Currently cached credentials")
        .register(registry);

    Gauge.builder(
            "paygate.lightning.healthy",
            lightningHealthySupplier,
            supplier -> {
              try {
                return safeBooleanToGaugeValue(supplier.get());
              } catch (Exception e) {
                return 0.0;
              }
            })
        .description("1=healthy, 0=unhealthy")
        .register(registry);

    this.caveatVerifyTimer =
        Timer.builder("paygate.caveats.verify.duration")
            .tag("protocol", "l402")
            .description("Duration of caveat verification per request")
            .register(registry);
  }

  /**
   * Records a 402 challenge being issued: increments {@code paygate.requests} with {@code
   * result=challenged} and {@code paygate.invoices.created}.
   *
   * @param protocol the protocol scheme (e.g. "L402", "Payment", "all")
   */
  public void recordChallenge(String endpoint, String protocol) {
    requestCounter(endpoint, "challenged", protocol).increment();
    invoicesCreatedCounter(endpoint, protocol).increment();
  }

  /**
   * Records a successful credential validation: increments {@code paygate.requests} with {@code
   * result=passed}, {@code paygate.revenue.sats}, and {@code paygate.invoices.settled}.
   *
   * @param protocol the protocol scheme (e.g. "L402", "Payment")
   */
  public void recordPassed(String endpoint, long priceSats, String protocol) {
    requestCounter(endpoint, "passed", protocol).increment();
    revenueSatsCounter(endpoint, protocol).increment(priceSats);
    invoicesSettledCounter(endpoint, protocol).increment();
  }

  /**
   * Records a cache eviction: increments {@code paygate.cache.evictions} counter tagged with the
   * eviction reason (lowercase).
   *
   * <p>Thread-safe: may be called from Caffeine's async removal listener thread.
   */
  public void recordCacheEviction(EvictionReason reason) {
    evictionCounters
        .computeIfAbsent(
            reason,
            r ->
                Counter.builder("paygate.cache.evictions")
                    .tag("reason", r.name().toLowerCase())
                    .description("Credential cache evictions")
                    .register(registry))
        .increment();
  }

  /**
   * Records a rejected credential: increments {@code paygate.requests} with {@code
   * result=rejected}.
   *
   * @param protocol the protocol scheme (e.g. "L402", "Payment", "unknown")
   */
  public void recordRejected(String endpoint, String protocol) {
    requestCounter(endpoint, "rejected", protocol).increment();
  }

  /**
   * Records the duration of caveat verification in nanoseconds. Called after every successful or
   * failed caveat verification attempt.
   */
  public void recordCaveatVerifyDuration(long nanos) {
    caveatVerifyTimer.record(nanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Records a caveat rejection: increments {@code paygate.caveats.rejected} counter tagged with the
   * caveat type (e.g. path, method, client_ip, escalation).
   */
  public void recordCaveatRejected(String caveatType) {
    String normalizedType = normalizeCaveatType(caveatType);
    caveatRejectedCounters
        .computeIfAbsent(
            normalizedType,
            ct ->
                Counter.builder("paygate.caveats.rejected")
                    .tag("caveat_type", ct)
                    .tag("protocol", "l402")
                    .description("Caveat verification rejections by type")
                    .register(registry))
        .increment();
  }

  private static Supplier<Boolean> healthSupplierFor(LightningBackend lightningBackend) {
    if (lightningBackend instanceof CachingLightningBackendWrapper wrapper) {
      return wrapper::lastKnownHealthy;
    }
    return lightningBackend::isHealthy;
  }

  private static double safeBooleanToGaugeValue(Boolean healthy) {
    return Boolean.TRUE.equals(healthy) ? 1.0 : 0.0;
  }

  private static String normalizeCaveatType(String caveatType) {
    if (caveatType == null) {
      return CAVEAT_TYPE_OTHER;
    }
    return KNOWN_CAVEAT_TYPES.contains(caveatType) ? caveatType : CAVEAT_TYPE_OTHER;
  }

  /**
   * Registers a gauge tracking the number of active rate-limiter buckets. Called once after
   * construction when a rate limiter is available.
   *
   * @param bucketCountSupplier supplies the current bucket count
   */
  public void registerRateLimiterMetrics(Supplier<Long> bucketCountSupplier) {
    try {
      Gauge.builder("paygate.ratelimiter.buckets.active", bucketCountSupplier, Supplier::get)
          .description("Current number of tracked IP rate-limit buckets")
          .register(registry);
    } catch (Exception e) {
      // Best-effort: do not fail the application if metric registration fails
    }
  }

  /**
   * Records a rate-limiter bucket eviction: increments {@code paygate.ratelimiter.evictions}
   * counter tagged with the eviction reason (e.g., "expired", "size").
   *
   * <p>Thread-safe: may be called from Caffeine's async removal listener thread.
   */
  public void recordRateLimiterEviction(String reason) {
    try {
      rateLimiterEvictionCounters
          .computeIfAbsent(
              reason,
              r ->
                  Counter.builder("paygate.ratelimiter.evictions")
                      .tag("reason", r)
                      .description("Rate limiter bucket evictions")
                      .register(registry))
          .increment();
    } catch (Exception e) {
      // Best-effort: do not fail the request if metric recording fails
    }
  }

  /**
   * Records a rate-limit rejection (429 response): increments {@code
   * paygate.ratelimiter.rejections} counter tagged with the endpoint.
   */
  public void recordRateLimitRejection(String endpoint) {
    try {
      rateLimitRejectionCounters
          .computeIfAbsent(
              endpoint,
              ep ->
                  Counter.builder("paygate.ratelimiter.rejections")
                      .tag("endpoint", ep)
                      .description("Rate limit rejections (429 responses)")
                      .register(registry))
          .increment();
    } catch (Exception e) {
      // Best-effort: do not fail the request if metric recording fails
    }
  }

  private Counter requestCounter(String endpoint, String result, String protocol) {
    String key = endpoint + '\0' + result + '\0' + protocol;
    return requestCounters.computeIfAbsent(
        key,
        _ ->
            Counter.builder("paygate.requests")
                .tag("endpoint", endpoint)
                .tag("result", result)
                .tag("protocol", protocol)
                .description("Total protected endpoint requests")
                .register(registry));
  }

  private Counter invoicesCreatedCounter(String endpoint, String protocol) {
    String key = endpoint + '\0' + protocol;
    return invoicesCreatedCounters.computeIfAbsent(
        key,
        _ ->
            Counter.builder("paygate.invoices.created")
                .tag("endpoint", endpoint)
                .tag("protocol", protocol)
                .description("Invoices generated")
                .register(registry));
  }

  private Counter revenueSatsCounter(String endpoint, String protocol) {
    String key = endpoint + '\0' + protocol;
    return revenueSatsCounters.computeIfAbsent(
        key,
        _ ->
            Counter.builder("paygate.revenue.sats")
                .tag("endpoint", endpoint)
                .tag("protocol", protocol)
                .description("Total sats earned")
                .register(registry));
  }

  private Counter invoicesSettledCounter(String endpoint, String protocol) {
    String key = endpoint + '\0' + protocol;
    return invoicesSettledCounters.computeIfAbsent(
        key,
        _ ->
            Counter.builder("paygate.invoices.settled")
                .tag("endpoint", endpoint)
                .tag("protocol", protocol)
                .description("Invoices paid")
                .register(registry));
  }
}
