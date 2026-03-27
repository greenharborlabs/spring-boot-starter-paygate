# paygate-protocol-mpp

The MPP (Modern Payment Protocol) implementation for the `spring-boot-starter-paygate` project. This is a **pure Java module with zero external dependencies** -- it depends only on `paygate-api` (which is itself JDK-only) and uses only JDK classes (`javax.crypto`, `java.security`, `java.util`, `java.nio`, `java.time`).

**KEY CONSTRAINT:** This module has **NO dependency on `paygate-core`**. It is an entirely independent protocol implementation that shares only the `paygate-api` abstraction layer with the L402 protocol module. This architectural separation ensures that MPP can evolve independently and that applications can include one or both protocol modules without pulling in unnecessary dependencies.

---

## Table of Contents

- [Design Principles](#design-principles)
- [Installation](#installation)
- [Architecture](#architecture)
- [Key Classes](#key-classes)
- [MPP Protocol Flow](#mpp-protocol-flow)
- [Challenge Binding Algorithm](#challenge-binding-algorithm)
- [Credential Format](#credential-format)
- [Encoding](#encoding)
- [Security Considerations](#security-considerations)
- [Testing](#testing)
- [Module Dependency Graph](#module-dependency-graph)
- [License](#license)

---

## Design Principles

- **Zero external dependencies.** The compile classpath contains only `paygate-api` and JDK modules. No Jackson, no Guava, no Apache Commons. JSON parsing and serialization are handled by purpose-built zero-dependency implementations within this module.
- **Independent from paygate-core.** MPP does not use macaroons, HMAC signature chains, or any L402-specific cryptography. It implements its own HMAC-SHA256 challenge binding scheme, its own constant-time comparison, and its own JSON handling.
- **Stateless server-side verification.** Challenge state is encoded entirely in the HMAC-bound challenge ID. The server does not need to store pending challenges in a database or in-memory map -- it recomputes the HMAC on validation and compares.
- **Security-critical validation order.** The `validate()` method checks preimage hash first (before HMAC), then challenge binding, then expiry. This ordering prevents oracle attacks where an attacker could use HMAC verification timing to probe for valid challenge parameters.
- **Constant-time secret comparison.** All HMAC and preimage comparisons use XOR accumulation. `Arrays.equals` is never used for secret data.

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

If you need to depend on `paygate-protocol-mpp` directly:

```kotlin
implementation("com.greenharborlabs:paygate-protocol-mpp:0.1.0")
```

### Build Dependencies

| Scope | Dependency | Purpose |
|-------|-----------|---------|
| compile | `paygate-api` | `PaymentProtocol`, `PaymentCredential`, `ChallengeContext`, `ChallengeResponse`, `PaymentReceipt`, `ProtocolMetadata` interfaces and records |
| compile | *no external libraries* | Zero external compile dependencies -- JDK only |
| test | JUnit 5 | Test framework |
| test | AssertJ 3.27.3 | Fluent test assertions |

---

## Architecture

The module is organized as a single package with eight classes:

```
paygate-protocol-mpp/
  src/main/java/com/greenharborlabs/paygate/protocol/mpp/
    MppProtocol.java            PaymentProtocol implementation ("Payment" scheme)
    MppChallengeBinding.java    HMAC-SHA256 stateless challenge binding
    MppCredentialParser.java    Base64url-nopad JSON credential parser
    MppMetadata.java            Protocol-specific metadata record
    MppReceipt.java             Payment receipt builder
    JcsSerializer.java          RFC 8785 JSON Canonicalization Scheme serializer
    MinimalJsonParser.java      Zero-dependency recursive-descent JSON parser
    LightningChargeRequest.java Lightning-specific charge request data record
  src/test/java/com/greenharborlabs/paygate/protocol/mpp/
    MppProtocolTest.java        Full protocol lifecycle tests
    MppChallengeBindingTest.java HMAC binding round-trip and tamper tests
    MppCredentialParserTest.java Credential parsing and validation tests
    MinimalJsonParserTest.java  JSON parser correctness tests
    JcsSerializerTest.java      JCS serialization tests
```

---

## Key Classes

### MppProtocol

The central class of this module. Implements the `PaymentProtocol` interface from `paygate-api` for the `Payment` authentication scheme.

| Method | Description |
|--------|-------------|
| `scheme()` | Returns `"Payment"` |
| `canHandle(String)` | Returns `true` if the Authorization header starts with `"Payment "` (case-insensitive) |
| `parseCredential(String)` | Strips the `"Payment "` prefix, delegates to `MppCredentialParser.parse()`, and validates that the payment method is `"lightning"` |
| `formatChallenge(ChallengeContext)` | Builds the `WWW-Authenticate: Payment ...` header with HMAC-bound ID, JCS-serialized charge request, RFC 3339 expiry, and optional opaque/description fields |
| `validate(PaymentCredential, Map)` | Executes the security-critical four-step validation: (1) preimage hash check, (2) HMAC binding check, (3) expiry check, (4) method check |
| `createReceipt(PaymentCredential, ChallengeContext)` | Delegates to `MppReceipt.from()` to build a `PaymentReceipt` |

Constructor requires a `byte[] challengeBindingSecret` of at least 32 bytes. The secret is defensively copied.

### MppChallengeBinding

Utility class that computes and verifies HMAC-SHA256 challenge IDs for stateless server-side verification. The challenge ID binds all challenge parameters into a single tamper-proof token.

| Method | Description |
|--------|-------------|
| `createId(realm, method, intent, requestB64, expires, digest, opaqueB64, secret)` | Computes HMAC-SHA256 over the pipe-delimited 7-slot input and returns base64url-nopad encoded result |
| `verify(id, realm, method, intent, requestB64, expires, digest, opaqueB64, secret)` | Recomputes the HMAC and compares against the presented ID using constant-time comparison |

The HMAC input is a pipe-delimited string with 7 slots. Absent optional fields (expires, digest, opaque) use empty string in their slot. A fresh `Mac.getInstance("HmacSHA256")` is obtained per call for virtual-thread safety; JCA provider lookups are cached after the first call, so the overhead is negligible.

### MppCredentialParser

Utility class that parses `Authorization: Payment <base64url-nopad>` credential blobs into `PaymentCredential` records. The parsing pipeline:

1. Base64url decode the blob
2. Parse the resulting JSON using `MinimalJsonParser`
3. Extract the `challenge` object as a `Map<String, String>` of echoed challenge fields
4. Extract `challenge.id` as the token ID
5. Extract `payload.preimage` as a 64-character lowercase hex string
6. Decode the preimage and compute SHA-256 to derive the payment hash
7. Extract optional `source` field
8. Build `MppMetadata` and return a `PaymentCredential`

Rejects: invalid base64url, invalid JSON, missing `challenge` object, missing `challenge.id`, missing `payload.preimage`, invalid preimage hex (wrong length, uppercase, non-hex characters).

### MppMetadata

An immutable `record` implementing `ProtocolMetadata` that carries MPP-specific data alongside the protocol-agnostic `PaymentCredential`:

| Field | Type | Description |
|-------|------|-------------|
| `echoedChallenge` | `Map<String, String>` | The challenge parameters echoed back by the client (defensively copied via `Map.copyOf()`) |
| `source` | `String` | Optional identifier for the credential source (may be `null`) |
| `rawCredentialJson` | `String` | The raw JSON credential string as received from the client |

### MppReceipt

Utility class that builds a `PaymentReceipt` from a validated `PaymentCredential` and the originating `ChallengeContext`. The receipt includes:

- `status`: `"success"`
- `challengeId`: extracted from the echoed challenge `id` field
- `method`: `"lightning"`
- `amountSats`: from the challenge context
- `timestamp`: ISO-8601 instant at receipt creation time
- `protocolScheme`: `"Payment"`

### JcsSerializer

A minimal [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) (JSON Canonicalization Scheme) implementation. Produces deterministic JSON output suitable for cryptographic signing:

- Keys are sorted in Unicode code point order (via `TreeMap`)
- No whitespace between tokens
- Supports `String`, `Integer`, `Long`, `Double`, `Float`, `Boolean`, `null`, nested `Map`, and `List`
- String escaping follows RFC 8259: escapes `"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t`, and control characters below `0x20` as `\uXXXX`
- Forward slashes are **not** escaped (per RFC 8785)
- Integer-valued doubles render without a decimal point (ES6-compatible number serialization)
- Rejects `NaN` and `Infinity`

### MinimalJsonParser

A zero-dependency recursive-descent JSON parser that supports the minimal subset needed for credential parsing:

- Objects (`Map<String, Object>`)
- Strings (with full escape sequence support including `\uXXXX`)
- `null` values
- Nested objects

Arrays, numbers, and booleans are **not** supported and throw `JsonParseException`. This deliberate limitation keeps the parser small and avoids unnecessary attack surface. The `expectEnd()` method rejects trailing content after the root object.

### LightningChargeRequest

An immutable `record` representing the `request` field in an MPP challenge. Contains Lightning-specific payment details:

| Field | Type | Description |
|-------|------|-------------|
| `amount` | `String` | Satoshi amount as a string (e.g., `"100"`) |
| `currency` | `String` | Currency code, typically `"BTC"` |
| `description` | `String` | Human-readable description; nullable (omitted from wire format when null) |
| `methodDetails` | `MethodDetails` | Nested record with `invoice` (BOLT-11), `paymentHash` (hex), and `network` |

The `toJcsMap()` method returns an insertion-ordered map suitable for `JcsSerializer.serialize()`.

---

## MPP Protocol Flow

```
Client                                  Server (MppProtocol)
  |                                       |
  |  GET /api/v1/data                     |
  |  (no Authorization header)            |
  |-------------------------------------->|
  |                                       |
  |                            MppChallengeBinding.bind()
  |                            HMAC-SHA256(secret, pipe-delimited fields)
  |                                       |
  |  402 Payment Required                 |
  |  WWW-Authenticate: Payment            |
  |    id="<hmac>", realm="my-api",       |
  |    method="lightning",                |
  |    intent="charge",                   |
  |    request="<b64url charge req>",     |
  |    expires="<ISO-8601>"               |
  |<--------------------------------------|
  |                                       |
  |  [pays Lightning invoice]             |
  |                                       |
  |  GET /api/v1/data                     |
  |  Authorization: Payment <b64url JSON> |
  |-------------------------------------->|
  |                                       |
  |                            MppCredentialParser.parse()
  |                            Validate: preimage, HMAC, expiry, method
  |                                       |
  |  200 OK                               |
  |  Payment-Receipt: <b64url receipt>    |
  |<--------------------------------------|
```

**Step 1: Challenge issuance.** When a request arrives without credentials at a protected endpoint, `MppProtocol.formatChallenge()` builds a `WWW-Authenticate: Payment` header. The charge request (containing the BOLT-11 invoice, payment hash, amount, and network) is JCS-serialized and base64url-encoded into the `request` parameter. All challenge parameters are HMAC-bound into the `id` field.

**Step 2: Payment.** The client extracts the BOLT-11 invoice from the decoded `request` parameter and pays it through the Lightning Network.

**Step 3: Credential presentation.** The client echoes back the entire challenge object plus the payment preimage in a base64url-encoded JSON blob in the `Authorization: Payment` header.

**Step 4: Validation.** `MppProtocol.validate()` verifies the credential in security-critical order: preimage hash, HMAC binding, expiry, and method. On success, a `Payment-Receipt` header is included in the response.

---

## Challenge Binding Algorithm

The challenge binding ensures that the server can verify a returning credential without storing any per-challenge state. The HMAC covers all challenge parameters, so any tampering by the client is detected.

```
Server creates challenge:
+------------------------------------------------------------------+
| HMAC input (pipe-delimited):                                     |
|   realm | method | intent | request_b64url | expires | digest |  |
|   opaque_b64url                                                  |
+------------------------------------------------------------------+
           |
           v
  HMAC-SHA256(secret, input)
           |
           v
  base64url-nopad encode --> challenge "id"
           |
           v
  WWW-Authenticate: Payment id="<hmac>", realm="...", ...

Client echoes challenge + provides preimage:
+------------------------------------------------------------------+
| Authorization: Payment <base64url-nopad JSON>                    |
|   { "challenge": { "id": "...", "realm": "...", ... },           |
|     "payload": { "preimage": "<64-hex>" } }                     |
+------------------------------------------------------------------+

Server validates (security-critical order):
  1. SHA-256(preimage) == payment_hash       (preimage check)
  2. HMAC-SHA256(secret, fields) == id        (binding check)
  3. Instant.now() < expires                   (expiry check)
  4. method == "lightning"                      (method check)
```

### HMAC Input Format

The 7-slot pipe-delimited input string:

```
realm|method|intent|request_b64url|expires_or_empty|digest_or_empty|opaque_b64url_or_empty
```

- **Required slots**: `realm`, `method`, `intent`, `request_b64url` (must not be null)
- **Optional slots**: `expires`, `digest`, `opaque_b64url` (null maps to empty string `""`)
- The pipe delimiter `|` ensures that field values cannot be shifted across slot boundaries

### Validation Order Rationale

The preimage check runs **before** the HMAC binding check to prevent oracle attacks. If HMAC verification ran first, an attacker could submit varying challenge parameters and observe timing differences to determine whether the HMAC was valid -- effectively using the server as an HMAC oracle. By checking the preimage first (which requires knowledge of the payment preimage, i.e., proof of payment), the HMAC check only executes for clients that have actually paid.

---

## Credential Format

The `Authorization: Payment` header carries a base64url-nopad encoded JSON blob:

```json
{
  "challenge": {
    "id": "<base64url-nopad HMAC>",
    "realm": "my-api",
    "method": "lightning",
    "intent": "charge",
    "request": "<base64url-nopad JCS charge request>",
    "expires": "2026-12-31T23:59:59Z"
  },
  "source": "optional-payer-identity",
  "payload": {
    "preimage": "<64-character-lowercase-hex>"
  }
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `challenge` | Yes | Object echoing back the server's challenge parameters |
| `challenge.id` | Yes | The HMAC-bound challenge ID (used as `tokenId`) |
| `challenge.realm` | Yes | Service name from the challenge |
| `challenge.method` | Yes | Payment method (must be `"lightning"`) |
| `challenge.intent` | Yes | Challenge intent (e.g., `"charge"`) |
| `challenge.request` | Yes | Base64url-nopad encoded JCS charge request |
| `challenge.expires` | No | RFC 3339 expiry timestamp |
| `source` | No | Optional payer identity string (may be null or absent) |
| `payload.preimage` | Yes | 64-character lowercase hex string (32 bytes) |

The preimage must be exactly 64 lowercase hex characters (`[0-9a-f]`). Uppercase hex is rejected.

---

## Encoding

MPP uses **base64url encoding WITHOUT padding** throughout the protocol. This is different from L402, which uses standard base64 with padding.

| Encoding | Characters | Padding | Used By |
|----------|-----------|---------|---------|
| base64url-nopad | `A-Z`, `a-z`, `0-9`, `-`, `_` | No `=` padding | MPP (this module) |
| standard base64 | `A-Z`, `a-z`, `0-9`, `+`, `/` | `=` padding | L402 (paygate-core) |

Java API: `Base64.getUrlEncoder().withoutPadding()` for encoding, `Base64.getUrlDecoder()` for decoding (accepts both padded and unpadded input).

---

## Security Considerations

### Constant-Time Secret Comparison

Both `MppProtocol` and `MppChallengeBinding` implement constant-time byte array comparison using XOR accumulation:

```java
private static boolean constantTimeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) {
        return false;
    }
    int result = 0;
    for (int i = 0; i < a.length; i++) {
        result |= a[i] ^ b[i];
    }
    return result == 0;
}
```

This prevents timing side-channel attacks. The method is used for:

- HMAC challenge ID verification in `MppChallengeBinding.verify()`
- Preimage-to-payment-hash comparison in `MppProtocol.validate()`

`Arrays.equals` is **never** used for secret data.

### Header Injection Sanitization

`MppProtocol.sanitizeHeaderValue()` rejects any string containing control characters (`0x00`-`0x1F`, `0x7F`) or double-quote characters before including it in the `WWW-Authenticate` header. This prevents HTTP header injection attacks where a malicious service name or description could break out of a quoted-string parameter.

### Virtual-Thread-Safe Mac Instantiation

`MppChallengeBinding` calls `Mac.getInstance("HmacSHA256")` fresh on each HMAC computation rather than caching instances in a `ThreadLocal`. This is safe for virtual threads (no pinning or memory-leak risk) and has negligible overhead because the JCA provider lookup is cached internally after the first call. Each fresh `Mac` instance is initialized with `mac.init(new SecretKeySpec(...))` and discarded after use.

### Defensive Secret Copying

The `MppProtocol` constructor defensively clones the `challengeBindingSecret` byte array to prevent external mutation after construction.

### Minimum Secret Length

The challenge binding secret must be at least 32 bytes (256 bits). Shorter secrets are rejected at construction time with `IllegalArgumentException`.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-protocol-mpp:test
```

### Test Architecture

Tests use **JUnit 5** with **AssertJ** for fluent assertions. No external JSON libraries are used in tests -- all JSON construction is done with string templates.

### Test Coverage by Component

#### MppProtocol (`MppProtocolTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Constructor rejects null secret | Input validation |
| Constructor rejects secret shorter than 32 bytes | Minimum key length enforcement |
| Constructor accepts 32-byte and 64-byte secrets | Valid key lengths |
| `scheme()` returns `"Payment"` | Scheme identity |
| `canHandle()` accepts `"Payment "`, `"payment "`, `"PAYMENT "`, mixed case | Case-insensitive prefix matching |
| `canHandle()` rejects `"L402 "`, `"Bearer "`, null, empty, short strings | Non-MPP scheme rejection |
| `formatChallenge()` produces valid WWW-Authenticate header | Challenge header format |
| Challenge expires is RFC 3339 within the timeout window | Expiry correctness |
| Challenge request decodes to valid JCS JSON with sorted keys | JCS serialization integration |
| Challenge ID is valid base64url-nopad and decodes to 32 bytes | HMAC output format |
| Description included when present, omitted when null/empty | Optional field handling |
| Opaque included when present, omitted when null | Optional field handling |
| Body data contains all expected fields | Response body completeness |
| `parseCredential()` parses valid lightning credential | Happy path parsing |
| `parseCredential()` rejects non-lightning method | Method validation |
| `validate()` accepts valid credential | End-to-end happy path |
| `validate()` rejects bad preimage | Preimage integrity |
| `validate()` rejects tampered HMAC ID | Challenge binding integrity |
| `validate()` rejects expired challenge | Temporal validation |
| Security order: preimage checked before HMAC | Oracle attack prevention |
| `createReceipt()` returns receipt with correct fields | Receipt generation |

#### MppChallengeBinding (`MppChallengeBindingTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Round-trip with all 7 slots filled | Full binding correctness |
| Round-trip with null optional slots | Absent field handling |
| Round-trip with only expires null | Partial optional field handling |
| Tampered ID (one byte flipped) rejected | Integrity detection |
| Different realm/method/request/secret rejected | Per-field binding isolation |
| Malformed base64 ID rejected without crash | Graceful error handling |
| Empty ID rejected | Length mismatch detection |
| Null required parameters throw NPE | Input validation |
| Output is valid base64url-nopad | Encoding format |
| Output decodes to exactly 32 bytes | HMAC-SHA256 output size |
| Same inputs produce same output | Determinism |
| Null optional field equals empty string | Null-to-empty normalization |
| Different expires/digest/opaque produce different IDs | Slot isolation |

#### MppCredentialParser (`MppCredentialParserTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Parses valid credential with source | Happy path with all fields |
| Parses valid credential with null source | Null source handling |
| Parses valid credential with absent source | Missing source handling |
| Extracts echoed challenge as map with all fields | Challenge extraction |
| Metadata contains raw JSON and source | Metadata completeness |
| Rejects invalid base64url encoding | Input validation |
| Rejects invalid JSON | Parse error handling |
| Rejects trailing content | Strict JSON parsing |
| Rejects missing challenge object | Required field validation |
| Rejects challenge as string (not object) | Type validation |
| Rejects missing/empty challenge ID | Token ID validation |
| Rejects missing payload/preimage | Required field validation |
| Rejects invalid preimage hex (short, long, uppercase, non-hex, empty) | Preimage format enforcement |
| Handles escaped strings in JSON | Escape sequence support |
| Handles minimal valid credential | Minimum viable input |
| Accepts base64url with and without padding | Decoder flexibility |

#### MinimalJsonParser (`MinimalJsonParserTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Parses empty object | Minimal input |
| Parses single string field | Basic key-value |
| Parses null value | Null literal handling |
| Parses nested objects | Recursive descent |
| Parses escaped strings (newlines, tabs, backslash, quotes, slashes) | Escape sequences |
| Parses unicode escapes (`\uXXXX`) | Unicode support |
| Parses multiple fields | Multi-entry objects |
| Handles whitespace | Whitespace tolerance |
| Rejects unterminated strings | Error handling |
| Rejects trailing commas | Strict syntax |
| Rejects missing colons | Syntax enforcement |
| Rejects unsupported value types (numbers) | Deliberate limitation |
| Rejects trailing content | `expectEnd()` enforcement |
| Rejects empty input | Edge case handling |

#### JcsSerializer (`JcsSerializerTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Sorts keys lexicographically | RFC 8785 key ordering |
| Sorts by Unicode code point order | Correct sort semantics |
| Recursively sorts nested map keys | Deep sorting |
| Handles deeply nested maps | Recursive serialization |
| Serializes mixed types (String, Integer, Long, Double, Boolean, null) | Type dispatch |
| Serializes false and zero correctly | Falsy value handling |
| Preserves list insertion order | Array semantics |
| Serializes empty lists and nested lists | List edge cases |
| Escapes quotes, backslashes, control characters | String escaping |
| Escapes low control characters as `\uXXXX` | RFC 8259 compliance |
| Does NOT escape forward slashes | RFC 8785 compliance |
| Null map returns `"null"` | Null handling |
| Empty map returns `"{}"` | Empty object handling |
| Integer doubles render without decimal point | ES6-compatible numbers |
| Rejects NaN and Infinity | Invalid number handling |
| `LightningChargeRequest.toJcsMap()` produces correct canonical JSON | Integration with charge request records |

---

## Module Dependency Graph

```
paygate-protocol-mpp  (this module -- depends only on paygate-api)
    |
    v
paygate-api           (protocol abstraction -- zero external dependencies, JDK only)

---

paygate-protocol-l402  (L402 protocol -- depends on paygate-api + paygate-core)
    |
    +---> paygate-api
    +---> paygate-core  (macaroons, HMAC chains, root key stores)

---

paygate-spring-autoconfigure
    |
    +---> paygate-protocol-l402
    +---> paygate-protocol-mpp   (this module)
    +---> paygate-spring-security (optional)
```

`paygate-protocol-mpp` and `paygate-protocol-l402` are **sibling modules** that both implement `PaymentProtocol` from `paygate-api`. They share no code and have no dependency on each other. The auto-configuration layer selects which protocols to activate based on the `paygate.protocols.*` configuration properties.

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
