# l402-lightning-lnbits

[LNbits](https://lnbits.com/) REST backend for the `spring-boot-starter-l402` project. This module implements the `LightningBackend` interface from `l402-core` using the LNbits REST API, enabling L402 payment-gated authentication with any LNbits instance.

LNbits is a lightweight, account-based Lightning wallet system with a simple REST API. It is the easiest Lightning backend to get started with -- you can use a hosted instance or self-host one in minutes.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Auto-Configuration](#auto-configuration)
- [Usage](#usage)
- [Error Handling](#error-handling)
- [Testing](#testing)
- [LNbits API Reference](#lnbits-api-reference)

---

## Prerequisites

- **Java 25** (LTS)
- **An LNbits instance** -- self-hosted or a hosted provider
- **An LNbits wallet** with an **Invoice/Read API key** (found under your wallet's API Info section)

### Obtaining an LNbits API Key

1. Log in to your LNbits instance (e.g., `https://lnbits.example.com`)
2. Open the wallet you want to use for receiving L402 payments
3. Click **API Info** in the wallet sidebar
4. Copy the **Invoice/read key** -- this is the key you will configure as `l402.lnbits.api-key`

The Invoice/read key has permission to create invoices and check payment status, which is all this module requires. Do not use the Admin key unless you have a specific reason to do so.

---

## Installation

Add this module alongside the starter. You must include both the starter (which pulls in auto-configuration and core) and this backend module.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0")
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
    <artifactId>l402-lightning-lnbits</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Dependencies

This module depends on:

| Dependency | Purpose |
|------------|---------|
| `l402-core` | `LightningBackend` interface, `Invoice` record, `InvoiceStatus` enum |
| `jackson-databind` (2.18.2) | JSON serialization/deserialization for LNbits API requests and responses |

HTTP communication uses `java.net.http.HttpClient` from the JDK -- no additional HTTP client library is required.

---

## Configuration

Set `l402.backend=lnbits` to select this module, then provide your LNbits instance URL and API key.

### application.yml

```yaml
l402:
  enabled: true
  backend: lnbits
  service-name: my-api
  lnbits:
    url: https://your-lnbits-instance.com
    api-key: ${LNBITS_API_KEY}
```

### application.properties

```properties
l402.enabled=true
l402.backend=lnbits
l402.service-name=my-api
l402.lnbits.url=https://your-lnbits-instance.com
l402.lnbits.api-key=${LNBITS_API_KEY}
```

### Configuration Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `l402.backend` | `string` | -- | Yes | Must be set to `lnbits` to activate this module. |
| `l402.lnbits.url` | `string` | -- | Yes | Base URL of the LNbits instance (e.g., `https://lnbits.example.com`). Trailing slashes are stripped automatically. |
| `l402.lnbits.api-key` | `string` | -- | Yes | Invoice/read API key for authentication. Sent as the `X-Api-Key` header on all requests. |

### Security: Handling the API Key

The API key is a secret credential. Do not commit it to source control. Recommended approaches:

- **Environment variable**: `l402.lnbits.api-key=${LNBITS_API_KEY}` (shown above)
- **Spring Cloud Config / Vault**: Inject via external configuration
- **Docker secrets**: Mount as a file and reference with `spring.config.import`

The `LnbitsConfig` record redacts the API key in its `toString()` output (`apiKey=***REDACTED***`), so it will not leak into logs when the configuration object is printed.

---

## Architecture

The module contains three classes, all in the `com.greenharborlabs.l402.lightning.lnbits` package:

```
l402-lightning-lnbits/
  src/main/java/com/greenharborlabs/l402/lightning/lnbits/
    LnbitsBackend.java       LightningBackend implementation
    LnbitsConfig.java        Immutable configuration record
    LnbitsException.java     Runtime exception for API failures
  src/test/java/com/greenharborlabs/l402/lightning/lnbits/
    LnbitsBackendTest.java   Integration tests using MockWebServer
    LnbitsConfigTest.java    Config validation and redaction tests
```

### LnbitsConfig

A Java `record` holding the `baseUrl` and `apiKey`. The compact constructor validates that neither field is null or blank. The `toString()` method redacts the API key to prevent accidental exposure in logs.

```java
public record LnbitsConfig(String baseUrl, String apiKey) { ... }
```

### LnbitsBackend

Implements the `LightningBackend` interface from `l402-core`. It uses `java.net.http.HttpClient` for HTTP communication and Jackson `ObjectMapper` for JSON processing.

The constructor accepts three dependencies:

| Parameter | Type | Purpose |
|-----------|------|---------|
| `config` | `LnbitsConfig` | LNbits URL and API key |
| `objectMapper` | `ObjectMapper` | Jackson JSON mapper (shared with Spring context) |
| `httpClient` | `HttpClient` | JDK HTTP client instance |

#### Methods

| Method | LNbits API Endpoint | Description |
|--------|-------------------|-------------|
| `createInvoice(long amountSats, String memo)` | `POST /api/v1/payments` | Creates a Lightning invoice. Returns an `Invoice` with status `PENDING`. |
| `lookupInvoice(byte[] paymentHash)` | `GET /api/v1/payments/{hash}` | Checks payment status by payment hash. Returns `SETTLED` or `PENDING` with preimage when available. |
| `isHealthy()` | `GET /api/v1/wallet` | Returns `true` if the LNbits wallet endpoint responds with HTTP 200. |

All API requests include the `X-Api-Key` header and have a **5-second timeout**. The connect timeout on the `HttpClient` is **10 seconds** (configured in auto-configuration).

Invoice expiry is set to **1 hour** from creation time by default.

### LnbitsException

A `RuntimeException` subclass thrown when any LNbits API call fails. This includes:

- Non-2xx HTTP status codes (e.g., 401 Unauthorized, 500 Internal Server Error)
- Missing required fields in the API response
- Network errors, timeouts, or JSON parsing failures
- Thread interruption during HTTP calls

---

## Auto-Configuration

When the following conditions are met, the `L402AutoConfiguration` class in `l402-spring-autoconfigure` automatically creates an `LnbitsBackend` bean:

1. `l402.enabled=true`
2. `l402.backend=lnbits`
3. The class `com.greenharborlabs.l402.lightning.lnbits.LnbitsBackend` is on the classpath
4. No existing `LightningBackend` bean has been registered

The auto-configuration:

- Reads `l402.lnbits.url` and `l402.lnbits.api-key` from `L402Properties`
- Creates an `LnbitsConfig` record from those values
- Uses the Spring-managed `ObjectMapper` from the application context
- Creates a `java.net.http.HttpClient` with a 10-second connect timeout
- Wraps the backend in a `CachingLightningBackendWrapper` (if `l402.health-cache.enabled=true`, which is the default) to cache `isHealthy()` results

### Overriding the Auto-Configured Backend

To customize the `HttpClient`, `ObjectMapper`, or any other aspect, declare your own `LightningBackend` bean. The `@ConditionalOnMissingBean` guard ensures the auto-configured bean is skipped:

```java
@Configuration
public class CustomLnbitsConfiguration {

    @Bean
    public LightningBackend lightningBackend(ObjectMapper objectMapper) {
        var config = new LnbitsConfig(
                "https://lnbits.example.com",
                System.getenv("LNBITS_API_KEY"));

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        return new LnbitsBackend(config, objectMapper, httpClient);
    }
}
```

---

## Usage

Once configured, the module works transparently with the rest of the L402 stack. You do not interact with `LnbitsBackend` directly -- the `L402SecurityFilter` calls it automatically when:

1. A request hits an `@L402Protected` endpoint without valid credentials, triggering `createInvoice()` to generate a payment challenge
2. A request presents L402 credentials, triggering `lookupInvoice()` to verify the payment was made
3. The health indicator or filter checks backend availability via `isHealthy()`

### Minimal Example

```java
@RestController
@RequestMapping("/api/v1")
public class MyController {

    @L402Protected(priceSats = 10)
    @GetMapping("/premium")
    public Map<String, String> premium() {
        return Map.of("data", "premium content");
    }
}
```

```yaml
# application.yml
l402:
  enabled: true
  backend: lnbits
  service-name: my-api
  lnbits:
    url: https://lnbits.example.com
    api-key: ${LNBITS_API_KEY}
```

Requests to `GET /api/v1/premium` without credentials receive HTTP 402 with a Lightning invoice. After paying and presenting the L402 credential, the client receives the premium content.

---

## Error Handling

### Fail-Closed Semantics

This module follows the project-wide **fail-closed** security model:

- If LNbits is unreachable (network error, timeout, DNS failure), `isHealthy()` returns `false`
- When the backend is unhealthy, the `L402SecurityFilter` returns **HTTP 503 Service Unavailable** for protected endpoints
- Protected content is **never** returned with HTTP 200 when the Lightning backend cannot be reached

This ensures that infrastructure failures do not accidentally grant free access to paid content.

### Exception Handling by Method

| Method | Failure Behavior |
|--------|-----------------|
| `createInvoice()` | Throws `LnbitsException` on any error (HTTP error, missing fields, timeout, network failure). The filter translates this to HTTP 503. |
| `lookupInvoice()` | Throws `LnbitsException` on any error. The filter treats this as an invalid credential. |
| `isHealthy()` | Returns `false` on any exception (never throws). Catches all exceptions including `InterruptedException`. |

### HTTP Status Code Handling

All non-2xx responses from LNbits produce an `LnbitsException` with a message including the HTTP status code (e.g., `"LNbits API returned HTTP 401"`). Common scenarios:

| LNbits HTTP Status | Likely Cause |
|-------------------|--------------|
| 401 | Invalid or expired API key |
| 403 | API key lacks required permissions |
| 404 | Payment hash not found (for lookup) |
| 500 | LNbits internal error |
| 502/503 | LNbits upstream Lightning node issue |

### Thread Interruption

If the calling thread is interrupted during an HTTP call, the module:

1. Re-sets the thread's interrupt flag via `Thread.currentThread().interrupt()`
2. Throws `LnbitsException` (for `createInvoice` and `lookupInvoice`) or returns `false` (for `isHealthy`)

### Response Validation

The module validates required fields in LNbits API responses and throws `LnbitsException` with descriptive messages when fields are missing:

- `createInvoice`: Requires `payment_hash` and `payment_request` in the response
- `lookupInvoice`: Requires `paid`, `details`, `details.bolt11`, and `details.amount` in the response

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :l402-lightning-lnbits:test
```

### Test Architecture

Tests use **OkHttp MockWebServer** to simulate the LNbits REST API without requiring a real LNbits instance. This approach:

- Runs entirely offline with no external dependencies
- Verifies exact HTTP method, path, headers, and request body sent to LNbits
- Simulates success responses, error responses, and malformed responses
- Executes fast (no network latency)

### Test Coverage

`LnbitsBackendTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `createInvoice_postsToPaymentsEndpoint` | Correct POST to `/api/v1/payments` with `out=false`, `amount`, `memo`; response mapped to `Invoice` with `PENDING` status |
| `lookupInvoice_getsPaymentByHash` | Correct GET to `/api/v1/payments/{hash}`; unpaid response mapped to `PENDING` |
| `lookupInvoice_settledInvoice_returnsSettledStatus` | Paid response mapped to `SETTLED` with preimage extracted |
| `isHealthy_returnsTrue_when200` | Wallet endpoint 200 produces `true` |
| `isHealthy_returnsFalse_whenNon200` | Wallet endpoint 500 produces `false` |
| `allRequests_includeApiKeyHeader` | Every request type includes the `X-Api-Key` header |
| `createInvoice_throws_when4xxResponse` | HTTP 401 produces `LnbitsException` |
| `createInvoice_throws_when5xxResponse` | HTTP 500 produces `LnbitsException` |
| `lookupInvoice_throws_when4xxResponse` | HTTP 404 produces `LnbitsException` |
| `lookupInvoice_throws_when5xxResponse` | HTTP 502 produces `LnbitsException` |
| `createInvoice_throws_whenPaymentHashMissing` | Missing `payment_hash` in response produces `LnbitsException` |
| `createInvoice_throws_whenPaymentRequestMissing` | Missing `payment_request` in response produces `LnbitsException` |
| `lookupInvoice_throws_whenPaidFieldMissing` | Missing `paid` field produces `LnbitsException` |
| `lookupInvoice_throws_whenDetailsMissing` | Missing `details` object produces `LnbitsException` |
| `lookupInvoice_throws_whenDetailsBolt11Missing` | Missing `details.bolt11` produces `LnbitsException` |
| `lookupInvoice_throws_whenDetailsAmountMissing` | Missing `details.amount` produces `LnbitsException` |
| `implementsLightningBackendInterface` | `LnbitsBackend` is an instance of `LightningBackend` |

`LnbitsConfigTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `toStringShouldRedactApiKey` | API key does not appear in string representation |
| `accessorsShouldReturnOriginalValues` | Record accessors return configured values |
| `equalityShouldUseActualValues` | Equality is based on actual field values |
| `shouldRejectNullBaseUrl` | Null base URL throws `IllegalArgumentException` |
| `shouldRejectBlankApiKey` | Blank API key throws `IllegalArgumentException` |

### Writing Your Own Tests

If you provide a custom `LightningBackend` bean that wraps `LnbitsBackend`, you can use MockWebServer in the same pattern:

```java
@Test
void myCustomTest() throws Exception {
    var server = new MockWebServer();
    server.start();

    server.enqueue(new MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                    {"payment_hash": "abcd...64hex...", "payment_request": "lnbc..."}
                    """));

    var config = new LnbitsConfig(server.url("/").toString(), "test-key");
    var backend = new LnbitsBackend(config, new ObjectMapper(), HttpClient.newHttpClient());

    Invoice invoice = backend.createInvoice(100L, "test");
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);

    server.shutdown();
}
```

---

## LNbits API Reference

This module uses the following LNbits REST API endpoints. For full API documentation, see the [LNbits API docs](https://demo.lnbits.com/docs).

### POST /api/v1/payments -- Create Invoice

**Request:**

```json
{
    "out": false,
    "amount": 100,
    "memo": "Payment for API access"
}
```

**Response (201):**

```json
{
    "payment_hash": "a1b2c3d4...64-hex-chars...",
    "payment_request": "lnbc1000n1p..."
}
```

### GET /api/v1/payments/{payment_hash} -- Check Payment

**Response (200):**

```json
{
    "paid": true,
    "preimage": "ff00ff00...64-hex-chars...",
    "details": {
        "payment_hash": "a1b2c3d4...64-hex-chars...",
        "bolt11": "lnbc1000n1p...",
        "amount": 100,
        "memo": "Payment for API access"
    }
}
```

### GET /api/v1/wallet -- Wallet Info (Health Check)

**Response (200):**

```json
{
    "id": "wallet-id",
    "name": "My Wallet",
    "balance": 50000
}
```

### External Resources

- [LNbits GitHub](https://github.com/lnbits/lnbits)
- [LNbits API Documentation](https://demo.lnbits.com/docs)
- [LNbits Installation Guide](https://github.com/lnbits/lnbits/blob/main/docs/guide/installation.md)
- [L402 Protocol Specification](https://docs.lightning.engineering/the-lightning-network/l402)

---

## Comparison with LND Backend

| Aspect | l402-lightning-lnbits | l402-lightning-lnd |
|--------|----------------------|-------------------|
| Protocol | REST/JSON | gRPC/Protobuf |
| Dependencies | Jackson | gRPC, Protobuf, Netty |
| Setup complexity | Low (API key only) | Higher (TLS cert, macaroon file, gRPC channel) |
| Self-hosted required | No (hosted instances available) | Yes (you run your own LND node) |
| Best for | Getting started, hosted setups, smaller deployments | Production deployments with your own Lightning node |
| Configuration | `l402.lnbits.url` + `l402.lnbits.api-key` | `l402.lnd.host` + `l402.lnd.port` + TLS cert + macaroon |

Both backends implement the same `LightningBackend` interface. Switching between them requires only changing the `l402.backend` property and the corresponding connection configuration -- no application code changes are needed.

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
