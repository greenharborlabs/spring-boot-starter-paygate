# l402-spring-security

Optional [Spring Security](https://spring.io/projects/spring-security) integration for the `spring-boot-starter-l402` project. This module bridges L402 payment authentication into Spring Security's filter chain, providing an `AuthenticationFilter`, `AuthenticationProvider`, and `AuthenticationToken` that let you protect endpoints using standard Spring Security patterns -- `SecurityFilterChain`, `@PreAuthorize`, role-based access, and the `SecurityContextHolder`.

If you do not use Spring Security, you do not need this module. The base `l402-spring-autoconfigure` module provides a standalone servlet `Filter` (`L402SecurityFilter`) and `@L402Protected` annotation that work without Spring Security on the classpath.

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
- [Comparison with Non-Spring-Security Approach](#comparison-with-non-spring-security-approach)
- [Testing](#testing)

---

## When to Use This Module

Use `l402-spring-security` when your application already uses Spring Security and you want L402 authentication to participate in the security filter chain alongside other authentication mechanisms (OAuth2, HTTP Basic, form login, etc.).

Use the base `l402-spring-autoconfigure` module (with `@L402Protected`) when you have a simpler setup without Spring Security, or when you want annotation-driven L402 protection that operates independently of any security framework.

| Scenario | Recommended Module |
|----------|-------------------|
| No Spring Security dependency | `l402-spring-autoconfigure` with `@L402Protected` |
| Spring Security is present, L402 is the only auth mechanism | `l402-spring-security` |
| Spring Security with mixed auth (L402 + OAuth2/JWT/Basic) | `l402-spring-security` |
| Need `@PreAuthorize` expressions based on L402 caveats | `l402-spring-security` |
| Need `ROLE_L402` authority for access control | `l402-spring-security` |

---

## Prerequisites

- **Java 25** (LTS)
- **Spring Boot 4.0.3** with **Spring Security** on the classpath
- **A configured Lightning backend** (`l402-lightning-lnbits` or `l402-lightning-lnd`) -- required for the `L402Validator` bean that this module depends on
- **`l402.enabled=true`** in application properties

---

## Installation

Add this module alongside the starter and a Lightning backend. The starter pulls in `l402-core` and `l402-spring-autoconfigure` transitively.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:l402-spring-security:0.1.0")
implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0") // or l402-lightning-lnd
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-spring-security</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-lightning-lnbits</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| `l402-core` | `L402Validator`, `L402Credential`, `Macaroon`, `Caveat` -- core protocol types |
| `spring-security-core` | `AuthenticationProvider`, `AbstractAuthenticationToken`, `GrantedAuthority` |
| `spring-security-web` | `OncePerRequestFilter` for the authentication filter |
| `spring-security-config` | `@EnableWebSecurity` detection for conditional auto-configuration |
| `spring-boot-autoconfigure` | `@AutoConfiguration`, `@ConditionalOnBean`, `@ConditionalOnClass` |

---

## Architecture

The module contains five classes, all in the `com.greenharborlabs.l402.spring.security` package:

```
l402-spring-security/
  src/main/java/com/greenharborlabs/l402/spring/security/
    L402AuthenticationEntryPoint.java      Issues 402 challenges with Lightning invoices
    L402AuthenticationFilter.java          Extracts L402 credentials from Authorization header
    L402AuthenticationProvider.java        Validates credentials via L402Validator
    L402AuthenticationToken.java           Spring Security token (unauthenticated and authenticated states)
    L402SecurityAutoConfiguration.java     Registers beans when Spring Security is present
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
     |   AuthenticationManager.authenticate(unauthenticatedToken)
     |       |
     |       v
     |   L402AuthenticationProvider
     |       |
     |       |-- Reconstructs "L402 <macaroon>:<preimage>" header
     |       |-- Delegates to L402Validator.validate()
     |       |-- Returns authenticated L402AuthenticationToken with:
     |       |     - ROLE_L402 authority
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
| `serviceName` | Service name from configuration (`l402.service-name`) |
| `authenticated` | `true` |
| `authorities` | `[ROLE_L402]` |
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

`L402SecurityAutoConfiguration` is registered via Spring Boot's `AutoConfiguration.imports` mechanism and activates when both conditions are met:

1. `EnableWebSecurity` and `L402Validator` classes are on the classpath (`@ConditionalOnClass`)
2. An `L402Validator` bean exists in the application context (`@ConditionalOnBean`)

It registers three beans:

| Bean | Condition | Description |
|------|-----------|-------------|
| `L402AuthenticationProvider` | `@ConditionalOnMissingBean` | Validates L402 tokens using the `L402Validator` and `l402.service-name` property |
| `L402AuthenticationFilter` | `@ConditionalOnMissingBean` + `@ConditionalOnBean(AuthenticationManager.class)` | Extracts credentials from the Authorization header |
| `L402AuthenticationEntryPoint` | `@ConditionalOnMissingBean` | Issues HTTP 402 challenges with Lightning invoices for unauthenticated requests. Uses `L402ChallengeService` and `L402EndpointRegistry` from `l402-spring-autoconfigure`. |

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
l402:
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

## Comparison with Non-Spring-Security Approach

| Aspect | `l402-spring-autoconfigure` (`L402SecurityFilter`) | `l402-spring-security` |
|--------|---------------------------------------------------|----------------------|
| Spring Security dependency | Not required | Required |
| Protection mechanism | `@L402Protected` annotation on controller methods | `SecurityFilterChain` with `authorizeHttpRequests` |
| Filter type | Jakarta `Filter` registered via `FilterRegistrationBean` | `OncePerRequestFilter` added to Spring Security filter chain |
| Invoice generation | Built-in: generates 402 response with invoice | Built-in via `L402AuthenticationEntryPoint`: generates 402 response with invoice when configured as the exception handling entry point |
| Mixed auth support | L402 only | L402 + OAuth2 + JWT + Basic + any Spring Security provider |
| Role/authority model | None | `ROLE_L402` granted authority |
| `@PreAuthorize` support | No | Yes, with full SpEL on `L402AuthenticationToken` attributes |
| Caveat-based access control | Via `CaveatVerifier` implementations at validation time | Via `CaveatVerifier` + SpEL expressions at authorization time |
| `SecurityContextHolder` integration | No | Yes, authenticated token in security context |
| Session management | Stateless (no session) | Configurable (STATELESS recommended) |
| 402 challenge response | Automatic with invoice | Automatic via `L402AuthenticationEntryPoint` when configured as the entry point in `SecurityFilterChain` |

### Mutual Exclusion via `l402.security-mode`

The servlet filter and Spring Security paths are mutually exclusive. The `l402.security-mode` property controls which one is active:

| Value | Servlet filter (`L402SecurityFilter`) | Spring Security (`L402AuthenticationFilter` + entry point) |
|-------|--------------------------------------|----------------------------------------------------------|
| `auto` (default) | Active when Spring Security is **not** on the classpath | Active when Spring Security **is** on the classpath |
| `servlet` | Always active | Disabled, even if Spring Security is on the classpath |
| `spring-security` | Disabled | Always active. Fails at startup if Spring Security is not on the classpath. |

Only one mode is active at a time. This prevents conflicts where both the servlet filter and the Spring Security filter chain attempt to handle the same request.

When using `spring-security` mode, the `L402AuthenticationEntryPoint` replaces the servlet filter's built-in 402 challenge generation. Configure the entry point in your `SecurityFilterChain` to get the full payment flow (challenge issuance + credential validation) through Spring Security.

Set the mode explicitly when both modules are on the classpath:

```yaml
l402:
  enabled: true
  security-mode: spring-security
```

### L402AuthenticationEntryPoint

The entry point implements Spring Security's `AuthenticationEntryPoint` interface. When an unauthenticated request reaches a protected endpoint, it:

1. Looks up the endpoint's L402 configuration from the `L402EndpointRegistry` (price, timeout, pricing strategy)
2. Delegates to `L402ChallengeService` to create a Lightning invoice and mint a macaroon
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

If the requested path is not registered in the `L402EndpointRegistry` (i.e., it has no `@L402Protected` annotation), the entry point returns a plain 401 Unauthorized response instead of a 402 challenge.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :l402-spring-security:test
```

### Test Architecture

Tests use **Mockito** with `MockitoExtension` and Spring's `MockHttpServletRequest`/`MockHttpServletResponse` for testing filter behavior without a running application context. The `L402Validator` is mocked, so no Lightning backend or root key store is needed.

### Test Coverage

`L402AuthenticationFilterTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `constructorRejectsNullAuthenticationManager` | Null guard on constructor |
| `skipsWhenNoAuthorizationHeader` | No header: filter chain continues, no authentication attempt |
| `skipsWhenBlankAuthorizationHeader` | Blank header: filter chain continues |
| `skipsWhenNonL402AuthorizationHeader` | Bearer/Basic headers: filter chain continues (pass-through to other filters) |
| `extractsL402CredentialAndAuthenticates` | Valid `L402` header: extracts macaroon and preimage, authenticates, populates SecurityContext |
| `extractsLsatCredentialAndAuthenticates` | Legacy `LSAT` header: same behavior as `L402` |
| `returns401WhenAuthenticationFails` | Authentication failure: 401 status, `WWW-Authenticate: L402` header, JSON error body, security context cleared |
| `skipsWhenPreimageNotHex` | Invalid preimage format: filter chain continues without authentication attempt |
| `extractsUppercaseHexPreimageAndAuthenticates` | Uppercase hex preimage accepted |
| `extractsMixedCaseHexPreimageAndAuthenticates` | Mixed-case hex preimage accepted |
| `skipsWhenMacaroonEmpty` | Empty macaroon field: filter chain continues |

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

`L402AuthenticationTokenTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `unauthenticatedTokenHoldsRawCredentials` | Unauthenticated state: raw values stored, no tokenId/serviceName/authorities |
| `unauthenticatedTokenPrincipalIsRawMacaroon` | Principal is raw macaroon, credentials is `macaroon:preimage` |
| `unauthenticatedTokenRejectsNullMacaroon` | Null guard on macaroon |
| `unauthenticatedTokenRejectsNullPreimage` | Null guard on preimage |
| `authenticatedTokenExposesCredentialDetails` | Authenticated state: correct tokenId, serviceName, principal, credential |
| `authenticatedTokenHasL402Authority` | `ROLE_L402` authority present |
| `authenticatedTokenExtractsCaveatAttributes` | Caveat key-value pairs extracted into attributes map |
| `builtInAttributesCannotBeOverwrittenByCaveats` | Attacker-controlled caveat keys `tokenId`/`serviceName` overwritten by trusted values |
| `authenticatedTokenWithNullServiceName` | Null service name omitted from attributes, tokenId still present |

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
