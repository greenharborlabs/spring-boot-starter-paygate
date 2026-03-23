# paygate-spring-autoconfigure

Spring Boot auto-configuration module for the `spring-boot-starter-paygate` project. This module wires up all payment protocol components -- Lightning backends, root key stores, credential caches, the security filter, health indicators, rate limiting, metrics, actuator endpoints, and dual-protocol support (L402 + MPP) -- based on application properties under the `paygate.*` prefix.

You do not use this module directly. Instead, add the `paygate-spring-boot-starter` dependency, which pulls in this module along with `paygate-core`. Then add whichever Lightning backend module you need (`paygate-lightning-lnbits` or `paygate-lightning-lnd`) and set `paygate.enabled=true`.

---

## Table of Contents

- [How Auto-Configuration Works](#how-auto-configuration-works)
- [Dual-Protocol Auto-Configuration](#dual-protocol-auto-configuration)
- [Configuration Properties](#configuration-properties)
- [Bean Creation and Conditions](#bean-creation-and-conditions)
- [Lightning Backend Selection](#lightning-backend-selection)
- [Root Key Store](#root-key-store)
- [@PaymentRequired Annotation](#paymentrequired-annotation)
- [PaygateResponseWriter](#paygateresponsewriter)
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
| `PaygateAutoConfiguration` | `paygate.enabled=true` | Core beans: root key store, credential cache, caveat verifiers, validator, endpoint registry, rate limiter, security filter, earnings tracker, Lightning backend |
| `PaygateActuatorAutoConfiguration` | Actuator on classpath + `PaygateEndpointRegistry` bean present + `paygate.actuator.enabled!=false` | Actuator endpoint at `/actuator/paygate` |
| `PaygateMetricsAutoConfiguration` | Micrometer on classpath + `MeterRegistry` bean present + `paygate.enabled=true` | Micrometer counters and gauges |
| `TestModeAutoConfiguration` | `paygate.test-mode=true` (runs **before** `PaygateAutoConfiguration`) | Dummy Lightning backend for development |

When `paygate.enabled` is `false` or absent, no L402 beans are created. The entire auto-configuration is skipped and all endpoints are accessible without payment.

All beans are guarded with `@ConditionalOnMissingBean`, so you can override any component by defining your own bean of the same type.

---

## Dual-Protocol Auto-Configuration

The auto-configuration supports two payment protocols that can run simultaneously: L402 and MPP (Modern Payment Protocol). Protocol beans are created by nested `@Configuration` classes inside `PaygateAutoConfiguration`.

### L402ProtocolConfiguration

- **Condition:** `L402Protocol` class on classpath + `paygate.protocols.l402.enabled=true` (default)
- **Bean:** `l402Protocol` (`PaymentProtocol`) -- wraps `L402Validator` and the configured service name
- L402 uses macaroons with HMAC-SHA256 signature chains and standard base64 encoding

### MppProtocolConfiguration

- **Condition:** `MppProtocol` class on classpath + `MppEnabledCondition` matches
- **Bean:** `mppProtocol` (`PaymentProtocol`) -- initialized with the challenge binding secret
- MPP uses HMAC-SHA256 challenge binding with base64url encoding (no padding) and RFC 8785 JCS

### MppEnabledCondition

The MPP protocol has a three-state enable flag:

| `paygate.protocols.mpp.enabled` | Behavior |
|----------------------------------|----------|
| `false` | MPP is disabled |
| `true` | MPP is enabled; startup fails if `challenge-binding-secret` is missing or too short |
| `auto` (default) | MPP is enabled only if `challenge-binding-secret` is present and non-blank |

### ProtocolStartupValidator

Created unconditionally by `PaygateAutoConfiguration`. Validates at startup that:

1. If `paygate.protocols.mpp.enabled=true`, the `challenge-binding-secret` must be present
2. If a secret is present, it must be at least 32 UTF-8 bytes
3. At least one protocol must be active (when protocol JARs are on the classpath)

Startup fails with `IllegalStateException` if any validation fails.

### Protocol Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.protocols.l402.enabled` | `boolean` | `true` | Enable/disable the L402 protocol |
| `paygate.protocols.mpp.enabled` | `string` | `auto` | `auto` enables MPP when secret is present; `true` requires secret; `false` disables |
| `paygate.protocols.mpp.challenge-binding-secret` | `string` | -- | HMAC secret for MPP challenge binding. Minimum 32 UTF-8 bytes. |

### Delegation Caveat Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.caveat.max-values-per-caveat` | `int` | `50` | Maximum comma-separated values per delegation caveat (path, method, client_ip) |
| `paygate.trusted-proxy-addresses` | `List<String>` | empty | Trusted reverse proxy IPs for X-Forwarded-For resolution by `ClientIpResolver` |

---

## Configuration Properties

All properties are bound from the `paygate.*` namespace via `PaygateProperties`.

### Core Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.enabled` | `boolean` | `false` | Master switch. When `false`, no L402 beans are registered. |
| `paygate.backend` | `string` | -- | Lightning backend to use: `lnbits` or `lnd`. Must match a backend module on the classpath. |
| `paygate.service-name` | `string` | `"default"` | Logical service name embedded in macaroon caveats. Used by `ServicesCaveatVerifier` for service-scoped authorization. |
| `paygate.default-price-sats` | `long` | `10` | Default price in satoshis for payment-protected endpoints. Individual endpoints override this via `@PaymentRequired(priceSats = ...)`. |
| `paygate.default-timeout-seconds` | `long` | `3600` | Default invoice expiry in seconds. |
| `paygate.test-mode` | `boolean` | `false` | Enables test mode with an in-memory Lightning backend. Must not be used in production. See [Test Mode](#test-mode). |
| `paygate.trust-forwarded-headers` | `boolean` | `false` | Whether to read `X-Forwarded-For` for client IP resolution. Enable only behind a trusted reverse proxy. See [Rate Limiting](#rate-limiting). |

### Root Key Store Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.root-key-store` | `string` | `"file"` | Storage backend for macaroon signing keys. `file` persists to disk; `memory` keeps keys in-memory (lost on restart). |
| `paygate.root-key-store-path` | `string` | `"~/.paygate/keys"` | File system path for the file-based root key store. Supports `~` expansion. The directory is created automatically. Only used when `paygate.root-key-store=file`. |

### Credential Cache Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.credential-cache-max-size` | `int` | `10000` | Maximum number of validated credentials to cache. Higher values use more memory but reduce re-verification overhead. |

### Rate Limiting Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.rate-limit.requests-per-second` | `double` | `10.0` | Token refill rate per second per client IP. Controls the sustained rate of 402 challenge issuance. |
| `paygate.rate-limit.burst-size` | `int` | `20` | Maximum burst capacity per client IP. Allows short bursts above the sustained rate before throttling. |

### Health Cache Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.health-cache.enabled` | `boolean` | `true` | Whether to cache `isHealthy()` results from the Lightning backend. |
| `paygate.health-cache.ttl-seconds` | `int` | `5` | How long health check results are cached, in seconds. |

### LNbits Backend Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `paygate.lnbits.url` | `string` | -- | When `paygate.backend=lnbits` | Base URL of the LNbits instance. |
| `paygate.lnbits.api-key` | `string` | -- | When `paygate.backend=lnbits` | LNbits Invoice/read API key. Keep this value secret. |
| `paygate.lnbits.request-timeout-seconds` | `Integer` | -- | No | Per-request HTTP timeout override. |
| `paygate.lnbits.connect-timeout-seconds` | `Integer` | -- | No | Connection timeout override. |

### LND Backend Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `paygate.lnd.host` | `string` | `"localhost"` | When `paygate.backend=lnd` | Hostname or IP of the LND gRPC endpoint. |
| `paygate.lnd.port` | `int` | `10009` | When `paygate.backend=lnd` | Port of the LND gRPC endpoint. |
| `paygate.lnd.tls-cert-path` | `string` | -- | When `paygate.backend=lnd` (unless plaintext) | Path to the LND TLS certificate (`tls.cert`). |
| `paygate.lnd.macaroon-path` | `string` | -- | No | Path to the LND admin macaroon file (`admin.macaroon`). |
| `paygate.lnd.allow-plaintext` | `boolean` | `false` | No | Allow plaintext gRPC (no TLS). For local development only. |
| `paygate.lnd.keep-alive-time-seconds` | `int` | `60` | No | gRPC keepalive ping interval. |
| `paygate.lnd.keep-alive-timeout-seconds` | `int` | `20` | No | Keepalive ping ack timeout. |
| `paygate.lnd.idle-timeout-minutes` | `int` | `5` | No | Idle connection timeout. |
| `paygate.lnd.max-inbound-message-size` | `int` | `4194304` | No | Max inbound gRPC message size. |
| `paygate.lnd.rpc-deadline-seconds` | `Integer` | -- | No | Per-call gRPC deadline. |

### Example application.yml

```yaml
paygate:
  enabled: true
  backend: lnbits
  service-name: my-api
  default-price-sats: 10
  default-timeout-seconds: 3600
  root-key-store: file
  root-key-store-path: ~/.paygate/keys
  credential-cache-max-size: 10000
  caveat:
    max-values-per-caveat: 50
  rate-limit:
    requests-per-second: 10.0
    burst-size: 20
  health-cache:
    enabled: true
    ttl-seconds: 5
  protocols:
    l402:
      enabled: true
    mpp:
      enabled: auto
      challenge-binding-secret: ${PAYGATE_MPP_SECRET:}
  lnbits:
    url: https://lnbits.example.com
    api-key: ${LNBITS_API_KEY}
```

---

## Bean Creation and Conditions

The following table shows every bean created by `PaygateAutoConfiguration`, the conditions that must be met, and the type created.

| Bean | Type | Condition | Notes |
|------|------|-----------|-------|
| `rootKeyStore` | `RootKeyStore` | `@ConditionalOnMissingBean` | `InMemoryRootKeyStore` when `paygate.root-key-store=memory`; `FileBasedRootKeyStore` otherwise |
| `credentialStore` | `CredentialStore` | `@ConditionalOnMissingBean` + Caffeine class check | `CaffeineCredentialStore` if Caffeine is on the classpath; `InMemoryCredentialStore` otherwise |
| `caveatVerifiers` | `List<CaveatVerifier>` | `@ConditionalOnMissingBean(name="caveatVerifiers")` | `[ServicesCaveatVerifier, ValidUntilCaveatVerifier, CapabilitiesCaveatVerifier, PathCaveatVerifier, MethodCaveatVerifier, ClientIpCaveatVerifier]` |
| `l402Validator` | `L402Validator` | `@ConditionalOnMissingBean` | Wires root key store, credential store, caveat verifiers, and service name |
| `l402Protocol` | `PaymentProtocol` | `L402Protocol` on classpath + `paygate.protocols.l402.enabled=true` + `@ConditionalOnMissingBean(name="l402Protocol")` | L402 protocol implementation wrapping `L402Validator` |
| `mppProtocol` | `PaymentProtocol` | `MppProtocol` on classpath + `MppEnabledCondition` + `@ConditionalOnMissingBean(name="mppProtocol")` | MPP protocol implementation with HMAC challenge binding secret |
| `protocolStartupValidator` | `ProtocolStartupValidator` | Always (when auto-config is active) | Validates protocol configuration and secret requirements at startup |
| `clientIpResolver` | `ClientIpResolver` | `@ConditionalOnMissingBean` | Resolves client IP from request, with optional X-Forwarded-For and trusted proxy support |
| `l402EndpointRegistry` | `PaygateEndpointRegistry` | `@ConditionalOnMissingBean` | Scans `@PaymentRequired` annotations from Spring MVC handler mappings |
| `l402RateLimiter` | `PaygateRateLimiter` | `@ConditionalOnMissingBean` | `TokenBucketRateLimiter` with configured burst size and refill rate |
| `l402EarningsTracker` | `PaygateEarningsTracker` | `@ConditionalOnMissingBean` | In-memory tracker; resets on restart |
| `l402ChallengeService` | `PaygateChallengeService` | `@ConditionalOnMissingBean` | Encapsulates challenge generation and invoice creation logic |
| `l402SecurityModeResolver` | `PaygateSecurityModeResolver` | `@ConditionalOnMissingBean` | Determines which security integration mode to use (servlet filter vs Spring Security) |
| `l402SecurityFilter` | `PaygateSecurityFilter` | `@ConditionalOnMissingBean` | The core servlet filter. Receives `List<PaymentProtocol>` and iterates over all registered protocols to match credentials. Metrics, earnings tracker, and rate limiter are optional. |
| `l402SecurityFilterRegistration` | `FilterRegistrationBean<PaygateSecurityFilter>` | Always (when auto-config is active) | Registered at `Ordered.HIGHEST_PRECEDENCE + 10`, matching `/*` |
| `lightningBackend` (LNbits) | `LightningBackend` | `paygate.backend=lnbits` + `LnbitsBackend` on classpath + `@ConditionalOnMissingBean` | Creates `LnbitsBackend` with 10-second connect timeout `HttpClient` |
| `lightningBackend` (LND) | `LightningBackend` | `paygate.backend=lnd` + `LndBackend` on classpath + `@ConditionalOnMissingBean` | Creates gRPC `ManagedChannel` and `LndBackend` |
| `lndManagedChannel` | `ManagedChannel` | `paygate.backend=lnd` + `@ConditionalOnMissingBean(ManagedChannel.class)` | TLS with optional macaroon interceptor. `destroyMethod="shutdown"` |

### Ordering

`TestModeAutoConfiguration` runs **before** `PaygateAutoConfiguration` (via `@AutoConfiguration(before = ...)`). This ensures the test-mode `LightningBackend` bean is registered first, and `PaygateAutoConfiguration` finds it via `@ConditionalOnMissingBean`.

`PaygateActuatorAutoConfiguration` and `PaygateMetricsAutoConfiguration` run **after** `PaygateAutoConfiguration` (via `@AutoConfiguration(after = ...)`), because they depend on beans created by the core auto-configuration.

---

## Lightning Backend Selection

The Lightning backend is selected by the `paygate.backend` property combined with classpath detection:

| `paygate.backend` | Required on classpath | Bean created |
|----------------|-----------------------|--------------|
| `lnbits` | `paygate-lightning-lnbits` module | `LnbitsBackend` (via `LnbitsBackendConfiguration`) |
| `lnd` | `paygate-lightning-lnd` module + gRPC dependencies | `LndBackend` (via `LndBackendConfiguration`) |
| (test mode) | Nothing extra | `TestModeLightningBackend` (via `TestModeAutoConfiguration`) |

If `paygate.backend` is set but the corresponding module is not on the classpath, no `LightningBackend` bean is created, and the `PaygateSecurityFilter` bean will fail to initialize because its required dependency is missing.

### LND Channel Configuration

When `paygate.backend=lnd`, the auto-configuration builds a gRPC `ManagedChannel`:

- If `paygate.lnd.tls-cert-path` is set, the channel uses TLS with the provided certificate. If `paygate.lnd.macaroon-path` is also set, a `MacaroonClientInterceptor` attaches the macaroon hex as gRPC metadata on every call.
- If `paygate.lnd.tls-cert-path` is not set and `paygate.lnd.allow-plaintext=true`, a plaintext channel is created (a warning is logged). This is only suitable for local development.
- If neither TLS cert nor plaintext is configured, startup fails with `IllegalStateException`.

---

## Root Key Store

The root key store holds the signing keys for macaroons. Two implementations are available:

| `paygate.root-key-store` | Implementation | Persistence |
|-----------------------|----------------|-------------|
| `file` (default) | `FileBasedRootKeyStore` | Keys are written to `paygate.root-key-store-path` (default `~/.paygate/keys`). Survives restarts. |
| `memory` | `InMemoryRootKeyStore` | Keys exist only in memory. All issued credentials become invalid on restart. |

The `~` prefix in `paygate.root-key-store-path` is expanded to `System.getProperty("user.home")`.

---

## Credential Cache

Validated L402 credentials are cached to avoid re-verifying macaroon signatures and re-querying the Lightning backend on every request with the same credential.

| Caffeine on classpath? | Implementation | Behavior |
|------------------------|----------------|----------|
| Yes | `CaffeineCredentialStore` | Per-entry TTL based on the credential's `valid_until` caveat. Bounded by `paygate.credential-cache-max-size`. |
| No | `InMemoryCredentialStore` | Simple `ConcurrentHashMap`-based store with configurable max size. |

`CaffeineCredentialStore` uses Caffeine's variable expiry API (`Expiry`) so each cached credential expires independently based on its own TTL, rather than a global expiration.

---

## @PaymentRequired Annotation

`@PaymentRequired` marks controller methods as requiring payment. The `PaygateSecurityFilter` discovers annotated methods at startup and enforces payment for matching requests.

```java
@PaymentRequired(priceSats = 5, description = "Premium quote of the day")
@GetMapping("/api/v1/quote")
public QuoteResponse quote() { ... }
```

**Attributes:** `priceSats`, `timeoutSeconds` (default `-1` for global default), `description`, `pricingStrategy`, `capability`.

`PaygateEndpointRegistry` scans for `@PaymentRequired` annotations during `scanAnnotatedEndpoints()`.

---

## PaygateResponseWriter

Static utility class for writing HTTP error responses in a consistent JSON format. All methods are static; the class is not instantiable. Used by both `PaygateSecurityFilter` and the Spring Security integration.

| Method | HTTP Status | When |
|--------|-------------|------|
| `writePaymentRequired(response, context, challenges)` | 402 | Multi-protocol 402 challenge. Each protocol adds a separate `WWW-Authenticate` header via `addHeader`. Response body includes `protocols` map with per-protocol data. |
| `writePaymentRequired(response, result)` | 402 | Single-protocol (legacy L402) 402 challenge. |
| `writeMalformedHeader(response, message, tokenId)` | 400 | Malformed `Authorization` header. |
| `writeValidationError(response, errorCode, message, tokenId)` | varies | L402 credential validation failure. Status from `ErrorCode`. |
| `writeMppError(response, exception, challenges)` | varies | MPP validation failure using RFC 9457 Problem Details format. Includes fresh challenges for 402 responses. |
| `writeRateLimited(response)` | 429 | Rate limit exceeded. Includes `Retry-After: 1` header. |
| `writeLightningUnavailable(response)` | 503 | Lightning backend is down. |
| `writeReceipt(response, receipt)` | -- | Sets `Payment-Receipt` header with base64url-nopad encoded JSON receipt. |
| `writeMethodUnsupported(response, message)` | 400 | Unsupported payment method (RFC 9457 Problem Details). |

---

## Security Filter

`PaygateSecurityFilter` is a standard Jakarta Servlet `Filter` that enforces payment authentication on protected endpoints. It is registered at `Ordered.HIGHEST_PRECEDENCE + 10` and matches all URL patterns (`/*`). The filter iterates over all registered `PaymentProtocol` beans (`List<PaymentProtocol>`) to find one that can handle the incoming `Authorization` header.

### Request Flow

1. **Match**: The filter checks if the request path and HTTP method match any `@PaymentRequired` endpoint in the `PaygateEndpointRegistry`. If no match, the request passes through untouched.
2. **Credential validation**: If the `Authorization` header contains an `L402` or `LSAT` prefix, the credential is validated locally via `L402Validator` (no Lightning network call needed). Valid credentials pass through; malformed headers get HTTP 400; expired or invalid credentials get the appropriate error status.
3. **Health check**: If no valid credential is present, the Lightning backend's `isHealthy()` is checked. If the backend is down, the filter returns HTTP 503 (fail-closed).
4. **Rate limit check**: Before creating an invoice, the filter checks the `PaygateRateLimiter`. If the client IP has exceeded the rate limit, HTTP 429 is returned with a `Retry-After: 1` header.
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

- Each client IP gets an independent token bucket with `paygate.rate-limit.burst-size` maximum tokens and a refill rate of `paygate.rate-limit.requests-per-second` tokens per second.
- New buckets start full. Each challenge request consumes one token.
- When a bucket is empty, the client receives HTTP 429 until tokens refill.
- Stale entries are cleaned up lazily every 1000 `tryAcquire` calls. The total number of tracked IPs is capped at 100,000 to prevent memory exhaustion.

### Client IP Resolution

By default, the filter uses `request.getRemoteAddr()` for rate limiting. If the service runs behind a reverse proxy:

- Set `paygate.trust-forwarded-headers=true` to read the client IP from the `X-Forwarded-For` header (leftmost value).
- When an `X-Forwarded-For` header is detected but `trust-forwarded-headers` is `false`, the filter logs a one-time warning suggesting you enable it.
- When `trust-forwarded-headers` is `false`, spoofed `X-Forwarded-For` headers are ignored, preventing rate limit bypass.

### Overriding the Rate Limiter

Provide your own `PaygateRateLimiter` bean to replace the default `TokenBucketRateLimiter`:

```java
@Bean
public PaygateRateLimiter l402RateLimiter() {
    return key -> true; // disable rate limiting
}
```

The `PaygateRateLimiter` interface is `@FunctionalInterface` with a single method: `boolean tryAcquire(String key)`.

---

## Health Cache

When `paygate.health-cache.enabled=true` (the default), a `BeanPostProcessor` wraps every `LightningBackend` bean in a `CachingLightningBackendWrapper`. This caches the result of `isHealthy()` for `paygate.health-cache.ttl-seconds` (default 5 seconds) to avoid hammering the Lightning node on every request to a protected endpoint.

- `createInvoice()` and `lookupInvoice()` are **not** cached -- they always delegate directly to the underlying backend.
- `TestModeLightningBackend` is excluded from wrapping (it is already a stub).
- Thread safety is achieved via a single `volatile` snapshot record.

To disable health caching, set `paygate.health-cache.enabled=false`.

---

## Health Indicator (Actuator)

When Spring Boot Actuator is on the classpath, `PaygateActuatorAutoConfiguration` registers an `PaygateLightningHealthIndicator` that reports the Lightning backend status in the `/actuator/health` endpoint.

The health indicator caches its own result using the `paygate.health-cache.ttl-seconds` value (in milliseconds) to avoid redundant checks during actuator scrapes.

| Health Status | Condition |
|---------------|-----------|
| `UP` | `backend.isHealthy()` returns `true`. Detail: `backend=reachable` |
| `DOWN` | `backend.isHealthy()` returns `false`. Detail: `backend=unreachable` |
| `DOWN` | `backend.isHealthy()` throws an exception. Detail: `backend=error` |

---

## Actuator Endpoint

When Spring Boot Actuator is on the classpath, a custom endpoint is available at `GET /actuator/paygate` via `PaygateActuatorEndpoint`. It is enabled by default and can be disabled with `paygate.actuator.enabled=false`.

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

When Micrometer is on the classpath and a `MeterRegistry` bean exists, `PaygateMetricsAutoConfiguration` creates an `PaygateMetrics` bean that registers the following metrics:

### Gauges (registered eagerly)

| Metric | Description |
|--------|-------------|
| `paygate.credentials.active` | Currently cached credentials |
| `paygate.lightning.healthy` | `1.0` if the Lightning backend is healthy, `0.0` otherwise |

### Counters (recorded by the security filter)

| Metric | Tags | Description |
|--------|------|-------------|
| `paygate.requests` | `endpoint`, `result` (`challenged`, `passed`, `rejected`), `protocol` | Total requests to protected endpoints. The `protocol` tag identifies which protocol handled the request (e.g., `L402`, `Payment`, `all`). |
| `paygate.invoices.created` | `endpoint`, `protocol` | Invoices generated |
| `paygate.invoices.settled` | `endpoint`, `protocol` | Invoices paid and verified |
| `paygate.revenue.sats` | `endpoint`, `protocol` | Total satoshis earned |
| `paygate.caveats.rejected` | `caveat_type` (`path`, `method`, `client_ip`, `escalation`, `unknown`), `protocol` | Caveat verification rejections by type |
| `paygate.cache.evictions` | `reason` | Credential cache evictions by reason |

### Timers

| Metric | Tags | Description |
|--------|------|-------------|
| `paygate.caveats.verify.duration` | `protocol` | Duration of caveat verification per request |

The `PaygateMetrics` bean is injected into the `PaygateSecurityFilter` via setter injection. If Micrometer is not on the classpath, no metrics are recorded and the filter operates without any metrics overhead.

---

## Test Mode

Test mode provides a fully functional L402 flow without requiring a real Lightning node. When `paygate.test-mode=true`, `TestModeAutoConfiguration` provides a `TestModeLightningBackend` that:

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
paygate:
  enabled: true
  test-mode: true
  root-key-store: memory
  service-name: my-api
```

```bash
# Run with a dev profile
SPRING_PROFILES_ACTIVE=dev ./gradlew :paygate-example-app:bootRun
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

Endpoints can use dynamic pricing by specifying a pricing strategy bean name in the `@PaymentRequired` annotation:

```java
@PaymentRequired(priceSats = 10, pricingStrategy = "surgePricing")
@GetMapping("/api/v1/premium")
public Map<String, String> premium() {
    return Map.of("data", "premium content");
}
```

The strategy bean must implement `PaygatePricingStrategy`:

```java
@FunctionalInterface
public interface PaygatePricingStrategy {
    long calculatePrice(HttpServletRequest request, long defaultPrice);
}
```

```java
@Bean("surgePricing")
public PaygatePricingStrategy surgePricing() {
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

The module includes `META-INF/additional-spring-configuration-metadata.json` which provides IDE autocomplete, type information, descriptions, default values, and value hints for all `paygate.*` configuration properties. This works out of the box in IntelliJ IDEA, VS Code with Spring Boot extensions, and Eclipse with STS.

The metadata includes value hints for enum-like properties:

- `paygate.backend`: `lnbits`, `lnd`
- `paygate.root-key-store`: `file`, `memory`

The `spring-boot-configuration-processor` annotation processor is configured in `build.gradle.kts` to generate additional metadata from `PaygateProperties` at compile time.

---

## Architecture

### Package Structure

```
paygate-spring-autoconfigure/
  src/main/java/com/greenharborlabs/paygate/spring/
    PaygateAutoConfiguration.java            Core auto-configuration (all bean definitions)
      L402ProtocolConfiguration              Nested: creates L402Protocol bean
      MppProtocolConfiguration               Nested: creates MppProtocol bean
      MppEnabledCondition                    Nested: three-state MPP enable condition
      ProtocolStartupValidator               Nested: validates protocol configuration
    PaygateActuatorAutoConfiguration.java    Actuator endpoint auto-configuration
    PaygateMetricsAutoConfiguration.java     Micrometer metrics auto-configuration
    TestModeAutoConfiguration.java           Test-mode auto-configuration
    PaygateProperties.java                   @ConfigurationProperties binding class
    PaygateChallengeService.java             Challenge generation and invoice creation
    PaygateResponseWriter.java               Static utility for writing HTTP error responses
    PaygateSecurityModeResolver.java         Resolves servlet filter vs Spring Security mode
    PaygateSecurityFilter.java               Jakarta Servlet Filter enforcing payment auth
    PaygateEndpointRegistry.java             Registry of @PaymentRequired endpoints
    PaygateEndpointConfig.java               Immutable endpoint configuration record
    PaymentRequired.java                     Annotation for marking protected endpoints
    ClientIpResolver.java                    Client IP resolution with X-Forwarded-For and trusted proxy support
    PaygatePathUtils.java                    Path normalization utilities
    L402Validator                            (from paygate-core) Wired as a bean
    CaffeineCredentialStore.java             Caffeine-backed CredentialStore
    CachingLightningBackendWrapper.java      Health-check caching decorator
    TimeoutEnforcingLightningBackendWrapper.java  Timeout wrapper for Lightning backend calls
    PaygateLightningHealthIndicator.java     Actuator HealthIndicator
    PaygateActuatorEndpoint.java             Custom /actuator/paygate endpoint
    PaygateMetrics.java                      Micrometer metric definitions
    PaygateMeterFilter.java                  Micrometer meter filter for endpoint cardinality
    PaygateEarningsTracker.java              In-memory invoice/earnings tracker
    PaygateRateLimiter.java                  @FunctionalInterface for rate limiting
    TokenBucketRateLimiter.java              IP-based token bucket implementation
    PaygatePricingStrategy.java              @FunctionalInterface for dynamic pricing
    TestModeLightningBackend.java            Dummy backend for test mode
    MacaroonClientInterceptor.java           gRPC interceptor for LND macaroon auth
  src/main/resources/META-INF/
    spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    additional-spring-configuration-metadata.json
```

### Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `paygate-core` | `api` | Core interfaces (`LightningBackend`, `RootKeyStore`, `CredentialStore`, `L402Validator`, etc.) |
| `paygate-api` | `api` | Protocol abstraction API (`PaymentProtocol`, `ChallengeContext`, `PaymentCredential`, etc.) |
| `paygate-protocol-l402` | `compileOnly` | Optional; L402 protocol implementation (classpath-detected) |
| `paygate-protocol-mpp` | `compileOnly` | Optional; MPP protocol implementation (classpath-detected) |
| `spring-boot-autoconfigure` | `implementation` | `@AutoConfiguration`, `@ConditionalOn*`, `@ConfigurationProperties` |
| `spring-webmvc` | `implementation` | `RequestMappingHandlerMapping` for annotation scanning |
| `jakarta.servlet-api` | `compileOnly` | Servlet `Filter` API |
| `caffeine` | `compileOnly` | Optional; used when on the classpath for `CaffeineCredentialStore` |
| `micrometer-core` | `compileOnly` | Optional; used when on the classpath for `PaygateMetrics` |
| `spring-boot-actuator-autoconfigure` | `compileOnly` | Optional; used when on the classpath for health indicators and actuator endpoints |
| `spring-boot-health` | `compileOnly` | Optional; `HealthIndicator` interface |
| `paygate-lightning-lnbits` | `compileOnly` | Optional; `LnbitsBackend` class detection |
| `paygate-lightning-lnd` | `compileOnly` | Optional; `LndBackend` class detection |
| `grpc-netty-shaded`, `grpc-stub` | `compileOnly` | Optional; gRPC channel construction for LND |
| `jackson-databind` | `compileOnly` | Optional; used by LNbits backend for JSON |
| `spring-boot-configuration-processor` | `annotationProcessor` | Generates configuration metadata at compile time |

Lightning backend modules, protocol modules, and Caffeine are `compileOnly` -- consumers bring whichever ones they need at runtime.

---

## Testing

### Running the Tests

```bash
./gradlew :paygate-spring-autoconfigure:test
```

### Test Architecture

Tests use Spring Boot's `WebApplicationContextRunner` to spin up the auto-configuration in isolation, verifying that beans are created (or not created) under various property and classpath conditions. No real Lightning backend or Caffeine cache is required -- tests use stub implementations.

### Test Coverage

| Test Class | What It Verifies |
|------------|-----------------|
| `AutoConfigurationTest` | All expected beans are created when `paygate.enabled=true`; correct `RootKeyStore` type for `paygate.root-key-store=memory` |
| `DisabledConfigTest` | No L402 beans when `paygate.enabled=false` or property is absent |
| `BeanOverrideTest` | Custom `RootKeyStore` and `L402Validator` beans suppress auto-configured defaults |
| `CacheConditionalTest` | `CaffeineCredentialStore` vs `InMemoryCredentialStore` selection based on classpath |
| `CaffeineCredentialStoreTest` | Per-entry TTL, max size, store/get/revoke/activeCount operations |
| `CachingLightningBackendWrapperTest` | Health caching TTL, delegation for `createInvoice`/`lookupInvoice`, null/negative TTL rejection |
| `PaygateSecurityFilterTest` | Full filter flow: pass-through for unprotected paths, 402 challenge, credential validation, 503 on backend failure, header sanitization |
| `PaygateSecurityFilterRealStoreTest` | End-to-end filter test with real `FileBasedRootKeyStore` |
| `FailClosedTest` | HTTP 503 when Lightning backend is unhealthy; protected content never leaks |
| `L402RateLimitingTest` | Rate limiting integration with the security filter; 429 responses |
| `TokenBucketRateLimiterTest` | Token bucket algorithm: burst, refill, stale cleanup, max buckets cap |
| `TestModeConfigTest` | `TestModeLightningBackend` is created when `paygate.test-mode=true`; not created when `false` |
| `TestModeLightningBackendTest` | Dummy invoice creation, always-settled lookup, preimage/hash consistency |
| `TestModeProductionGuardTest` | Startup fails with `prod`/`production` profiles; startup fails without allowed profiles |
| `PaygateLightningHealthIndicatorTest` | Health UP/DOWN reporting, caching, exception handling |
| `PaygateActuatorEndpointTest` | Actuator response structure with endpoint list, credentials, and earnings |
| `PaygateMetricsTest` | Micrometer counter and gauge registration and increment |
| `LsatChallengeSchemeTest` | Backward compatibility with `LSAT` prefix in Authorization header |
| `DynamicPricingTest` | Pricing strategy bean lookup and fallback to static price |
| `PricingFallbackTest` | Fallback behavior when pricing strategy bean is missing |

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
