# spring-boot-starter-paygate

A Spring Boot starter that adds [L402](https://docs.lightning.engineering/the-lightning-network/l402) and MPP (Modern Payment Protocol) dual-protocol payment-gated authentication to your Spring Boot APIs. Paygate is a payment gateway for the agent economy -- protect any endpoint with a single annotation and get paid in Bitcoin over the Lightning Network.

[![CI](https://github.com/greenharborlabs/spring-boot-starter-l402/actions/workflows/ci.yml/badge.svg)](https://github.com/greenharborlabs/spring-boot-starter-l402/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.greenharborlabs/paygate-spring-boot-starter)](https://central.sonatype.com/artifact/com.greenharborlabs/paygate-spring-boot-starter)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.0](https://img.shields.io/badge/Spring%20Boot-4.0.3-green.svg)](https://spring.io/projects/spring-boot)

---

## Table of Contents

- [What is L402?](#what-is-l402)
- [What is MPP?](#what-is-mpp)
- [Dual-Protocol Support](#dual-protocol-support)
- [Features](#features)
- [Quickstart](#quickstart)
- [JVM Requirements](#jvm-requirements)
- [Configuration Reference](#configuration-reference)
- [Lightning Backend Setup](#lightning-backend-setup)
- [Dynamic Pricing](#dynamic-pricing)
- [Delegation Caveats](#delegation-caveats)
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

## What is MPP?

MPP (Modern Payment Protocol) is an alternative HTTP 402 authentication protocol that uses the `Payment` authentication scheme. Unlike L402, MPP is fully stateless on the server side -- it uses HMAC-SHA256 challenge binding instead of a credential cache, making it simpler to deploy in horizontally scaled environments.

When a client requests a protected resource:

1. The server responds with **HTTP 402 Payment Required** and a `WWW-Authenticate: Payment` challenge containing a Lightning invoice, an HMAC-bound challenge ID, and expiry metadata
2. The client pays the Lightning invoice, obtaining the payment preimage
3. The client echoes the challenge fields and includes the preimage in a base64url-encoded JSON credential in the `Authorization: Payment` header
4. The server recomputes the HMAC to verify the challenge was not tampered with, checks the preimage against the payment hash, and grants access
5. The server returns a `Payment-Receipt` response header as proof of payment

Key differences from L402:

- Uses the `Payment` authentication scheme (not `L402`)
- Credentials are base64url-encoded JSON without padding (not standard base64 macaroons)
- Challenge binding via HMAC-SHA256 -- no server-side session, cache, or macaroon storage needed
- Error responses use RFC 9457 problem type URIs
- Deterministic serialization via RFC 8785 JCS (JSON Canonicalization Scheme)

```
Client                                  Server
  |                                       |
  |  GET /api/v1/data                     |
  |  (no Authorization header)            |
  |-------------------------------------->|
  |                                       |
  |  402 Payment Required                 |
  |  WWW-Authenticate: Payment            |
  |    id="<hmac>", realm="my-api",       |
  |    method="lightning",                |
  |    intent="charge",                   |
  |    request="<base64url invoice>",     |
  |    expires="<ISO-8601>"               |
  |<--------------------------------------|
  |                                       |
  |  [pays Lightning invoice]             |
  |                                       |
  |  GET /api/v1/data                     |
  |  Authorization: Payment <base64url    |
  |    JSON with echoed challenge +       |
  |    preimage>                          |
  |-------------------------------------->|
  |                                       |
  |  Server recomputes HMAC, verifies     |
  |  preimage, checks expiry              |
  |                                       |
  |  200 OK                               |
  |  Payment-Receipt: <base64url receipt> |
  |<--------------------------------------|
```

---

## Dual-Protocol Support

Paygate serves both L402 and MPP challenges simultaneously. When a client hits a protected endpoint without credentials, the server returns multiple `WWW-Authenticate` headers -- one for each enabled protocol. The client chooses which protocol to use.

```
Client                                  Server (PaygateSecurityFilter)
  |                                       |
  |  GET /api/v1/data                     |
  |  (no Authorization header)            |
  |-------------------------------------->|
  |                                       |
  |                            +--------------------------+
  |                            | For each PaymentProtocol:|
  |                            |   protocol.formatChallenge|
  |                            +--------------------------+
  |                                       |
  |  402 Payment Required                 |
  |  WWW-Authenticate: L402 macaroon="..",|
  |    invoice=".."                       |
  |  WWW-Authenticate: Payment id="..",   |
  |    realm="..", method="lightning",    |
  |    intent="charge", request="..",    |
  |    expires=".."                       |
  |<--------------------------------------|
  |                                       |
  |  Client chooses protocol:             |
  |                                       |
  |  Option A: L402                       |
  |  Authorization: L402 <mac>:<preimage> |
  |-------------------------------------->|
  |                                       |
  |  Option B: MPP                        |
  |  Authorization: Payment <base64url>   |
  |-------------------------------------->|
  |                                       |
  |  200 OK                               |
  |<--------------------------------------|
```

Both protocols share the same Lightning invoice -- the client pays once regardless of which protocol it uses for authentication.

### Protocol Configuration

Control which protocols are active via `paygate.protocols.*` properties:

```yaml
paygate:
  protocols:
    l402:
      enabled: true                          # default: true
    mpp:
      enabled: auto                          # auto / true / false
      challenge-binding-secret: ${MPP_SECRET}  # min 32 bytes, required when enabled
```

- **`auto`** (default): MPP is enabled when `challenge-binding-secret` is present, disabled otherwise.
- **`true`**: MPP is required -- startup fails if `challenge-binding-secret` is missing.
- **`false`**: MPP is disabled regardless of whether a secret is configured.

The `PaymentProtocol` SPI (`paygate-api`) is the extension point. Each protocol implements `PaymentProtocol` with methods for `scheme()`, `canHandle()`, `parseCredential()`, `formatChallenge()`, `validate()`, and optionally `createReceipt()`.

---

## Features

- **Dual-protocol support** -- L402 + MPP via the `PaymentProtocol` SPI; both served simultaneously with multiple `WWW-Authenticate` headers
- **Annotation-driven** -- protect any Spring MVC endpoint with `@PaymentRequired(priceSats = 10)`
- **MPP challenge binding** -- HMAC-SHA256 stateless challenge verification (no credential cache needed for MPP)
- **`Payment-Receipt` response header** -- MPP returns a receipt after successful validation as proof of payment
- **Delegation caveat verifiers** -- `path`, `method`, `client_ip` caveats with glob matching (`*` and `**`) and IPv6 normalization
- **Spring Security integration** -- optional `paygate-spring-security` module provides `AuthenticationProvider`, `AuthenticationFilter`, and `L402AuthenticationToken` for use in Spring Security filter chains
- **Pluggable Lightning backends** -- LND (gRPC) and LNbits (REST) included; implement `LightningBackend` for others
- **Dynamic pricing** -- implement `PaygatePricingStrategy` to price based on request content, user tier, or time of day
- **Macaroon V2** -- binary-compatible with the Go [go-macaroon](https://github.com/go-macaroon/macaroon) library
- **Caveats** -- built-in `services`, `valid_until`, `path`, `method`, and `client_ip` verifiers; add custom `CaveatVerifier` implementations
- **Credential caching** -- Caffeine-backed cache with configurable size (falls back to in-memory)
- **Health check caching** -- `CachingLightningBackendWrapper` caches `isHealthy()` results with configurable TTL to avoid hammering the Lightning node
- **Rate limiting** -- built-in `TokenBucketRateLimiter` prevents invoice flooding attacks on challenge issuance
- **Micrometer metrics** -- counters for challenges, passes, rejections, revenue; gauges for credential cache size and Lightning health
- **Health indicator** -- `PaygateLightningHealthIndicator` integrates with Spring Boot Actuator health checks
- **Actuator endpoint** -- `GET /actuator/paygate` for runtime status, protected endpoints, and earnings
- **Test mode** -- develop and test without a real Lightning node
- **Fail-closed** -- Lightning backend unreachable returns 503, never leaks protected content
- **LSAT backward compatibility** -- accepts both `L402` and `LSAT` authorization schemes
- **Zero-dependency core** -- `paygate-core` uses only the JDK (no external libraries)
- **Constant-time security** -- all secret comparisons use XOR accumulation to prevent timing attacks

---

## Quickstart

### 1. Add the dependency

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")

// Choose ONE Lightning backend:
implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.0")  // LNbits (REST)
// OR
implementation("com.greenharborlabs:paygate-lightning-lnd:0.1.0")     // LND (gRPC)
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Choose ONE Lightning backend -->
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-lightning-lnbits</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure your application

```yaml
# application.yml
paygate:
  enabled: true
  backend: lnbits                    # or "lnd"
  service-name: my-api
  default-price-sats: 10
  default-timeout-seconds: 3600
  lnbits:
    url: https://your-lnbits-instance.com
    api-key: ${LNBITS_API_KEY}       # use environment variables for secrets
  protocols:
    mpp:
      challenge-binding-secret: ${MPP_SECRET}  # optional: enables MPP alongside L402
```

### 3. Annotate your endpoints

```java
@RestController
@RequestMapping("/api/v1")
public class PremiumController {

    @PaymentRequired(priceSats = 10)
    @GetMapping("/data")
    public DataResponse getData() {
        return new DataResponse("premium content");
    }

    @PaymentRequired(priceSats = 50, description = "AI analysis")
    @PostMapping("/analyze")
    public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
        // expensive computation here
        return new AnalysisResponse(result);
    }
}
```

That is it. Unauthenticated requests to `/api/v1/data` now receive a 402 response with a Lightning invoice. After payment, the client includes the credential in the `Authorization` header and receives the content.

Endpoints without `@PaymentRequired` are unaffected and pass through normally.

---

## JVM Requirements

### Native Access Flag

Applications using Netty (including gRPC backends) should pass the `--enable-native-access=ALL-UNNAMED` JVM flag. On Java 24-25, this flag suppresses native-access warnings emitted by the Foreign Function and Memory API; on Java 26+, it will be **required** to avoid runtime failures.

**Dockerfile:**
```dockerfile
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
```

**Gradle (`bootRun`):**
```kotlin
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

**systemd:**
```ini
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -jar /opt/app/app.jar
```

This flag is already configured in the starter's test configuration.

---

## Configuration Reference

All properties are under the `paygate.*` prefix.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.enabled` | `boolean` | `false` | Master switch. Paygate filter is only active when `true`. |
| `paygate.backend` | `string` | -- | Lightning backend to use: `lnbits` or `lnd`. |
| `paygate.service-name` | `string` | -- | Service name embedded in macaroon caveats. Falls back to `"default"` at runtime if unset. |
| `paygate.default-price-sats` | `long` | `10` | Fallback price when not specified in `@PaymentRequired`. |
| `paygate.default-timeout-seconds` | `long` | `3600` | Credential TTL in seconds. |
| `paygate.root-key-store` | `string` | `file` | Root key storage: `file` or `memory`. |
| `paygate.root-key-store-path` | `string` | `~/.paygate/keys` | Directory for file-based root key storage. |
| `paygate.credential-cache-max-size` | `int` | `10000` | Maximum cached credentials. |
| `paygate.security-mode` | `string` | `auto` | Security integration mode: `auto`, `servlet`, or `spring-security`. See [Spring Security Integration](#spring-security-integration). |
| `paygate.test-mode` | `boolean` | `false` | Enable test mode (dummy invoices, auto-settle). |
| `paygate.trust-forwarded-headers` | `boolean` | `false` | Trust `X-Forwarded-For` for client IP resolution. Enable only behind a trusted reverse proxy. |

### Protocol Configuration (`paygate.protocols.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.protocols.l402.enabled` | `boolean` | `true` | Enable/disable L402 protocol. |
| `paygate.protocols.mpp.enabled` | `string` | `auto` | `auto` enables MPP when secret is present, `true` requires secret, `false` disables. |
| `paygate.protocols.mpp.challenge-binding-secret` | `string` | -- | HMAC secret for MPP challenge binding. Minimum 32 bytes. |

### Delegation Caveat Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.caveat.max-values-per-caveat` | `int` | `50` | Maximum comma-separated values per caveat. |
| `paygate.trusted-proxy-addresses` | `List<String>` | `[]` | Trusted reverse proxy IPs for `X-Forwarded-For` resolution. |

### Rate Limiting

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.rate-limit.requests-per-second` | `double` | `10.0` | Token refill rate per second for the challenge rate limiter. |
| `paygate.rate-limit.burst-size` | `int` | `20` | Maximum burst size (token bucket capacity) for the challenge rate limiter. |

### Lightning Backend Timeout

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.lightning.timeout-seconds` | `int` | `5` | Global timeout in seconds for Lightning backend RPC/HTTP calls. Backend-specific properties override this when set. |

### Health Check Caching

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.health-cache.enabled` | `boolean` | `true` | Enable health check result caching for the Lightning backend. |
| `paygate.health-cache.ttl-seconds` | `int` | `5` | TTL in seconds for cached health check results. |

### LNbits Backend

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.lnbits.url` | `string` | -- | LNbits instance URL. |
| `paygate.lnbits.api-key` | `string` | -- | LNbits admin API key. |
| `paygate.lnbits.request-timeout-seconds` | `int` | -- | HTTP request timeout. Overrides `paygate.lightning.timeout-seconds` when set. |
| `paygate.lnbits.connect-timeout-seconds` | `int` | `10` | TCP connect timeout in seconds. |

### LND Backend

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.lnd.host` | `string` | `localhost` | LND gRPC host. |
| `paygate.lnd.port` | `int` | `10009` | LND gRPC port. |
| `paygate.lnd.tls-cert-path` | `string` | -- | Path to LND TLS certificate. Omit for plaintext (dev only). |
| `paygate.lnd.macaroon-path` | `string` | -- | Path to LND admin macaroon file. |
| `paygate.lnd.allow-plaintext` | `boolean` | `false` | Allow plaintext gRPC (no TLS). Dev only. |
| `paygate.lnd.rpc-deadline-seconds` | `int` | -- | Per-call gRPC deadline. Overrides `paygate.lightning.timeout-seconds` when set. |
| `paygate.lnd.keep-alive-time-seconds` | `int` | `60` | Interval between gRPC keepalive pings. |
| `paygate.lnd.keep-alive-timeout-seconds` | `int` | `20` | Timeout for keepalive ping acknowledgement. |
| `paygate.lnd.idle-timeout-minutes` | `int` | `5` | Idle gRPC connection timeout. |
| `paygate.lnd.max-inbound-message-size` | `int` | `4194304` | Maximum inbound gRPC message size in bytes. |

### Metrics

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.metrics.max-endpoint-cardinality` | `int` | `100` | Maximum distinct endpoint tag values before overflow bucketing. |
| `paygate.metrics.overflow-tag-value` | `string` | `_other` | Tag value used when the endpoint cardinality cap is exceeded. |

---

## Lightning Backend Setup

### LNbits

[LNbits](https://lnbits.com/) is a lightweight Lightning wallet with a REST API. It is the simplest backend to get started with.

1. Deploy or use a hosted LNbits instance
2. Create a wallet and note the admin API key
3. Configure:

```yaml
paygate:
  enabled: true
  backend: lnbits
  lnbits:
    url: https://your-lnbits.com
    api-key: ${LNBITS_API_KEY}
```

**Dependency:**

```kotlin
implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.0")
```

### LND

[LND](https://github.com/lightningnetwork/lnd) is a full Lightning Network node implementation. Use this for production deployments where you operate your own node.

1. Ensure your LND node is running and accessible via gRPC
2. Locate the TLS certificate and admin macaroon files
3. Configure:

```yaml
paygate:
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
implementation("com.greenharborlabs:paygate-lightning-lnd:0.1.0")
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

For endpoints where the price depends on request content, implement `PaygatePricingStrategy`:

```java
@Component("analysisPricer")
public class AnalysisPricingStrategy implements PaygatePricingStrategy {

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
@PaymentRequired(priceSats = 50, pricingStrategy = "analysisPricer")
@PostMapping("/analyze")
public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
    // ...
}
```

If the named pricing strategy bean is not found at runtime, the filter falls back to the static `priceSats` value.

---

## Delegation Caveats

Delegation caveats allow macaroon holders to attenuate (restrict) their credentials before sharing them with third parties. Paygate includes three built-in delegation caveat verifiers:

### `path` Caveat

Restricts the credential to specific URL paths using glob pattern matching.

- `*` matches any single path segment (e.g., `/api/*/data` matches `/api/v1/data` but not `/api/v1/v2/data`)
- `**` matches zero or more path segments (e.g., `/api/v1/**` matches `/api/v1/data` and `/api/v1/nested/deep/data`)
- Multiple patterns can be comma-separated: `path=/api/v1/**,/api/v2/**`
- Encoded slashes (`%2F`) in request paths are rejected for security
- Paths are normalized (trailing slashes removed, double slashes collapsed)

### `method` Caveat

Restricts the credential to specific HTTP methods.

- Case-insensitive matching: `method=GET,POST` matches `get`, `Get`, `GET`
- Multiple methods can be comma-separated

### `client_ip` Caveat

Restricts the credential to specific client IP addresses.

- IPv6 normalization via `InetAddress.ofLiteral()` -- ensures `::1` and `0:0:0:0:0:0:0:1` are treated as the same address
- Multiple IPs can be comma-separated: `client_ip=10.0.0.1,10.0.0.2`
- When `paygate.trust-forwarded-headers=true`, the client IP is resolved from the `X-Forwarded-For` header using the configured `paygate.trusted-proxy-addresses` list

### Delegation Caveat Enforcement

```
Macaroon caveats:
  path=/api/v1/**
  method=GET,POST
  client_ip=10.0.0.1,10.0.0.2

Incoming request:
  GET /api/v1/data from 10.0.0.1

Verification:
  +-- PathCaveatVerifier
  |     PathGlobMatcher.match("/api/v1/**", "/api/v1/data") --> PASS
  +-- MethodCaveatVerifier
  |     "GET" in [GET,POST] --> PASS
  +-- ClientIpCaveatVerifier
        InetAddress match "10.0.0.1" --> PASS

All caveats passed --> credential accepted
```

### Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `paygate.caveat.max-values-per-caveat` | `int` | `50` | Maximum comma-separated values allowed in a single caveat. Prevents abuse via overly broad caveats. |
| `paygate.trusted-proxy-addresses` | `List<String>` | `[]` | Trusted reverse proxy IP addresses for `X-Forwarded-For` header resolution. Required when `paygate.trust-forwarded-headers=true` and using `client_ip` caveats. |

---

## Spring Security Integration

For applications that use Spring Security, the optional `paygate-spring-security` module provides first-class integration with Spring Security filter chains.

**Add the dependency:**

```kotlin
implementation("com.greenharborlabs:paygate-spring-security:0.1.0")
```

When both Spring Security and an `L402Validator` bean are present, the module auto-configures:

- **`L402AuthenticationProvider`** -- validates L402 credentials via `L402Validator` and produces an authenticated `L402AuthenticationToken`
- **`L402AuthenticationFilter`** -- extracts L402 credentials from the `Authorization` header and delegates to the `AuthenticationManager`
- **`L402AuthenticationToken`** -- carries the validated credential, token ID, service name, and caveat-derived attributes accessible via SpEL in `@PreAuthorize` expressions
- **`L402AuthenticationEntryPoint`** -- issues HTTP 402 Payment Required challenges with Lightning invoices when an unauthenticated request hits a protected endpoint, replacing the default 401 response

### Security Mode (`paygate.security-mode`)

The `paygate.security-mode` property controls how L402 protection is applied. This determines whether the standalone servlet filter or the Spring Security integration handles requests.

| Value | Behavior |
|-------|----------|
| `auto` (default) | Detects Spring Security on the classpath. If present, uses `spring-security` mode; otherwise, uses `servlet` mode. |
| `servlet` | Forces the standalone `PaygateSecurityFilter` (from `paygate-spring-autoconfigure`). The Spring Security module is ignored even if on the classpath. Use this when Spring Security is present but you want annotation-driven `@PaymentRequired` handling. |
| `spring-security` | Forces the Spring Security path. The standalone servlet filter is disabled. Fails at startup if Spring Security is not on the classpath. |

The two modes are mutually exclusive -- only one is active at a time. Configure the mode explicitly when both modules are on the classpath and you want deterministic behavior:

```yaml
paygate:
  enabled: true
  security-mode: spring-security   # or "servlet" or "auto"
```

### Authentication Entry Point

The `L402AuthenticationEntryPoint` bridges Spring Security's exception handling with the L402 payment flow. When an unauthenticated request reaches a protected endpoint, the entry point:

1. Looks up the endpoint's Paygate configuration (price, timeout, pricing strategy)
2. Creates a Lightning invoice via the configured backend
3. Returns HTTP 402 with a `WWW-Authenticate: L402` header containing the macaroon and invoice

Configure it in your `SecurityFilterChain`:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 L402AuthenticationFilter l402Filter,
                                                 L402AuthenticationProvider l402Provider,
                                                 L402AuthenticationEntryPoint l402EntryPoint) throws Exception {
    return http
            .authenticationProvider(l402Provider)
            .addFilterBefore(l402Filter, BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/public/**").permitAll()
                    .requestMatchers("/api/premium/**").hasRole("L402")
                    .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(l402EntryPoint)
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
}
```

### Accessing L402 Credentials

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

See the [paygate-spring-security README](paygate-spring-security/README.md) for detailed documentation, SpEL examples, and mixed-auth patterns. A complete `SecurityFilterChain` example is available in `paygate-example-app/src/main/java/.../example/SecurityConfig.java` (commented out by default).

---

## Observability

### Micrometer Metrics

When [Micrometer](https://micrometer.io/) is on the classpath, the starter automatically registers the following metrics:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `paygate.requests` | Counter | `endpoint`, `result` | Total requests to protected endpoints. `result` is `challenged`, `passed`, or `rejected`. |
| `paygate.invoices.created` | Counter | `endpoint` | Lightning invoices generated. |
| `paygate.invoices.settled` | Counter | `endpoint` | Invoices confirmed paid. |
| `paygate.revenue.sats` | Counter | `endpoint` | Total satoshis earned. |
| `paygate.credentials.active` | Gauge | -- | Currently cached credentials. |
| `paygate.lightning.healthy` | Gauge | -- | Lightning backend health: `1` = healthy, `0` = unhealthy. |

No additional configuration is needed. Add `spring-boot-starter-actuator` and your preferred metrics registry (Prometheus, Datadog, etc.) to export these metrics.

### Actuator Endpoint

When Spring Boot Actuator is on the classpath, a custom endpoint is available at `GET /actuator/paygate`:

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
        include: health,info,paygate
```

---

## Test Mode

For development and testing without a real Lightning node, enable test mode:

```yaml
paygate:
  enabled: true
  test-mode: true
  service-name: my-api
```

In test mode:
- A `TestModeLightningBackend` is automatically provided (no `backend` property needed)
- Invoices are dummy values with valid structure
- All invoices are treated as immediately settled
- The full L402 flow (challenge, credential validation) still executes

The example app activates test mode via the `dev` profile (`application-dev.yml` sets `paygate.test-mode: true`), not directly in `application.yml`.

**Safety guard:** Test mode refuses to start if any active Spring profile is `production` or `prod`, throwing an `IllegalStateException` at application startup.

---

## Architecture

```
+-------------------------------+
|  paygate-spring-boot-starter  |   Dependency aggregator (no code)
|  (user adds this dependency)  |   Pulls in autoconfigure + core + protocols
+-------------------------------+
         |              |
         v              v
+----------------+  +---------------------------+  +-------------------------+
|  paygate-core  |  |  paygate-spring-           |  |  paygate-spring-        |
|                |  |    autoconfigure           |  |    security             |
|  Macaroon V2   |  |                            |  |                         |
|  HMAC-SHA256   |  |  PaygateAutoConfiguration  |  |  L402Authentication-    |
|  Credential    |  |  PaygateSecurityFilter     |  |    Provider             |
|    Store       |  |  PaygateProperties         |  |  L402Authentication-    |
|  Lightning     |  |  @PaymentRequired          |  |    Filter               |
|    Backend     |  |                            |  |  L402Authentication-    |
|    (interface) |  |  PaygatePricingStrategy    |  |    Token                |
|  Delegation    |  |  PaygateMetrics            |  |                         |
|    Caveats     |  |  PaygateActuatorEndpoint   |  |  Integrates with       |
|                |  |  CachingLightning-         |  |  Spring Security       |
|  ZERO external |  |    BackendWrapper          |  |  filter chains         |
|  dependencies  |  |  TokenBucketRateLimiter    |  +-------------------------+
+----------------+  |  TestModeAutoConfiguration |
                    +---------------------------+
                         |              |
                         v              v
                  +-------------+  +---------------+
                  | paygate-    |  | paygate-       |
                  | lightning-  |  | lightning-lnd  |
                  | lnbits      |  |                |
                  | REST/JSON   |  | gRPC/Protobuf  |
                  | Jackson     |  | Netty          |
                  +-------------+  +---------------+

+-----------------+  +---------------------+  +---------------------+
| paygate-api     |  | paygate-protocol-   |  | paygate-protocol-   |
|                 |  |   l402              |  |   mpp               |
| PaymentProtocol |  |                     |  |                     |
|   (SPI)         |  | L402Protocol        |  | MppProtocol         |
| ChallengeContext|  | Adapts paygate-core |  | HMAC-SHA256 binding |
| PaymentCredential| | to PaymentProtocol  |  | JCS serialization   |
| PaymentReceipt  |  |                     |  | base64url-nopad     |
|                 |  | Depends on:         |  |                     |
| ZERO external   |  |   paygate-api       |  | Depends on:         |
| dependencies    |  |   paygate-core      |  |   paygate-api ONLY  |
+-----------------+  +---------------------+  | (NO paygate-core)   |
                                               +---------------------+

+-------------------------------+  +-------------------------------+
|  paygate-example-app          |  |  paygate-integration-tests    |
|  (not published as artifact)  |  |  (not published as artifact)  |
|  Shows annotation + dynamic   |  |  Cross-module integration     |
|  pricing + dual-protocol      |  |  tests                        |
+-------------------------------+  +-------------------------------+
```

### Module Dependency Graph

```
paygate-spring-boot-starter
  +-- paygate-spring-autoconfigure
  |     +-- paygate-core
  |     +-- paygate-api
  |     +-- paygate-protocol-l402 (optional)
  |     |     +-- paygate-api
  |     |     +-- paygate-core
  |     +-- paygate-protocol-mpp (optional)
  |     |     +-- paygate-api         (NO paygate-core)
  |     +-- paygate-spring-security (optional)
  +-- paygate-core
  +-- paygate-protocol-l402
  |     +-- paygate-api
  |     +-- paygate-core
  +-- paygate-protocol-mpp
        +-- paygate-api               (NO paygate-core)
```

### Module Responsibilities

| Module | Description | External Dependencies |
|--------|-------------|----------------------|
| `paygate-api` | Protocol abstraction SPI (`PaymentProtocol`, `ChallengeContext`, `PaymentCredential`, `PaymentReceipt`). Zero external dependencies. | **None** (JDK only) |
| `paygate-core` | Macaroon V2 serialization, HMAC-SHA256 crypto, credential store interface, Lightning backend interface, L402 protocol validation, delegation caveat verifiers (`path`, `method`, `client_ip`) | **None** (JDK only) |
| `paygate-protocol-l402` | L402 protocol adapter implementing `PaymentProtocol` by delegating to paygate-core. Handles `L402` and `LSAT` authorization schemes. | paygate-api, paygate-core |
| `paygate-protocol-mpp` | MPP protocol implementation. HMAC-SHA256 challenge binding, JCS serialization, base64url-nopad encoding. Depends on paygate-api ONLY (no paygate-core). | paygate-api |
| `paygate-lightning-lnbits` | `LightningBackend` implementation using the LNbits REST API | Jackson |
| `paygate-lightning-lnd` | `LightningBackend` implementation using the LND gRPC API | gRPC, Protobuf, Netty |
| `paygate-spring-autoconfigure` | Spring Boot auto-configuration, servlet filter, annotation scanning, metrics, actuator, health caching, rate limiting, dual-protocol bean wiring | Spring Boot, Spring MVC, Caffeine (optional), Micrometer (optional), Actuator (optional) |
| `paygate-spring-security` | Spring Security integration: `L402AuthenticationProvider`, `L402AuthenticationFilter`, and `L402AuthenticationToken` for use in security filter chains | Spring Security |
| `paygate-spring-boot-starter` | Dependency aggregator. No source code. | -- |
| `paygate-example-app` | Runnable reference application with dynamic pricing and dual-protocol support | Spring Boot Web |
| `paygate-integration-tests` | Cross-module integration tests verifying dual-protocol behavior, fail-closed semantics, Go interoperability, and tamper detection | Spring Boot Test |

### Key Design Decisions

- **paygate-core has zero external dependencies.** All cryptography uses `javax.crypto` and `java.security` from the JDK. This makes the core portable and auditable.
- **paygate-api has zero external dependencies.** The protocol abstraction layer depends only on the JDK, ensuring protocol implementations can be added without pulling in framework dependencies.
- **paygate-protocol-mpp depends only on paygate-api (not paygate-core).** This keeps MPP entirely independent from the macaroon-based L402 implementation.
- **Macaroon V2 binary format is byte-level compatible with Go `go-macaroon`.** Cross-language interoperability is a first-class requirement.
- **Identifier layout is fixed at 66 bytes:** `[version:2 bytes BE][payment_hash:32][token_id:32]`. This ensures deterministic parsing.
- **All beans are guarded with `@ConditionalOnMissingBean`.** Users can override any component by declaring their own bean.
- **Fail-closed security model.** If the Lightning backend is unreachable, the filter returns HTTP 503 -- it never returns 200 for a protected endpoint without valid credentials.

---

## Security Considerations

This library handles payment credentials and cryptographic tokens. The following security properties are enforced:

### Cryptographic Integrity

- **HMAC-SHA256** for macaroon minting and verification, using JDK `javax.crypto.Mac`
- **HMAC-SHA256** for MPP challenge binding, ensuring stateless challenge verification
- **Constant-time comparison** for all secret material (root keys, signatures, preimages, HMAC bindings) using XOR accumulation -- never `Arrays.equals`
- **Key derivation** follows the macaroon specification: `HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`
- **SecureRandom** for all random byte generation (token IDs, root keys)

### Operational Security

- **Root key storage** defaults to file-based storage at `~/.paygate/keys`. In production, ensure this directory has restricted permissions (`chmod 700`)
- **Never log full macaroon values** -- only token IDs appear in logs
- **Environment variables** should be used for Lightning backend credentials (`api-key`, `macaroon-path`) and MPP challenge binding secrets, not plaintext in configuration files
- **Test mode is blocked in production** -- the `TestModeAutoConfiguration` throws at startup if `prod` or `production` profiles are active
- **Fail-closed** -- any unexpected exception during validation produces HTTP 503, never leaking protected content

### Recommendations for Production

1. Use file-based root key storage with proper filesystem permissions
2. Store Lightning credentials and MPP secrets in environment variables or a secrets manager
3. Enable TLS for LND connections (do not use plaintext in production)
4. Monitor the `paygate.lightning.healthy` gauge and alert when it drops to 0
5. Set `paygate.credential-cache-max-size` based on your expected concurrent credential volume
6. Review and rotate root keys periodically
7. Use a strong, randomly generated `challenge-binding-secret` for MPP (minimum 32 bytes)

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
./gradlew :paygate-core:test
```

Test coverage reports (JaCoCo) are generated at `build/reports/jacoco/` in each module and aggregated at the root.

---

## Spec Deviations

### Price Unit: Satoshis vs Milli-satoshis

The L402 `protocol-specification.md` recommends expressing prices in milli-satoshis (1/1000th of a satoshi). This library uses **satoshis** as the price unit (`paygate.default-price-sats`, `@PaymentRequired(priceSats = ...)`).

**Rationale:** Satoshis are the practical unit for most L402 use cases. BOLT 11 invoices handle the conversion to milli-satoshis internally. Using whole satoshis avoids fractional pricing complexity for the vast majority of API monetization scenarios where sub-satoshi granularity is unnecessary.

---

## Contributing

Contributions are welcome. Please follow these guidelines:

1. **Open an issue first** for significant changes to discuss the approach
2. **Fork and branch** from `main`
3. **Follow existing code conventions** -- Java 25 idioms (records, sealed classes, pattern matching), Javadoc on public types
4. **Maintain the zero-dependency constraint** on `paygate-core` and `paygate-api` -- no external libraries
5. **Add tests** -- the project enforces code coverage via JaCoCo (80% for paygate-core, 60% for most modules, 40% for example app)
6. **All secret comparisons must be constant-time** (XOR accumulation)
7. **Never log full macaroon values** -- only token IDs

---

## License

This project is licensed under the [MIT License](LICENSE).

Copyright (c) 2026 Green Harbor Labs
