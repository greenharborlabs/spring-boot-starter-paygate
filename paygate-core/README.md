# paygate-core

The foundational module of the `spring-boot-starter-paygate` project. This is a **pure Java library with zero external dependencies** -- it relies only on JDK classes (`javax.crypto`, `java.security`, `java.util`, `java.nio`, `java.io`). It implements the Macaroon V2 binary format, L402 protocol primitives, cryptographic operations, and the abstractions that all other modules build upon.

Every module in the project -- `paygate-lightning-lnd`, `paygate-lightning-lnbits`, `paygate-spring-autoconfigure`, `paygate-spring-security` -- depends on `paygate-core`.

---

## Table of Contents

- [Design Principles](#design-principles)
- [Installation](#installation)
- [Architecture](#architecture)
- [Macaroon V2 Binary Format](#macaroon-v2-binary-format)
- [Identifier Layout and Key Derivation](#identifier-layout-and-key-derivation)
- [Caveat System](#caveat-system)
- [Lightning Backend Interface](#lightning-backend-interface)
- [Root Key Store](#root-key-store)
- [Credential Store](#credential-store)
- [L402 Protocol Layer](#l402-protocol-layer)
- [Security Considerations](#security-considerations)
- [Testing](#testing)
- [Module Dependency Graph](#module-dependency-graph)

---

## Design Principles

- **Zero external dependencies.** The compile classpath contains only JDK modules. No Jackson, no Guava, no Apache Commons. This keeps the module lightweight, avoids transitive dependency conflicts, and makes it safe to include in any Java project regardless of its dependency tree.
- **Immutability.** All public-facing types (`Macaroon`, `MacaroonIdentifier`, `Caveat`, `Invoice`, `PaymentPreimage`, `L402Credential`, `L402Challenge`) are immutable. Byte array fields are defensively copied on construction and on access.
- **Fail closed.** When the Lightning backend is unreachable, the system returns HTTP 503 -- never HTTP 200. Protected content is never served without valid credentials.
- **Constant-time secret comparison.** All signature and preimage comparisons use XOR accumulation. `Arrays.equals` is never used for secret data.
- **No secret logging.** `toString()` methods on `Macaroon`, `MacaroonIdentifier`, `L402Credential`, and `PaymentPreimage` redact sensitive fields and expose only structural metadata (lengths, token IDs).

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

If you need to depend on `paygate-core` directly (for example, to implement a custom `LightningBackend` or `RootKeyStore`):

```kotlin
implementation("com.greenharborlabs:paygate-core:0.1.0")
```

### Build Dependencies

| Scope | Dependency | Purpose |
|-------|-----------|---------|
| compile | *none* | Zero external compile dependencies -- JDK only |
| test | JUnit 5 | Test framework |
| test | AssertJ 3.27.3 | Fluent test assertions |
| test | Jackson Databind 2.18.2 | Parsing Go interoperability test vectors (JSON) |

---

## Architecture

The module is organized into four packages:

```
paygate-core/
  src/main/java/com/greenharborlabs/l402/core/
    macaroon/
      Caveat.java                   First-party caveat (key=value record)
      CaveatVerifier.java           Interface for verifying individual caveats
      ServicesCaveatVerifier.java   Verifier for "services" caveats
      ValidUntilCaveatVerifier.java Verifier for time-expiry caveats
      CapabilitiesCaveatVerifier.java Verifier for capabilities caveats
      L402VerificationContext.java  Context object passed to caveat verifiers
      Macaroon.java                 Immutable macaroon representation
      MacaroonIdentifier.java       66-byte binary identifier record
      MacaroonCrypto.java           HMAC-SHA256, key derivation, constant-time comparison
      MacaroonMinter.java           Creates new macaroons with HMAC signature chain
      MacaroonVerifier.java         Verifies macaroon signatures and caveats
      MacaroonSerializer.java       V2 binary serialization/deserialization
      MacaroonVerificationException.java  Thrown on signature or caveat failure
      Varint.java                   Unsigned LEB128 varint encoding/decoding
      RootKeyStore.java             Interface for root key lifecycle management
      InMemoryRootKeyStore.java     ConcurrentHashMap-backed implementation
      FileBasedRootKeyStore.java    File-per-key persistent implementation
    lightning/
      LightningBackend.java         Interface for Lightning Network operations
      Invoice.java                  Immutable Lightning invoice record
      InvoiceStatus.java            Enum: PENDING, SETTLED, CANCELLED, EXPIRED
      PaymentPreimage.java          32-byte preimage with SHA-256 hash verification
    credential/
      CredentialStore.java          Interface for caching validated credentials
      CachedCredential.java         Internal record pairing credential with expiry
      InMemoryCredentialStore.java   ConcurrentHashMap-backed implementation with TTL
    protocol/
      L402Challenge.java            Payment challenge (402 response) with WWW-Authenticate header
      L402Credential.java           Parsed Authorization header (macaroon + preimage)
      L402Validator.java            Orchestrates full credential validation pipeline
      L402Exception.java            Runtime exception with error code and token ID
      ErrorCode.java                Enum mapping error types to HTTP status codes
    util/
      JsonEscaper.java              RFC 8259 JSON string escaper (zero dependencies)
```

### Key Types

| Type | Kind | Description |
|------|------|-------------|
| `Macaroon` | class | Immutable macaroon with 66-byte identifier, optional location, ordered caveat list, and 32-byte HMAC-SHA256 signature. |
| `MacaroonIdentifier` | record | `[version:2 BE][paymentHash:32][tokenId:32]` = 66 bytes. Encode/decode methods for binary conversion. |
| `Caveat` | record | First-party caveat as `key=value`. Serialized to UTF-8 bytes for HMAC chain input. |
| `MacaroonCrypto` | utility class | `deriveKey()`, `hmac()`, `constantTimeEquals()`, `bindForRequest()`. |
| `MacaroonMinter` | utility class | Creates new macaroons by computing the HMAC-SHA256 signature chain. |
| `MacaroonVerifier` | utility class | Recomputes the signature chain and delegates caveat verification to registered `CaveatVerifier` instances. |
| `MacaroonSerializer` | utility class | V2 binary format serialization and deserialization, compatible with Go `go-macaroon`. |
| `Varint` | utility class | Unsigned LEB128 encoding/decoding for V2 format field types and payload lengths. |
| `Invoice` | record | Lightning invoice with payment hash, bolt11, amount, status, optional preimage, and timestamps. |
| `PaymentPreimage` | record | 32-byte preimage with `matchesHash()` (SHA-256 + constant-time comparison) and hex conversion. |
| `L402Challenge` | record | Payment challenge with `toWwwAuthenticateHeader()` and `toJsonBody()` methods. |
| `L402Credential` | record | Parsed L402/LSAT Authorization header with macaroon, preimage, and token ID. |
| `L402Validator` | class | Full validation pipeline: parse header, check cache, verify signature, verify preimage, run caveat verifiers, cache on success. |
| `ErrorCode` | enum | `INVALID_MACAROON(401)`, `INVALID_PREIMAGE(401)`, `EXPIRED_CREDENTIAL(401)`, `INVALID_SERVICE(401)`, `REVOKED_CREDENTIAL(401)`, `LIGHTNING_UNAVAILABLE(503)`, `MALFORMED_HEADER(402)`. |
| `LightningBackend` | interface | Contract for Lightning implementations (`createInvoice`, `lookupInvoice`, `isHealthy`). |
| `RootKeyStore` | interface | Contract for root key generation, retrieval, and revocation. |
| `CredentialStore` | interface | Contract for caching validated credentials with TTL. |

---

## Macaroon V2 Binary Format

The serialization format is byte-level compatible with the Go [`go-macaroon`](https://github.com/go-macaroon/macaroon) library. This ensures interoperability with Lightning Network infrastructure that uses Go implementations.

### Wire Format

```
0x02                          # Version discriminator (always 0x02 for V2)
[location packet]?            # Optional (fieldType=1)
identifier packet             # Required (fieldType=2)
0x00                          # EOS (end of header section)
(                             # Per caveat:
  identifier packet           #   fieldType=2, caveat "key=value" as UTF-8
  0x00                        #   EOS
)*
0x00                          # EOS (end of all caveats)
signature packet              # fieldType=6, 32-byte HMAC-SHA256 signature
```

### Packet Structure

Each packet is encoded as:

```
fieldType(varint) payloadLength(varint) data[payloadLength]
```

| Field Type | Value | Description |
|-----------|-------|-------------|
| EOS | 0 | End of section marker |
| Location | 1 | Optional service URL hint (not signed) |
| Identifier | 2 | Macaroon identifier or caveat identifier |
| Signature | 6 | 32-byte HMAC-SHA256 signature |

Varints use unsigned LEB128 encoding, compatible with protobuf varints and Go `binary.PutUvarint` / `binary.Uvarint`.

### Base64 Encoding

Serialized macaroons are encoded using **standard base64 with padding** (`java.util.Base64.getEncoder()`), not base64url. This matches the Go `go-macaroon` library's encoding.

---

## Identifier Layout and Key Derivation

### Identifier Binary Layout

The macaroon identifier is a fixed 66-byte binary blob:

```
Offset  Length  Field
------  ------  -----
0       2       version    (uint16, big-endian) -- currently always 0
2       32      paymentHash (SHA-256 hash of the payment preimage)
34      32      tokenId     (random 32-byte key ID for root key lookup)
```

Total: 66 bytes.

The `MacaroonIdentifier` record provides `encode()` and `decode()` methods for converting between the structured representation and the 66-byte binary form. The `decode()` method rejects any version other than 0.

### Key Derivation

Root keys are never used directly as HMAC keys. Instead, the signing key is derived following the same algorithm as Go `go-macaroon`:

```
derivedKey = HMAC-SHA256(key="macaroons-key-generator", data=rootKey)
```

This derivation is performed by `MacaroonCrypto.deriveKey()`. The derived key is then used to start the HMAC signature chain.

### Signature Chain

The macaroon signature is computed as a chain of HMAC-SHA256 operations:

```
sig = HMAC-SHA256(derivedKey, identifierBytes)

for each caveat:
    sig = HMAC-SHA256(sig, "key=value".getBytes(UTF-8))
```

The final `sig` is the 32-byte macaroon signature. Verification recomputes this chain and compares the result using constant-time comparison.

---

## Caveat System

Caveats are first-party restrictions embedded in the macaroon. Each caveat is a `key=value` pair that constrains how the macaroon can be used.

### Caveat Record

```java
public record Caveat(String key, String value) {
    // Rejects null, empty, or blank keys and values.
    // toString() returns "key=value" -- used as HMAC chain input.
}
```

### CaveatVerifier Interface

```java
public interface CaveatVerifier {
    String getKey();
    void verify(Caveat caveat, L402VerificationContext context);
}
```

During macaroon verification, `MacaroonVerifier` matches each caveat to a registered `CaveatVerifier` by key. If no verifier is found for a caveat, that caveat is **skipped** -- verification continues without evaluating it. This follows the L402 cross-service delegation model, where a macaroon may carry caveats intended for other services that this service does not understand. Note that this differs from the original macaroons paper, which recommends failing closed on unknown caveats. If your application requires strict unknown-caveat rejection, register a custom `CaveatVerifier` that rejects all unrecognized keys.

### Built-in Caveat Verifiers

| Verifier | Caveat Key | Behavior |
|----------|-----------|----------|
| `ServicesCaveatVerifier` | `services` | Parses a comma-separated list of `name:tier` entries. Verifies that the service name from `L402VerificationContext` appears in the list. Throws `L402Exception(INVALID_SERVICE)` if not found. |
| `ValidUntilCaveatVerifier` | `{serviceName}_valid_until` | Parses the caveat value as a Unix epoch timestamp. Throws `L402Exception(EXPIRED_CREDENTIAL)` if the timestamp is not strictly after the current time from `L402VerificationContext`. |
| `CapabilitiesCaveatVerifier` | `capabilities` | Verifies that the macaroon grants the required capabilities for the request. |

### L402VerificationContext

The context object carries information needed by caveat verifiers:

| Field | Type | Description |
|-------|------|-------------|
| `serviceName` | `String` | The service name for `services` caveat verification (may be null) |
| `currentTime` | `Instant` | The current time for time-based caveat verification |
| `requestMetadata` | `Map<String, String>` | Arbitrary key-value metadata for custom caveat verifiers |

A fluent builder is available via `L402VerificationContext.builder()`.

### Custom Caveat Verifiers

Implement `CaveatVerifier` to add custom restrictions:

```java
public class IpAddressCaveatVerifier implements CaveatVerifier {

    @Override
    public String getKey() {
        return "client_ip";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        String allowedIp = caveat.value();
        String actualIp = context.getRequestMetadata().get("client_ip");
        if (!allowedIp.equals(actualIp)) {
            throw new L402Exception(ErrorCode.INVALID_MACAROON,
                    "Client IP does not match caveat", null);
        }
    }
}
```

---

## Lightning Backend Interface

The `LightningBackend` interface defines the contract that Lightning Network implementations must fulfill. It is implemented by `paygate-lightning-lnd` (gRPC) and `paygate-lightning-lnbits` (REST).

```java
public interface LightningBackend {
    Invoice createInvoice(long amountSats, String memo);
    Invoice lookupInvoice(byte[] paymentHash);
    boolean isHealthy();
}
```

### Method Contract

| Method | Description | Failure Behavior |
|--------|-------------|-----------------|
| `createInvoice(long, String)` | Creates a Lightning invoice for the given amount in satoshis. Returns an `Invoice` with status `PENDING`. | Throws a backend-specific exception. The filter translates this to HTTP 503. |
| `lookupInvoice(byte[])` | Checks payment status by 32-byte payment hash. Returns an `Invoice` with status `SETTLED` or `PENDING`, including the preimage when available. | Throws a backend-specific exception. The filter treats this as an invalid credential. |
| `isHealthy()` | Returns `true` if the Lightning backend is reachable and operational. | Returns `false` on any exception (never throws). |

### Invoice Record

```java
public record Invoice(
    byte[] paymentHash,   // 32 bytes, defensively copied
    String bolt11,        // BOLT11 payment request string
    long amountSats,      // must be > 0
    String memo,          // optional description
    InvoiceStatus status, // PENDING, SETTLED, CANCELLED, EXPIRED
    byte[] preimage,      // 32 bytes when settled, null otherwise
    Instant createdAt,
    Instant expiresAt
) { ... }
```

### PaymentPreimage Record

A 32-byte value whose SHA-256 hash equals the payment hash. Provides:

- `matchesHash(byte[] paymentHash)` -- verifies SHA-256(preimage) == paymentHash using constant-time comparison
- `toHex()` / `fromHex(String)` -- hex string conversion
- `equals()` -- uses `MacaroonCrypto.constantTimeEquals()`, not `Arrays.equals`

---

## Root Key Store

The `RootKeyStore` interface manages the lifecycle of root keys used for macaroon signing and verification.

```java
public interface RootKeyStore {
    record GenerationResult(byte[] rootKey, byte[] tokenId) { ... }

    GenerationResult generateRootKey();
    byte[] getRootKey(byte[] keyId);
    void revokeRootKey(byte[] keyId);
}
```

### InMemoryRootKeyStore

- Backed by `ConcurrentHashMap<String, byte[]>` keyed on hex-encoded token IDs
- Keys are generated using `SecureRandom` (32 bytes each for root key and token ID)
- Thread-safe via `ConcurrentHashMap`
- Keys are lost when the JVM exits
- Suitable for testing and short-lived processes

### FileBasedRootKeyStore

- Each root key is persisted as a hex-encoded file; the filename is the hex-encoded token ID
- Thread safety via `ReadWriteLock` (read lock for `getRootKey`, write lock for `generateRootKey` and `revokeRootKey`)
- Writes are atomic: writes to a `.tmp` file first, then renames with `ATOMIC_MOVE`
- On POSIX systems: directory created with `700` permissions, key files with `600` permissions
- In-memory LRU cache (default max 10,000 entries) backed by `LinkedHashMap` with access-order eviction
- Evicted keys are re-read from disk on next access -- the cache is a performance optimization, not a correctness requirement
- Path traversal defense: `resolveKeyFile()` validates that the resolved path stays within the storage directory

---

## Credential Store

The `CredentialStore` interface caches validated L402 credentials to avoid re-verifying the full macaroon signature chain on every request.

```java
public interface CredentialStore {
    void store(String tokenId, L402Credential credential, long ttlSeconds);
    L402Credential get(String tokenId);
    void revoke(String tokenId);
    long activeCount();
}
```

### InMemoryCredentialStore

- Backed by `ConcurrentHashMap<String, CachedCredential>`
- Configurable maximum size (default 10,000)
- TTL-based expiration with lazy eviction on `get()` and `activeCount()`
- When at capacity: evicts expired entries first, then falls back to random eviction
- Updating an existing entry always succeeds regardless of capacity
- Thread-safe via `ReentrantLock` for store operations and `ConcurrentHashMap` for reads

---

## L402 Protocol Layer

### L402Challenge

Represents the HTTP 402 response sent to clients that have not yet paid. Provides:

- `toWwwAuthenticateHeader()` -- formats `L402 macaroon="<base64>", invoice="<bolt11>"`
- `toJsonBody()` -- builds a JSON response body with `code`, `message`, `price_sats`, `description`, and `invoice` fields using manual string construction (no JSON library dependency)

### L402Credential

Parses the `Authorization` header from clients presenting L402 credentials:

```
L402 <base64-macaroon>:<64-hex-preimage>
LSAT <base64-macaroon>:<64-hex-preimage>   (backward compatible)
```

The `parse()` method:

1. Validates the header matches the `(LSAT|L402) <macaroon>:<preimage>` pattern
2. Decodes the base64 macaroon and deserializes from V2 binary format
3. Parses the 64-character hex preimage
4. Extracts the token ID from the macaroon identifier
5. Returns an `L402Credential` record or throws `L402Exception(MALFORMED_HEADER)`

### L402Validator

Orchestrates the full credential validation pipeline:

1. **Parse** the Authorization header into an `L402Credential`
2. **Check cache** -- if a cached credential exists for this token ID:
   - Verify the root key has not been revoked
   - Verify the presented macaroon signature matches the cached signature (constant-time)
   - Verify the presented preimage matches the cached preimage (constant-time)
   - Re-verify time-based caveats against the current time
   - Return the cached credential with `freshValidation=false`
3. **Look up root key** by token ID from the `RootKeyStore`
4. **Verify macaroon signature** using `MacaroonVerifier` (recomputes HMAC chain, runs all caveat verifiers)
5. **Verify preimage** -- SHA-256(preimage) must equal the payment hash (constant-time comparison)
6. **Cache the credential** with a TTL derived from `valid_until` caveats (minus 30-second safety margin), capped at the default TTL of 3600 seconds
7. Return the credential with `freshValidation=true`

The `ValidationResult` record wraps the credential with a `freshValidation` flag indicating whether it was freshly verified or served from cache.

### ErrorCode

Maps protocol-level error types to HTTP status codes:

| Error Code | HTTP Status | When |
|-----------|-------------|------|
| `INVALID_MACAROON` | 401 | Signature verification failed, tampered macaroon |
| `INVALID_PREIMAGE` | 401 | SHA-256(preimage) does not match payment hash |
| `EXPIRED_CREDENTIAL` | 401 | `valid_until` caveat timestamp is in the past |
| `INVALID_SERVICE` | 401 | Service name not found in `services` caveat |
| `REVOKED_CREDENTIAL` | 401 | Root key has been revoked or not found |
| `LIGHTNING_UNAVAILABLE` | 503 | Lightning backend is unreachable |
| `MALFORMED_HEADER` | 402 | Authorization header does not match L402/LSAT format |

---

## Security Considerations

### Constant-Time Secret Comparison

All comparisons of secret byte arrays use `MacaroonCrypto.constantTimeEquals()`, which accumulates XOR differences without short-circuiting:

```java
public static boolean constantTimeEquals(byte[] a, byte[] b) {
    int result = a.length ^ b.length;
    int len = Math.min(a.length, b.length);
    for (int i = 0; i < len; i++) {
        result |= a[i] ^ b[i];
    }
    return result == 0;
}
```

This prevents timing side-channel attacks. The method is used for:

- Macaroon signature verification
- Preimage comparison in `PaymentPreimage.equals()`
- Cached credential signature matching in `L402Validator`
- `Macaroon.equals()` for both identifier and signature fields

`Arrays.equals` is **never** used for secret data.

### No Secret Logging

- `Macaroon.toString()` returns `Macaroon[identifierLength=66, location=..., caveatCount=N]` -- no signature or identifier bytes
- `MacaroonIdentifier.toString()` returns `MacaroonIdentifier[version=0]` -- no payment hash or token ID
- `L402Credential.toString()` returns `L402Credential[tokenId=...]` -- only the token ID, not the macaroon or preimage
- `Invoice.toString()` returns `Invoice[bolt11=..., amountSats=..., status=...]` -- no payment hash or preimage

### Defensive Copying

All byte array fields in immutable types are defensively copied on construction and on access. This prevents external mutation of internal state:

- `Macaroon`: `identifier` and `signature` are cloned in the constructor and in accessors
- `MacaroonIdentifier`: `paymentHash` and `tokenId` are cloned in the compact constructor and in accessors
- `Invoice`: `paymentHash` and `preimage` are cloned
- `PaymentPreimage`: `value` is cloned
- `RootKeyStore.GenerationResult`: `rootKey` and `tokenId` are cloned

### Fail-Closed Semantics

- If the Lightning backend is unreachable, `isHealthy()` returns `false` and the filter returns HTTP 503
- Protected content is **never** served with HTTP 200 when the backend cannot be reached
- Unknown caveats are skipped during verification to support cross-service delegation (fail-closed applies to Lightning backend connectivity and signature verification, not to unknown caveats)
- Revoked root keys are detected both on fresh validation and on cache hits
- Expired credentials in the cache are re-verified against the current time and evicted if expired

### Path Traversal Protection

`FileBasedRootKeyStore.resolveKeyFile()` validates that the resolved path stays within the storage directory using `Path.startsWith()`. While the public API passes hex-encoded key IDs (which contain only `[0-9a-f]` characters and cannot represent path traversal sequences), this defense-in-depth check guards against future refactors.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-core:test
```

### Test Architecture

Tests use **JUnit 5** with **AssertJ** for fluent assertions. The test suite is organized into focused test classes that verify individual components and their interactions. **Jackson** is used only in tests for parsing Go interoperability test vectors from JSON.

### Test Coverage by Component

#### Macaroon Cryptography (`MacaroonCryptoTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| `deriveKey` returns 32 bytes | Key derivation output size |
| `deriveKey` matches independent HMAC-SHA256 | Correctness against reference implementation |
| `deriveKey` is deterministic | Same root key produces same derived key |
| `hmac` matches reference HMAC-SHA256 | Correctness of HMAC computation |
| `constantTimeEquals` for identical, different, and different-length arrays | No short-circuit on mismatch, length mismatch detection |
| `bindForRequest` is deterministic and non-symmetric | Discharge macaroon binding correctness |

#### Constant-Time Comparison (`ConstantTimeTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Identical arrays return true | Baseline correctness |
| Single-bit flip at every position detected | No short-circuit at any position (0 through 31) |
| Different lengths return false | Length mismatch always rejected |
| All-zero vs all-0xFF rejected | Full byte difference detected |

#### Macaroon Minting (`MacaroonMinterTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Signature matches HMAC chain with no caveats | Base signature computation |
| Signature matches HMAC chain with one caveat | Single-caveat chain |
| Signature matches HMAC chain with multiple caveats in order | Multi-caveat chain ordering |
| Identifier, location, and caveats are correctly set | Field preservation |
| Null arguments rejected | Input validation |

#### Macaroon Verification (`MacaroonVerifierTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Valid macaroon with and without caveats passes | Baseline verification |
| Tampered signature fails | Signature integrity |
| Tampered caveat value fails | Caveat integrity |
| Wrong root key fails | Key binding |
| Caveat verifier rejection propagates | Verifier integration |
| Unknown caveats are skipped without error | Unknown caveat pass-through |

#### V2 Binary Serialization (`MacaroonSerializerTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Serializes minimal, with-location, with-caveats, and full macaroons to exact expected bytes | Byte-level correctness |
| Location packet precedes identifier packet | Field ordering |
| Version byte is always 0x02 | Format compliance |
| Signature is always the last field | Format compliance |
| Round-trip (serialize then deserialize) preserves all fields | Lossless encoding |
| Rejects wrong version byte, empty input, null input, truncated input | Malformed input rejection |
| Rejects oversized length varints in header, caveats, and signature sections | Denial-of-service protection |

#### Go Interoperability (`GoVectorVerificationTest`)

Reads test vectors from `src/test/resources/test-vectors/go-macaroon-vectors.json` and verifies:

| Test Case | What It Verifies |
|-----------|-----------------|
| HMAC signature chain matches expected signature | Cross-language cryptographic compatibility |
| V2 binary serialization matches expected bytes | Cross-language format compatibility |
| Base64 encoding matches expected string | Encoding compatibility |
| Identifier is exactly 66 bytes | Layout compliance |
| Signature is exactly 32 bytes | Size compliance |

#### Tamper Detection (`TamperDetectionTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Every byte position of a no-caveat macaroon is tamper-detected | Exhaustive integrity coverage |
| Every byte position of a macaroon with caveats is tamper-detected | Exhaustive integrity coverage |
| Location tampering does not affect signature (location is unsigned) | Protocol compliance |
| Targeted identifier, signature, and caveat byte tampering rejected | Region-specific coverage |

#### Round-Trip Pipeline (`MacaroonRoundTripTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Mint -> serialize -> deserialize preserves all fields | End-to-end pipeline |
| Mint -> serialize -> deserialize -> verify succeeds | Full pipeline with verification |
| Standard base64 with padding (not base64url) | Encoding standard compliance |
| Equality, hashCode, and toString | Object contract |

#### Varint (`VarintTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Encodes and decodes values from 0 to Long.MAX_VALUE | Full unsigned range |
| Round-trip encode/decode returns original value | Lossless encoding |
| Rejects negative values, null data, out-of-bounds offsets, truncated input | Input validation |

#### Caveat Records and Verifiers (`CaveatTest`, `ServicesCaveatVerifierTest`, `ValidUntilCaveatVerifierTest`, `CapabilitiesCaveatVerifierTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Caveat construction, validation, equality, toString | Record contract |
| ServicesCaveatVerifier accepts matching service, rejects non-matching | Service restriction enforcement |
| ValidUntilCaveatVerifier accepts future timestamps, rejects past and equal timestamps | Time-based expiry enforcement |

#### Identifier (`MacaroonIdentifierTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Construction with valid and invalid inputs | Input validation |
| Encode produces 66 bytes with correct big-endian layout | Binary layout compliance |
| Decode round-trips correctly, rejects wrong sizes and unsupported versions | Deserialization correctness |
| Defensive copies of paymentHash and tokenId | Immutability guarantee |

#### Root Key Stores (`InMemoryRootKeyStoreTest`, `FileBasedRootKeyStoreTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Generate returns 32-byte keys and token IDs | Key generation |
| Retrieve returns same key, null for unknown | Key lookup |
| Revocation removes key, does not affect others | Key lifecycle |
| Defensive copies on retrieval | Immutability |
| Concurrent generate/revoke (virtual threads) | Thread safety |
| File permissions (600 for keys, 700 for directory) on POSIX | Security posture |
| Atomic writes (tmp + rename) | Crash safety |
| Cache eviction falls back to disk reads | Cache correctness |
| Path traversal rejection | Security defense-in-depth |

#### Credential Store (`InMemoryCredentialStoreTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Store, retrieve, revoke | Basic lifecycle |
| TTL expiration with lazy eviction | Time-based cache management |
| Max size enforcement with expired-first eviction | Bounded memory usage |
| Concurrent stores never exceed maxSize (virtual threads) | Thread safety |
| Updating existing entry does not trigger eviction | Update semantics |

#### L402 Protocol (`L402ValidatorTest`, `L402CredentialTest`, `L402ChallengeTest`, `L402ChallengeJsonBodyTest`, `L402ExceptionTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Valid credential passes full validation pipeline | End-to-end happy path |
| Tampered signature returns INVALID_MACAROON | Signature integrity |
| Wrong preimage returns INVALID_PREIMAGE | Preimage verification |
| Cached credential returned on cache hit | Caching behavior |
| Revoked root key detected on cache hit, credential evicted | Revocation handling |
| Expired caveat on cached credential detected and evicted | Time-based re-verification |
| Cache TTL derived from valid_until caveats | Intelligent cache management |
| L402 and LSAT header prefixes both accepted | Backward compatibility |
| Malformed headers (null, empty, wrong scheme, bad preimage length, invalid base64) rejected | Input validation |
| Non-66-byte identifiers rejected | Format enforcement |
| WWW-Authenticate header round-trips through serialize/deserialize | Challenge format |
| JSON body contains required fields and escapes special characters | Response format |

#### Cross-Service and Revocation Integration (`CrossServiceTest`, `RevocationTest`, `PreimageValidationTest`)

| Test Case | What It Verifies |
|-----------|-----------------|
| Macaroon minted for serviceA rejected by serviceB | Cross-service isolation |
| Multi-service caveat accepts listed service, rejects unlisted | Service list semantics |
| Validation succeeds before revocation, fails after | Revocation lifecycle |
| Valid preimage passes, wrong preimage returns INVALID_PREIMAGE | Preimage pipeline |

---

## Module Dependency Graph

```
paygate-core  (this module -- zero external dependencies)
    ^
    |
    +--- paygate-lightning-lnd        (gRPC/Protobuf + paygate-core)
    |
    +--- paygate-lightning-lnbits     (Jackson + paygate-core)
    |
    +--- paygate-spring-autoconfigure (Spring Boot + paygate-core + conditional on lnd/lnbits)
    |        ^
    |        |
    |        +--- paygate-spring-security (Spring Security + paygate-spring-autoconfigure)
    |        |
    |        +--- paygate-spring-boot-starter (dependency aggregator, no source)
    |
    +--- paygate-example-app          (reference implementation)
```

All modules depend on `paygate-core` for:

- The `LightningBackend` interface (implemented by `paygate-lightning-lnd` and `paygate-lightning-lnbits`)
- The `RootKeyStore` and `CredentialStore` interfaces
- Macaroon creation, serialization, and verification
- The L402 protocol types (`L402Challenge`, `L402Credential`, `L402Validator`, `L402Exception`, `ErrorCode`)
- The `Invoice`, `InvoiceStatus`, and `PaymentPreimage` types

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
