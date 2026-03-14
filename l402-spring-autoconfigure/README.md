# l402-spring-autoconfigure

Spring Boot auto-configuration module for the `spring-boot-starter-l402` project. This module wires up all L402 protocol components -- Lightning backends, root key stores, credential caches, the security filter, health indicators, rate limiting, metrics, and actuator endpoints -- based on application properties under the `l402.*` prefix.

You do not use this module directly. Instead, add the `l402-spring-boot-starter` dependency, which pulls in this module along with `l402-core`. Then add whichever Lightning backend module you need (`l402-lightning-lnbits` or `l402-lightning-lnd`) and set `l402.enabled=true`.

---

## Table of Contents

- [How Auto-Configuration Works](#how-auto-configuration-works)
- [Configuration Properties](#configuration-properties)
- [Bean Creation and Conditions](#bean-creation-and-conditions)
- [Lightning Backend Selection](#lightning-backend-selection)
- [Root Key Store](#root-key-store)
- [Credential Cache](#credential-cache)
- [Security Filter](#security-filter)
- [Rate Limiting](#rate-limiting)
- [Health Cache](#health-cache)
- [Health Indicator (Actuator)](#health-indicator-actuator)
- [Actuator Endpoint](#actuator-endpoint)
- [Micrometer Metrics](#micrometer-metrics)
- [Test Mode](#test-mode)
- [Dynamic Pricing](#dynamic-pricing)
- [Overriding Default Beans](#overriding-default-beans)
- [IDE Autocomplete Support](#ide-autocomplete-support)
- [Architecture](#architecture)
- [Testing](#testing)

---

## How Auto-Configuration Works

The module registers four `@AutoConfiguration` classes, declared in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

| Auto-Configuration Class | Condition | Purpose |
|--------------------------|-----------|---------|
| `L402AutoConfiguration` | `l402.enabled=true` | Core beans: root key store, credential cache, caveat verifiers, validator, endpoint registry, rate limiter, security filter, earnings tracker, Lightning backend |
| `L402ActuatorAutoConfiguration` | Actuator on classpath + `L402EndpointRegistry` bean present + `l402.actuator.enabled!=false` | Actuator endpoint at `/actuator/l402` |
| `L402MetricsAutoConfiguration` | Micrometer on classpath + `MeterRegistry` bean present + `l402.enabled=true` | Micrometer counters and gauges |
| `TestModeAutoConfiguration` | `l402.test-mode=true` (runs **before** `L402AutoConfiguration`) | Dummy Lightning backend for development |

When `l402.enabled` is `false` or absent, no L402 beans are created. The entire auto-configuration is skipped and all endpoints are accessible without payment.

All beans are guarded with `@ConditionalOnMissingBean`, so you can override any component by defining your own bean of the same type.

---

## Configuration Properties

All properties are bound from the `l402.*` namespace via `L402Properties`.

### Core Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.enabled` | `boolean` | `false` | Master switch. When `false`, no L402 beans are registered. |
| `l402.backend` | `string` | -- | Lightning backend to use: `lnbits` or `lnd`. Must match a backend module on the classpath. |
| `l402.service-name` | `string` | `"default"` | Logical service name embedded in macaroon caveats. Used by `ServicesCaveatVerifier` for service-scoped authorization. |
| `l402.default-price-sats` | `long` | `10` | Default price in satoshis for L402-protected endpoints. Individual endpoints override this via `@L402Protected(priceSats = ...)`. |
| `l402.default-timeout-seconds` | `long` | `3600` | Default invoice expiry in seconds. |
| `l402.test-mode` | `boolean` | `false` | Enables test mode with an in-memory Lightning backend. Must not be used in production. See [Test Mode](#test-mode). |
| `l402.trust-forwarded-headers` | `boolean` | `false` | Whether to read `X-Forwarded-For` for client IP resolution. Enable only behind a trusted reverse proxy. See [Rate Limiting](#rate-limiting). |

### Root Key Store Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.root-key-store` | `string` | `"file"` | Storage backend for macaroon signing keys. `file` persists to disk; `memory` keeps keys in-memory (lost on restart). |
| `l402.root-key-store-path` | `string` | `"~/.l402/keys"` | File system path for the file-based root key store. Supports `~` expansion. The directory is created automatically. Only used when `l402.root-key-store=file`. |

### Credential Cache Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.credential-cache` | `string` | `"caffeine"` | Cache implementation for validated L402 credentials. `caffeine` requires the Caffeine library on the classpath. |
| `l402.credential-cache-max-size` | `int` | `10000` | Maximum number of validated credentials to cache. Higher values use more memory but reduce re-verification overhead. |

### Rate Limiting Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.rate-limit.requests-per-second` | `double` | `10.0` | Token refill rate per second per client IP. Controls the sustained rate of 402 challenge issuance. |
| `l402.rate-limit.burst-size` | `int` | `20` | Maximum burst capacity per client IP. Allows short bursts above the sustained rate before throttling. |

### Health Cache Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.health-cache.enabled` | `boolean` | `true` | Whether to cache `isHealthy()` results from the Lightning backend. |
| `l402.health-cache.ttl-seconds` | `int` | `5` | How long health check results are cached, in seconds. |

### LNbits Backend Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `l402.lnbits.url` | `string` | -- | When `l402.backend=lnbits` | Base URL of the LNbits instance. |
| `l402.lnbits.api-key` | `string` | -- | When `l402.backend=lnbits` | LNbits Invoice/read API key. Keep this value secret. |

### LND Backend Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `l402.lnd.host` | `string` | `"localhost"` | When `l402.backend=lnd` | Hostname or IP of the LND gRPC endpoint. |
| `l402.lnd.port` | `int` | `10009` | When `l402.backend=lnd` | Port of the LND gRPC endpoint. |
| `l402.lnd.tls-cert-path` | `string` | -- | When `l402.backend=lnd` (unless plaintext) | Path to the LND TLS certificate (`tls.cert`). |
| `l402.lnd.macaroon-path` | `string` | -- | No | Path to the LND admin macaroon file (`admin.macaroon`). |
| `l402.lnd.allow-plaintext` | `boolean` | `false` | No | Allow plaintext gRPC (no TLS). For local development only. |

### Example application.yml

```yaml
l402:
  enabled: true
  backend: lnbits
  service-name: my-api
  default-price-sats: 10
  default-timeout-seconds: 3600
  root-key-store: file
  root-key-store-path: ~/.l402/keys
  credential-cache-max-size: 10000
  rate-limit:
    requests-per-second: 10.0
    burst-size: 20
  health-cache:
    enabled: true
    ttl-seconds: 5
  lnbits:
    url: https://lnbits.example.com
    api-key: ${LNBITS_API_KEY}
```

---

## Bean Creation and Conditions

The following table shows every bean created by `L402AutoConfiguration`, the conditions that must be met, and the type created.

| Bean | Type | Condition | Notes |
|------|------|-----------|-------|
| `rootKeyStore` | `RootKeyStore` | `@ConditionalOnMissingBean` | `InMemoryRootKeyStore` when `l402.root-key-store=memory`; `FileBasedRootKeyStore` otherwise |
| `credentialStore` | `CredentialStore` | `@ConditionalOnMissingBean` + Caffeine class check | `CaffeineCredentialStore` if Caffeine is on the classpath; `InMemoryCredentialStore` otherwise |
| `caveatVerifiers` | `List<CaveatVerifier>` | `@ConditionalOnMissingBean(name="caveatVerifiers")` | `[ServicesCaveatVerifier, ValidUntilCaveatVerifier]` |
| `l402Validator` | `L402Validator` | `@ConditionalOnMissingBean` | Wires root key store, credential store, caveat verifiers, and service name |
| `l402EndpointRegistry` | `L402EndpointRegistry` | `@ConditionalOnMissingBean` | Scans `@L402Protected` annotations from Spring MVC handler mappings |
| `l402RateLimiter` | `L402RateLimiter` | `@ConditionalOnMissingBean` | `TokenBucketRateLimiter` with configured burst size and refill rate |
| `l402EarningsTracker` | `L402EarningsTracker` | `@ConditionalOnMissingBean` | In-memory tracker; resets on restart |
| `l402SecurityFilter` | `L402SecurityFilter` | `@ConditionalOnMissingBean` | The core servlet filter. Receives metrics, earnings tracker, and rate limiter via setter injection (all optional) |
| `l402SecurityFilterRegistration` | `FilterRegistrationBean<L402SecurityFilter>` | Always (when auto-config is active) | Registered at `Ordered.HIGHEST_PRECEDENCE + 10`, matching `/*` |
| `lightningBackend` (LNbits) | `LightningBackend` | `l402.backend=lnbits` + `LnbitsBackend` on classpath + `@ConditionalOnMissingBean` | Creates `LnbitsBackend` with 10-second connect timeout `HttpClient` |
| `lightningBackend` (LND) | `LightningBackend` | `l402.backend=lnd` + `LndBackend` on classpath + `@ConditionalOnMissingBean` | Creates gRPC `ManagedChannel` and `LndBackend` |
| `lndManagedChannel` | `ManagedChannel` | `l402.backend=lnd` + `@ConditionalOnMissingBean(ManagedChannel.class)` | TLS with optional macaroon interceptor. `destroyMethod="shutdown"` |

### Ordering

`TestModeAutoConfiguration` runs **before** `L402AutoConfiguration` (via `@AutoConfiguration(before = ...)`). This ensures the test-mode `LightningBackend` bean is registered first, and `L402AutoConfiguration` finds it via `@ConditionalOnMissingBean`.

`L402ActuatorAutoConfiguration` and `L402MetricsAutoConfiguration` run **after** `L402AutoConfiguration` (via `@AutoConfiguration(after = ...)`), because they depend on beans created by the core auto-configuration.

---

## Lightning Backend Selection

The Lightning backend is selected by the `l402.backend` property combined with classpath detection:

| `l402.backend` | Required on classpath | Bean created |
|----------------|-----------------------|--------------|
| `lnbits` | `l402-lightning-lnbits` module | `LnbitsBackend` (via `LnbitsBackendConfiguration`) |
| `lnd` | `l402-lightning-lnd` module + gRPC dependencies | `LndBackend` (via `LndBackendConfiguration`) |
| (test mode) | Nothing extra | `TestModeLightningBackend` (via `TestModeAutoConfiguration`) |

If `l402.backend` is set but the corresponding module is not on the classpath, no `LightningBackend` bean is created, and the `L402SecurityFilter` bean will fail to initialize because its required dependency is missing.

### LND Channel Configuration

When `l402.backend=lnd`, the auto-configuration builds a gRPC `ManagedChannel`:

- If `l402.lnd.tls-cert-path` is set, the channel uses TLS with the provided certificate. If `l402.lnd.macaroon-path` is also set, a `MacaroonClientInterceptor` attaches the macaroon hex as gRPC metadata on every call.
- If `l402.lnd.tls-cert-path` is not set and `l402.lnd.allow-plaintext=true`, a plaintext channel is created (a warning is logged). This is only suitable for local development.
- If neither TLS cert nor plaintext is configured, startup fails with `IllegalStateException`.

---

## Root Key Store

The root key store holds the signing keys for macaroons. Two implementations are available:

| `l402.root-key-store` | Implementation | Persistence |
|-----------------------|----------------|-------------|
| `file` (default) | `FileBasedRootKeyStore` | Keys are written to `l402.root-key-store-path` (default `~/.l402/keys`). Survives restarts. |
| `memory` | `InMemoryRootKeyStore` | Keys exist only in memory. All issued credentials become invalid on restart. |

The `~` prefix in `l402.root-key-store-path` is expanded to `System.getProperty("user.home")`.

---

## Credential Cache

Validated L402 credentials are cached to avoid re-verifying macaroon signatures and re-querying the Lightning backend on every request with the same credential.

| Caffeine on classpath? | Implementation | Behavior |
|------------------------|----------------|----------|
| Yes | `CaffeineCredentialStore` | Per-entry TTL based on the credential's `valid_until` caveat. Bounded by `l402.credential-cache-max-size`. |
| No | `InMemoryCredentialStore` | Simple `ConcurrentHashMap`-based store with configurable max size. |

`CaffeineCredentialStore` uses Caffeine's variable expiry API (`Expiry`) so each cached credential expires independently based on its own TTL, rather than a global expiration.

---

## Security Filter

`L402SecurityFilter` is a standard Jakarta Servlet `Filter` that enforces L402 payment authentication. It is registered at `Ordered.HIGHEST_PRECEDENCE + 10` and matches all URL patterns (`/*`).

### Request Flow

1. **Match**: The filter checks if the request path and HTTP method match any `@L402Protected` endpoint in the `L402EndpointRegistry`. If no match, the request passes through untouched.
2. **Credential validation**: If the `Authorization` header contains an `L402` or `LSAT` prefix, the credential is validated locally via `L402Validator` (no Lightning network call needed). Valid credentials pass through; malformed headers get HTTP 400; expired or invalid credentials get the appropriate error status.
3. **Health check**: If no valid credential is present, the Lightning backend's `isHealthy()` is checked. If the backend is down, the filter returns HTTP 503 (fail-closed).
4. **Rate limit check**: Before creating an invoice, the filter checks the `L402RateLimiter`. If the client IP has exceeded the rate limit, HTTP 429 is returned with a `Retry-After: 1` header.
5. **Invoice creation**: A root key is generated, a Lightning invoice is created, a macaroon is minted with service and expiry caveats, and HTTP 402 is returned with a `WWW-Authenticate` header containing the macaroon and invoice.

### Fail-Closed Semantics

The filter follows a strict fail-closed security model:

- Lightning backend unreachable: HTTP 503, never HTTP 200
- Invoice creation failure: HTTP 503
- Unexpected validation error: HTTP 503
- Protected content is never returned when the Lightning backend cannot verify payments

### HTTP Response Codes

| Code | Meaning | When |
|------|---------|------|
| 200 | Success | Valid L402 credential presented and verified |
| 400 | Bad Request | Malformed `Authorization` header (unparseable L402/LSAT token) |
| 402 | Payment Required | No credential; includes `WWW-Authenticate` header with macaroon and Lightning invoice |
| 429 | Too Many Requests | Rate limit exceeded for challenge issuance |
| 503 | Service Unavailable | Lightning backend is down or invoice creation failed |

---

## Rate Limiting

Rate limiting prevents invoice flooding attacks by throttling 402 challenge issuance per client IP address. It uses a token bucket algorithm implemented in `TokenBucketRateLimiter`.

### How It Works

- Each client IP gets an independent token bucket with `l402.rate-limit.burst-size` maximum tokens and a refill rate of `l402.rate-limit.requests-per-second` tokens per second.
- New buckets start full. Each challenge request consumes one token.
- When a bucket is empty, the client receives HTTP 429 until tokens refill.
- Stale entries are cleaned up lazily every 1000 `tryAcquire` calls. The total number of tracked IPs is capped at 100,000 to prevent memory exhaustion.

### Client IP Resolution

By default, the filter uses `request.getRemoteAddr()` for rate limiting. If the service runs behind a reverse proxy:

- Set `l402.trust-forwarded-headers=true` to read the client IP from the `X-Forwarded-For` header (leftmost value).
- When an `X-Forwarded-For` header is detected but `trust-forwarded-headers` is `false`, the filter logs a one-time warning suggesting you enable it.
- When `trust-forwarded-headers` is `false`, spoofed `X-Forwarded-For` headers are ignored, preventing rate limit bypass.

### Overriding the Rate Limiter

Provide your own `L402RateLimiter` bean to replace the default `TokenBucketRateLimiter`:

```java
@Bean
public L402RateLimiter l402RateLimiter() {
    return key -> true; // disable rate limiting
}
```

The `L402RateLimiter` interface is `@FunctionalInterface` with a single method: `boolean tryAcquire(String key)`.

---

## Health Cache

When `l402.health-cache.enabled=true` (the default), a `BeanPostProcessor` wraps every `LightningBackend` bean in a `CachingLightningBackendWrapper`. This caches the result of `isHealthy()` for `l402.health-cache.ttl-seconds` (default 5 seconds) to avoid hammering the Lightning node on every request to a protected endpoint.

- `createInvoice()` and `lookupInvoice()` are **not** cached -- they always delegate directly to the underlying backend.
- `TestModeLightningBackend` is excluded from wrapping (it is already a stub).
- Thread safety is achieved via a single `volatile` snapshot record.

To disable health caching, set `l402.health-cache.enabled=false`.

---

## Health Indicator (Actuator)

When Spring Boot Actuator is on the classpath, `L402ActuatorAutoConfiguration` registers an `L402LightningHealthIndicator` that reports the Lightning backend status in the `/actuator/health` endpoint.

The health indicator caches its own result using the `l402.health-cache.ttl-seconds` value (in milliseconds) to avoid redundant checks during actuator scrapes.

| Health Status | Condition |
|---------------|-----------|
| `UP` | `backend.isHealthy()` returns `true`. Detail: `backend=reachable` |
| `DOWN` | `backend.isHealthy()` returns `false`. Detail: `backend=unreachable` |
| `DOWN` | `backend.isHealthy()` throws an exception. Detail: `backend=error` |

---

## Actuator Endpoint

When Spring Boot Actuator is on the classpath, a custom endpoint is available at `GET /actuator/l402` via `L402ActuatorEndpoint`. It is enabled by default and can be disabled with `l402.actuator.enabled=false`.

### Response Structure

```json
{
  "enabled": true,
  "backend": "lnbits",
  "backendHealthy": true,
  "serviceName": "my-api",
  "protectedEndpoints": [
    {
      "method": "GET",
      "path": "/api/v1/premium",
      "priceSats": 10,
      "timeoutSeconds": 3600,
      "description": "Premium content",
      "pricingStrategy": null
    }
  ],
  "credentials": {
    "active": 42,
    "maxSize": 10000
  },
  "earnings": {
    "totalInvoicesCreated": 150,
    "totalInvoicesSettled": 98,
    "totalSatsEarned": 980,
    "note": "In-memory only; resets on application restart"
  }
}
```

**Security warning:** This endpoint exposes operational data including backend health, protected endpoint paths with pricing, active credential counts, and earnings. In production, secure it behind authentication or restrict it to the management port.

---

## Micrometer Metrics

When Micrometer is on the classpath and a `MeterRegistry` bean exists, `L402MetricsAutoConfiguration` creates an `L402Metrics` bean that registers the following metrics:

### Gauges (registered eagerly)

| Metric | Description |
|--------|-------------|
| `l402.credentials.active` | Currently cached credentials |
| `l402.lightning.healthy` | `1.0` if the Lightning backend is healthy, `0.0` otherwise |

### Counters (recorded by the security filter)

| Metric | Tags | Description |
|--------|------|-------------|
| `l402.requests` | `endpoint`, `result` (`challenged`, `passed`, `rejected`) | Total requests to protected endpoints |
| `l402.invoices.created` | `endpoint` | Invoices generated |
| `l402.invoices.settled` | `endpoint` | Invoices paid and verified |
| `l402.revenue.sats` | `endpoint` | Total satoshis earned |

The `L402Metrics` bean is injected into the `L402SecurityFilter` via setter injection. If Micrometer is not on the classpath, no metrics are recorded and the filter operates without any metrics overhead.

---

## Test Mode

Test mode provides a fully functional L402 flow without requiring a real Lightning node. When `l402.test-mode=true`, `TestModeAutoConfiguration` provides a `TestModeLightningBackend` that:

- `createInvoice()`: Generates a random preimage, computes `paymentHash = SHA-256(preimage)`, and returns a dummy invoice with a fake bolt11 string. The preimage is included on the `Invoice` so the 402 response body contains a `test_preimage` field, enabling end-to-end testing with curl.
- `lookupInvoice()`: Always returns `InvoiceStatus.SETTLED` with the correct preimage.
- `isHealthy()`: Always returns `true`.

### Production Safety Guards

Test mode has a two-layer guard to prevent accidental production use:

1. **Denylist (belt):** If any active Spring profile matches `production` or `prod` (case-insensitive), startup fails immediately with `IllegalStateException`, even if an allowed profile is also active.
2. **Allowlist (suspenders):** At least one of `test`, `dev`, `local`, or `development` must be an active profile. This catches custom production profile names like `prd`, `live`, or `staging` that would bypass the denylist.

### Usage

```yaml
# application-dev.yml
l402:
  enabled: true
  test-mode: true
  root-key-store: memory
  service-name: my-api
```

```bash
# Run with a dev profile
SPRING_PROFILES_ACTIVE=dev ./gradlew :l402-example-app:bootRun
```

### Curl End-to-End Test

```bash
# 1. Hit a protected endpoint -- get a 402 with an invoice and test_preimage
curl -i http://localhost:8080/api/v1/premium

# 2. Parse the macaroon from WWW-Authenticate header and test_preimage from body
#    Build the Authorization header: L402 <macaroon>:<preimage_hex>
curl -H "Authorization: L402 <macaroon_base64>:<preimage_hex>" \
     http://localhost:8080/api/v1/premium
```

---

## Dynamic Pricing

Endpoints can use dynamic pricing by specifying a pricing strategy bean name in the `@L402Protected` annotation:

```java
@L402Protected(priceSats = 10, pricingStrategy = "surgePricing")
@GetMapping("/api/v1/premium")
public Map<String, String> premium() {
    return Map.of("data", "premium content");
}
```

The strategy bean must implement `L402PricingStrategy`:

```java
@FunctionalInterface
public interface L402PricingStrategy {
    long calculatePrice(HttpServletRequest request, long defaultPrice);
}
```

```java
@Bean("surgePricing")
public L402PricingStrategy surgePricing() {
    return (request, defaultPrice) -> {
        // Double the price during peak hours
        int hour = LocalTime.now().getHour();
        return (hour >= 9 && hour <= 17) ? defaultPrice * 2 : defaultPrice;
    };
}
```

If the named bean does not exist or throws an exception, the filter falls back to the static `priceSats` value from the annotation. A warning is logged.

---

## Overriding Default Beans

Every bean created by the auto-configuration is guarded with `@ConditionalOnMissingBean`. To override any component, define your own bean of the same type in a `@Configuration` class.

### Override the Root Key Store

```java
@Configuration
public class CustomKeyStoreConfig {

    @Bean
    public RootKeyStore rootKeyStore() {
        return new MyDatabaseBackedRootKeyStore(dataSource);
    }
}
```

### Override the Credential Store

```java
@Configuration
public class CustomCredentialStoreConfig {

    @Bean
    public CredentialStore credentialStore() {
        return new RedisCredentialStore(redisTemplate);
    }
}
```

### Override the Lightning Backend

```java
@Configuration
public class CustomBackendConfig {

    @Bean
    public LightningBackend lightningBackend(ObjectMapper objectMapper) {
        var config = new LnbitsConfig("https://lnbits.example.com", System.getenv("LNBITS_API_KEY"));
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return new LnbitsBackend(config, objectMapper, httpClient);
    }
}
```

### Override the Validator

```java
@Configuration
public class CustomValidatorConfig {

    @Bean
    public L402Validator l402Validator(RootKeyStore rootKeyStore,
                                       CredentialStore credentialStore) {
        return new L402Validator(rootKeyStore, credentialStore,
                List.of(new ServicesCaveatVerifier(), new MyCustomCaveatVerifier()),
                "my-service");
    }
}
```

### Override Caveat Verifiers

```java
@Configuration
public class CustomCaveatConfig {

    @Bean("caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers() {
        return List.of(
                new ServicesCaveatVerifier(),
                new ValidUntilCaveatVerifier("my-service"),
                new MyCustomCaveatVerifier()
        );
    }
}
```

### Override the LND gRPC Channel

```java
@Configuration
public class CustomLndChannelConfig {

    @Bean
    public ManagedChannel lndManagedChannel() {
        return ManagedChannelBuilder.forAddress("lnd.example.com", 10009)
                .usePlaintext()
                .build();
    }
}
```

---

## IDE Autocomplete Support

The module includes `META-INF/additional-spring-configuration-metadata.json` which provides IDE autocomplete, type information, descriptions, default values, and value hints for all `l402.*` configuration properties. This works out of the box in IntelliJ IDEA, VS Code with Spring Boot extensions, and Eclipse with STS.

The metadata includes value hints for enum-like properties:

- `l402.backend`: `lnbits`, `lnd`
- `l402.root-key-store`: `file`, `memory`
- `l402.credential-cache`: `caffeine`, `none`

The `spring-boot-configuration-processor` annotation processor is configured in `build.gradle.kts` to generate additional metadata from `L402Properties` at compile time.

---

## Architecture

### Package Structure

```
l402-spring-autoconfigure/
  src/main/java/com/greenharborlabs/l402/spring/
    L402AutoConfiguration.java            Core auto-configuration (all bean definitions)
    L402ActuatorAutoConfiguration.java    Actuator endpoint auto-configuration
    L402MetricsAutoConfiguration.java     Micrometer metrics auto-configuration
    TestModeAutoConfiguration.java        Test-mode auto-configuration
    L402Properties.java                   @ConfigurationProperties binding class
    L402SecurityFilter.java               Jakarta Servlet Filter enforcing L402
    L402EndpointRegistry.java             Registry of @L402Protected endpoints
    L402EndpointConfig.java               Immutable endpoint configuration record
    L402Protected.java                    Annotation for marking protected endpoints
    L402Validator                         (from l402-core) Wired as a bean
    CaffeineCredentialStore.java          Caffeine-backed CredentialStore
    CachingLightningBackendWrapper.java   Health-check caching decorator
    L402LightningHealthIndicator.java     Actuator HealthIndicator
    L402ActuatorEndpoint.java             Custom /actuator/l402 endpoint
    L402Metrics.java                      Micrometer metric definitions
    L402EarningsTracker.java              In-memory invoice/earnings tracker
    L402RateLimiter.java                  @FunctionalInterface for rate limiting
    TokenBucketRateLimiter.java           IP-based token bucket implementation
    L402PricingStrategy.java              @FunctionalInterface for dynamic pricing
    TestModeLightningBackend.java         Dummy backend for test mode
    MacaroonClientInterceptor.java        gRPC interceptor for LND macaroon auth
  src/main/resources/META-INF/
    spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    additional-spring-configuration-metadata.json
```

### Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `l402-core` | `api` | Core interfaces (`LightningBackend`, `RootKeyStore`, `CredentialStore`, `L402Validator`, etc.) |
| `spring-boot-autoconfigure` | `implementation` | `@AutoConfiguration`, `@ConditionalOn*`, `@ConfigurationProperties` |
| `spring-webmvc` | `implementation` | `RequestMappingHandlerMapping` for annotation scanning |
| `jakarta.servlet-api` | `compileOnly` | Servlet `Filter` API |
| `caffeine` | `compileOnly` | Optional; used when on the classpath for `CaffeineCredentialStore` |
| `micrometer-core` | `compileOnly` | Optional; used when on the classpath for `L402Metrics` |
| `spring-boot-actuator-autoconfigure` | `compileOnly` | Optional; used when on the classpath for health indicators and actuator endpoints |
| `spring-boot-health` | `compileOnly` | Optional; `HealthIndicator` interface |
| `l402-lightning-lnbits` | `compileOnly` | Optional; `LnbitsBackend` class detection |
| `l402-lightning-lnd` | `compileOnly` | Optional; `LndBackend` class detection |
| `grpc-netty-shaded`, `grpc-stub` | `compileOnly` | Optional; gRPC channel construction for LND |
| `jackson-databind` | `compileOnly` | Optional; used by LNbits backend for JSON |
| `spring-boot-configuration-processor` | `annotationProcessor` | Generates configuration metadata at compile time |

Lightning backend modules and Caffeine are `compileOnly` -- consumers bring whichever ones they need at runtime.

---

## Testing

### Running the Tests

```bash
./gradlew :l402-spring-autoconfigure:test
```

### Test Architecture

Tests use Spring Boot's `WebApplicationContextRunner` to spin up the auto-configuration in isolation, verifying that beans are created (or not created) under various property and classpath conditions. No real Lightning backend or Caffeine cache is required -- tests use stub implementations.

### Test Coverage

| Test Class | What It Verifies |
|------------|-----------------|
| `AutoConfigurationTest` | All expected beans are created when `l402.enabled=true`; correct `RootKeyStore` type for `l402.root-key-store=memory` |
| `DisabledConfigTest` | No L402 beans when `l402.enabled=false` or property is absent |
| `BeanOverrideTest` | Custom `RootKeyStore` and `L402Validator` beans suppress auto-configured defaults |
| `CacheConditionalTest` | `CaffeineCredentialStore` vs `InMemoryCredentialStore` selection based on classpath |
| `CaffeineCredentialStoreTest` | Per-entry TTL, max size, store/get/revoke/activeCount operations |
| `CachingLightningBackendWrapperTest` | Health caching TTL, delegation for `createInvoice`/`lookupInvoice`, null/negative TTL rejection |
| `L402SecurityFilterTest` | Full filter flow: pass-through for unprotected paths, 402 challenge, credential validation, 503 on backend failure, header sanitization |
| `L402SecurityFilterRealStoreTest` | End-to-end filter test with real `FileBasedRootKeyStore` |
| `FailClosedTest` | HTTP 503 when Lightning backend is unhealthy; protected content never leaks |
| `L402RateLimitingTest` | Rate limiting integration with the security filter; 429 responses |
| `TokenBucketRateLimiterTest` | Token bucket algorithm: burst, refill, stale cleanup, max buckets cap |
| `TestModeConfigTest` | `TestModeLightningBackend` is created when `l402.test-mode=true`; not created when `false` |
| `TestModeLightningBackendTest` | Dummy invoice creation, always-settled lookup, preimage/hash consistency |
| `TestModeProductionGuardTest` | Startup fails with `prod`/`production` profiles; startup fails without allowed profiles |
| `L402LightningHealthIndicatorTest` | Health UP/DOWN reporting, caching, exception handling |
| `L402ActuatorEndpointTest` | Actuator response structure with endpoint list, credentials, and earnings |
| `L402MetricsTest` | Micrometer counter and gauge registration and increment |
| `LsatChallengeSchemeTest` | Backward compatibility with `LSAT` prefix in Authorization header |
| `DynamicPricingTest` | Pricing strategy bean lookup and fallback to static price |
| `PricingFallbackTest` | Fallback behavior when pricing strategy bean is missing |

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
