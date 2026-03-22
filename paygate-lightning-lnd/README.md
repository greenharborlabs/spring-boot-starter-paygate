# paygate-lightning-lnd

[LND](https://github.com/lightningnetwork/lnd) gRPC backend for the `spring-boot-starter-paygate` project. This module implements the `LightningBackend` interface from `paygate-core` using LND's gRPC API, enabling L402 payment-gated authentication with any LND node.

LND (Lightning Network Daemon) is the most widely deployed Lightning Network implementation. It exposes a gRPC API for invoice creation, payment verification, and node management. This module communicates with LND over a TLS-encrypted gRPC channel, authenticating with macaroon credentials -- the same authentication model LND uses natively.

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
- [gRPC and Protobuf Details](#grpc-and-protobuf-details)
- [LND API Reference](#lnd-api-reference)
- [Comparison with LNbits Backend](#comparison-with-lnbits-backend)

---

## Prerequisites

- **Java 25** (LTS)
- **A running LND node** (v0.15+ recommended) with gRPC enabled
- **TLS certificate** (`tls.cert`) -- LND generates this on startup (typically at `~/.lnd/tls.cert`)
- **Macaroon file** -- an `invoice.macaroon` or `admin.macaroon` (typically at `~/.lnd/data/chain/bitcoin/mainnet/`)

### Required LND Permissions

This module only needs permissions to:

1. **Create invoices** (`AddInvoice` RPC)
2. **Look up invoices** (`LookupInvoice` RPC)
3. **Query node info** (`GetInfo` RPC -- used for health checks)

The `invoice.macaroon` provides exactly these permissions. Using `admin.macaroon` works but grants more access than necessary -- prefer `invoice.macaroon` for least-privilege operation.

### Locating LND Credentials

Default LND file paths vary by platform:

| Platform | TLS Certificate | Macaroon Directory |
|----------|----------------|--------------------|
| Linux | `~/.lnd/tls.cert` | `~/.lnd/data/chain/bitcoin/mainnet/` |
| macOS | `~/Library/Application Support/Lnd/tls.cert` | `~/Library/Application Support/Lnd/data/chain/bitcoin/mainnet/` |
| Docker | Mounted from container volume | Mounted from container volume |

For remote LND nodes, copy the `tls.cert` and `invoice.macaroon` files to the machine running your Spring Boot application.

---

## Installation

Add this module alongside the starter. You must include both the starter (which pulls in auto-configuration and core) and this backend module.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:paygate-lightning-lnd:0.1.0")
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
    <artifactId>paygate-lightning-lnd</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Dependencies

This module depends on:

| Dependency | Version | Purpose |
|------------|---------|---------|
| `paygate-core` | -- | `LightningBackend` interface, `Invoice` record, `InvoiceStatus` enum |
| `io.grpc:grpc-netty-shaded` | 1.68.1 | gRPC transport with shaded Netty (no Netty version conflicts) |
| `io.grpc:grpc-protobuf` | 1.68.1 | Protobuf message marshalling for gRPC |
| `io.grpc:grpc-stub` | 1.68.1 | Generated gRPC stub classes |
| `com.google.protobuf:protobuf-java` | 4.29.3 | Protobuf runtime |

---

## Configuration

Set `paygate.backend=lnd` to select this module, then provide your LND node's connection details.

### application.yml (Production -- TLS + Macaroon)

```yaml
paygate:
  enabled: true
  backend: lnd
  service-name: my-api
  lnd:
    host: 127.0.0.1
    port: 10009
    tls-cert-path: /path/to/tls.cert
    macaroon-path: /path/to/invoice.macaroon
```

### application.yml (Local Development -- Plaintext)

```yaml
paygate:
  enabled: true
  backend: lnd
  service-name: my-api
  lnd:
    host: localhost
    port: 10009
    allow-plaintext: true
```

### application.properties

```properties
paygate.enabled=true
paygate.backend=lnd
paygate.service-name=my-api
paygate.lnd.host=127.0.0.1
paygate.lnd.port=10009
paygate.lnd.tls-cert-path=/path/to/tls.cert
paygate.lnd.macaroon-path=/path/to/invoice.macaroon
```

### Configuration Properties

| Property | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `paygate.backend` | `string` | -- | Yes | Must be set to `lnd` to activate this module. |
| `paygate.lnd.host` | `string` | `localhost` | No | Hostname or IP address of the LND gRPC endpoint. |
| `paygate.lnd.port` | `int` | `10009` | No | Port number of the LND gRPC endpoint. LND defaults to `10009`. |
| `paygate.lnd.tls-cert-path` | `string` | `null` | Yes* | Absolute path to the LND TLS certificate file (`tls.cert`). Required unless `allow-plaintext=true`. |
| `paygate.lnd.macaroon-path` | `string` | `null` | No | Absolute path to the LND macaroon file (e.g., `invoice.macaroon`). When set, the macaroon is read at startup, hex-encoded, and attached to every gRPC call. |
| `paygate.lnd.allow-plaintext` | `boolean` | `false` | No | Enables plaintext (unencrypted) gRPC connections. Intended only for local development with a localhost LND node. A warning is logged when active. |
| `paygate.lnd.keep-alive-time-seconds` | `int` | `60` | No | Interval between gRPC keepalive pings. |
| `paygate.lnd.keep-alive-timeout-seconds` | `int` | `20` | No | Timeout for keepalive ping acknowledgement. |
| `paygate.lnd.idle-timeout-minutes` | `int` | `5` | No | Idle gRPC connection timeout. |
| `paygate.lnd.max-inbound-message-size` | `int` | `4194304` | No | Maximum inbound gRPC message size in bytes (4 MB). |
| `paygate.lnd.rpc-deadline-seconds` | `Integer` | -- | No | Per-call gRPC deadline. Overrides `paygate.lightning.timeout-seconds`. |

*If `tls-cert-path` is not set and `allow-plaintext` is `false` (the default), the application will fail to start with an `IllegalStateException`.

### Security: Handling Credentials

The TLS certificate and macaroon file are sensitive credentials. Recommended approaches:

- **File paths via environment variables**: `paygate.lnd.tls-cert-path=${LND_TLS_CERT_PATH}`
- **Docker volume mounts**: Mount LND credential files into the container and reference the mount paths
- **Kubernetes secrets**: Mount as files via secret volumes

The macaroon file is read once at startup and held in memory as a hex string. The file path itself may appear in logs, but the macaroon value is never logged.

---

## Architecture

The module contains three classes, all in the `com.greenharborlabs.paygate.lightning.lnd` package:

```
paygate-lightning-lnd/
  src/main/java/com/greenharborlabs/paygate/lightning/lnd/
    LndBackend.java       LightningBackend implementation using gRPC
    LndChannelFactory.java  Factory for building gRPC ManagedChannel instances
    LndConfig.java        Immutable configuration record
    LndException.java     Runtime exception for gRPC failures
    LndTimeoutException.java  Timeout-specific subclass of LndException
    MacaroonClientInterceptor.java  gRPC interceptor for LND macaroon auth
    package-info.java     Package documentation
  src/main/proto/
    lightning.proto        Minimal LND proto definitions (3 RPCs)
  src/test/java/com/greenharborlabs/paygate/lightning/lnd/
    LndBackendTest.java   Unit tests using gRPC in-process transport
```

### LndConfig

A Java `record` holding the gRPC connection parameters: `host`, `port`, `tlsCertPath`, and `macaroonPath`. The `tlsCertPath` and `macaroonPath` fields are nullable -- `null` indicates that TLS or macaroon authentication is not configured (for plaintext/test channels).

```java
public record LndConfig(String host, int port, String tlsCertPath, String macaroonPath) { }
```

### LndBackend

Implements the `LightningBackend` interface from `paygate-core`. The constructor accepts a `ManagedChannel` (a gRPC abstraction), from which it creates a blocking stub for synchronous RPC calls.

```java
public LndBackend(ManagedChannel channel) { ... }
```

This design decouples channel construction (TLS, macaroon, plaintext) from the backend logic. The auto-configuration builds the channel; tests can inject an in-process channel.

#### Methods

| Method | LND gRPC RPC | Description |
|--------|-------------|-------------|
| `createInvoice(long amountSats, String memo)` | `AddInvoice` | Creates a Lightning invoice with the specified amount and memo. Sets a 1-hour expiry. Returns an `Invoice` with status `PENDING`. |
| `lookupInvoice(byte[] paymentHash)` | `LookupInvoice` | Looks up an invoice by its 32-byte payment hash. Maps the LND invoice state (`OPEN`, `SETTLED`, `CANCELED`, `ACCEPTED`) to the `InvoiceStatus` enum. Extracts the preimage when the invoice is settled. |
| `isHealthy()` | `GetInfo` | Calls `GetInfo` and returns `true` only if the node reports `synced_to_chain=true`. Returns `false` on any gRPC error (including `UNAVAILABLE`, `DEADLINE_EXCEEDED`). Never throws. |

All RPC calls use a **5-second deadline** (`withDeadlineAfter(5, TimeUnit.SECONDS)`).

### Invoice State Mapping

LND uses a four-state model. This module maps it to the `InvoiceStatus` enum from `paygate-core`:

| LND `InvoiceState` | `InvoiceStatus` | Meaning |
|-------------------|-----------------|---------|
| `OPEN` | `PENDING` | Invoice created, awaiting payment |
| `ACCEPTED` | `PENDING` | HTLC received, awaiting settlement |
| `SETTLED` | `SETTLED` | Payment completed, preimage revealed |
| `CANCELED` | `CANCELLED` | Invoice explicitly canceled |
| `UNRECOGNIZED` | `PENDING` | Unknown state from a newer LND version; treated conservatively as pending |

### gRPC Channel Management

The gRPC `ManagedChannel` is created by the auto-configuration and managed as a Spring bean with `destroyMethod = "shutdown"`. This ensures the channel is cleanly shut down when the application context closes.

The channel is built in one of two modes:

**TLS mode** (production): Uses `NettyChannelBuilder` with an `SslContext` built from the LND TLS certificate. If a macaroon path is configured, a `MacaroonClientInterceptor` is attached to inject the hex-encoded macaroon into the `macaroon` metadata key on every gRPC call.

**Plaintext mode** (development only): Uses `ManagedChannelBuilder.forAddress().usePlaintext()`. Requires `paygate.lnd.allow-plaintext=true`. A warning is logged at startup.

### MacaroonClientInterceptor

A gRPC `ClientInterceptor` (defined in `paygate-spring-autoconfigure`) that attaches the LND macaroon as gRPC metadata on every outgoing call. The macaroon is read from the file at startup, hex-encoded, and injected into the `macaroon` metadata key -- matching LND's expected authentication format.

---

## Auto-Configuration

When the following conditions are met, the `PaygateAutoConfiguration` class in `paygate-spring-autoconfigure` automatically creates the LND backend beans:

1. `paygate.enabled=true`
2. `paygate.backend=lnd`
3. The class `com.greenharborlabs.paygate.lightning.lnd.LndBackend` is on the classpath
4. No existing `LightningBackend` bean has been registered

The auto-configuration creates two beans:

### `lndManagedChannel` (ManagedChannel)

- Built from `paygate.lnd.*` properties
- TLS + macaroon in production; plaintext when explicitly allowed
- Registered with `destroyMethod = "shutdown"` for clean lifecycle management
- Guarded by `@ConditionalOnMissingBean(ManagedChannel.class)` -- provide your own to customize

### `lightningBackend` (LightningBackend)

- An `LndBackend` instance wrapping the managed channel
- Automatically wrapped in a `CachingLightningBackendWrapper` (when `paygate.health-cache.enabled=true`, which is the default) to cache `isHealthy()` results for the configured TTL (default: 5 seconds)
- Guarded by `@ConditionalOnMissingBean(LightningBackend.class)`

### Overriding the Auto-Configured Backend

To customize the gRPC channel or backend, declare your own beans. The `@ConditionalOnMissingBean` guards ensure the auto-configured beans are skipped:

```java
@Configuration
public class CustomLndConfiguration {

    @Bean
    public ManagedChannel lndManagedChannel() {
        // Custom channel with specific options
        return NettyChannelBuilder
                .forAddress("lnd.example.com", 10009)
                .sslContext(buildCustomSslContext())
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .intercept(new MacaroonClientInterceptor(loadMacaroonHex()))
                .build();
    }

    @Bean
    public LightningBackend lightningBackend(ManagedChannel lndManagedChannel) {
        return new LndBackend(lndManagedChannel);
    }
}
```

You can also override just the channel while letting auto-configuration create the `LndBackend`:

```java
@Bean
public ManagedChannel lndManagedChannel() {
    // Your custom channel; LndBackend will be auto-created using it
    return NettyChannelBuilder.forAddress("lnd.example.com", 10009)
            .sslContext(buildCustomSslContext())
            .build();
}
```

---

## Usage

Once configured, the module works transparently with the rest of the L402 stack. You do not interact with `LndBackend` directly -- the `PaygateSecurityFilter` calls it automatically when:

1. A request hits an `@PaygateProtected` endpoint without valid credentials, triggering `createInvoice()` to generate a payment challenge
2. A request presents L402 credentials, triggering `lookupInvoice()` to verify the payment was made
3. The health indicator or filter checks backend availability via `isHealthy()`

### Minimal Example

```java
@RestController
@RequestMapping("/api/v1")
public class MyController {

    @PaygateProtected(priceSats = 10)
    @GetMapping("/premium")
    public Map<String, String> premium() {
        return Map.of("data", "premium content");
    }
}
```

```yaml
# application.yml
paygate:
  enabled: true
  backend: lnd
  service-name: my-api
  lnd:
    host: 127.0.0.1
    port: 10009
    tls-cert-path: /home/user/.lnd/tls.cert
    macaroon-path: /home/user/.lnd/data/chain/bitcoin/mainnet/invoice.macaroon
```

Requests to `GET /api/v1/premium` without credentials receive HTTP 402 with a Lightning invoice. After paying and presenting the L402 credential, the client receives the premium content.

### Docker Compose Example

When running alongside an LND node in Docker Compose, mount the credential files:

```yaml
services:
  app:
    image: my-paygate-app:latest
    environment:
      L402_ENABLED: "true"
      L402_BACKEND: lnd
      L402_LND_HOST: lnd
      L402_LND_PORT: "10009"
      L402_LND_TLS_CERT_PATH: /lnd/tls.cert
      L402_LND_MACAROON_PATH: /lnd/invoice.macaroon
    volumes:
      - lnd-data:/lnd:ro

  lnd:
    image: lightninglabs/lnd:v0.18.0-beta
    volumes:
      - lnd-data:/root/.lnd

volumes:
  lnd-data:
```

---

## Error Handling

### Fail-Closed Semantics

This module follows the project-wide **fail-closed** security model:

- If LND is unreachable (network error, gRPC `UNAVAILABLE`, deadline exceeded), `isHealthy()` returns `false`
- When the backend is unhealthy, the `PaygateSecurityFilter` returns **HTTP 503 Service Unavailable** for protected endpoints
- Protected content is **never** returned with HTTP 200 when the Lightning backend cannot be reached

This ensures that infrastructure failures do not accidentally grant free access to paid content.

### Exception Handling by Method

| Method | Failure Behavior |
|--------|-----------------|
| `createInvoice()` | Throws `StatusRuntimeException` on any gRPC error (deadline exceeded, unavailable, permission denied). The filter translates this to HTTP 503. |
| `lookupInvoice()` | Throws `StatusRuntimeException` on any gRPC error (including `NOT_FOUND` for unknown payment hashes). The filter treats this as an invalid credential. |
| `isHealthy()` | Returns `false` on any `StatusRuntimeException` (never throws). Also returns `false` when LND reports `synced_to_chain=false`, indicating the node is still syncing. |

### Common gRPC Error Scenarios

| gRPC Status | Likely Cause | Effect |
|-------------|--------------|--------|
| `UNAVAILABLE` | LND node is down, network unreachable, or TLS handshake failed | `isHealthy()` returns `false`; other methods throw |
| `DEADLINE_EXCEEDED` | RPC took longer than the 5-second deadline | Same as `UNAVAILABLE` |
| `UNAUTHENTICATED` | Macaroon is missing, invalid, or expired | All methods fail |
| `PERMISSION_DENIED` | Macaroon lacks required permissions (e.g., using a read-only macaroon) | `createInvoice()` fails; `lookupInvoice()` and `getInfo` may succeed |
| `NOT_FOUND` | Payment hash does not match any invoice on the LND node | `lookupInvoice()` throws |
| `INTERNAL` | LND internal error | All methods fail |

### Health Check: synced_to_chain

The `isHealthy()` method returns `true` only when LND reports `synced_to_chain=true` in its `GetInfo` response. A node that is still performing initial block download (IBD) or catching up after being offline will report `synced_to_chain=false`, causing `isHealthy()` to return `false`. This prevents the system from creating invoices on a node that may not be able to settle them.

---

## Testing

### Running the Tests

From the project root:

```bash
./gradlew :paygate-lightning-lnd:test
```

### Test Architecture

Tests use **gRPC in-process transport** (`grpc-testing` and `grpc-inprocess`) to simulate LND's gRPC service without requiring a real LND node or network connections. A `FakeLightningService` extends the generated `LightningGrpc.LightningImplBase` and returns configurable responses.

This approach:

- Runs entirely in-process with no network I/O
- Exercises the full gRPC serialization/deserialization path (protobuf encoding, stub invocation)
- Allows precise control over responses and error conditions
- Executes fast (sub-millisecond RPC round-trips)

### Test Coverage

`LndBackendTest` covers:

| Test Case | What It Verifies |
|-----------|-----------------|
| `createInvoice_callsAddInvoice` | Correct `AddInvoice` RPC with `value`, `memo`, and `expiry=3600`; response mapped to `Invoice` with `PENDING` status; `createdAt` and `expiresAt` timestamps are reasonable |
| `lookupInvoice_callsLookupInvoice` | Correct `LookupInvoice` RPC with payment hash; `OPEN` state mapped to `PENDING` status |
| `lookupInvoice_settledInvoice_returnsSettledStatus` | `SETTLED` state mapped to `SETTLED` status with preimage extracted |
| `isHealthy_returnsTrue_whenSynced` | `GetInfo` with `synced_to_chain=true` produces `true` |
| `isHealthy_returnsFalse_whenNotSynced` | `GetInfo` with `synced_to_chain=false` produces `false` |
| `isHealthy_returnsFalse_whenRpcFails` | `UNAVAILABLE` gRPC error produces `false` (no exception thrown) |
| `implementsLightningBackendInterface` | `LndBackend` is an instance of `LightningBackend` (compile-time and runtime check) |

### Writing Your Own Tests

If you provide a custom `LightningBackend` bean that wraps `LndBackend`, you can use the in-process gRPC transport in the same pattern:

```java
@Test
void myCustomTest() throws Exception {
    var fakeService = new LightningGrpc.LightningImplBase() {
        @Override
        public void addInvoice(Lnrpc.Invoice request,
                               StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
            responseObserver.onNext(Lnrpc.AddInvoiceResponse.newBuilder()
                    .setRHash(ByteString.copyFrom(new byte[32]))
                    .setPaymentRequest("lnbc100n1ptest")
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void lookupInvoice(Lnrpc.PaymentHash request,
                                  StreamObserver<Lnrpc.Invoice> responseObserver) {
            responseObserver.onNext(Lnrpc.Invoice.newBuilder()
                    .setRHash(request.getRHash())
                    .setPaymentRequest("lnbc100n1ptest")
                    .setValue(100L)
                    .setState(Lnrpc.Invoice.InvoiceState.SETTLED)
                    .setRPreimage(ByteString.copyFrom(new byte[32]))
                    .setCreationDate(Instant.now().getEpochSecond())
                    .setExpiry(3600L)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void getInfo(Lnrpc.GetInfoRequest request,
                            StreamObserver<Lnrpc.GetInfoResponse> responseObserver) {
            responseObserver.onNext(Lnrpc.GetInfoResponse.newBuilder()
                    .setSyncedToChain(true)
                    .build());
            responseObserver.onCompleted();
        }
    };

    String serverName = InProcessServerBuilder.generateName();
    Server server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start();

    ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();

    LndBackend backend = new LndBackend(channel);

    Invoice invoice = backend.createInvoice(100L, "test");
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);

    channel.shutdownNow();
    server.shutdownNow();
}
```

### Test Dependencies

The following test-scoped dependencies are used:

| Dependency | Purpose |
|------------|---------|
| `io.grpc:grpc-testing` | gRPC test utilities and helpers |
| `io.grpc:grpc-inprocess` | In-process gRPC transport (no network, no TLS) |
| `org.junit.jupiter:junit-jupiter` | JUnit 5 test framework |
| `org.assertj:assertj-core` | Fluent assertion library |

---

## gRPC and Protobuf Details

### Proto Definition

This module uses a **minimal proto file** (`src/main/proto/lightning.proto`) derived from LND's full `lnrpc/lightning.proto`. It includes only the three RPCs and message types needed by `LndBackend`:

```protobuf
service Lightning {
    rpc AddInvoice (Invoice) returns (AddInvoiceResponse);
    rpc LookupInvoice (PaymentHash) returns (Invoice);
    rpc GetInfo (GetInfoRequest) returns (GetInfoResponse);
}
```

This minimal approach avoids pulling in LND's full proto dependency tree (which includes dozens of RPCs and hundreds of message types), keeping the module lightweight and compilation fast.

The proto file uses `package lnrpc` and `option java_package = "lnrpc"` to match LND's native package naming. The generated Java classes are in the `lnrpc` package (outer class `Lnrpc`), and the generated gRPC stubs are in `LightningGrpc`.

### Protobuf Code Generation

The `com.google.protobuf` Gradle plugin (v0.9.4) generates Java classes from the proto file at build time. The `protoc-gen-grpc-java` plugin generates the gRPC stubs. Both are configured in `build.gradle.kts`:

- `protoc` compiler: `com.google.protobuf:protoc:4.29.3`
- gRPC Java plugin: `io.grpc:protoc-gen-grpc-java:1.68.1`

Generated sources are placed in `build/generated/source/proto/` and are automatically included in the source set. The generated `lnrpc/**` classes are excluded from JaCoCo code coverage reporting.

### Message Types

| Proto Message | Key Fields | Used By |
|---------------|-----------|---------|
| `Invoice` (request) | `value` (sats), `memo`, `expiry` (seconds) | `createInvoice()` input |
| `AddInvoiceResponse` | `r_hash` (payment hash), `payment_request` (BOLT-11) | `createInvoice()` output |
| `PaymentHash` | `r_hash` (32-byte payment hash) | `lookupInvoice()` input |
| `Invoice` (response) | `r_hash`, `payment_request`, `value`, `memo`, `state`, `r_preimage`, `creation_date`, `expiry` | `lookupInvoice()` output |
| `GetInfoRequest` | (empty) | `isHealthy()` input |
| `GetInfoResponse` | `synced_to_chain`, `synced_to_graph`, `version` | `isHealthy()` output |

---

## LND API Reference

This module uses the following LND gRPC RPCs. For full API documentation, see the [LND API Reference](https://api.lightning.community/).

### AddInvoice -- Create Invoice

Creates a new Lightning invoice on the LND node.

**Request (`lnrpc.Invoice`):**

| Field | Type | Value |
|-------|------|-------|
| `value` | `int64` | Amount in satoshis |
| `memo` | `string` | Human-readable description |
| `expiry` | `int64` | Invoice expiry in seconds (set to `3600`) |

**Response (`lnrpc.AddInvoiceResponse`):**

| Field | Type | Description |
|-------|------|-------------|
| `r_hash` | `bytes` | 32-byte payment hash |
| `payment_request` | `string` | BOLT-11 encoded payment request |
| `payment_addr` | `bytes` | 32-byte payment address |

### LookupInvoice -- Check Payment

Looks up an existing invoice by its payment hash.

**Request (`lnrpc.PaymentHash`):**

| Field | Type | Description |
|-------|------|-------------|
| `r_hash` | `bytes` | 32-byte payment hash |

**Response (`lnrpc.Invoice`):**

| Field | Type | Description |
|-------|------|-------------|
| `r_hash` | `bytes` | Payment hash |
| `payment_request` | `string` | BOLT-11 encoded payment request |
| `value` | `int64` | Invoice amount in satoshis |
| `memo` | `string` | Invoice description |
| `state` | `InvoiceState` | `OPEN`, `SETTLED`, `CANCELED`, or `ACCEPTED` |
| `r_preimage` | `bytes` | 32-byte preimage (populated when settled) |
| `creation_date` | `int64` | Unix timestamp of invoice creation |
| `expiry` | `int64` | Expiry duration in seconds |

### GetInfo -- Node Health Check

Returns general information about the LND node.

**Request:** Empty (`lnrpc.GetInfoRequest`)

**Response (`lnrpc.GetInfoResponse`):**

| Field | Type | Description |
|-------|------|-------------|
| `synced_to_chain` | `bool` | Whether the node is fully synced to the blockchain |
| `synced_to_graph` | `bool` | Whether the node is fully synced to the channel graph |
| `version` | `string` | LND version string |

### External Resources

- [LND GitHub Repository](https://github.com/lightningnetwork/lnd)
- [LND API Documentation](https://api.lightning.community/)
- [LND gRPC API Reference (lightning.proto)](https://api.lightning.community/#lnd-grpc-api-reference)
- [LND Installation Guide](https://docs.lightning.engineering/lightning-network-tools/lnd/run-lnd)
- [LND Macaroons Guide](https://docs.lightning.engineering/the-lightning-network/lnd/macaroons)
- [L402 Protocol Specification](https://docs.lightning.engineering/the-lightning-network/l402)

---

## Comparison with LNbits Backend

| Aspect | paygate-lightning-lnd | paygate-lightning-lnbits |
|--------|-------------------|----------------------|
| Protocol | gRPC/Protobuf | REST/JSON |
| Dependencies | gRPC, Protobuf, Netty (shaded) | Jackson |
| Authentication | TLS certificate + macaroon file | API key (HTTP header) |
| Setup complexity | Higher (LND node, TLS cert, macaroon file) | Low (API key only) |
| Self-hosted required | Yes (you run your own LND node) | No (hosted instances available) |
| Health check | `GetInfo` RPC -- checks `synced_to_chain` | `GET /api/v1/wallet` -- checks HTTP 200 |
| RPC deadline / timeout | 5-second gRPC deadline per call | 5-second HTTP timeout per request |
| Connection management | gRPC `ManagedChannel` (persistent, multiplexed) | `HttpClient` (connection pooling by JDK) |
| Best for | Production deployments with your own Lightning node | Getting started, hosted setups, smaller deployments |
| Configuration | `paygate.lnd.host` + `paygate.lnd.port` + TLS cert + macaroon | `paygate.lnbits.url` + `paygate.lnbits.api-key` |

Both backends implement the same `LightningBackend` interface. Switching between them requires only changing the `paygate.backend` property and the corresponding connection configuration -- no application code changes are needed.

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
