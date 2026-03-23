# paygate-example-app-spring-security

A reference Spring Boot application demonstrating how to integrate `spring-boot-starter-paygate` with **Spring Security** to protect API endpoints with Lightning payments using dual-protocol support (L402 + MPP).

This application runs in **test mode** by default, so no real Lightning node is required. You can exercise the complete payment flow -- challenge, payment, credential presentation, and authorization -- entirely on your local machine.

For the servlet filter approach (without Spring Security), see the sibling module [`paygate-example-app`](../paygate-example-app/).

---

## Table of Contents

- [Overview](#overview)
- [How This Differs from the Servlet Filter Example](#how-this-differs-from-the-servlet-filter-example)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
  - [Authentication Flow](#authentication-flow)
  - [Spring Security Components](#spring-security-components)
  - [SecurityFilterChain Configuration](#securityfilterchain-configuration)
- [Authorization Model](#authorization-model)
  - [ROLE_PAYMENT -- Universal Payment Role](#role_payment----universal-payment-role)
  - [ROLE_L402 -- Protocol-Specific Role](#role_l402----protocol-specific-role)
  - [L402_CAPABILITY_* -- Capability-Based Authorization](#l402_capability----capability-based-authorization)
  - [Authorization Summary](#authorization-summary)
- [Endpoints](#endpoints)
- [Configuration](#configuration)
  - [Base Configuration](#base-configuration)
  - [Dev Profile](#dev-profile)
  - [Key Properties](#key-properties)
- [Dual-Protocol Support](#dual-protocol-support)
  - [The 402 Challenge Response](#the-402-challenge-response)
  - [Protocol Behavior Matrix](#protocol-behavior-matrix)
- [Accessing Authentication Details](#accessing-authentication-details)
- [Testing the Flow with curl](#testing-the-flow-with-curl)
  - [Step 1: Health Check (Public)](#step-1-health-check-public)
  - [Step 2: Request a Protected Endpoint](#step-2-request-a-protected-endpoint)
  - [Step 3: Present Credentials](#step-3-present-credentials)
  - [Step 4: Protocol-Specific Authorization](#step-4-protocol-specific-authorization)
- [Running the Tests](#running-the-tests)
  - [What the Tests Cover](#what-the-tests-cover)
  - [How the Full-Flow Test Works](#how-the-full-flow-test-works)
- [Comparison with Servlet Filter Mode](#comparison-with-servlet-filter-mode)
- [Project Structure](#project-structure)
- [Connecting a Real Lightning Backend](#connecting-a-real-lightning-backend)

---

## Overview

This example demonstrates the **Spring Security integration** for Paygate. Instead of a standalone servlet filter that handles both challenge issuance and credential validation, this approach uses Spring Security's standard architecture:

- A `SecurityFilterChain` bean defines which endpoints require payment and what authorization rules apply.
- Payment credentials flow through Spring Security's `AuthenticationManager`, producing a `PaygateAuthenticationToken` that lives in the `SecurityContextHolder`.
- Authorization decisions use standard Spring Security mechanisms: `hasRole()` in the filter chain and `@PreAuthorize` annotations on controller methods.

This gives you fine-grained, declarative control over authorization -- including the ability to require specific protocols (L402 only) or specific capabilities (from macaroon caveats) on individual endpoints.

---

## How This Differs from the Servlet Filter Example

| Aspect | Servlet Filter (`paygate-example-app`) | Spring Security (this module) |
|--------|----------------------------------------|-------------------------------|
| Integration point | `PaygateSecurityFilter` (standalone servlet filter) | `PaygateAuthenticationFilter` + `PaygateAuthenticationProvider` + `PaygateAuthenticationEntryPoint` |
| Configuration | Auto-configured; no explicit setup | Explicit `SecurityFilterChain` bean |
| Authorization model | Binary: paid or not paid | Role-based (`ROLE_PAYMENT`, `ROLE_L402`) and capability-based (`L402_CAPABILITY_*`) |
| Protocol-specific rules | Not supported | `hasRole("L402")` restricts to L402 only |
| Method-level security | Not supported | `@PreAuthorize` with SpEL expressions |
| Authentication context | Request attribute | `SecurityContextHolder` (`PaygateAuthenticationToken`) |
| Dependency | `paygate-spring-boot-starter` | `paygate-spring-boot-starter` + `paygate-spring-security` + `spring-boot-starter-security` |

Choose the servlet filter approach for simple "pay to access" use cases. Choose Spring Security when you need role-based authorization, protocol-specific restrictions, capability-based access control, or integration with an existing Spring Security configuration.

---

## Prerequisites

- **Java 25** (LTS)
- **curl** (or any HTTP client) for testing the endpoints

No Lightning node or wallet is required. The application ships with test mode enabled.

---

## Quick Start

From the **project root** (not from inside this module's directory):

```bash
./gradlew :paygate-example-app-spring-security:bootRun
```

The application starts on `http://localhost:8081`.

Verify it is running:

```bash
curl http://localhost:8081/api/v1/health
```

Expected response:

```json
{"status":"ok"}
```

---

## Architecture

### Authentication Flow

When a client requests a protected endpoint, the following sequence occurs:

```
Client                                      Spring Security
  |                                              |
  |  GET /api/v1/data                            |
  |  (no Authorization header)                   |
  |--------------------------------------------->|
  |                                              |
  |  PaygateAuthenticationFilter: no credential  |
  |  -> filter chain continues                   |
  |  -> SecurityFilterChain: hasRole("PAYMENT")  |
  |  -> no authentication in SecurityContext     |
  |  -> PaygateAuthenticationEntryPoint invoked  |
  |     -> issues 402 with L402 + MPP challenges |
  |                                              |
  |  402 Payment Required                        |
  |  WWW-Authenticate: L402 macaroon="...",      |
  |                    invoice="..."             |
  |  WWW-Authenticate: Payment ...               |
  |<---------------------------------------------|
  |                                              |
  |  (client pays invoice, obtains preimage)     |
  |                                              |
  |  GET /api/v1/data                            |
  |  Authorization: L402 <macaroon>:<preimage>   |
  |--------------------------------------------->|
  |                                              |
  |  PaygateAuthenticationFilter:                |
  |    -> extracts L402 credential               |
  |    -> creates unauthenticated token          |
  |    -> calls AuthenticationManager            |
  |  PaygateAuthenticationProvider:              |
  |    -> validates macaroon signature           |
  |    -> verifies SHA-256(preimage)==paymentHash |
  |    -> verifies caveats                       |
  |    -> returns authenticated token with       |
  |       ROLE_PAYMENT + ROLE_L402               |
  |  SecurityContextHolder populated             |
  |  -> SecurityFilterChain: hasRole("PAYMENT")  |
  |  -> GRANTED                                  |
  |                                              |
  |  200 OK                                      |
  |  {"data": "premium content"}                 |
  |<---------------------------------------------|
```

### Spring Security Components

Paygate provides three Spring Security components, all auto-configured by the `paygate-spring-security` module when `paygate.security-mode=spring-security`:

| Component | Role | Spring Security Interface |
|-----------|------|--------------------------|
| `PaygateAuthenticationFilter` | Extracts payment credentials from the `Authorization` header and delegates to the `AuthenticationManager`. Supports both L402 (`L402 <macaroon>:<preimage>`) and protocol-agnostic credentials (e.g., MPP `Payment` scheme). | Extends `OncePerRequestFilter` |
| `PaygateAuthenticationProvider` | Validates L402 credentials via `L402Validator` and protocol-agnostic credentials via `PaymentProtocol.validate()`. Returns an authenticated `PaygateAuthenticationToken` with roles and capabilities. | Implements `AuthenticationProvider` |
| `PaygateAuthenticationEntryPoint` | Issues HTTP 402 Payment Required challenges when an unauthenticated request reaches a protected endpoint. Generates Lightning invoices and challenge headers for all active protocols. | Implements `AuthenticationEntryPoint` |

These beans are auto-configured and injected into your `SecurityFilterChain`. You do not instantiate them manually.

### SecurityFilterChain Configuration

The core of this example is the `SecurityConfig` class, which wires the Paygate components into a standard Spring Security filter chain:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PaygateAuthenticationFilter paygateFilter,
            PaygateAuthenticationProvider paygateProvider,
            PaygateAuthenticationEntryPoint paygateEntryPoint) throws Exception {

        return http
                .authenticationProvider(paygateProvider)
                .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Health endpoint is public
                        .requestMatchers("/api/v1/health").permitAll()

                        // L402-only endpoint: requires L402 protocol specifically
                        .requestMatchers("/api/v1/l402-only").hasRole("L402")

                        // All other /api/** endpoints accept any payment protocol
                        .requestMatchers("/api/**").hasRole("PAYMENT")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(paygateEntryPoint)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
```

Key points:

- **`addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)`** -- Places the payment credential extraction filter early in the chain, before Spring Security's built-in authentication filters.
- **`authenticationProvider(paygateProvider)`** -- Registers the Paygate provider so the `AuthenticationManager` knows how to validate `PaygateAuthenticationToken` instances.
- **`authenticationEntryPoint(paygateEntryPoint)`** -- When an unauthenticated request reaches a `hasRole()` check that fails, this entry point issues the 402 challenge instead of a 401 redirect.
- **`SessionCreationPolicy.STATELESS`** -- Payment credentials are verified on every request. No HTTP session is created.
- **`csrf.disable()`** -- CSRF protection is disabled because payment APIs are stateless and authenticated via the `Authorization` header.
- **`@EnableMethodSecurity`** -- Enables `@PreAuthorize` annotations for capability-based authorization on individual controller methods.

---

## Authorization Model

The Spring Security integration provides a three-tier authorization model.

### ROLE_PAYMENT -- Universal Payment Role

Every successfully authenticated payment credential -- regardless of protocol (L402 or MPP) -- is granted the `ROLE_PAYMENT` authority. Use this when you want to accept payment from any protocol:

```java
.requestMatchers("/api/**").hasRole("PAYMENT")
```

This is the most common authorization pattern. Any client that has paid via either L402 or MPP will pass this check.

### ROLE_L402 -- Protocol-Specific Role

Only L402 credentials are granted the `ROLE_L402` authority. MPP credentials do not receive this role. Use this to restrict specific endpoints to L402 only:

```java
.requestMatchers("/api/v1/l402-only").hasRole("L402")
```

An MPP credential presented to this endpoint will be authenticated (the filter and provider succeed), but the authorization check will fail with **403 Forbidden** because the token lacks `ROLE_L402`.

This is useful when an endpoint depends on L402-specific features like macaroon caveats, or when you want to enforce a specific protocol for compliance or business reasons.

### L402_CAPABILITY_* -- Capability-Based Authorization

L402 macaroons can carry capability caveats (e.g., `example-api_capabilities=premium-analyze`). These are mapped to Spring Security authorities with the prefix `L402_CAPABILITY_`. Use `@PreAuthorize` with `hasAuthority()` for fine-grained access control:

```java
@PaymentRequired(priceSats = 25, capability = "premium-analyze",
        description = "Capability-gated analysis")
@PreAuthorize("hasAuthority('L402_CAPABILITY_premium-analyze')")
@PostMapping("/premium-analyze")
public AnalyzeResponse premiumAnalyze(@RequestBody AnalyzeRequest request) {
    // Only accessible with an L402 credential that has the "premium-analyze" capability
}
```

The `capability` attribute on `@PaymentRequired` tells the validator to check the macaroon's capability caveat. The `@PreAuthorize` annotation enforces the same check at the Spring Security authorization layer. Together, they ensure that:

1. The macaroon was minted with the `premium-analyze` capability caveat.
2. The Spring Security context contains the corresponding authority.

### Authorization Summary

| Authority | Granted To | Use Case |
|-----------|------------|----------|
| `ROLE_PAYMENT` | All authenticated credentials (L402 + MPP) | Accept payment from any protocol |
| `ROLE_L402` | L402 credentials only | Require L402 protocol specifically |
| `L402_CAPABILITY_<name>` | L402 credentials with matching capability caveat | Fine-grained, capability-based access control |

---

## Endpoints

| Method | Path | Price | Authorization | Description |
|--------|------|-------|---------------|-------------|
| GET | `/api/v1/health` | -- | `permitAll()` | Health check. Always accessible without payment. |
| GET | `/api/v1/quote` | 5 sats | `hasRole("PAYMENT")` | Premium quote. Accepts L402 or MPP. |
| GET | `/api/v1/data` | 10 sats | `hasRole("PAYMENT")` | Premium content. Accepts L402 or MPP. |
| POST | `/api/v1/analyze` | 50 sats | `hasRole("PAYMENT")` | Content analysis with dynamic pricing. Accepts L402 or MPP. |
| GET | `/api/v1/protocol-info` | 1 sat | `hasRole("PAYMENT")` | Returns details about the authenticated credential (protocol, tokenId, attributes). |
| GET | `/api/v1/l402-only` | 10 sats | `hasRole("L402")` | L402-exclusive endpoint. MPP credentials receive 403 Forbidden. |
| POST | `/api/v1/premium-analyze` | 25 sats | `hasRole("PAYMENT")` + `@PreAuthorize("hasAuthority('L402_CAPABILITY_premium-analyze')")` | Capability-gated analysis. Requires L402 with the `premium-analyze` capability caveat. |

---

## Configuration

### Base Configuration

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: paygate-example-app-spring-security
  profiles:
    active: dev

server:
  port: 8081

paygate:
  enabled: true
  service-name: example-api
  security-mode: spring-security
  protocols:
    mpp:
      challenge-binding-secret: ${PAYGATE_MPP_SECRET:}
```

### Dev Profile

`src/main/resources/application-dev.yml`:

```yaml
paygate:
  test-mode: true
  protocols:
    mpp:
      challenge-binding-secret: dev-only-mpp-test-secret-do-not-use-in-production
```

The `dev` profile is active by default. It enables test mode (no real Lightning node needed) and provides a development-only MPP challenge binding secret.

### Key Properties

| Property | Value | Effect |
|----------|-------|--------|
| `paygate.enabled` | `true` | Activates Paygate auto-configuration. |
| `paygate.security-mode` | `spring-security` | Uses Spring Security components instead of the standalone servlet filter. **This is the critical property.** |
| `paygate.service-name` | `example-api` | Appears in macaroon caveats (`services=example-api:0`). Used to scope capability caveats. |
| `paygate.test-mode` | `true` (dev profile) | Uses `TestModeLightningBackend` instead of a real node. |
| `paygate.protocols.mpp.challenge-binding-secret` | (secret) | HMAC secret for MPP challenge binding. When present and non-blank, enables the MPP protocol alongside L402. Minimum 32 bytes in production. |

The `paygate.security-mode` property controls which integration style is activated:

| Value | Behavior |
|-------|----------|
| `auto` (default) | Uses Spring Security if `spring-boot-starter-security` is on the classpath, otherwise falls back to servlet filter. |
| `spring-security` | Explicitly uses Spring Security. Fails if Spring Security is not on the classpath. |
| `servlet` | Explicitly uses the standalone servlet filter, even if Spring Security is present. |

---

## Dual-Protocol Support

When both L402 and MPP protocols are active, the server issues challenges for both protocols simultaneously. The client chooses which protocol to use.

### The 402 Challenge Response

When a client requests a protected endpoint without credentials, the response contains **multiple `WWW-Authenticate` headers** -- one for each active protocol:

```
HTTP/1.1 402
WWW-Authenticate: L402 macaroon="AgJ...base64...", invoice="lntb10test..."
WWW-Authenticate: Payment method="lightning", ...
Content-Type: application/json

{
  "code": 402,
  "message": "Payment required",
  "price_sats": 10,
  "description": "",
  "invoice": "lntb10test...",
  "protocols": {
    "Payment": {
      "method": "lightning",
      ...
    }
  }
}
```

The JSON body includes a `protocols` object with details for each active protocol, allowing clients to programmatically select a payment method.

### Protocol Behavior Matrix

| Scenario | L402 Credential | MPP Credential |
|----------|-----------------|----------------|
| `hasRole("PAYMENT")` endpoint | 200 OK | 200 OK |
| `hasRole("L402")` endpoint | 200 OK | 403 Forbidden |
| `@PreAuthorize("hasAuthority('L402_CAPABILITY_...')")` | 200 OK (if caveat present) | 403 Forbidden |
| No credential | 402 Payment Required (both challenges) | 402 Payment Required (both challenges) |
| Invalid credential | 401 Unauthorized | 401 Unauthorized |

---

## Accessing Authentication Details

After successful authentication, the `PaygateAuthenticationToken` is available from the `SecurityContextHolder`. Use it to access protocol details, token IDs, and attributes in your controller methods:

```java
@PaymentRequired(priceSats = 1, description = "Protocol info")
@GetMapping(value = "/protocol-info", produces = MediaType.APPLICATION_JSON_VALUE)
public ProtocolInfoResponse protocolInfo() {
    var auth = (PaygateAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
    return new ProtocolInfoResponse(
            auth.getTokenId(),
            auth.getProtocolScheme(),
            auth.getAttributes(),
            Instant.now().toString()
    );
}
```

### Available Methods on PaygateAuthenticationToken

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getTokenId()` | `String` | Unique identifier for the payment credential. |
| `getProtocolScheme()` | `String` | Protocol used: `"L402"` or `"Payment"`. |
| `getAttributes()` | `Map<String, String>` | All attributes including tokenId, serviceName, protocolScheme, and (for L402) macaroon caveats. |
| `getAttribute(String key)` | `String` | Look up a single attribute by key. |
| `getServiceName()` | `String` | The service name from configuration. |
| `getL402Credential()` | `L402Credential` | The underlying L402 credential (null for MPP). |
| `getPaymentCredential()` | `PaymentCredential` | The protocol-agnostic credential (null for L402-only path). |
| `getAuthorities()` | `Collection<GrantedAuthority>` | Granted authorities (`ROLE_PAYMENT`, `ROLE_L402`, `L402_CAPABILITY_*`). |

---

## Testing the Flow with curl

### Step 1: Health Check (Public)

```bash
curl http://localhost:8081/api/v1/health
```

Expected: `{"status":"ok"}` (HTTP 200)

### Step 2: Request a Protected Endpoint

```bash
curl -i http://localhost:8081/api/v1/data
```

Expected (HTTP 402):

```
HTTP/1.1 402
WWW-Authenticate: L402 macaroon="AgJ...base64...", invoice="lntb10test..."
WWW-Authenticate: Payment ...
Content-Type: application/json

{"code":402,"message":"Payment required","price_sats":10,...,"test_preimage":"a1b2c3..."}
```

The `test_preimage` field only appears in test mode. In a real deployment, the preimage is obtained by paying the Lightning invoice.

### Step 3: Present Credentials

Extract the macaroon from the `WWW-Authenticate` header and the `test_preimage` from the JSON body:

```bash
# Full automated flow
RESPONSE=$(curl -s -i http://localhost:8081/api/v1/data)

MACAROON=$(echo "$RESPONSE" | grep -o 'macaroon="[^"]*"' | sed 's/macaroon="//;s/"//')

PREIMAGE=$(echo "$RESPONSE" | grep -o '"test_preimage":"[^"]*"' | sed 's/"test_preimage":"//;s/"//')

curl -i -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
     http://localhost:8081/api/v1/data
```

Expected (HTTP 200):

```json
{"data":"premium content","timestamp":"2026-03-23T..."}
```

### Step 4: Protocol-Specific Authorization

Using the same L402 credential from Step 3, request the L402-only endpoint:

```bash
curl -i -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
     http://localhost:8081/api/v1/l402-only
```

Expected (HTTP 200):

```json
{"data":"L402-exclusive content","timestamp":"2026-03-23T..."}
```

An MPP credential sent to this endpoint would receive HTTP 403 Forbidden.

---

## Running the Tests

From the project root:

```bash
./gradlew :paygate-example-app-spring-security:test
```

### What the Tests Cover

| Test Class | Test | What It Verifies |
|------------|------|------------------|
| `HealthEndpoint` | `returns200WithoutAuth` | Health endpoint is accessible without authentication. |
| `DataEndpointNoAuth` | `returns402WithL402Challenge` | Protected endpoint returns 402 with `WWW-Authenticate: L402` header containing macaroon and invoice. |
| `DataEndpointNoAuth` | `returns402WithMppChallenge` | Protected endpoint returns 402 with `WWW-Authenticate: Payment` header for MPP. |
| `DataEndpointNoAuth` | `returns402JsonBody` | The 402 response body includes JSON with `code: 402` and `price_sats: 10`. |
| `DataEndpointNoAuth` | `returns402WithProtocolsArray` | The 402 response body includes a `protocols` object with MPP details. |
| `FullL402Flow` | `validCredentialReturns200` | A self-minted L402 credential grants access with HTTP 200. |
| `ProtocolInfoEndpoint` | `l402CredentialReturnsProtocolInfo` | The protocol-info endpoint returns protocol scheme and token ID from the `PaygateAuthenticationToken`. |
| `L402OnlyEndpoint` | `l402CredentialGrantsAccess` | An L402 credential passes the `hasRole("L402")` authorization check. |

### How the Full-Flow Test Works

The integration test cannot use a preimage from a server-issued 402 challenge because the test backend generates a random payment hash (inverting SHA-256 to derive the preimage is impossible). Instead, the test:

1. Generates a random 32-byte preimage.
2. Computes `paymentHash = SHA-256(preimage)`.
3. Generates a root key from the application's own `RootKeyStore` (using `root-key-store=memory` so the test shares the same store as the security filter).
4. Mints a macaroon with an identifier containing the payment hash and token ID, signed with the root key.
5. Builds the `Authorization: L402 <macaroon>:<preimage>` header.
6. Sends the request and asserts HTTP 200 with the expected response.

This exercises the full Spring Security authentication path: filter extraction, provider validation (root key lookup, macaroon signature check, preimage-to-paymentHash SHA-256 match), token creation with roles, and `SecurityContextHolder` population.

The test uses `@TestPropertySource` to configure the security mode:

```java
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.security-mode=spring-security",
        "paygate.protocols.mpp.challenge-binding-secret=test-only-mpp-secret-minimum-32-bytes-long"
})
```

---

## Comparison with Servlet Filter Mode

| Feature | Servlet Filter (`paygate-example-app`) | Spring Security (this module) |
|---------|----------------------------------------|-------------------------------|
| Setup complexity | Zero-config (auto-configured) | Requires a `SecurityFilterChain` bean |
| Dependencies | `paygate-spring-boot-starter` | `paygate-spring-boot-starter` + `paygate-spring-security` + `spring-boot-starter-security` |
| Authorization granularity | Binary (paid / not paid) | Role-based + capability-based |
| Protocol-specific restrictions | Not available | `hasRole("L402")` to require L402 specifically |
| Method-level security | Not available | `@PreAuthorize` with SpEL |
| Authentication context | Request attributes | `SecurityContextHolder` (standard Spring Security) |
| Composability with other auth | Standalone filter | Composes with other Spring Security providers (OAuth2, Basic, etc.) |
| Port (in examples) | 8080 | 8081 |
| **When to choose** | Simple pay-per-call APIs; no existing Spring Security setup | Apps with existing Spring Security; need for role/capability authorization; protocol-specific restrictions |

---

## Project Structure

```
paygate-example-app-spring-security/
  src/main/java/com/greenharborlabs/paygate/example/security/
    SecurityExampleApplication.java     Main class (@SpringBootApplication)
    SecurityConfig.java                 SecurityFilterChain configuration (the primary teaching artifact)
    SecurityExampleController.java      REST endpoints with @PaymentRequired and @PreAuthorize
  src/main/resources/
    application.yml                     Base configuration (security-mode, protocols, service name)
    application-dev.yml                 Dev profile (test mode, MPP secret)
  src/test/java/
    SecurityExampleAppIntegrationTest.java   Full payment + authorization flow integration tests
  build.gradle.kts                      Module build file
```

### Dependencies

Declared in `build.gradle.kts`:

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `paygate-spring-boot-starter` | implementation | Pulls in core, auto-configuration, and transitive dependencies |
| `paygate-spring-security` | implementation | Spring Security integration (filter, provider, entry point, token) |
| `paygate-lightning-lnbits` | implementation | LNbits backend (on the classpath for real deployments; test mode overrides it) |
| `paygate-lightning-lnd` | implementation | LND backend (on the classpath for real deployments; test mode overrides it) |
| `spring-boot-starter-web` | implementation | Spring MVC, embedded Tomcat |
| `spring-boot-starter-security` | implementation | Spring Security framework |
| `spring-boot-starter-test` | testImplementation | JUnit 5, MockMvc, AssertJ |
| `spring-boot-starter-webmvc-test` | testImplementation | `@AutoConfigureMockMvc` support |
| `spring-security-test` | testImplementation | Spring Security test utilities |

---

## Connecting a Real Lightning Backend

To use this example with a real Lightning node, disable test mode and configure a backend. The process is identical to the servlet filter example.

### With LNbits

```yaml
paygate:
  enabled: true
  test-mode: false
  backend: lnbits
  security-mode: spring-security
  service-name: example-api
  lnbits:
    url: https://your-lnbits-instance.com
    api-key: ${LNBITS_API_KEY}
  protocols:
    mpp:
      challenge-binding-secret: ${PAYGATE_MPP_SECRET}
```

### With LND

```yaml
paygate:
  enabled: true
  test-mode: false
  backend: lnd
  security-mode: spring-security
  service-name: example-api
  lnd:
    host: localhost
    port: 10009
    tls-cert-path: /path/to/tls.cert
    macaroon-path: /path/to/invoice.macaroon
  protocols:
    mpp:
      challenge-binding-secret: ${PAYGATE_MPP_SECRET}
```

See [`paygate-lightning-lnbits/README.md`](../paygate-lightning-lnbits/README.md) and [`paygate-lightning-lnd/README.md`](../paygate-lightning-lnd/README.md) for backend-specific details.

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
