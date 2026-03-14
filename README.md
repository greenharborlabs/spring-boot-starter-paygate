# spring-boot-starter-l402

A Spring Boot starter that adds [L402](https://docs.lightning.engineering/the-lightning-network/l402) (Lightning HTTP 402) payment-gated authentication to your Spring Boot APIs. Protect any endpoint with a single annotation and get paid in Bitcoin over the Lightning Network.

[![Build](https://github.com/greenharborlabs/spring-boot-starter-l402/actions/workflows/build.yml/badge.svg)](https://github.com/greenharborlabs/spring-boot-starter-l402/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.0](https://img.shields.io/badge/Spring%20Boot-4.0.3-green.svg)](https://spring.io/projects/spring-boot)

---

## Table of Contents

- [What is L402?](#what-is-l402)
- [Features](#features)
- [Quickstart](#quickstart)
- [Configuration Reference](#configuration-reference)
- [Lightning Backend Setup](#lightning-backend-setup)
- [Dynamic Pricing](#dynamic-pricing)
- [Spring Security Integration](#spring-security-integration)
- [Observability](#observability)
- [Test Mode](#test-mode)
- [Architecture](#architecture)
- [Security Considerations](#security-considerations)
- [Compatibility](#compatibility)
- [Building from Source](#building-from-source)
- [Contributing](#contributing)
- [License](#license)

---

## What is L402?

L402 is an HTTP authentication protocol that uses Bitcoin Lightning Network micropayments as access credentials. When a client requests a protected resource:

1. The server responds with **HTTP 402 Payment Required** and a Lightning invoice
2. The client pays the invoice and receives a **macaroon** (a cryptographic bearer token)
3. The client presents the macaroon + payment preimage in the `Authorization` header
4. The server validates the credential and grants access

This enables machine-to-machine API monetization, pay-per-use pricing, and metered access without user accounts, API keys, or subscription management.

```
Client                              Server
  |                                    |
  |  GET /api/premium-data             |
  |  --------------------------------> |
  |                                    |
  |  402 Payment Required              |
  |  WWW-Authenticate: L402            |
  |    macaroon="...", invoice="..."   |
  |  <-------------------------------- |
  |                                    |
  |  [pays Lightning invoice]          |
  |                                    |
  |  GET /api/premium-data             |
  |  Authorization: L402 <mac>:<proof> |
  |  --------------------------------> |
  |                                    |
  |  200 OK                            |
  |  {"data": "premium content"}       |
  |  <-------------------------------- |
```

---

## Features

- **Annotation-driven** -- protect any Spring MVC endpoint with `@L402Protected(priceSats = 10)`
- **Spring Security integration** -- optional `l402-spring-security` module provides `AuthenticationProvider`, `AuthenticationFilter`, and `L402AuthenticationToken` for use in Spring Security filter chains
- **Pluggable Lightning backends** -- LND (gRPC) and LNbits (REST) included; implement `LightningBackend` for others
- **Dynamic pricing** -- implement `L402PricingStrategy` to price based on request content, user tier, or time of day
- **Macaroon V2** -- binary-compatible with the Go [go-macaroon](https://github.com/go-macaroon/macaroon) library
- **Caveats** -- built-in `services` and `valid_until` verifiers; add custom `CaveatVerifier` implementations
- **Credential caching** -- Caffeine-backed cache with configurable size (falls back to in-memory)
- **Health check caching** -- `CachingLightningBackendWrapper` caches `isHealthy()` results with configurable TTL to avoid hammering the Lightning node
- **Rate limiting** -- built-in `TokenBucketRateLimiter` prevents invoice flooding attacks on challenge issuance
- **Micrometer metrics** -- counters for challenges, passes, rejections, revenue; gauges for credential cache size and Lightning health
- **Health indicator** -- `L402LightningHealthIndicator` integrates with Spring Boot Actuator health checks
- **Actuator endpoint** -- `GET /actuator/l402` for runtime status, protected endpoints, and earnings
- **Test mode** -- develop and test without a real Lightning node
- **Fail-closed** -- Lightning backend unreachable returns 503, never leaks protected content
- **LSAT backward compatibility** -- accepts both `L402` and `LSAT` authorization schemes
- **Zero-dependency core** -- `l402-core` uses only the JDK (no external libraries)
- **Constant-time security** -- all secret comparisons use XOR accumulation to prevent timing attacks

---

## Quickstart

### 1. Add the dependency

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")

// Choose ONE Lightning backend:
implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0")  // LNbits (REST)
// OR
implementation("com.greenharborlabs:l402-lightning-lnd:0.1.0")     // LND (gRPC)
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Choose ONE Lightning backend -->
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-lightning-lnbits</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure your application

```yaml
# application.yml
l402:
  enabled: true
  backend: lnbits                    # or "lnd"
  service-name: my-api
  default-price-sats: 10
  default-timeout-seconds: 3600
  lnbits:
    url: https://your-lnbits-instance.com
    api-key: ${LNBITS_API_KEY}       # use environment variables for secrets
```

### 3. Annotate your endpoints

```java
@RestController
@RequestMapping("/api/v1")
public class PremiumController {

    @L402Protected(priceSats = 10)
    @GetMapping("/data")
    public DataResponse getData() {
        return new DataResponse("premium content");
    }

    @L402Protected(priceSats = 50, description = "AI analysis")
    @PostMapping("/analyze")
    public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
        // expensive computation here
        return new AnalysisResponse(result);
    }
}
```

That is it. Unauthenticated requests to `/api/v1/data` now receive a 402 response with a Lightning invoice. After payment, the client includes the credential in the `Authorization` header and receives the content.

Endpoints without `@L402Protected` are unaffected and pass through normally.

---

## Configuration Reference

All properties are under the `l402.*` prefix.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.enabled` | `boolean` | `false` | Master switch. L402 filter is only active when `true`. |
| `l402.backend` | `string` | -- | Lightning backend to use: `lnbits` or `lnd`. |
| `l402.service-name` | `string` | `default` | Service name embedded in macaroon caveats. Falls back to `"default"` if unset. |
| `l402.default-price-sats` | `long` | `10` | Fallback price when not specified in `@L402Protected`. |
| `l402.default-timeout-seconds` | `long` | `3600` | Credential TTL in seconds. |
| `l402.root-key-store` | `string` | `file` | Root key storage: `file` or `memory`. |
| `l402.root-key-store-path` | `string` | `~/.l402/keys` | Directory for file-based root key storage. |
| `l402.credential-cache` | `string` | `caffeine` | Cache implementation. Caffeine used when on classpath. |
| `l402.credential-cache-max-size` | `int` | `10000` | Maximum cached credentials. |
| `l402.test-mode` | `boolean` | `false` | Enable test mode (dummy invoices, auto-settle). |

### Health Check Caching

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.health-cache.enabled` | `boolean` | `true` | Enable health check result caching for the Lightning backend. |
| `l402.health-cache.ttl-seconds` | `int` | `5` | TTL in seconds for cached health check results. |

### LNbits Backend

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.lnbits.url` | `string` | -- | LNbits instance URL. |
| `l402.lnbits.api-key` | `string` | -- | LNbits admin API key. |

### LND Backend

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `l402.lnd.host` | `string` | `localhost` | LND gRPC host. |
| `l402.lnd.port` | `int` | `10009` | LND gRPC port. |
| `l402.lnd.tls-cert-path` | `string` | -- | Path to LND TLS certificate. Omit for plaintext (dev only). |
| `l402.lnd.macaroon-path` | `string` | -- | Path to LND admin macaroon file. |

---

## Lightning Backend Setup

### LNbits

[LNbits](https://lnbits.com/) is a lightweight Lightning wallet with a REST API. It is the simplest backend to get started with.

1. Deploy or use a hosted LNbits instance
2. Create a wallet and note the admin API key
3. Configure:

```yaml
l402:
  enabled: true
  backend: lnbits
  lnbits:
    url: https://your-lnbits.com
    api-key: ${LNBITS_API_KEY}
```

**Dependency:**

```kotlin
implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0")
```

### LND

[LND](https://github.com/lightningnetwork/lnd) is a full Lightning Network node implementation. Use this for production deployments where you operate your own node.

1. Ensure your LND node is running and accessible via gRPC
2. Locate the TLS certificate and admin macaroon files
3. Configure:

```yaml
l402:
  enabled: true
  backend: lnd
  lnd:
    host: your-lnd-host
    port: 10009
    tls-cert-path: /path/to/tls.cert
    macaroon-path: /path/to/admin.macaroon
```

**Dependency:**

```kotlin
implementation("com.greenharborlabs:l402-lightning-lnd:0.1.0")
```

### Custom Backend

Implement the `LightningBackend` interface and register it as a Spring bean:

```java
@Component
public class MyCustomBackend implements LightningBackend {

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        // create invoice via your Lightning provider
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        // look up invoice payment status
    }

    @Override
    public boolean isHealthy() {
        // return true if the backend is reachable
    }
}
```

When a custom `LightningBackend` bean is present, auto-configuration will not create its own.

---

## Dynamic Pricing

For endpoints where the price depends on request content, implement `L402PricingStrategy`:

```java
@Component("analysisPricer")
public class AnalysisPricingStrategy implements L402PricingStrategy {

    @Override
    public long calculatePrice(HttpServletRequest request, long defaultPrice) {
        int contentLength = request.getContentLength();
        if (contentLength <= 1000) {
            return defaultPrice;
        }
        // charge 1 extra sat per 100 bytes over 1KB
        return defaultPrice + contentLength / 100;
    }
}
```

Reference the strategy by bean name in the annotation:

```java
@L402Protected(priceSats = 50, pricingStrategy = "analysisPricer")
@PostMapping("/analyze")
public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
    // ...
}
```

If the named pricing strategy bean is not found at runtime, the filter falls back to the static `priceSats` value.

---

## Spring Security Integration

For applications that use Spring Security, the optional `l402-spring-security` module provides first-class integration with Spring Security filter chains.

**Add the dependency:**

```kotlin
implementation("com.greenharborlabs:l402-spring-security:0.1.0")
```

When both Spring Security and an `L402Validator` bean are present, the module auto-configures:

- **`L402AuthenticationProvider`** -- validates L402 credentials via `L402Validator` and produces an authenticated `L402AuthenticationToken`
- **`L402AuthenticationFilter`** -- extracts L402 credentials from the `Authorization` header and delegates to the `AuthenticationManager`
- **`L402AuthenticationToken`** -- carries the validated credential, token ID, service name, and caveat-derived attributes accessible via SpEL in `@PreAuthorize` expressions

The authenticated token grants the `ROLE_L402` authority and exposes caveat values as attributes:

```java
@PreAuthorize("hasRole('L402')")
@GetMapping("/api/v1/protected")
public Response protectedEndpoint(Authentication auth) {
    var l402Token = (L402AuthenticationToken) auth;
    String tokenId = l402Token.getTokenId();
    String service = l402Token.getServiceName();
    Map<String, String> attrs = l402Token.getAttributes();
    // ...
}
```

Register the filter in your security filter chain configuration. The auto-configuration provides the beans; placement in the filter chain is left to your `SecurityFilterChain` definition.

---

## Observability

### Micrometer Metrics

When [Micrometer](https://micrometer.io/) is on the classpath, the starter automatically registers the following metrics:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `l402.requests` | Counter | `endpoint`, `result` | Total requests to protected endpoints. `result` is `challenged`, `passed`, or `rejected`. |
| `l402.invoices.created` | Counter | `endpoint` | Lightning invoices generated. |
| `l402.invoices.settled` | Counter | `endpoint` | Invoices confirmed paid. |
| `l402.revenue.sats` | Counter | `endpoint` | Total satoshis earned. |
| `l402.credentials.active` | Gauge | -- | Currently cached credentials. |
| `l402.lightning.healthy` | Gauge | -- | Lightning backend health: `1` = healthy, `0` = unhealthy. |

No additional configuration is needed. Add `spring-boot-starter-actuator` and your preferred metrics registry (Prometheus, Datadog, etc.) to export these metrics.

### Actuator Endpoint

When Spring Boot Actuator is on the classpath, a custom endpoint is available at `GET /actuator/l402`:

```json
{
  "enabled": true,
  "backend": "lnbits",
  "backendHealthy": true,
  "testMode": false,
  "serviceName": "my-api",
  "protectedEndpoints": [
    {
      "method": "GET",
      "path": "/api/v1/data",
      "priceSats": 10,
      "timeoutSeconds": 3600,
      "description": "",
      "pricingStrategy": null
    }
  ],
  "credentials": {
    "active": 42,
    "maxSize": 10000
  },
  "earnings": {
    "totalInvoicesCreated": 156,
    "totalInvoicesSettled": 89,
    "totalSatsEarned": 1230
  }
}
```

Expose the endpoint in your actuator configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,l402
```

---

## Test Mode

For development and testing without a real Lightning node, enable test mode:

```yaml
l402:
  enabled: true
  test-mode: true
  service-name: my-api
```

In test mode:
- A `TestModeLightningBackend` is automatically provided (no `backend` property needed)
- Invoices are dummy values with valid structure
- All invoices are treated as immediately settled
- The full L402 flow (challenge, credential validation) still executes

**Safety guard:** Test mode refuses to start if any active Spring profile is `production` or `prod`, throwing an `IllegalStateException` at application startup.

---

## Architecture

```
+-------------------------------+
|  l402-spring-boot-starter     |   Dependency aggregator (no code)
|  (user adds this dependency)  |   Pulls in autoconfigure + core
+-------------------------------+
         |              |
         v              v
+----------------+  +---------------------------+  +-------------------------+
|  l402-core     |  |  l402-spring-autoconfigure |  |  l402-spring-security   |
|                |  |                            |  |                         |
|  Macaroon V2   |  |  L402AutoConfiguration     |  |  L402Authentication-    |
|  HMAC-SHA256   |  |  L402SecurityFilter        |  |    Provider             |
|  Credential    |  |  L402Properties            |  |  L402Authentication-    |
|    Store       |  |  @L402Protected            |  |    Filter               |
|  Lightning     |  |  L402PricingStrategy       |  |  L402Authentication-    |
|    Backend     |  |  L402Metrics               |  |    Token                |
|    (interface) |  |  L402ActuatorEndpoint      |  |                         |
|                |  |  CachingLightning-         |  |  Integrates with       |
|  ZERO external |  |    BackendWrapper          |  |  Spring Security       |
|  dependencies  |  |  TokenBucketRateLimiter    |  |  filter chains         |
+----------------+  |  TestModeAutoConfiguration |  +-------------------------+
                    +---------------------------+
                         |              |
                         v              v
                  +-------------+  +---------------+
                  | l402-light- |  | l402-light-   |
                  | ning-lnbits |  | ning-lnd      |
                  |             |  |               |
                  | REST/JSON   |  | gRPC/Protobuf |
                  | Jackson     |  | Netty         |
                  +-------------+  +---------------+

+-------------------------------+
|  l402-example-app             |   Reference implementation
|  (not published as artifact)  |   Shows annotation + dynamic pricing
+-------------------------------+
```

### Module Responsibilities

| Module | Description | External Dependencies |
|--------|-------------|----------------------|
| `l402-core` | Macaroon V2 serialization, HMAC-SHA256 crypto, credential store interface, Lightning backend interface, L402 protocol validation | **None** (JDK only) |
| `l402-lightning-lnbits` | `LightningBackend` implementation using the LNbits REST API | Jackson |
| `l402-lightning-lnd` | `LightningBackend` implementation using the LND gRPC API | gRPC, Protobuf, Netty |
| `l402-spring-autoconfigure` | Spring Boot auto-configuration, servlet filter, annotation scanning, metrics, actuator, health caching, rate limiting | Spring Boot, Spring MVC, Caffeine (optional), Micrometer (optional), Actuator (optional) |
| `l402-spring-security` | Spring Security integration: `L402AuthenticationProvider`, `L402AuthenticationFilter`, and `L402AuthenticationToken` for use in security filter chains | Spring Security |
| `l402-spring-boot-starter` | Dependency aggregator. No source code. | -- |
| `l402-example-app` | Runnable reference application with dynamic pricing | Spring Boot Web |

### Key Design Decisions

- **l402-core has zero external dependencies.** All cryptography uses `javax.crypto` and `java.security` from the JDK. This makes the core portable and auditable.
- **Macaroon V2 binary format is byte-level compatible with Go `go-macaroon`.** Cross-language interoperability is a first-class requirement.
- **Identifier layout is fixed at 66 bytes:** `[version:2 bytes BE][payment_hash:32][token_id:32]`. This ensures deterministic parsing.
- **All beans are guarded with `@ConditionalOnMissingBean`.** Users can override any component by declaring their own bean.
- **Fail-closed security model.** If the Lightning backend is unreachable, the filter returns HTTP 503 -- it never returns 200 for a protected endpoint without valid credentials.

---

## Security Considerations

This library handles payment credentials and cryptographic tokens. The following security properties are enforced:

### Cryptographic Integrity

- **HMAC-SHA256** for macaroon minting and verification, using JDK `javax.crypto.Mac`
- **Constant-time comparison** for all secret material (root keys, signatures, preimages) using XOR accumulation -- never `Arrays.equals`
- **Key derivation** follows the macaroon specification: `HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`
- **SecureRandom** for all random byte generation (token IDs, root keys)

### Operational Security

- **Root key storage** defaults to file-based storage at `~/.l402/keys`. In production, ensure this directory has restricted permissions (`chmod 700`)
- **Never log full macaroon values** -- only token IDs appear in logs
- **Environment variables** should be used for Lightning backend credentials (`api-key`, `macaroon-path`), not plaintext in configuration files
- **Test mode is blocked in production** -- the `TestModeAutoConfiguration` throws at startup if `prod` or `production` profiles are active
- **Fail-closed** -- any unexpected exception during validation produces HTTP 503, never leaking protected content

### Recommendations for Production

1. Use file-based root key storage with proper filesystem permissions
2. Store Lightning credentials in environment variables or a secrets manager
3. Enable TLS for LND connections (do not use plaintext in production)
4. Monitor the `l402.lightning.healthy` gauge and alert when it drops to 0
5. Set `l402.credential-cache-max-size` based on your expected concurrent credential volume
6. Review and rotate root keys periodically

---

## Compatibility

| Component | Version |
|-----------|---------|
| Java | 25 (LTS) |
| Spring Boot | 4.0.3 |
| Spring Framework | 7.x |
| Jakarta EE | 11 (Servlet 6.1) |
| Gradle | 8.12+ |
| Caffeine | 3.1.8 |
| gRPC | 1.68.1 |
| Protobuf | 4.29.3 |
| Jackson | 2.18.2 |
| Micrometer | (Spring Boot managed) |

---

## Building from Source

Prerequisites: JDK 25 and Git.

```bash
git clone https://github.com/greenharborlabs/spring-boot-starter-l402.git
cd spring-boot-starter-l402
./gradlew build
```

Run all tests:

```bash
./gradlew test
```

Run tests for a specific module:

```bash
./gradlew :l402-core:test
```

Test coverage reports (JaCoCo) are generated at `build/reports/jacoco/` in each module and aggregated at the root.

---

## Spec Deviations

### Price Unit: Satoshis vs Milli-satoshis

The L402 `protocol-specification.md` recommends expressing prices in milli-satoshis (1/1000th of a satoshi). This library uses **satoshis** as the price unit (`l402.default-price-sats`, `@L402Protected(priceSats = ...)`).

**Rationale:** Satoshis are the practical unit for most L402 use cases. BOLT 11 invoices handle the conversion to milli-satoshis internally. Using whole satoshis avoids fractional pricing complexity for the vast majority of API monetization scenarios where sub-satoshi granularity is unnecessary.

---

## Contributing

Contributions are welcome. Please follow these guidelines:

1. **Open an issue first** for significant changes to discuss the approach
2. **Fork and branch** from `main`
3. **Follow existing code conventions** -- Java 25 idioms (records, sealed classes, pattern matching), Javadoc on public types
4. **Maintain the zero-dependency constraint** on `l402-core` -- no external libraries
5. **Add tests** -- the project enforces a minimum 40% code coverage via JaCoCo
6. **All secret comparisons must be constant-time** (XOR accumulation)
7. **Never log full macaroon values** -- only token IDs

---

## License

This project is licensed under the [MIT License](LICENSE).

Copyright (c) 2026 Green Harbor Labs
