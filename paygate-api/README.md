# paygate-api

The protocol abstraction API for the `spring-boot-starter-paygate` project. This is a **pure Java module with zero external dependencies** -- it relies only on JDK classes (`java.util`, `java.util.Objects`, `java.util.Arrays`). It defines the SPI (Service Provider Interface) that decouples the security filter and challenge service from specific payment protocol implementations, enabling the framework to support multiple payment protocols (L402, MPP, and future additions) without any protocol-specific knowledge in the core request pipeline.

Every protocol implementation module -- `paygate-protocol-l402` and `paygate-protocol-mpp` -- implements the interfaces defined here.

---

## Table of Contents

- [Design Principles](#design-principles)
- [Installation](#installation)
- [Architecture](#architecture)
- [PaymentProtocol SPI](#paymentprotocol-spi)
- [Public Types](#public-types)
- [ErrorCode Enum](#errorcode-enum)
- [Security Considerations](#security-considerations)
- [Testing](#testing)
- [Module Dependency Graph](#module-dependency-graph)
- [License](#license)

---

## Design Principles

- **Zero external dependencies.** The compile classpath contains only JDK modules. No Spring, no Jackson, no Apache Commons. This keeps the API lightweight and free of transitive dependency conflicts, making it safe to depend on from any module regardless of its own dependency tree.
- **Protocol agnosticism.** The types in this module carry payment data without encoding any protocol-specific wire format. The security filter operates on `PaymentCredential` and `ChallengeResponse` without knowing whether the underlying protocol is L402, MPP, or something else entirely.
- **Immutability.** All public-facing types (`ChallengeContext`, `ChallengeResponse`, `PaymentCredential`, `PaymentReceipt`) are immutable records. Byte array fields are defensively copied on construction and on access. Map fields are wrapped in unmodifiable views.
- **Constant-time secret comparison.** `ChallengeContext` and `PaymentCredential` use XOR-accumulation comparison for byte array fields in their `equals()` methods. `Arrays.equals` is never used for secret data.
- **No secret logging.** `toString()` methods on `ChallengeContext` and `PaymentCredential` redact sensitive byte array fields (payment hashes, preimages, root keys) and expose only structural metadata (token IDs, protocol schemes, prices).

---

## Installation

This module is not used directly by application developers. It is pulled in transitively through the starter and protocol modules:

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

If you need to depend on `paygate-api` directly (for example, to implement a custom `PaymentProtocol`):

```kotlin
implementation("com.greenharborlabs:paygate-api:0.1.0")
```

### Build Dependencies

| Scope | Dependency | Purpose |
|-------|-----------|---------|
| compile | *none* | Zero external compile dependencies -- JDK only |
| test | JUnit 5 | Test framework |
| test | AssertJ 3.27.3 | Fluent test assertions |

---

## Architecture

The module is organized as a single flat package:

```
paygate-api/
  src/main/java/com/greenharborlabs/paygate/api/
    PaymentProtocol.java           SPI interface for payment protocol implementations
    ChallengeContext.java          Data record for creating payment challenges
    ChallengeResponse.java         Formatted WWW-Authenticate header + body data
    PaymentCredential.java         Parsed credential from Authorization header
    PaymentReceipt.java            Receipt data for Payment-Receipt response header
    ProtocolMetadata.java          Marker interface for protocol-specific metadata
    PaymentValidationException.java  Runtime exception with ErrorCode and RFC 9457 URIs
  src/test/java/com/greenharborlabs/paygate/api/
    ChallengeContextTest.java      Defensive copy, validation, equality tests
    ChallengeResponseTest.java     Immutability and field access tests
    PaymentCredentialTest.java     Defensive copy, toString safety, equality tests
    PaymentReceiptTest.java        Validation and field access tests
    PaymentValidationExceptionTest.java  ErrorCode mapping and constructor tests
```

---

## PaymentProtocol SPI

The `PaymentProtocol` interface is the primary extension point. Protocol implementations are registered as Spring beans and injected into the security filter as a `List<PaymentProtocol>`. Each implementation handles a single authentication scheme.

```
+-------------------------+       +---------------------------+
| PaygateSecurityFilter   |       | PaygateChallengeService   |
+-------------------------+       +---------------------------+
         |                                   |
         | List<PaymentProtocol>             | ChallengeContext
         v                                   v
+--------------------+             +--------------------+
|  PaymentProtocol   |             |  PaymentProtocol   |
|  (interface)       |             |  (interface)       |
+--------------------+             +--------------------+
| scheme()           |             | formatChallenge()  |
| canHandle(header)  |             |   --> ChallengeResponse
| parseCredential()  |             | createReceipt()    |
|   --> PaymentCredential          |   --> PaymentReceipt
| validate(cred, ctx)|             +--------------------+
+--------------------+
         ^                                   ^
         |                                   |
  +------+------+                     +------+------+
  | L402Protocol |                     | MppProtocol  |
  | (paygate-    |                     | (paygate-    |
  |  protocol-   |                     |  protocol-   |
  |  l402)       |                     |  mpp)        |
  +--------------+                     +--------------+
```

### Interface Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `scheme()` | `String scheme()` | Returns the unique scheme identifier (e.g., `"L402"`, `"Payment"`). Used to match `Authorization` headers and format `WWW-Authenticate` challenges. |
| `canHandle()` | `boolean canHandle(String authorizationHeader)` | Fast, prefix-based check to determine if this protocol recognizes the header format. Must not throw on malformed input. |
| `parseCredential()` | `PaymentCredential parseCredential(String authorizationHeader)` | Parses a raw `Authorization` header into a protocol-agnostic `PaymentCredential`. Throws `PaymentValidationException` if the header is malformed. |
| `formatChallenge()` | `ChallengeResponse formatChallenge(ChallengeContext context)` | Formats a `ChallengeContext` into a `WWW-Authenticate` header value and optional response body data. |
| `validate()` | `void validate(PaymentCredential credential, Map<String, String> requestContext)` | Validates a parsed credential (signature checks, preimage verification, caveat enforcement). Throws `PaymentValidationException` on failure. The `requestContext` map carries per-request data (path, method, client IP) for delegation caveats; pass an empty map if no context is available. |
| `createReceipt()` | `default Optional<PaymentReceipt> createReceipt(PaymentCredential credential, ChallengeContext context)` | Creates a receipt after successful validation. Returns `Optional.empty()` by default; protocols that support proof-of-payment receipts override this method. |

---

## Public Types

| Type | Kind | Description |
|------|------|-------------|
| `PaymentProtocol` | interface | SPI for payment protocol implementations. Defines the full lifecycle: header detection, credential parsing, challenge formatting, validation, and receipt creation. |
| `ChallengeContext` | record | Protocol-agnostic data carrying all information needed to create a payment challenge: payment hash, token ID, bolt11 invoice, price, root key bytes, opaque data map, and digest. Produced by `PaygateChallengeService`, consumed by `PaymentProtocol.formatChallenge()`. |
| `ChallengeResponse` | record | A protocol's formatted challenge output containing the `WWW-Authenticate` header value, the protocol scheme that produced it, and optional body data for the JSON response. |
| `PaymentCredential` | record | Protocol-agnostic representation of a parsed payment credential: payment hash, preimage, token ID, source protocol scheme, optional payer identity (DID format from MPP), and protocol-specific metadata. |
| `PaymentReceipt` | record | Data for the `Payment-Receipt` response header: status, HMAC-bound challenge ID, payment method, method-specific reference, amount in satoshis, RFC 3339 timestamp, and protocol scheme. |
| `ProtocolMetadata` | marker interface | Carried by `PaymentCredential` to hold protocol-specific fields that the core framework does not need to understand. Each protocol module provides its own implementation (e.g., `L402Metadata`, `MppMetadata`). Consumers that know the concrete protocol can cast to the expected subtype. |
| `PaymentValidationException` | class | Runtime exception thrown when credential validation fails. Carries an `ErrorCode` that determines the HTTP status code and RFC 9457 problem type URI. Optionally carries a `tokenId` for logging (safe to log, unlike the full credential). |

### ChallengeContext Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `paymentHash` | `byte[]` | yes | SHA-256 hash of the payment preimage (defensively copied) |
| `tokenId` | `String` | yes | Token/challenge identifier |
| `bolt11Invoice` | `String` | yes | BOLT11 Lightning payment request string |
| `priceSats` | `long` | yes | Price in satoshis (must be > 0) |
| `description` | `String` | no | Human-readable payment description |
| `serviceName` | `String` | no | Service name for caveat generation |
| `timeoutSeconds` | `long` | yes | Challenge validity duration in seconds |
| `capability` | `String` | no | Required capability for the endpoint |
| `rootKeyBytes` | `byte[]` | no | Root key for macaroon signing (defensively copied) |
| `opaque` | `Map<String, String>` | no | Protocol-specific opaque data (immutable copy) |
| `digest` | `String` | no | Content digest for challenge binding |

### PaymentCredential Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `paymentHash` | `byte[]` | yes | SHA-256 hash of the preimage (defensively copied) |
| `preimage` | `byte[]` | yes | 32-byte preimage proving payment (defensively copied) |
| `tokenId` | `String` | yes | Token/challenge identifier |
| `sourceProtocolScheme` | `String` | yes | Protocol that parsed this credential (`"L402"` or `"Payment"`) |
| `source` | `String` | no | Optional payer identity (DID format from MPP) |
| `metadata` | `ProtocolMetadata` | yes | Protocol-specific metadata |

### PaymentReceipt Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `status` | `String` | yes | Receipt status (e.g., `"success"`) |
| `challengeId` | `String` | yes | HMAC-bound challenge ID |
| `method` | `String` | yes | Payment method (e.g., `"lightning"`) |
| `reference` | `String` | no | Method-specific reference (e.g., bolt11 invoice) |
| `amountSats` | `long` | yes | Amount paid in satoshis (must be > 0) |
| `timestamp` | `String` | yes | RFC 3339 timestamp |
| `protocolScheme` | `String` | yes | Protocol scheme (e.g., `"Payment"`) |

---

## ErrorCode Enum

`PaymentValidationException.ErrorCode` classifies validation failures and maps each to an HTTP status code and an RFC 9457 problem type URI.

| Error Code | HTTP Status | Problem Type URI | When |
|-----------|-------------|------------------|------|
| `MALFORMED_CREDENTIAL` | 402 | `https://paymentauth.org/problems/malformed-credential` | Authorization header cannot be parsed by any protocol |
| `INVALID_PREIMAGE` | 402 | `https://paymentauth.org/problems/verification-failed` | SHA-256(preimage) does not match the payment hash |
| `INVALID_CHALLENGE_BINDING` | 402 | `https://paymentauth.org/problems/verification-failed` | HMAC challenge binding verification failed (MPP) |
| `EXPIRED_CREDENTIAL` | 402 | `https://paymentauth.org/problems/verification-failed` | Credential has expired |
| `METHOD_UNSUPPORTED` | 400 | `https://paymentauth.org/problems/method-unsupported` | Requested payment method is not supported |

All error codes map to 4xx status codes. The problem type URIs follow the RFC 9457 pattern for structured error responses.

---

## Security Considerations

### Constant-Time Secret Comparison

`ChallengeContext` and `PaymentCredential` implement custom `equals()` methods that use XOR-accumulation comparison for byte array fields:

```java
private static boolean constantTimeEquals(byte[] a, byte[] b) {
    if (a == b) return true;
    if (a == null || b == null || a.length != b.length) return false;
    int result = 0;
    for (int i = 0; i < a.length; i++) {
        result |= a[i] ^ b[i];
    }
    return result == 0;
}
```

This prevents timing side-channel attacks when comparing payment hashes, preimages, and root key bytes. `Arrays.equals` is **never** used for secret data.

### Defensive Copying

All byte array fields in immutable records are defensively copied on construction and on access. This prevents external mutation of internal state:

- `ChallengeContext`: `paymentHash` and `rootKeyBytes` are cloned in the compact constructor and in accessors
- `PaymentCredential`: `paymentHash` and `preimage` are cloned in the compact constructor and in accessors

Map fields (`ChallengeContext.opaque`, `ChallengeResponse.bodyData`) are wrapped in unmodifiable views via `Map.copyOf()` or `Collections.unmodifiableMap()`.

### No Secret Logging

- `ChallengeContext.toString()` returns `ChallengeContext[tokenId=..., priceSats=..., serviceName=...]` -- no payment hash or root key bytes
- `PaymentCredential.toString()` returns `PaymentCredential[tokenId=..., sourceProtocolScheme=..., source=...]` -- no payment hash or preimage

### Input Validation

All records validate required fields in their compact constructors:

- Null checks on required fields throw `NullPointerException` with descriptive messages
- `ChallengeContext.priceSats` must be positive
- `PaymentReceipt.amountSats` must be positive
- `PaymentValidationException` requires a non-null `ErrorCode`

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-api:test
```

### Test Architecture

Tests use **JUnit 5** with **AssertJ** for fluent assertions. Each public type has a dedicated test class that verifies construction, validation, immutability, and object contract compliance.

### Test Coverage by Component

#### ChallengeContext (`ChallengeContextTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Construct with all valid fields | All fields round-trip correctly through accessors |
| Construct with null optional fields | `description`, `serviceName`, `capability`, `rootKeyBytes`, `opaque`, `digest` accept null |
| Null required fields throw | `paymentHash`, `tokenId`, `bolt11Invoice` reject null with descriptive messages |
| Non-positive priceSats throws | Values 0, -1, -100, and `Long.MIN_VALUE` all rejected |
| One-sat price is valid | Boundary value acceptance |
| Constructor makes defensive copy of paymentHash | Mutating the input array does not affect the record |
| paymentHash accessor returns defensive copy | Two calls return distinct array instances with equal content |
| Mutating returned paymentHash does not affect record | Modifying the returned array does not corrupt internal state |
| Constructor makes defensive copy of rootKeyBytes | Same defensive copy semantics as paymentHash |
| rootKeyBytes accessor returns null when null | Null pass-through for optional field |
| Opaque map is immutable after construction | `put()` throws `UnsupportedOperationException` |
| Opaque map is defensively copied from input | Mutating the input map after construction has no effect |
| equals returns true for identical contexts | Value equality with matching hashCode |
| equals returns false for differing fields | paymentHash, tokenId, priceSats, rootKeyBytes differences all detected |
| equals handles null rootKeyBytes on both sides | Null-null comparison returns true |
| toString contains tokenId, price, and serviceName | Structural metadata is present |
| toString does not leak sensitive bytes | No `rootKeyBytes` or `paymentHash` in output |

#### ChallengeResponse (`ChallengeResponseTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Construct with all valid fields | Field round-trip correctness |
| Construct with null bodyData | Null is allowed for optional body |
| Null required fields throw | `wwwAuthenticateHeader` and `protocolScheme` reject null |
| bodyData is immutable after construction | `put()` and `remove()` throw `UnsupportedOperationException` |
| bodyData is defensively copied from input | Mutating the input map after construction has no effect |
| Fields round-trip for MPP-style response | Verifies with `"Payment"` scheme and multiple body entries |

#### PaymentCredential (`PaymentCredentialTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Construct with all valid fields | All fields round-trip correctly |
| Construct with null source | Optional payer identity accepts null |
| Null required fields throw | `paymentHash`, `preimage`, `tokenId`, `sourceProtocolScheme`, `metadata` reject null |
| Defensive copy of paymentHash on construction and access | Mutation isolation |
| Defensive copy of preimage on construction and access | Mutation isolation |
| toString does not leak secrets | No `preimage` or `paymentHash` in output; contains `tokenId` and `sourceProtocolScheme` |
| equals with same instance, equal content, differing fields, null, different type | Full object contract compliance |

#### PaymentReceipt (`PaymentReceiptTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Construct with all valid fields | Field round-trip correctness |
| Construct with null reference | Optional field accepts null |
| Null required fields throw | `status`, `challengeId`, `method`, `timestamp`, `protocolScheme` reject null |
| Non-positive amountSats throws | Values 0, -1, -100, and `Long.MIN_VALUE` all rejected |
| One-sat amount is valid | Boundary value acceptance |
| Fields round-trip with different values | Verifies with alternative field values |

#### PaymentValidationException (`PaymentValidationExceptionTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Each ErrorCode maps to correct HTTP status and problem type URI | `MALFORMED_CREDENTIAL` -> 402, `INVALID_PREIMAGE` -> 402, `INVALID_CHALLENGE_BINDING` -> 402, `EXPIRED_CREDENTIAL` -> 402, `METHOD_UNSUPPORTED` -> 400 |
| All error codes have non-null HTTPS problem type URIs | URI format compliance |
| All error codes have 4xx/5xx HTTP status | Status range validation |
| All five error codes exist | Enum completeness guard |
| Two-arg constructor sets fields correctly | Message, errorCode, null tokenId, derived httpStatus and problemTypeUri |
| Two-arg constructor with null errorCode throws | Input validation |
| Three-arg String constructor sets tokenId | Token ID propagation |
| Three-arg String constructor with null tokenId is allowed | Optional token ID |
| Three-arg Throwable constructor preserves cause chain | Nested exception chaining |
| Is a RuntimeException | Type hierarchy |
| Each ErrorCode propagates status and URI through exception | Parameterized test across all enum values |

---

## Module Dependency Graph

```
paygate-api  (this module -- zero external dependencies)
    ^
    |
    +--- paygate-protocol-l402         (paygate-api + paygate-core)
    |
    +--- paygate-protocol-mpp          (paygate-api only, no paygate-core)
    |
    +--- paygate-spring-autoconfigure  (Spring Boot + paygate-api + protocol modules)
    |        ^
    |        |
    |        +--- paygate-spring-security (Spring Security + paygate-spring-autoconfigure)
    |        |
    |        +--- paygate-spring-boot-starter (dependency aggregator, no source)
    |
    +--- paygate-example-app           (reference implementation)
```

Protocol implementation modules depend on `paygate-api` for:

- The `PaymentProtocol` interface (implemented by `L402Protocol` and `MppProtocol`)
- The `ChallengeContext` and `ChallengeResponse` records (challenge formatting)
- The `PaymentCredential` and `PaymentReceipt` records (credential parsing and receipt creation)
- The `ProtocolMetadata` marker interface (protocol-specific metadata)
- The `PaymentValidationException` and its `ErrorCode` enum (validation error reporting)

The security filter (`PaygateSecurityFilter`) and challenge service (`PaygateChallengeService`) in `paygate-spring-autoconfigure` depend on `paygate-api` to operate on protocol-agnostic types without importing any protocol implementation module directly.

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
