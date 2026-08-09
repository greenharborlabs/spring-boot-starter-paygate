# paygate-spring-security

Optional [Spring Security](https://spring.io/projects/spring-security) integration for the `spring-boot-starter-paygate` project. This module bridges L402 and other registered `PaymentProtocol` credentials into Spring Security's filter chain, providing a request filter, `AuthenticationProvider`, and `AuthenticationToken` that let you protect endpoints using standard Spring Security patterns -- `SecurityFilterChain`, `@PreAuthorize`, role-based access, and the `SecurityContextHolder`.

If you do not use Spring Security, you do not need this module. The base `paygate-spring-autoconfigure` module provides a standalone servlet `Filter` (`PaygateSecurityFilter`) and `@PaymentRequired` annotation that work without Spring Security on the classpath.

---

## Table of Contents

- [When to Use This Module](#when-to-use-this-module)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Architecture](#architecture)
- [Auto-Configuration](#auto-configuration)
- [Usage](#usage)
- [Path-Based Protection Patterns](#path-based-protection-patterns)
- [Accessing Payment Credentials in Controllers](#accessing-payment-credentials-in-controllers)
- [SpEL and @PreAuthorize](#spel-and-preauthorize)
- [Capability Enforcement](#capability-enforcement)
- [Trust and Serialization Guarantees](#trust-and-serialization-guarantees)
- [Comparison with Non-Spring-Security Approach](#comparison-with-non-spring-security-approach)
- [Testing](#testing)

---

## When to Use This Module

Use `paygate-spring-security` when your application already uses Spring Security and you want L402 authentication to participate in the security filter chain alongside other authentication mechanisms (OAuth2, HTTP Basic, form login, etc.).

Use the base `paygate-spring-autoconfigure` module (with `@PaymentRequired`) when you have a simpler setup without Spring Security, or when you want annotation-driven L402 protection that operates independently of any security framework.

| Scenario | Recommended Module |
|----------|-------------------|
| No Spring Security dependency | `paygate-spring-autoconfigure` with `@PaymentRequired` |
| Spring Security is present, L402 is the only auth mechanism | `paygate-spring-security` |
| Spring Security with mixed auth (L402 + OAuth2/JWT/Basic) | `paygate-spring-security` |
| Need `@PreAuthorize` expressions based on L402 caveats | `paygate-spring-security` |
| Need `ROLE_L402` authority for access control | `paygate-spring-security` |

---

## Prerequisites

- **Java 25** (LTS)
- **Spring Boot 4.0.5** with **Spring Security** on the classpath
- **A configured Lightning backend** (`paygate-lightning-lnbits` or `paygate-lightning-lnd`) -- required for the `L402Validator` bean that this module depends on
- **`paygate.enabled=true`** in application properties

---

## Installation

Add this module alongside the starter and a Lightning backend. The starter pulls in `paygate-core` and `paygate-spring-autoconfigure` transitively.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.4")
implementation("com.greenharborlabs:paygate-spring-security:0.1.4")
implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.4") // or paygate-lightning-lnd
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-boot-starter</artifactId>
    <version>0.1.4</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-security</artifactId>
    <version>0.1.4</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-lightning-lnbits</artifactId>
    <version>0.1.4</version>
</dependency>
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| `paygate-core` | `L402Validator`, `L402Credential`, `Macaroon`, `Caveat` -- core protocol types |
| `spring-security-core` | `AuthenticationProvider`, `AbstractAuthenticationToken`, `GrantedAuthority` |
| `spring-security-web` | `OncePerRequestFilter` for the authentication filter |
| `spring-security-config` | `@EnableWebSecurity` detection for conditional auto-configuration |
| `spring-boot-autoconfigure` | `@AutoConfiguration`, `@ConditionalOnBean`, `@ConditionalOnClass` |

---

## Architecture

The module's main classes are in the `com.greenharborlabs.paygate.spring.security` package:

```
paygate-spring-security/
  src/main/java/com/greenharborlabs/paygate/spring/security/
    PaygateAuthenticationEntryPoint.java      Issues 402 challenges with Lightning invoices
    PaygateAuthenticationFilter.java          Extracts payment credentials from Authorization headers
    PaygateAuthenticationProvider.java        Validates L402 and other PaymentProtocol credentials
    PaygateAuthenticationToken.java           Spring Security token (unauthenticated/authenticated states)
    PaygateAuthFailureRateLimitFilter.java    Optional auth-failure rate-limiting filter
    PaygateSecurityAutoConfiguration.java     Registers beans when Spring Security is present
    CapabilityResolver.java                   Capability resolution strategy interface
    DefaultCapabilityResolver.java            Non-L402 resolver backed by cache and request metadata
    CapabilityResolutionContext.java          Immutable context for capability resolution
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/com/greenharborlabs/paygate/spring/security/
    PaygateAuthenticationEntryPointTest.java  Entry point challenge and error handling tests
    PaygateAuthenticationFilterTest.java      Filter behavior tests with MockHttpServletRequest
    PaygateAuthenticationProviderTest.java    Provider validation and error handling tests
    PaygateAuthenticationTokenTest.java       Token lifecycle and attribute extraction tests
```

### Recommended Filter Chain Order

When rate limiting is enabled (`PaygateRateLimiter` bean present), the recommended filter order is:

```
PaygateAuthFailureRateLimitFilter --> PaygateAuthenticationFilter --> (rest of chain)
```

The rate limit filter is auto-configured only when a `PaygateRateLimiter` bean exists. It applies two phases of protection:

1. **Pre-check:** Consumes a rate limiter token on entry. If exhausted, returns 429 before any downstream processing.
2. **Post-failure penalty:** After the chain returns, if the response status is 401 or 503 (auth failure), consumes an additional penalty token to penalize brute-force probing.

Requests without a payment-scheme `Authorization` header bypass this filter entirely (challenge issuance in `PaygateAuthenticationEntryPoint` has its own rate limiting via `PaygateChallengeService`).

### Request Flow

```
Client Request
     |
     v
PaygateAuthFailureRateLimitFilter (OncePerRequestFilter, optional)
     |
     |-- No payment Authorization header? --> skip (shouldNotFilter)
     |-- Endpoint not protected? --> pass through, no rate limiting
     |-- Rate limit exhausted? --> 429 Too Many Requests
     |-- Rate limit OK? --> continue to next filter
     |       |
     |       v (after chain returns: 401/503 --> consume penalty token)
     |
     v
PaygateAuthenticationFilter (OncePerRequestFilter)
     |
     |-- No recognized payment Authorization header? --> continue filter chain
     |
     |-- Header matches L402/LSAT or another registered PaymentProtocol?
     |       |
     |       v
     |   Resolve capability from PaygateEndpointRegistry (if configured)
     |       |
     |       v
     |   AuthenticationManager.authenticate(unauthenticatedToken)
     |       |
     |       v
     |   PaygateAuthenticationProvider
     |       |
     |       |-- Reconstructs "L402 <macaroon>:<preimage>" header
     |       |-- Builds L402VerificationContext with requestMetadata (including capability)
     |       |-- Delegates to L402Validator.validate() (includes CapabilitiesCaveatVerifier)
     |       |-- Returns authenticated PaygateAuthenticationToken with:
     |       |     - ROLE_PAYMENT authority (plus ROLE_L402 for L402)
     |       |     - PAYGATE_CAPABILITY_* authorities (from the final verified capability ceiling)
     |       |     - tokenId as principal
     |       |     - credential-free authenticated state
     |       |     - verifier-approved attributes map
     |       |
     |       v
     |   SecurityContextHolder populated --> continue filter chain
     |
     |-- Authentication fails? --> 401 with WWW-Authenticate: L402
```

### PaygateAuthenticationToken

The token has two states:

**Unauthenticated** (created by the filter from a recognized header):

| Property | Value |
|----------|-------|
| `components` | Parsed `L402HeaderComponents` for L402/LSAT, otherwise `null` |
| `authorizationHeader` | Raw header for a non-L402 `PaymentProtocol`, otherwise `null` |
| `requestMetadata` | Immutable path, route, method, client-IP, digest, and capability metadata available for this request |
| `authenticated` | `false` |
| `authorities` | empty |
| `principal` | `[unauthenticated]` |
| `credentials` | `[REDACTED]` |

**Authenticated L402** (returned by the provider after validation):

| Property | Value |
|----------|-------|
| `credential` | Validated `L402Credential` object |
| `tokenId` | Hex-encoded 32-byte token identifier |
| `serviceName` | Service name from configuration (`paygate.service-name`) |
| `authenticated` | `true` |
| `authorities` | `[ROLE_PAYMENT, ROLE_L402]` + `[L402_CAPABILITY_*]` and `[PAYGATE_CAPABILITY_*]` for each capability in the final verified effective set |
| `principal` | token ID string |
| `credentials` | `[REDACTED]`; no raw authorization header, parsed credential components, preimage, `L402Credential`, or `PaymentCredential` is retained |
| `attributes` | Immutable map of verifier-approved values plus `tokenId` and `serviceName` |

Authenticated non-L402 protocols always receive `ROLE_PAYMENT` and expose `protocolScheme` plus any safe protocol attributes, but do not retain a `PaymentCredential`. They receive `ROLE_L402` only when the validated credential's source scheme is `L402`.

#### Security: Attribute Overwrite Protection

Built-in attributes (`tokenId`, `serviceName`) are inserted into the attributes map after caveat-derived entries. This ensures that attacker-controlled caveat keys cannot overwrite trusted values. A macaroon with a caveat `tokenId=attacker-value` will have that entry replaced by the real token ID.

### PaygateAuthenticationFilter

Extends `OncePerRequestFilter`. Parses the `Authorization` header using the pattern:

```
(?:LSAT|L402) ([^:]+):([a-fA-F0-9]{64})
```

This accepts both the current `L402` scheme and the legacy `LSAT` scheme. The preimage must be exactly 64 hex characters (case-insensitive). On an unprotected route, an absent, blank, or unrelated authorization header passes through without setting Paygate authentication, allowing other Spring Security mechanisms to handle it. On every registered paid route, missing or unrelated credentials are rejected by Paygate before downstream authorization; a `permitAll` rule cannot waive payment.

On authentication failure, the filter:

1. Clears the `SecurityContextHolder`
2. Returns HTTP 401 with `WWW-Authenticate: L402` header
3. Writes a JSON error body: `{"error": "L402 authentication failed"}`
4. Short-circuits the filter chain (does not call `doFilter`)

Endpoint-policy resolution failures are treated separately from authentication and Lightning
failures: the filter clears the security context, returns a sanitized HTTP 500 JSON response, and
does not invoke the authentication manager or downstream filter chain.

### PaygateAuthenticationProvider

Implements `AuthenticationProvider`. Accepts only `PaygateAuthenticationToken` instances (returns `null` for other token types, per the Spring Security contract). For L402 credentials it delegates to `L402Validator`; for other supported schemes it delegates to the matching `PaymentProtocol`. Validation failures are wrapped in `BadCredentialsException`.

---

## Auto-Configuration

`PaygateSecurityAutoConfiguration` is registered via Spring Boot's `AutoConfiguration.imports` mechanism and activates when both conditions are met:

1. `EnableWebSecurity` and `L402Validator` classes are on the classpath (`@ConditionalOnClass`)
2. An `L402Validator` bean exists in the application context (`@ConditionalOnBean`)

It registers up to five beans. A separate auto-configuration supplies the startup guard:

| Bean | Condition | Description |
|------|-----------|-------------|
| `CapabilityResolver` (`DefaultCapabilityResolver`) | `@ConditionalOnMissingBean` | Resolves endpoint capability requirements for authority mapping (`PAYGATE_CAPABILITY_*`). |
| `PaygateAuthenticationProvider` | `@ConditionalOnMissingBean` | Validates L402 and other registered payment tokens using `paygate.service-name` |
| `PaygateAuthenticationFilter` | `@ConditionalOnMissingBean` + `@ConditionalOnBean(AuthenticationManager.class)` | Extracts credentials from the Authorization header |
| `PaygateAuthFailureRateLimitFilter` | `@ConditionalOnMissingBean` + `@ConditionalOnBean(PaygateRateLimiter.class)` | Rate limits auth attempts with pre-check and post-failure penalty. Only created when rate limiting is enabled. |
| `PaygateAuthenticationEntryPoint` | `@ConditionalOnMissingBean` | Issues HTTP 402 challenges with Lightning invoices for unauthenticated requests. Uses `PaygateChallengeService` and `PaygateEndpointRegistry` from `paygate-spring-autoconfigure`. |
| `PaygateSpringSecurityFilterChainGuard` | Spring Security mode + `FilterChainProxy` on classpath | Fails startup if no `PaygateAuthenticationFilter` is present in the effective filter chain. |

The auto-configuration provides the beans but does **not** register the filter in the security filter chain. You must place the filter in your `SecurityFilterChain` definition (see Usage below). Startup validates every effective chain serving paid routes: it requires the Paygate filter, requires it before downstream authorization, checks rate-limit ordering when used, and requires `ERROR` redispatch coverage. It also prevents the security-chain-owned filter from being registered a second time as a container servlet filter. These failures are fail-closed. If you intentionally enforce Paygate through custom filter wiring that the guard cannot inspect, set `paygate.spring-security.custom-filter-chain-acknowledged=true`.

### Overriding Auto-Configured Beans

All of these beans are guarded with `@ConditionalOnMissingBean`. To customize behavior, declare your own:

```java
@Bean
public CapabilityResolver capabilityResolver() {
    return context -> Set.of();
}
```

---

## Usage

### Minimal SecurityFilterChain

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Optional: only present when rate limiting is enabled (PaygateRateLimiter bean exists)
    @Autowired(required = false)
    private PaygateAuthFailureRateLimitFilter paygateRateLimitFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     PaygateAuthenticationFilter paygateFilter,
                                                     PaygateAuthenticationProvider paygateProvider) throws Exception {
        // Rate limit filter runs BEFORE the auth filter when present
        if (paygateRateLimitFilter != null) {
            http.addFilterBefore(paygateRateLimitFilter, BasicAuthenticationFilter.class);
        }

        return http
                .authenticationProvider(paygateProvider)
                .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/premium/**").hasRole("PAYMENT")
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
```

### Combined with Other Authentication

L402 can coexist with other authentication mechanisms. If the request does not carry an L402 header, the filter passes through and subsequent filters (e.g., `BearerTokenAuthenticationFilter` for OAuth2) handle authentication:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 PaygateAuthenticationFilter l402Filter,
                                                 PaygateAuthenticationProvider l402Provider) throws Exception {
    return http
            .authenticationProvider(l402Provider)
            .addFilterBefore(l402Filter, UsernamePasswordAuthenticationFilter.class)
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/free/**").permitAll()
                    .requestMatchers("/api/paid/**").hasRole("L402")
                    .requestMatchers("/api/members/**").hasRole("USER")
                    .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
}
```

### application.yml

```yaml
paygate:
  enabled: true
  backend: lnbits
  service-name: my-api
  lnbits:
    url: https://lnbits.example.com
    api-key: ${LNBITS_API_KEY}
```

---

## Path-Based Protection Patterns

With Spring Security, you define which paths require L402 authentication using `authorizeHttpRequests` and the `ROLE_L402` authority:

```java
.authorizeHttpRequests(auth -> auth
    // Public endpoints -- no authentication
    .requestMatchers("/health", "/info").permitAll()

    // L402-protected endpoints -- require valid paid credential
    .requestMatchers("/api/v1/data/**").hasRole("L402")
    .requestMatchers("/api/v1/reports/**").hasRole("L402")

    // Admin endpoints -- require different auth
    .requestMatchers("/admin/**").hasRole("ADMIN")

    // Everything else requires some form of authentication
    .anyRequest().authenticated()
)
```

You can also use HTTP method matchers for fine-grained control:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.GET, "/api/v1/articles/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/articles/**").hasRole("L402")
)
```

---

## Accessing Payment Credentials in Controllers

After successful L402 authentication, the `SecurityContextHolder` contains an `PaygateAuthenticationToken`. You can access it in controller methods:

```java
@RestController
@RequestMapping("/api/v1")
public class PremiumController {

    @GetMapping("/data")
    public Map<String, Object> getData(Authentication authentication) {
        var l402Token = (PaygateAuthenticationToken) authentication;

        return Map.of(
            "tokenId", l402Token.getTokenId(),
            "service", l402Token.getServiceName(),
            "tier", l402Token.getAttribute("tier"),  // from macaroon caveats
            "data", "premium content"
        );
    }
}
```

The `attributes` map on an authenticated token contains only values that a registered caveat verifier accepted, plus server-issued values:

- Verified caveat values (for example `services`, `route`, `method`, and `<service>_valid_until`), when their registered verifiers explicitly accept them
- `tokenId` -- the hex-encoded 32-byte token identifier (overwrite-protected)
- `serviceName` -- the configured service name (overwrite-protected, omitted if null)

Unknown caveats and values merely added by a credential holder are not trusted attributes. The map is immutable, including when authentication is served from validation cache.

---

## SpEL and @PreAuthorize

Because `PaygateAuthenticationToken` extends `AbstractAuthenticationToken`, you can use SpEL expressions in `@PreAuthorize` annotations. The token is available as `authentication`:

```java
@PreAuthorize("hasRole('L402')")
@GetMapping("/basic")
public String basicPaidContent() {
    return "accessible with any valid L402 credential";
}

@PreAuthorize("hasRole('L402') and authentication.getAttribute('tier') == 'premium'")
@GetMapping("/premium")
public String premiumContent() {
    return "accessible only with a premium-tier L402 credential";
}

@PreAuthorize("hasRole('L402') and authentication.serviceName == 'my-api'")
@GetMapping("/service-specific")
public String serviceSpecific() {
    return "accessible only for credentials issued to my-api";
}
```

To use `@PreAuthorize`, enable method security in your configuration:

```java
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
```

---

## Capability Enforcement

L402 tokens can carry fine-grained capabilities that restrict what a paid credential is allowed to do. This builds on the role-based `ROLE_L402` authority with per-endpoint capability checks.

### How Capabilities Are Minted

When an endpoint is configured with a capability via `@PaymentRequired(capability = "analyze")`, the `PaygateChallengeService` includes a `{serviceName}_capabilities` caveat in the minted macaroon. For example, with `paygate.service-name=myapi`:

```
myapi_capabilities = analyze
```

Multiple capabilities use **any-of (OR)** semantics. For example, `capability = "search,analyze"` accepts a final verified ceiling containing `search` OR `analyze`; it does not require both. A wholly null or blank endpoint declaration is normalized to no named capability, so `@PaymentRequired` without `capability` (or with `capability = ""`) mints `~`, the authenticated empty capability ceiling. Blank comma-separated segments such as `search,,analyze` are invalid, and `~` cannot be declared by an endpoint either alone or mixed with names.

### How Capabilities Are Enforced

Capability enforcement happens at two levels:

1. **Macaroon verification (core layer):** The `PaygateAuthenticationProvider` builds an `L402VerificationContext` with `requestMetadata` that includes the requested capability declaration (via `VerificationContextKeys.REQUESTED_CAPABILITY`) resolved from the endpoint's `@PaymentRequired` configuration. The `CapabilitiesCaveatVerifier` requires set overlap between the declaration and the final verified ceiling. Thus requested `search,analyze` succeeds for final `{search}` or `{analyze}`, but rejects `{export}` and `~`. If the sets are disjoint, validation fails with a `BadCredentialsException`.

2. **Spring Security authorization (security layer):** For L402, `PaygateAuthenticationToken.authenticated()` maps only `L402Validator.ValidationResult.effectiveCapabilities()` to `PAYGATE_CAPABILITY_{name}` authorities. That immutable set is derived from the final successfully verified ceiling, not from requested metadata, a cache, or the union of repeated caveats. These authorities are available to `@PreAuthorize` expressions and `authorizeHttpRequests` rules.

### SpEL Examples

```java
// Require a specific capability
@PreAuthorize("hasAuthority('PAYGATE_CAPABILITY_analyze')")
@GetMapping("/api/v1/analyze")
public AnalysisResult analyze() { ... }

// Require role + capability
@PreAuthorize("hasRole('L402') and hasAuthority('PAYGATE_CAPABILITY_search')")
@GetMapping("/api/v1/search")
public SearchResult search() { ... }

// Require both capabilities (all-of) with two explicit authority checks
@PreAuthorize("hasAuthority('PAYGATE_CAPABILITY_search') and hasAuthority('PAYGATE_CAPABILITY_analyze')")
@GetMapping("/api/v1/search-and-analyze")
public AnalysisResult searchAndAnalyze() { ... }

// Check capability via attributes map (alternative)
@PreAuthorize("hasRole('L402') and authentication.getAttribute('myapi_capabilities').contains('analyze')")
@GetMapping("/api/v1/analyze-alt")
public AnalysisResult analyzeAlt() { ... }
```

The annotation/configuration list itself is not an all-of expression. Use explicit Spring Security
authority checks, as above, when an operation requires both capabilities.

### Attenuation and Compatibility

The issued capability value is a ceiling. Holder attenuation may retain or narrow a named set, including narrowing it to `~`; it cannot expand the set, turn `~` into a named grant, or mix `~` with names. Every signed ceiling is parsed and checked before use, so blank segments, mixed `~`/names, and malformed values fail closed. A final `~` cannot satisfy a named endpoint declaration and produces no capability-derived authorities.

Existing `hasRole('L402')` rules remain usable, but credential compatibility is intentionally stricter: previously issued credentials missing the capability ceiling, canonical `route`, or actual `method` are rejected even on cache hits. Clients recover by obtaining a new challenge; there is no fail-open compatibility switch.

## Trust and Serialization Guarantees

### Verified Attribute Provenance and Authorities

`MacaroonVerifier` accepts attribute provenance only from non-blank, uniquely registered, case-sensitive verifier keys. Each accepted value is captured immutably and is carried by `L402Validator.ValidationResult.verifiedAttributes()` for both fresh and cached validation. Spring Security maps attributes and capability authorities only from that verified result; it does not treat arbitrary caveats as claims.

Payment roles are issued by the server: validated credentials receive `ROLE_PAYMENT`, and L402 credentials also receive `ROLE_L402`. A holder cannot mint Spring Security roles or authorities by adding caveats such as `role=ADMIN`. Capability authorities likewise come only from the verified effective capability ceiling.

### Capability Non-Portability

A credential with named capabilities is valid only for an endpoint that declares an overlapping named capability. It is rejected for an endpoint that declares no capability. The `~` ceiling represents no capability: it cannot satisfy a named declaration and produces no capability-derived authorities. This prevents a credential minted for one named capability from becoming a general-purpose paid credential.

### Authenticated State and Serialization

The authenticated `PaygateAuthenticationToken` intentionally retains no raw `Authorization` header, parsed credential components or preimage, `L402Credential`, or `PaymentCredential`. Its trusted attributes are immutable. Receipt handoff is transient, and multipart-payment receipt generation completes before the token is reduced to credential-free authenticated state. Consequently, serializing an authenticated token does not serialize payment credentials or request secrets.

### Route, Method, and Deployment Prefixes

The authentication filter, entry point, and auth-failure rate limiter share application-relative request-path resolution. Context paths and applicable path-prefix servlet mappings are removed before endpoint lookup, while the selected canonical route pattern is passed separately into validation. Registered routes use the same Spring MVC request-mapping conditions and comparison semantics as servlet mode, including decoded application-relative path segments and the configured path parser. Ambiguous paid policies or detectable unsupported paid mapping sources fail closed with startup or resolution diagnostics.

For an actual `HEAD` request, endpoint resolution uses explicit HEAD first, then inherits the matching GET policy, then wildcard. Inheritance includes price, timeout, capability, and pricing strategy, but the credential remains bound to `HEAD`; GET and HEAD credentials are not interchangeable. Ambiguous route selection fails closed.

Authentication failures and diagnostic rendering redact credential material. Do not expose full macaroons, preimages, `Authorization` headers, root keys, or sensitive validation reasons; `L402Metadata` summaries contain only redaction labels, counts, and lengths.

---

## Comparison with Non-Spring-Security Approach

| Aspect | `paygate-spring-autoconfigure` (`PaygateSecurityFilter`) | `paygate-spring-security` |
|--------|---------------------------------------------------|----------------------|
| Spring Security dependency | Not required | Required |
| Protection mechanism | `@PaymentRequired` annotation on controller methods | `SecurityFilterChain` with `authorizeHttpRequests` |
| Filter type | Jakarta `Filter` registered via `FilterRegistrationBean` | `OncePerRequestFilter` added to Spring Security filter chain |
| Invoice generation | Built-in: generates 402 response with invoice | Built-in via `PaygateAuthenticationEntryPoint`: generates 402 response with invoice when configured as the exception handling entry point |
| Mixed auth support | L402 only | L402 + OAuth2 + JWT + Basic + any Spring Security provider |
| Role/authority model | None | `ROLE_L402` granted authority |
| `@PreAuthorize` support | No | Yes, with full SpEL on `PaygateAuthenticationToken` attributes |
| Caveat-based access control | Via `CaveatVerifier` implementations at validation time | Via `CaveatVerifier` + SpEL expressions at authorization time |
| `SecurityContextHolder` integration | No | Yes, authenticated token in security context |
| Session management | Stateless (no session) | Configurable (STATELESS recommended) |
| 402 challenge response | Automatic with invoice | Automatic via `PaygateAuthenticationEntryPoint` when configured as the entry point in `SecurityFilterChain` |

### Mutual Exclusion via `paygate.security-mode`

The servlet filter and Spring Security paths are mutually exclusive. The `paygate.security-mode` property controls which one is active:

| Value | Servlet filter (`PaygateSecurityFilter`) | Spring Security (`PaygateAuthenticationFilter` + entry point) |
|-------|--------------------------------------|----------------------------------------------------------|
| `auto` (default) | Active unless both Spring Security and `paygate-spring-security` are on the classpath | Active only when both Spring Security and `paygate-spring-security` are on the classpath |
| `servlet` | Always active | Disabled, even if Spring Security is on the classpath |
| `spring-security` | Disabled | Always active. Fails at startup if Spring Security or `paygate-spring-security` is not on the classpath. |

Only one documented enforcement path is active per deployment. Servlet mode uses the container `PaygateSecurityFilter` and its final MVC interceptor; Spring Security mode uses `PaygateAuthenticationFilter` and the configured security chain. This prevents both paths from processing the same request.

When using `spring-security` mode, the `PaygateAuthenticationEntryPoint` replaces the servlet filter's built-in 402 challenge generation. Configure the entry point and add `PaygateAuthenticationFilter` in your `SecurityFilterChain` to get the full payment flow (challenge issuance + credential validation) through Spring Security. If the filter is absent, startup fails closed unless `paygate.spring-security.custom-filter-chain-acknowledged=true` is set.

Set the mode explicitly when both modules are on the classpath:

```yaml
paygate:
  enabled: true
  security-mode: spring-security
```

### PaygateAuthenticationEntryPoint

The entry point implements Spring Security's `AuthenticationEntryPoint` interface. When an unauthenticated request reaches a protected endpoint, it:

1. Looks up the endpoint's L402 configuration from the `PaygateEndpointRegistry` (price, timeout, pricing strategy)
2. Delegates to `PaygateChallengeService` to create a Lightning invoice and mint a macaroon
3. Returns HTTP 402 with a `WWW-Authenticate: L402 macaroon="...", invoice="..."` header
4. Returns a sanitized 500 without challenge generation if endpoint-policy resolution fails
5. Falls back to 503 if the Lightning backend is unavailable (fail-closed)
6. Returns 429 with `Retry-After` if the rate limiter rejects the request

Register it in your `SecurityFilterChain`:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 PaygateAuthenticationFilter l402Filter,
                                                 PaygateAuthenticationProvider l402Provider,
                                                 PaygateAuthenticationEntryPoint l402EntryPoint) throws Exception {
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

If the requested path is not registered in the `PaygateEndpointRegistry` (i.e., it has no `@PaymentRequired` annotation), the entry point returns a plain 401 Unauthorized response instead of a 402 challenge.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-spring-security:test
```

### Test Architecture

Tests use **Mockito** with `MockitoExtension` and Spring's `MockHttpServletRequest`/`MockHttpServletResponse` for testing filter behavior without a running application context. The `L402Validator` is mocked, so no Lightning backend or root key store is needed.

### Test Coverage

`PaygateAuthenticationFilterTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `constructorRejectsNullAuthenticationManager` | Null guard on authentication manager parameter |
| `constructorRejectsNullEndpointRegistry` | Null guard on endpoint registry parameter |
| `skipsWhenNoAuthorizationHeader` | No header: filter chain continues, no authentication attempt |
| `skipsWhenBlankAuthorizationHeader` | Blank header: filter chain continues |
| `skipsWhenNonL402AuthorizationHeader` | Bearer/Basic headers: filter chain continues (pass-through to other filters) |
| `extractsL402CredentialAndAuthenticates` | Valid `L402` header: extracts macaroon and preimage, authenticates, populates SecurityContext |
| `extractsLsatCredentialAndAuthenticates` | Legacy `LSAT` header: same behavior as `L402` |
| `returns401WhenAuthenticationFails` | Authentication failure: 401 status, `WWW-Authenticate: L402` header, JSON error body, security context cleared |
| `skipsWhenPreimageNotHex` | Invalid preimage format: filter chain continues without authentication attempt |
| `extractsUppercaseHexPreimageAndAuthenticates` | Uppercase hex preimage accepted |
| `extractsMixedCaseHexPreimageAndAuthenticates` | Mixed-case hex preimage accepted |
| `returns503WhenRuntimeExceptionThrown` | Runtime exception: 503 status, JSON error body, security context cleared, filter chain short-circuited |
| `skipsWhenMacaroonEmpty` | Empty macaroon field: filter chain continues |
| `skipsWhenMacaroonExceedsMaxLength` | Oversized macaroon (>8192 chars): filter chain continues without authentication attempt |
| `skipsWhenMacaroonContainsInvalidCharacters` | Macaroon with invalid characters: filter chain continues without authentication attempt |
| `extractsMultiTokenHeaderAndAuthenticates` | Comma-separated multi-token macaroon: extracted as single raw value, authenticates |
| `skipsWhenMultiTokenExceedsMaxLength` | Oversized multi-token macaroon: filter chain continues without authentication attempt |
| `passesCapabilityFromRegistryToToken` | Capability from `PaygateEndpointRegistry` is set on the unauthenticated token |
| `skipsAuthWhenConfigNotFound` | No registered paid endpoint: authentication is skipped |
| `passesNullCapabilityWhenConfigHasEmptyCapability` | Empty capability string: null capability (permissive) |
| `passesNullCapabilityWhenConfigHasBlankCapability` | Blank capability string: null capability (permissive) |
| `returns500WhenRegistryThrowsException` | Registry failure: sanitized 500 and no authentication attempt |
| `passesNullCapabilityWhenConfigHasNullCapability` | Null capability in config: null capability (permissive) |

`PaygateAuthenticationProviderTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `constructorRejectsNullValidator` | Null guard on `L402Validator` parameter |
| `supportsPaygateAuthenticationToken` | `supports()` returns `true` for `PaygateAuthenticationToken.class` |
| `doesNotSupportOtherTokenTypes` | `supports()` returns `false` for `UsernamePasswordAuthenticationToken` |
| `returnsNullForNonPaygateAuthentication` | Non-L402 tokens return `null` (Spring Security contract) |
| `authenticatesValidL402Token` | Valid token: authenticated with `ROLE_L402`, correct tokenId, serviceName, caveat attributes |
| `throwsBadCredentialsOnValidationFailure` | `L402Exception` wrapped in `BadCredentialsException` with original cause preserved |
| `throwsBadCredentialsWhenComponentsMissing` | L402 token without parsed components is rejected |
| `allowsNullServiceName` | Null service name is accepted, `serviceName` attribute omitted |
| `passesRequestedCapabilityThroughToValidatorContext` | Requested capability from token is forwarded to `L402VerificationContext` |
| `passesNullCapabilityWhenNotSpecified` | Null capability when token has no requested capability |
| `capabilityMismatchResultsInBadCredentialsException` | Capability mismatch from validator is wrapped in `BadCredentialsException` |

`PaygateAuthenticationTokenTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `unauthenticatedTokenHoldsComponents` | Unauthenticated state: parsed header components and request metadata are retained |
| `unauthenticatedTokenRedactsSensitiveValues` | Unauthenticated token redacts raw credentials in `getPrincipal()` and `getCredentials()` |
| `unauthenticatedTokenRejectsNullComponents` | Null guard on parsed L402 header components |
| `authenticatedTokenExposesCredentialDetails` | Authenticated state: correct tokenId, serviceName, principal, credential |
| `authenticatedTokenHasL402Authority` | `ROLE_L402` authority present |
| `authenticatedTokenExtractsCaveatAttributes` | Caveat key-value pairs extracted into attributes map |
| `builtInAttributesCannotBeOverwrittenByCaveats` | Attacker-controlled caveat keys `tokenId`/`serviceName` overwritten by trusted values |
| `authenticatedTokenWithNullServiceName` | Null service name omitted from attributes map |
| `authenticatedTokenMapsCapabilitiesToAuthorities` | Capabilities caveat parsed into `PAYGATE_CAPABILITY_*` authorities |
| `authenticatedTokenWithNoCapabilitiesCaveatHasOnlyRoleL402` | No explicit capabilities: base `ROLE_PAYMENT` and `ROLE_L402` authorities only |
| `caveatRejectsEmptyCapabilitiesValue` | Caveat constructor rejects empty capabilities value |
| `authenticatedTokenAddsSingleCapabilityOnce` | Explicit capability authorities are emitted once |
| `authenticatedTokenWithNullServiceNameStillUsesExplicitCapabilities` | Explicit verified capabilities do not depend on a configured service name |
| `l402DualEmitCaveatAndExplicitCapabilityProducesBothPrefixes` | L402 compatibility and Paygate capability authority prefixes are both emitted from the verified set |
| `l402TwoArgDoesNotEmitCapabilityAuthoritiesWithoutResolvedCapabilities` | Raw caveat text alone is not treated as an authority source |

### Writing Your Own Tests

To test a controller that requires L402 authentication, create an authenticated token directly:

```java
@Test
void premiumEndpointReturnsDataForL402User() {
    // Create a test credential (see PaygateAuthenticationProviderTest for helper)
    L402Credential credential = createTestCredential(List.of(
        new Caveat("tier", "premium")
    ));

    var token = PaygateAuthenticationToken.authenticated(credential, "my-api");
    SecurityContextHolder.getContext().setAuthentication(token);

    // Call your controller or use MockMvc with .with(authentication(token))
}
```

With Spring Security Test and `MockMvc`:

```java
@Test
void premiumEndpointRequiresL402() throws Exception {
    mockMvc.perform(get("/api/premium/data"))
            .andExpect(status().isUnauthorized());
}

@Test
void premiumEndpointAccessibleWithL402() throws Exception {
    var token = PaygateAuthenticationToken.authenticated(credential, "my-api");

    mockMvc.perform(get("/api/premium/data")
            .with(authentication(token)))
            .andExpect(status().isOk());
}
```

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
