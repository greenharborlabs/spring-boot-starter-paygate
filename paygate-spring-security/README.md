# paygate-spring-security

Optional [Spring Security](https://spring.io/projects/spring-security) integration for the `spring-boot-starter-paygate` project. This module bridges L402 payment authentication into Spring Security's filter chain, providing an `AuthenticationFilter`, `AuthenticationProvider`, and `AuthenticationToken` that let you protect endpoints using standard Spring Security patterns -- `SecurityFilterChain`, `@PreAuthorize`, role-based access, and the `SecurityContextHolder`.

If you do not use Spring Security, you do not need this module. The base `paygate-spring-autoconfigure` module provides a standalone servlet `Filter` (`PaygateSecurityFilter`) and `@PaygateProtected` annotation that work without Spring Security on the classpath.

---

## Table of Contents

- [When to Use This Module](#when-to-use-this-module)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Architecture](#architecture)
- [Auto-Configuration](#auto-configuration)
- [Usage](#usage)
- [Path-Based Protection Patterns](#path-based-protection-patterns)
- [Accessing L402 Credentials in Controllers](#accessing-l402-credentials-in-controllers)
- [SpEL and @PreAuthorize](#spel-and-preauthorize)
- [Capability Enforcement](#capability-enforcement)
- [Comparison with Non-Spring-Security Approach](#comparison-with-non-spring-security-approach)
- [Testing](#testing)

---

## When to Use This Module

Use `paygate-spring-security` when your application already uses Spring Security and you want L402 authentication to participate in the security filter chain alongside other authentication mechanisms (OAuth2, HTTP Basic, form login, etc.).

Use the base `paygate-spring-autoconfigure` module (with `@PaygateProtected`) when you have a simpler setup without Spring Security, or when you want annotation-driven L402 protection that operates independently of any security framework.

| Scenario | Recommended Module |
|----------|-------------------|
| No Spring Security dependency | `paygate-spring-autoconfigure` with `@PaygateProtected` |
| Spring Security is present, L402 is the only auth mechanism | `paygate-spring-security` |
| Spring Security with mixed auth (L402 + OAuth2/JWT/Basic) | `paygate-spring-security` |
| Need `@PreAuthorize` expressions based on L402 caveats | `paygate-spring-security` |
| Need `ROLE_L402` authority for access control | `paygate-spring-security` |

---

## Prerequisites

- **Java 25** (LTS)
- **Spring Boot 4.0.3** with **Spring Security** on the classpath
- **A configured Lightning backend** (`paygate-lightning-lnbits` or `paygate-lightning-lnd`) -- required for the `L402Validator` bean that this module depends on
- **`paygate.enabled=true`** in application properties

---

## Installation

Add this module alongside the starter and a Lightning backend. The starter pulls in `paygate-core` and `paygate-spring-autoconfigure` transitively.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:paygate-spring-security:0.1.0")
implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.0") // or paygate-lightning-lnd
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-security</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-lightning-lnbits</artifactId>
    <version>0.1.0</version>
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

The module contains five classes, all in the `com.greenharborlabs.l402.spring.security` package:

```
paygate-spring-security/
  src/main/java/com/greenharborlabs/l402/spring/security/
    L402AuthenticationEntryPoint.java      Issues 402 challenges with Lightning invoices
    L402AuthenticationFilter.java          Extracts L402 credentials from Authorization header
    L402AuthenticationProvider.java        Validates credentials via L402Validator
    L402AuthenticationToken.java           Spring Security token (unauthenticated and authenticated states)
    PaygateSecurityAutoConfiguration.java     Registers beans when Spring Security is present
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/com/greenharborlabs/l402/spring/security/
    L402AuthenticationEntryPointTest.java  Entry point challenge and error handling tests
    L402AuthenticationFilterTest.java      Filter behavior tests with MockHttpServletRequest
    L402AuthenticationProviderTest.java    Provider validation and error handling tests
    L402AuthenticationTokenTest.java       Token lifecycle and attribute extraction tests
```

### Request Flow

```
Client Request
     |
     v
L402AuthenticationFilter (OncePerRequestFilter)
     |
     |-- No "Authorization: L402 ..." header? --> continue filter chain (other filters handle it)
     |
     |-- Header present, matches L402/LSAT pattern?
     |       |
     |       v
     |   Resolve capability from PaygateEndpointRegistry (if configured)
     |       |
     |       v
     |   AuthenticationManager.authenticate(unauthenticatedToken)
     |       |
     |       v
     |   L402AuthenticationProvider
     |       |
     |       |-- Reconstructs "L402 <macaroon>:<preimage>" header
     |       |-- Builds L402VerificationContext with requestedCapability
     |       |-- Delegates to L402Validator.validate() (includes CapabilitiesCaveatVerifier)
     |       |-- Returns authenticated L402AuthenticationToken with:
     |       |     - ROLE_L402 authority
     |       |     - L402_CAPABILITY_* authorities (from capabilities caveat)
     |       |     - tokenId as principal
     |       |     - L402Credential as credentials
     |       |     - Caveat-derived attributes map
     |       |
     |       v
     |   SecurityContextHolder populated --> continue filter chain
     |
     |-- Authentication fails? --> 401 with WWW-Authenticate: L402
```

### L402AuthenticationToken

The token has two states:

**Unauthenticated** (created by the filter from the raw header):

| Property | Value |
|----------|-------|
| `rawMacaroon` | Base64-encoded macaroon string from the header |
| `rawPreimage` | 64-character hex preimage from the header |
| `authenticated` | `false` |
| `authorities` | empty |
| `principal` | raw macaroon string |
| `credentials` | `"<macaroon>:<preimage>"` concatenation |

**Authenticated** (returned by the provider after validation):

| Property | Value |
|----------|-------|
| `credential` | Validated `L402Credential` object |
| `tokenId` | Hex-encoded 32-byte token identifier |
| `serviceName` | Service name from configuration (`paygate.service-name`) |
| `authenticated` | `true` |
| `authorities` | `[ROLE_L402]` + `[L402_CAPABILITY_*]` for each capability in the `{serviceName}_capabilities` caveat |
| `principal` | token ID string |
| `credentials` | `L402Credential` object |
| `attributes` | Map of caveat key-value pairs plus `tokenId` and `serviceName` |

#### Security: Attribute Overwrite Protection

Built-in attributes (`tokenId`, `serviceName`) are inserted into the attributes map after caveat-derived entries. This ensures that attacker-controlled caveat keys cannot overwrite trusted values. A macaroon with a caveat `tokenId=attacker-value` will have that entry replaced by the real token ID.

### L402AuthenticationFilter

Extends `OncePerRequestFilter`. Parses the `Authorization` header using the pattern:

```
(?:LSAT|L402) ([^:]+):([a-fA-F0-9]{64})
```

This accepts both the current `L402` scheme and the legacy `LSAT` scheme. The preimage must be exactly 64 hex characters (case-insensitive). If the header is absent, blank, or does not match this pattern, the filter passes the request through without setting authentication -- allowing other Spring Security filters to handle it.

On authentication failure, the filter:

1. Clears the `SecurityContextHolder`
2. Returns HTTP 401 with `WWW-Authenticate: L402` header
3. Writes a JSON error body: `{"error": "L402 authentication failed"}`
4. Short-circuits the filter chain (does not call `doFilter`)

### L402AuthenticationProvider

Implements `AuthenticationProvider`. Accepts only `L402AuthenticationToken` instances (returns `null` for other token types, per the Spring Security contract). Reconstructs the full authorization header string and delegates to `L402Validator.validate()`. On validation failure, wraps the `L402Exception` in a `BadCredentialsException`.

---

## Auto-Configuration

`PaygateSecurityAutoConfiguration` is registered via Spring Boot's `AutoConfiguration.imports` mechanism and activates when both conditions are met:

1. `EnableWebSecurity` and `L402Validator` classes are on the classpath (`@ConditionalOnClass`)
2. An `L402Validator` bean exists in the application context (`@ConditionalOnBean`)

It registers three beans:

| Bean | Condition | Description |
|------|-----------|-------------|
| `L402AuthenticationProvider` | `@ConditionalOnMissingBean` | Validates L402 tokens using the `L402Validator` and `paygate.service-name` property |
| `L402AuthenticationFilter` | `@ConditionalOnMissingBean` + `@ConditionalOnBean(AuthenticationManager.class)` | Extracts credentials from the Authorization header |
| `L402AuthenticationEntryPoint` | `@ConditionalOnMissingBean` | Issues HTTP 402 challenges with Lightning invoices for unauthenticated requests. Uses `PaygateChallengeService` and `PaygateEndpointRegistry` from `paygate-spring-autoconfigure`. |

The auto-configuration provides the beans but does **not** register the filter in the security filter chain. You must place the filter in your `SecurityFilterChain` definition (see Usage below). This gives you full control over filter ordering and which paths are protected.

### Overriding Auto-Configured Beans

Both beans are guarded with `@ConditionalOnMissingBean`. To customize behavior, declare your own:

```java
@Bean
public L402AuthenticationProvider l402AuthenticationProvider(L402Validator validator) {
    return new L402AuthenticationProvider(validator, "custom-service-name");
}
```

---

## Usage

### Minimal SecurityFilterChain

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     L402AuthenticationFilter l402Filter,
                                                     L402AuthenticationProvider l402Provider) throws Exception {
        return http
                .authenticationProvider(l402Provider)
                .addFilterBefore(l402Filter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/premium/**").hasRole("L402")
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
                                                 L402AuthenticationFilter l402Filter,
                                                 L402AuthenticationProvider l402Provider) throws Exception {
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

## Accessing L402 Credentials in Controllers

After successful L402 authentication, the `SecurityContextHolder` contains an `L402AuthenticationToken`. You can access it in controller methods:

```java
@RestController
@RequestMapping("/api/v1")
public class PremiumController {

    @GetMapping("/data")
    public Map<String, Object> getData(Authentication authentication) {
        var l402Token = (L402AuthenticationToken) authentication;

        return Map.of(
            "tokenId", l402Token.getTokenId(),
            "service", l402Token.getServiceName(),
            "tier", l402Token.getAttribute("tier"),  // from macaroon caveats
            "data", "premium content"
        );
    }
}
```

The `attributes` map on an authenticated token contains:

- All caveat key-value pairs from the macaroon (e.g., `service`, `valid_until`, custom caveats)
- `tokenId` -- the hex-encoded 32-byte token identifier (overwrite-protected)
- `serviceName` -- the configured service name (overwrite-protected, omitted if null)

---

## SpEL and @PreAuthorize

Because `L402AuthenticationToken` extends `AbstractAuthenticationToken`, you can use SpEL expressions in `@PreAuthorize` annotations. The token is available as `authentication`:

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

When an endpoint is configured with a capability via `@PaygateProtected(capability = "analyze")`, the `PaygateChallengeService` includes a `{serviceName}_capabilities` caveat in the minted macaroon. For example, with `paygate.service-name=myapi`:

```
myapi_capabilities = analyze
```

Multiple capabilities can be comma-separated (e.g., `"search,analyze"`). If no capability is configured on the endpoint (`@PaygateProtected` without `capability`, or `capability = ""`), no capabilities caveat is added.

### How Capabilities Are Enforced

Capability enforcement happens at two levels:

1. **Macaroon verification (core layer):** The `L402AuthenticationProvider` builds an `L402VerificationContext` with the `requestedCapability` resolved from the endpoint's `@PaygateProtected` configuration. The `CapabilitiesCaveatVerifier` checks that the requested capability is present in the macaroon's comma-separated capabilities list. If the capability is missing, validation fails with a `BadCredentialsException`.

2. **Spring Security authorization (security layer):** The `L402AuthenticationToken.authenticated()` factory method parses the `{serviceName}_capabilities` caveat and maps each capability to a `GrantedAuthority` named `L402_CAPABILITY_{name}`. These authorities are available to `@PreAuthorize` expressions and `authorizeHttpRequests` rules.

### SpEL Examples

```java
// Require a specific capability
@PreAuthorize("hasAuthority('L402_CAPABILITY_analyze')")
@GetMapping("/api/v1/analyze")
public AnalysisResult analyze() { ... }

// Require role + capability
@PreAuthorize("hasRole('L402') and hasAuthority('L402_CAPABILITY_search')")
@GetMapping("/api/v1/search")
public SearchResult search() { ... }

// Check capability via attributes map (alternative)
@PreAuthorize("hasRole('L402') and authentication.getAttribute('myapi_capabilities').contains('analyze')")
@GetMapping("/api/v1/analyze-alt")
public AnalysisResult analyzeAlt() { ... }
```

### Backward Compatibility

Capability enforcement is fully backward-compatible:

- **Tokens without capabilities caveats** receive only `ROLE_L402`. No `L402_CAPABILITY_*` authorities are added. Existing tokens continue to work for endpoints that do not require a specific capability.
- **Endpoints without a capability configured** (`@PaygateProtected` without `capability`) do not trigger capability verification. The `CapabilitiesCaveatVerifier` receives a `null` requested capability and passes without checking.
- **Existing `hasRole('L402')` rules** are unaffected. Capability authorities are additive.

---

## Comparison with Non-Spring-Security Approach

| Aspect | `paygate-spring-autoconfigure` (`PaygateSecurityFilter`) | `paygate-spring-security` |
|--------|---------------------------------------------------|----------------------|
| Spring Security dependency | Not required | Required |
| Protection mechanism | `@PaygateProtected` annotation on controller methods | `SecurityFilterChain` with `authorizeHttpRequests` |
| Filter type | Jakarta `Filter` registered via `FilterRegistrationBean` | `OncePerRequestFilter` added to Spring Security filter chain |
| Invoice generation | Built-in: generates 402 response with invoice | Built-in via `L402AuthenticationEntryPoint`: generates 402 response with invoice when configured as the exception handling entry point |
| Mixed auth support | L402 only | L402 + OAuth2 + JWT + Basic + any Spring Security provider |
| Role/authority model | None | `ROLE_L402` granted authority |
| `@PreAuthorize` support | No | Yes, with full SpEL on `L402AuthenticationToken` attributes |
| Caveat-based access control | Via `CaveatVerifier` implementations at validation time | Via `CaveatVerifier` + SpEL expressions at authorization time |
| `SecurityContextHolder` integration | No | Yes, authenticated token in security context |
| Session management | Stateless (no session) | Configurable (STATELESS recommended) |
| 402 challenge response | Automatic with invoice | Automatic via `L402AuthenticationEntryPoint` when configured as the entry point in `SecurityFilterChain` |

### Mutual Exclusion via `paygate.security-mode`

The servlet filter and Spring Security paths are mutually exclusive. The `paygate.security-mode` property controls which one is active:

| Value | Servlet filter (`PaygateSecurityFilter`) | Spring Security (`L402AuthenticationFilter` + entry point) |
|-------|--------------------------------------|----------------------------------------------------------|
| `auto` (default) | Active when Spring Security is **not** on the classpath | Active when Spring Security **is** on the classpath |
| `servlet` | Always active | Disabled, even if Spring Security is on the classpath |
| `spring-security` | Disabled | Always active. Fails at startup if Spring Security is not on the classpath. |

Only one mode is active at a time. This prevents conflicts where both the servlet filter and the Spring Security filter chain attempt to handle the same request.

When using `spring-security` mode, the `L402AuthenticationEntryPoint` replaces the servlet filter's built-in 402 challenge generation. Configure the entry point in your `SecurityFilterChain` to get the full payment flow (challenge issuance + credential validation) through Spring Security.

Set the mode explicitly when both modules are on the classpath:

```yaml
paygate:
  enabled: true
  security-mode: spring-security
```

### L402AuthenticationEntryPoint

The entry point implements Spring Security's `AuthenticationEntryPoint` interface. When an unauthenticated request reaches a protected endpoint, it:

1. Looks up the endpoint's L402 configuration from the `PaygateEndpointRegistry` (price, timeout, pricing strategy)
2. Delegates to `PaygateChallengeService` to create a Lightning invoice and mint a macaroon
3. Returns HTTP 402 with a `WWW-Authenticate: L402 macaroon="...", invoice="..."` header
4. Falls back to 503 if the Lightning backend is unavailable (fail-closed)
5. Returns 429 with `Retry-After` if the rate limiter rejects the request

Register it in your `SecurityFilterChain`:

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

If the requested path is not registered in the `PaygateEndpointRegistry` (i.e., it has no `@PaygateProtected` annotation), the entry point returns a plain 401 Unauthorized response instead of a 402 challenge.

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

`L402AuthenticationFilterTest` covers:

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
| `passesNullCapabilityWhenConfigNotFound` | No registry config: null capability (permissive) |
| `passesNullCapabilityWhenConfigHasEmptyCapability` | Empty capability string: null capability (permissive) |
| `passesNullCapabilityWhenConfigHasBlankCapability` | Blank capability string: null capability (permissive) |
| `passesNullCapabilityWhenRegistryThrowsException` | Registry exception: null capability, authentication proceeds |
| `passesNullCapabilityWhenConfigHasNullCapability` | Null capability in config: null capability (permissive) |

`L402AuthenticationProviderTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `constructorRejectsNullValidator` | Null guard on `L402Validator` parameter |
| `supportsL402AuthenticationToken` | `supports()` returns `true` for `L402AuthenticationToken.class` |
| `doesNotSupportOtherTokenTypes` | `supports()` returns `false` for `UsernamePasswordAuthenticationToken` |
| `returnsNullForNonL402Authentication` | Non-L402 tokens return `null` (Spring Security contract) |
| `authenticatesValidL402Token` | Valid token: authenticated with `ROLE_L402`, correct tokenId, serviceName, caveat attributes |
| `throwsBadCredentialsOnValidationFailure` | `L402Exception` wrapped in `BadCredentialsException` with original cause preserved |
| `throwsBadCredentialsWhenRawCredentialsMissing` | Already-authenticated token (no raw credentials) rejected |
| `allowsNullServiceName` | Null service name is accepted, `serviceName` attribute omitted |
| `passesRequestedCapabilityThroughToValidatorContext` | Requested capability from token is forwarded to `L402VerificationContext` |
| `passesNullCapabilityWhenNotSpecified` | Null capability when token has no requested capability |
| `capabilityMismatchResultsInBadCredentialsException` | Capability mismatch from validator is wrapped in `BadCredentialsException` |

`L402AuthenticationTokenTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `unauthenticatedTokenHoldsRawCredentials` | Unauthenticated state: raw values stored, no tokenId/serviceName/authorities |
| `unauthenticatedTokenRedactsSensitiveValues` | Unauthenticated token redacts raw credentials in `getPrincipal()` and `getCredentials()` |
| `unauthenticatedTokenRejectsNullMacaroon` | Null guard on macaroon |
| `unauthenticatedTokenRejectsNullPreimage` | Null guard on preimage |
| `authenticatedTokenExposesCredentialDetails` | Authenticated state: correct tokenId, serviceName, principal, credential |
| `authenticatedTokenHasL402Authority` | `ROLE_L402` authority present |
| `authenticatedTokenExtractsCaveatAttributes` | Caveat key-value pairs extracted into attributes map |
| `builtInAttributesCannotBeOverwrittenByCaveats` | Attacker-controlled caveat keys `tokenId`/`serviceName` overwritten by trusted values |
| `authenticatedTokenWithNullServiceName` | Null service name omitted from attributes map |
| `authenticatedTokenMapsCapabilitiesToAuthorities` | Capabilities caveat parsed into `L402_CAPABILITY_*` authorities |
| `authenticatedTokenWithNoCapabilitiesCaveatHasOnlyRoleL402` | No capabilities caveat: only `ROLE_L402` authority |
| `caveatRejectsEmptyCapabilitiesValue` | Caveat constructor rejects empty capabilities value |
| `authenticatedTokenDeduplicatesCapabilities` | Duplicate capabilities in one caveat deduplicated |
| `authenticatedTokenHandlesMalformedCapabilitiesValue` | Trailing commas and whitespace in capabilities handled |
| `authenticatedTokenWithNullServiceNameSkipsCapabilityExtraction` | Null service name: no capability extraction attempted |
| `authenticatedTokenDeduplicatesAcrossMultipleCapabilityCaveats` | Multiple capabilities caveats are merged and deduplicated into `L402_CAPABILITY_*` authorities |

### Writing Your Own Tests

To test a controller that requires L402 authentication, create an authenticated token directly:

```java
@Test
void premiumEndpointReturnsDataForL402User() {
    // Create a test credential (see L402AuthenticationProviderTest for helper)
    L402Credential credential = createTestCredential(List.of(
        new Caveat("tier", "premium")
    ));

    var token = L402AuthenticationToken.authenticated(credential, "my-api");
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
    var token = L402AuthenticationToken.authenticated(credential, "my-api");

    mockMvc.perform(get("/api/premium/data")
            .with(authentication(token)))
            .andExpect(status().isOk());
}
```

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
