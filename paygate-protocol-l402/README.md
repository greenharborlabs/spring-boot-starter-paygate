# paygate-protocol-l402

The L402 protocol adapter for the `spring-boot-starter-paygate` project. This module implements the protocol-agnostic `PaymentProtocol` interface from `paygate-api` by delegating to the existing L402 infrastructure in `paygate-core`. It bridges the gap between the multi-protocol framework and the battle-tested macaroon-based L402 authentication system.

---

## Table of Contents

- [Purpose](#purpose)
- [Installation](#installation)
- [Architecture](#architecture)
- [Key Classes](#key-classes)
  - [L402Protocol](#l402protocol)
  - [L402Metadata](#l402metadata)
- [Delegation Flow](#delegation-flow)
- [Error Code Mapping](#error-code-mapping)
- [Header Injection Defense](#header-injection-defense)
- [Security Considerations](#security-considerations)
- [Testing](#testing)
- [Module Dependency Graph](#module-dependency-graph)
- [License](#license)

---

## Purpose

The `paygate-api` module defines a `PaymentProtocol` interface that all payment protocols must implement. The `paygate-protocol-l402` module is the L402 implementation of that interface. Rather than reimplementing L402 logic, it acts as a thin adapter that delegates every operation to the proven types in `paygate-core`:

- **Credential parsing** delegates to `L402Credential.parse()`
- **Challenge formatting** delegates to `MacaroonMinter`, `MacaroonSerializer`, and standard base64 encoding
- **Validation** delegates to `L402Validator` via an `L402VerificationContext`

This keeps the L402 protocol logic in one place (`paygate-core`) while allowing the multi-protocol framework to treat L402 as just another `PaymentProtocol` implementation.

---

## Installation

This module is not used directly by application developers. It is pulled in transitively through the starter:

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

If you need to depend on `paygate-protocol-l402` directly:

```kotlin
implementation("com.greenharborlabs:paygate-protocol-l402:0.1.0")
```

### Build Dependencies

| Scope | Dependency | Purpose |
|-------|-----------|---------|
| api | `paygate-api` | `PaymentProtocol` interface and protocol-agnostic types |
| implementation | `paygate-core` | L402 macaroon infrastructure, validator, credential parsing |
| test | JUnit 5 | Test framework |
| test | AssertJ | Fluent test assertions |
| test | Mockito | Mocking `L402Validator` for isolated unit tests |

---

## Architecture

The module contains two types, both in the `com.greenharborlabs.paygate.protocol.l402` package:

```
paygate-protocol-l402/
  src/main/java/com/greenharborlabs/paygate/protocol/l402/
    L402Protocol.java       PaymentProtocol implementation (adapter)
    L402Metadata.java       Protocol-specific metadata record
  src/test/java/com/greenharborlabs/paygate/protocol/l402/
    L402ProtocolTest.java   Unit tests with mocked L402Validator
```

---

## Key Classes

### L402Protocol

The central class in this module. It implements `PaymentProtocol` and adapts the `paygate-core` L402 infrastructure to the protocol-agnostic API.

**Constructor:**

```java
public L402Protocol(L402Validator validator, String serviceName)
```

Both parameters are required and must not be null. The `validator` is the `paygate-core` validation pipeline; `serviceName` is embedded in macaroon caveats during challenge formatting and used for service verification during validation.

#### `scheme()`

Returns `"L402"` -- the unique scheme identifier for this protocol. Used by the framework to match protocols to incoming credentials and to label outgoing challenges.

#### `canHandle(String authorizationHeader)`

Performs a fast, case-insensitive prefix check against the `Authorization` header value. Returns `true` if the header starts with `"L402 "` or `"LSAT "` (five characters including the trailing space). This provides backward compatibility with the older LSAT scheme name.

Returns `false` for null input, strings shorter than 5 characters, and any other scheme prefix. This method never throws.

#### `parseCredential(String authorizationHeader)`

Parses a raw `Authorization` header into a protocol-agnostic `PaymentCredential`. The delegation chain is:

1. Calls `L402Credential.parse(authorizationHeader)` in `paygate-core`, which validates the `(L402|LSAT) <base64-macaroon>:<64-hex-preimage>` format, deserializes the V2 binary macaroon, and extracts the preimage
2. Decodes the 66-byte `MacaroonIdentifier` from the macaroon to extract the payment hash and token ID
3. Wraps the result in a `PaymentCredential` with:
   - `paymentHash` -- 32 bytes from the macaroon identifier
   - `preimage` -- 32 bytes from the hex preimage in the header
   - `tokenId` -- hex-encoded 32-byte token ID from the macaroon identifier
   - `sourceProtocolScheme` -- `"L402"`
   - `source` -- `null` (no source URI for L402)
   - `metadata` -- an `L402Metadata` record carrying the parsed macaroon, additional macaroons, and raw header

Throws `PaymentValidationException` with error code `MALFORMED_CREDENTIAL` if the header cannot be parsed.

#### `formatChallenge(ChallengeContext context)`

Formats an HTTP 402 challenge response. This is the server-side operation that creates a new macaroon and pairs it with a Lightning invoice for the client to pay.

The method:

1. Decodes the hex token ID from the context and constructs a `MacaroonIdentifier` with signed identifier version 1, the payment hash, and the token ID
2. Builds a caveat list:
   - `services` caveat: `"{serviceName}:0"` (always present)
   - `route` caveat: the canonical registered route pattern
   - `method` caveat: the actual request method
   - `{serviceName}_capabilities` caveat: the declared capability ceiling, or `~` for no capability
   - `{serviceName}_valid_until` caveat: Unix epoch timestamp of `now + timeoutSeconds`
3. Mints the macaroon via `MacaroonMinter.mint()` using the root key from the context
4. Serializes to V2 binary format via `MacaroonSerializer.serializeV2()` and encodes with **standard base64 with padding** (not base64url)
5. Sanitizes the BOLT11 invoice string against header injection (see [Header Injection Defense](#header-injection-defense))
6. Formats the `WWW-Authenticate` header as: `L402 version="0", token="<base64>", macaroon="<base64>", invoice="<bolt11>"`

The `token` and `macaroon` fields contain identical values. Both are included for compatibility with different client implementations.

Returns a `ChallengeResponse` with the formatted header, scheme `"L402"`, and null body data.

#### `validate(PaymentCredential credential, Map<String, String> requestContext)`

Validates a previously parsed credential. The delegation chain is:

1. Verifies the credential's metadata is an `L402Metadata` instance (rejects non-L402 metadata with `MALFORMED_CREDENTIAL`)
2. Builds an `L402VerificationContext` with:
   - `serviceName` from the protocol's configured service name
   - `currentTime` set to `Instant.now()`
   - `requestMetadata` from the caller's request context map (path, method, client_ip, capability, etc.) -- the requested capability flows through the metadata map via `VerificationContextKeys.REQUESTED_CAPABILITY`
3. Delegates to `L402Validator.validate()` with the raw authorization header from the metadata and the verification context
4. Maps any `L402Exception` to a `PaymentValidationException` (see [Error Code Mapping](#error-code-mapping))

Both `credential` and `requestContext` parameters must not be null.

### L402Metadata

An immutable record implementing the `ProtocolMetadata` marker interface from `paygate-api`. It carries L402-specific data that the generic `PaymentCredential` cannot represent:

```java
public record L402Metadata(
    Macaroon macaroon,
    List<Macaroon> additionalMacaroons,
    String rawAuthorizationHeader
) implements ProtocolMetadata { ... }
```

| Field | Type | Description |
|-------|------|-------------|
| `macaroon` | `Macaroon` | The primary macaroon from the `Authorization` header |
| `additionalMacaroons` | `List<Macaroon>` | Any additional macaroons presented alongside the primary one (immutable copy via `List.copyOf()`) |
| `rawAuthorizationHeader` | `String` | The original `Authorization` header value, preserved for downstream validation by `L402Validator` |

All fields are required (non-null). The compact constructor validates this and creates a defensive copy of the additional macaroons list.

---

## Delegation Flow

```
Authorization: L402 <mac>:<preimage>
         |
         v
  +----------------+
  | L402Protocol   |
  | .canHandle()   |----> "L402 " or "LSAT " prefix match (case-insensitive)
  | .parseCredential() |
  +----------------+
         |
         v
  +------------------+
  | L402Credential   |  (paygate-core)
  | .parse(header)   |
  +------------------+
         |
         v
  +------------------+
  | PaymentCredential|  (paygate-api)
  | + L402Metadata   |
  +------------------+

Validation:
  +----------------+
  | L402Protocol   |
  | .validate()    |
  +----------------+
         |
         v
  +------------------------+
  | L402VerificationContext |
  | + L402Validator         |  (paygate-core)
  +------------------------+

Challenge formatting:
  +----------------+
  | L402Protocol   |
  | .formatChallenge() |
  +----------------+
         |
         v
  +-------------------+     +----------------------+
  | MacaroonMinter    |---->| MacaroonSerializer   |  (paygate-core)
  | .mint(key, id,    |     | .serializeV2()       |
  |  caveats)         |     +----------------------+
  +-------------------+              |
                                     v
                              Base64.getEncoder()
                              (standard, with padding)
                                     |
                                     v
                            WWW-Authenticate header
```

---

## Error Code Mapping

When `L402Validator` throws an `L402Exception`, the `L402Protocol` maps the L402-specific `ErrorCode` to the protocol-agnostic `PaymentValidationException.ErrorCode`:

| L402 `ErrorCode` | Mapped `PaymentValidationException.ErrorCode` | Meaning |
|-------------------|----------------------------------------------|---------|
| `MALFORMED_HEADER` | `MALFORMED_CREDENTIAL` | Authorization header does not match L402/LSAT format |
| `INVALID_PREIMAGE` | `INVALID_PREIMAGE` | SHA-256(preimage) does not match payment hash |
| `EXPIRED_CREDENTIAL` | `EXPIRED_CREDENTIAL` | `valid_until` caveat timestamp is in the past |
| `INVALID_MACAROON` | `INVALID_CHALLENGE_BINDING` | Macaroon authenticity, structure, or attenuation validation failed |
| `INVALID_SERVICE` | `INVALID_CHALLENGE_BINDING` | Authenticated identifier schema, request boundary, or service constraints were not satisfied |
| `REVOKED_CREDENTIAL` | `INVALID_CHALLENGE_BINDING` | Root key has been revoked |
| `LIGHTNING_UNAVAILABLE` | `SERVICE_UNAVAILABLE` | Lightning backend is unreachable (503) |

The token ID from the `L402Exception` is preserved in the mapped exception. The adapter replaces
core validation details with a fixed safe message for each mapped category; in particular,
`INVALID_SERVICE` becomes `INVALID_CHALLENGE_BINDING` with HTTP 402 and exactly
`L402 credential validation failed`. The underlying core contract remains `INVALID_SERVICE`, HTTP
401, with exactly `Credential constraints were not satisfied`.

---

## Header Injection Defense

The `L402Challenge.sanitizeBolt11ForHeader()` method in paygate-core validates that a BOLT11 invoice string contains no characters that could enable HTTP header injection. Before embedding the invoice in the `WWW-Authenticate` header, it scans every character and rejects:

- C0 control characters (`0x00`-`0x1F`) -- includes `\r` and `\n` which could split headers
- DEL (`0x7F`)
- Double-quote (`"`) -- could break out of the quoted field value

If any illegal character is found, the method throws `IllegalArgumentException` with a message identifying the character position and hex value. A null input is treated as an empty string.

---

## Security Considerations

### Adapter-Only Design

This module contains no cryptographic logic of its own. All security-critical operations -- HMAC-SHA256 signature chains, constant-time comparisons, preimage verification, key derivation -- are implemented in `paygate-core` and merely invoked through this adapter. This minimizes the attack surface of the protocol layer.

### Metadata Type Safety

The `validate()` method uses Java pattern matching (`instanceof L402Metadata metadata`) to verify that the credential was actually produced by this protocol. If a `PaymentCredential` with non-L402 metadata is passed to `validate()`, it is rejected with `MALFORMED_CREDENTIAL` rather than attempting unsafe casts.

### Standard Base64 Encoding

L402 macaroons use **standard base64 with padding** (`java.util.Base64.getEncoder()`), not base64url. This matches the Go `go-macaroon` library's encoding and ensures cross-language interoperability.

### Immutable Metadata

`L402Metadata` is an immutable record. The `additionalMacaroons` list is defensively copied via `List.copyOf()` in the compact constructor, preventing external mutation after construction.

Its `toString()` is deliberately non-recursive and redacts the primary macaroon, additional macaroons, and raw `Authorization` header. It reports only the metadata type, redaction labels, additional-macaroon count, and raw-header length; diagnostics must not include credentials, preimages, root keys, or sensitive validation reasons.

### Request Boundaries and Attenuation

This tracked README is the canonical integration guidance for custom L402 issuers and adapters;
ignored workspace specifications are planning artifacts only.

Challenge formatting fails closed unless the canonical route and actual method are present. A `HEAD` request can inherit the complete `GET` endpoint policy, but its macaroon is still minted with `method=HEAD`, so GET- and HEAD-bound credentials are not interchangeable. The route is application-relative and remains stable when the application is deployed under a context path or path-prefix servlet mapping.

The capability caveat is an authenticated ceiling, not an additive grant. `~` means no capability; holder-added caveats may retain or narrow a named ceiling, including narrowing it to `~`, but cannot expand it or turn `~` into a named grant. Validation derives the effective capability set only after the entire attenuation chain succeeds.

Previously issued L402 credentials without `route`, `method`, or the capability ceiling are intentionally rejected, including cached credentials. Accepting the legacy `LSAT` scheme name does not grandfather those unbound credentials; clients must obtain a new challenge.

Built-in issuance always uses identifier version 1 and the five caveats listed above, in that
sequence. This sequence is a guarantee of the built-in issuer, not a universal caveat-position
requirement for custom issuers. A custom issuer must begin with
`new MacaroonIdentifier(1, paymentHash, tokenId)` and sign a canonical `route`, the actual `method`,
the active service's `{serviceName}_capabilities` ceiling, and every caveat required by the
verifiers registered with its validator. For applications that construct an `L402Validator`
directly, `ServicesCaveatVerifier` and `ValidUntilCaveatVerifier` are caller-selected; include their
caveats when those verifiers are registered. Registered verifiers determine required caveats, not
a universal caveat position. A correctly signed identifier-v0 credential is rejected even if its holder appends matching route, method, and capability caveats; clients must obtain and pay a new challenge.

This migration changes only the signed identifier version. The identifier remains 66 bytes (`[version:2 BE][paymentHash:32][tokenId:32]`), the HTTP challenge still advertises `version="0"`, and the macaroon still uses V2 binary encoding compatible with Go `go-macaroon` parsers.

### Fail Closed

The error mapping ensures that all L402 validation failures are translated to `PaymentValidationException`, which the framework treats as an authentication failure. No L402 error code silently grants access.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-protocol-l402:test
```

### Test Architecture

Tests use **JUnit 5** with **AssertJ** for fluent assertions and **Mockito** for mocking the `L402Validator`. The test suite builds real macaroons using `paygate-core` infrastructure (`MacaroonMinter`, `MacaroonSerializer`) to construct valid `Authorization` headers, then verifies that `L402Protocol` correctly delegates to and translates results from `paygate-core`.

### Test Coverage

#### Constructor Validation

| Test Case | What It Verifies |
|-----------|-----------------|
| `constructor_rejectsNullValidator` | Null `L402Validator` throws `NullPointerException` |
| `constructor_rejectsNullServiceName` | Null service name throws `NullPointerException` |

#### Scheme (`scheme()`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `scheme_returnsL402` | Returns the string `"L402"` |

#### Header Detection (`canHandle()`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `trueForL402OrLsatPrefix` | Accepts `L402 `, `LSAT `, `l402 `, `lsat ` prefixes (case-insensitive) |
| `trueForVariousCaseVariations` | Accepts mixed-case variations and minimal headers |
| `falseForNull` | Returns `false` for null input |
| `falseForShortStrings` | Returns `false` for strings shorter than 5 characters |
| `falseForNonL402Schemes` | Returns `false` for `Bearer`, `Basic`, `Payment`, and similar prefixes |

#### Credential Parsing (`parseCredential()`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `parsesValidL402Header` | L402 header produces credential with correct scheme, token ID, payment hash, and preimage |
| `parsesValidLsatHeader` | LSAT header produces credential with scheme `"L402"` (backward compatibility) |
| `metadataContainsMacaroonAndRawHeader` | `L402Metadata` carries the parsed macaroon and original header |
| `tokenIdMatchesExpectedHex` | Token ID in credential matches the hex encoding of the identifier bytes |
| `throwsPaymentValidationExceptionForMalformedHeader` | Invalid header throws `MALFORMED_CREDENTIAL` |
| `throwsPaymentValidationExceptionForNullHeader` | Null header throws `MALFORMED_CREDENTIAL` |
| `throwsPaymentValidationExceptionForEmptyHeader` | Empty header throws `MALFORMED_CREDENTIAL` |

#### Challenge Formatting (`formatChallenge()`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `producesCorrectWwwAuthenticateHeaderFormat` | Header starts with `L402 version="0"` and contains `token`, `macaroon`, and `invoice` fields |
| `macaroonInHeaderIsValidBase64` | The base64 macaroon decodes without error |
| `challengeIncludesServicesCaveat` | Macaroon contains `services={serviceName}:0` caveat |
| `challengeIncludesCapabilityCaveatWhenPresent` | Macaroon contains `{serviceName}_capabilities` caveat when capability is specified |
| `challengeOmitsCapabilityCaveatWhenNull` | Capability caveat is absent when capability is null |
| `challengeIncludesValidUntilCaveat` | Macaroon contains `{serviceName}_valid_until` caveat with a future epoch timestamp |
| `rejectsBolt11WithControlCharacters` | BOLT11 containing `\r\n` throws `IllegalArgumentException` |
| `tokenAndMacaroonFieldsAreIdentical` | The `token` and `macaroon` field values in the header are identical |

#### Validation Delegation (`validate()`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `delegatesToL402Validator` | Calls `L402Validator.validate()` with the raw authorization header |
| `passesRequestContextToVerificationContext` | The `L402VerificationContext` carries the service name, current time, and request metadata |
| `rejectsNullCredential` | Null credential throws `NullPointerException` |
| `rejectsNullRequestContext` | Null request context throws `NullPointerException` |
| `rejectsCredentialWithNonL402Metadata` | Non-L402 metadata throws `MALFORMED_CREDENTIAL` with the credential's token ID |

#### Error Code Mapping

| Test Case | What It Verifies |
|-----------|-----------------|
| `malformedHeaderMapsTOMalformedCredential` | `MALFORMED_HEADER` maps to `MALFORMED_CREDENTIAL` |
| `invalidPreimageMapsToInvalidPreimage` | `INVALID_PREIMAGE` maps to `INVALID_PREIMAGE` |
| `expiredCredentialMapsToExpiredCredential` | `EXPIRED_CREDENTIAL` maps to `EXPIRED_CREDENTIAL` |
| `invalidMacaroonMapsToInvalidChallengeBinding` | `INVALID_MACAROON` maps to `INVALID_CHALLENGE_BINDING` |
| `invalidServiceRemainsGenericallySanitized` | Core `INVALID_SERVICE` maps to `INVALID_CHALLENGE_BINDING`/402 with a fixed message and preserved token ID |
| `revokedCredentialMapsToInvalidChallengeBinding` | `REVOKED_CREDENTIAL` maps to `INVALID_CHALLENGE_BINDING` |
| `lightningUnavailableMapsToServiceUnavailable` | `LIGHTNING_UNAVAILABLE` maps to `SERVICE_UNAVAILABLE` |
| `errorMessageIsSanitizedAndTokenIdIsPreserved` | Unsafe core detail is replaced while the token ID survives the mapping |

---

## Module Dependency Graph

```
paygate-api  (protocol abstraction — zero external dependencies)
    ^
    |
paygate-protocol-l402  (this module)
    |
    v
paygate-core  (L402 macaroon infrastructure — zero external dependencies)

Position in the overall project:

paygate-spring-boot-starter
  └── paygate-spring-autoconfigure
        ├── paygate-protocol-l402  <── this module
        │     ├── paygate-api
        │     └── paygate-core
        ├── paygate-protocol-mpp
        │     └── paygate-api
        ├── paygate-lightning-lnd
        │     └── paygate-core
        ├── paygate-lightning-lnbits
        │     └── paygate-core
        └── paygate-spring-security (optional)
```

This module depends on:

- **`paygate-api`** (`api` scope) -- for `PaymentProtocol`, `PaymentCredential`, `ChallengeContext`, `ChallengeResponse`, `PaymentValidationException`, and `ProtocolMetadata`
- **`paygate-core`** (`implementation` scope) -- for `L402Credential`, `L402Validator`, `L402Exception`, `ErrorCode`, `MacaroonMinter`, `MacaroonSerializer`, `MacaroonIdentifier`, `Macaroon`, `Caveat`, `L402VerificationContext`, and `VerificationContextKeys`

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
