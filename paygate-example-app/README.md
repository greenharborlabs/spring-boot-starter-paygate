# paygate-example-app

A reference Spring Boot application demonstrating how to use `spring-boot-starter-paygate` to protect API endpoints with Lightning payments using dual-protocol support (L402 + MPP).

This application runs in **test mode** by default, so no real Lightning node is required. You can exercise the complete payment flow -- challenge, payment, and credential presentation -- entirely on your local machine.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [How L402 Works](#how-l402-works)
- [How Test Mode Works](#how-test-mode-works)
- [Running the Application](#running-the-application)
  - [With Gradle](#with-gradle)
  - [With Docker](#with-docker)
- [Endpoints](#endpoints)
- [Trying the L402 Flow](#trying-the-l402-flow)
  - [Step 1: Hit the unprotected health endpoint](#step-1-hit-the-unprotected-health-endpoint)
  - [Step 2: Request a protected endpoint without credentials](#step-2-request-a-protected-endpoint-without-credentials)
  - [Step 3: Complete the flow with the test preimage](#step-3-complete-the-flow-with-the-test-preimage)
  - [Step 4: Dynamic pricing](#step-4-dynamic-pricing)
  - [Step 5: Compare with a real deployment](#step-5-compare-with-a-real-deployment)
- [What the Example Demonstrates](#what-the-example-demonstrates)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Running the Tests](#running-the-tests)
- [Docker Setup Details](#docker-setup-details)
- [Connecting a Real Lightning Backend](#connecting-a-real-lightning-backend)

---

## Prerequisites

- **Java 25** (LTS)
- **curl** (or any HTTP client) for testing the endpoints
- **Docker** and **Docker Compose** (optional, for containerized runs)

No Lightning node or wallet is required. The application ships with test mode enabled.

---

## How L402 Works

L402 is an HTTP-native payment protocol. When a client requests a protected resource without paying:

1. The server responds with **HTTP 402 Payment Required** and a `WWW-Authenticate` header containing a **macaroon** (an authorization token) and a Lightning **invoice**.
2. The client pays the invoice via the Lightning Network and receives a **preimage** (proof of payment).
3. The client re-requests the resource, presenting the macaroon and preimage together in the `Authorization` header.
4. The server verifies the macaroon signature and checks that `SHA-256(preimage) == paymentHash` embedded in the macaroon. If both check out, access is granted.

```
Client                              Server
  |                                    |
  |  GET /api/v1/data                  |
  |----------------------------------->|
  |                                    |
  |  402 Payment Required              |
  |  WWW-Authenticate: L402            |
  |    macaroon="...", invoice="..."    |
  |<-----------------------------------|
  |                                    |
  |  (pay invoice, get preimage)       |
  |                                    |
  |  GET /api/v1/data                  |
  |  Authorization: L402 <mac>:<pre>   |
  |----------------------------------->|
  |                                    |
  |  200 OK                            |
  |  {"data": "premium content"}       |
  |<-----------------------------------|
```

---

## How Test Mode Works

When `paygate.test-mode=true`, the starter replaces the real Lightning backend (LND, LNbits, etc.) with a `TestModeLightningBackend`. This backend:

- **Creates invoices with random payment hashes.** When the server issues a 402 challenge, the invoice contains a randomly generated 32-byte payment hash and a fake bolt11 string (e.g., `lntb10test7a3b...`). No real Lightning node is contacted.
- **Reports all invoices as settled.** When the validator checks whether an invoice has been paid, the test backend always answers "yes, it's settled" and returns a random preimage.
- **Always reports healthy.** The health check always passes, so the server never returns 503.

To enable end-to-end testing with curl, the test backend includes the preimage in the 402 JSON response as a `test_preimage` field. In a real deployment this field is never present -- the preimage is only obtained by paying the Lightning invoice. See [Step 3](#step-3-complete-the-flow-with-the-test-preimage) for a walkthrough.

### Safety guard

`TestModeLightningBackend` refuses to start if any active Spring profile is `production` or `prod`, throwing an `IllegalStateException` to prevent accidental use in production.

---

## Running the Application

### With Gradle

From the **project root** (not from inside `paygate-example-app/`):

```bash
./gradlew :paygate-example-app:bootRun
```

The `gradlew` wrapper lives in the project root. The `:paygate-example-app:` prefix tells Gradle which submodule to run.

The application starts on `http://localhost:8080`.

### With Docker

From the **project root**:

```bash
docker compose up --build
```

This builds the example app using a multi-stage Dockerfile (JDK 25 build, JRE 25 runtime) and starts it on port 8080 with test mode enabled and an in-memory root key store.

To run in detached mode:

```bash
docker compose up --build -d
```

To view logs while running detached:

```bash
docker compose logs -f paygate-example-app
```

To stop:

```bash
docker compose down
```

See [Docker Setup Details](#docker-setup-details) for more information on the Docker configuration.

---

## Endpoints

| Method | Path              | Protected | Price    | Annotation | Description                              |
|--------|-------------------|-----------|----------|------------|------------------------------------------|
| GET    | `/api/v1/health`  | No        | --       | --         | Health check. Always accessible.         |
| GET    | `/api/v1/quote`   | Yes       | 5 sats   | `@PaymentRequired` | Premium quote of the day.                |
| GET    | `/api/v1/data`    | Yes       | 10 sats  | `@PaymentRequired` | Returns premium content. Fixed price.    |
| POST   | `/api/v1/analyze` | Yes       | 50+ sats | `@PaymentRequired` | Content analysis. Dynamic pricing.       |

Endpoints are protected by annotating the controller method with `@PaymentRequired`:

```java
@PaymentRequired(priceSats = 10, timeoutSeconds = 3600)
@GetMapping("/data")
public DataResponse data() { ... }

@PaymentRequired(priceSats = 5, description = "Premium quote of the day")
@GetMapping("/quote")
public QuoteResponse quote() { ... }
```

The `PaygateSecurityFilter` automatically discovers all `@PaymentRequired` methods at startup and enforces payment for matching requests.

### Dynamic Pricing

The `/api/v1/analyze` endpoint uses `AnalysisPricingStrategy`, a custom implementation of the `PaygatePricingStrategy` interface that scales the invoice price based on request content length:

- Requests up to 1,000 bytes pay the base price (50 sats)
- Larger payloads add 1 sat per 100 bytes of content beyond the threshold

The pricing strategy is wired to the endpoint via the `pricingStrategy` attribute:

```java
@PaymentRequired(priceSats = 50, timeoutSeconds = 3600, pricingStrategy = "analysisPricer")
@PostMapping("/analyze")
public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) { ... }
```

This demonstrates how to implement pay-per-use pricing that scales with resource consumption.

---

## Trying the L402 Flow

### Step 1: Hit the unprotected health endpoint

This confirms the application is running. No authentication is needed.

```bash
curl http://localhost:8080/api/v1/health
```

Expected response (HTTP 200):

```json
{"status":"ok"}
```

### Step 2: Request a protected endpoint without credentials

This triggers the L402 challenge. The server creates a Lightning invoice and a macaroon, then returns them in the `WWW-Authenticate` header.

```bash
curl -i http://localhost:8080/api/v1/data
```

Expected response (HTTP 402):

```
HTTP/1.1 402
WWW-Authenticate: L402 macaroon="AgJ...base64...", invoice="lntb10test7a3b..."
Content-Type: application/json

{"code": 402, "message": "Payment required", "price_sats": 10, "description": "", "invoice": "lntb10test7a3b...", "test_preimage": "a1b2c3...64-hex-chars..."}
```

Notice the `test_preimage` field -- this only appears in test mode. In a real deployment, you would obtain the preimage by paying the Lightning invoice with a wallet.

What happened behind the scenes:

1. The filter matched `GET /api/v1/data` against the endpoint registry and found the `@PaymentRequired(priceSats = 10)` configuration.
2. It checked the Lightning backend health (test backend always returns healthy).
3. No `Authorization` header was present, so it generated a new root key and token ID.
4. It called `TestModeLightningBackend.createInvoice(10, "")`, which generated a random 32-byte preimage, computed `paymentHash = SHA-256(preimage)`, and returned both.
5. It minted a macaroon containing the payment hash and token ID in its identifier, signed with the root key, and added caveats: `services=example-api:0` and `example-api_valid_until=<expiry-epoch>`.
6. It returned 402 with the base64-encoded macaroon, the bolt11 invoice string, and the test preimage.

### Step 3: Complete the flow with the test preimage

Extract the `macaroon` value from the `WWW-Authenticate` header and the `test_preimage` from the JSON body, then present them together in the `Authorization` header.

The `Authorization` header format is:

```
L402 <base64-encoded-macaroon>:<preimage-as-64-hex-chars>
```

Here is a script that automates the full flow:

```bash
# Save the 402 response to parse the macaroon and preimage
RESPONSE=$(curl -s -i http://localhost:8080/api/v1/data)

# Extract the macaroon from the WWW-Authenticate header (between macaroon=" and ")
MACAROON=$(echo "$RESPONSE" | grep -o 'macaroon="[^"]*"' | sed 's/macaroon="//;s/"//')

# Extract the test_preimage from the JSON body
PREIMAGE=$(echo "$RESPONSE" | grep -o '"test_preimage": "[^"]*"' | sed 's/"test_preimage": "//;s/"//')

# Present the credential to access the protected resource
curl -i -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
     http://localhost:8080/api/v1/data
```

Or if you already have the values from a previous 402 response, you can pass them directly:

```bash
curl -i -H "Authorization: L402 AgJCAABb976nP3z4iWZflFsXM/tDqgv9UKj0hrMC...:a05d63f6f7993a83e464229215f408a6b79e957679c2bafd67f3d6aa59237467" \
     http://localhost:8080/api/v1/data
```

Expected response (HTTP 200):

```
HTTP/1.1 200
X-L402-Credential-Expires: 2026-03-14T02:46:38.298632Z
Content-Type: application/json

{"data":"premium content","timestamp":"2026-03-14T01:46:38.299234Z"}
```

What the server verified:

1. **Macaroon signature** -- The HMAC-SHA256 chain is intact, proving the macaroon was minted by this server and has not been tampered with.
2. **Caveats** -- The `services` caveat matches `example-api` and the `valid_until` timestamp has not expired.
3. **Preimage** -- `SHA-256(preimage) == paymentHash` embedded in the macaroon identifier, proving the invoice was paid.

The response headers tell you:

- `X-L402-Credential-Expires` -- When the credential expires and a new payment will be needed.

Subsequent requests with the same valid credential are served from cache without re-running the full verification.

### Step 4: Dynamic pricing

The `/api/v1/analyze` endpoint uses `AnalysisPricingStrategy`, which charges the base price (50 sats) for request bodies up to 1 KB and adds 1 sat per 100 bytes beyond that.

```bash
curl -i -X POST \
     -H "Content-Type: application/json" \
     -d '{"content": "analyze this text"}' \
     http://localhost:8080/api/v1/analyze
```

Expected response (HTTP 402):

```json
{"code": 402, "message": "Payment required", "price_sats": 50, "description": "", "invoice": "lntb50test...", "test_preimage": "..."}
```

The price is 50 sats because the request body is under 1 KB. A larger payload would increase the price. You can complete this flow the same way as Step 3 -- extract the macaroon and preimage, then present them.

### Step 5: Compare with a real deployment

| Step | Test Mode | Real Deployment |
|------|-----------|-----------------|
| Get challenge | `curl -i /api/v1/data` | Same |
| Obtain preimage | Read `test_preimage` from 402 response | Pay the `invoice` with a Lightning wallet (e.g., Zeus, Phoenix, `lncli payinvoice`) |
| Present credential | `Authorization: L402 <mac>:<preimage>` | Same |
| Validation | Full cryptographic verification | Same |

The only difference is *how you get the preimage*. Everything else -- macaroon minting, signature verification, caveat checking, SHA-256 preimage matching, credential caching -- is identical.

---

## What the Example Demonstrates

This application is a minimal but complete reference implementation covering the main features of the L402 Spring Boot Starter:

| Feature | Where |
|---------|-------|
| Fixed-price endpoint protection | `ExampleController.data()` with `@PaymentRequired(priceSats = 10)` |
| Payment-gated quote endpoint | `ExampleController.quote()` with `@PaymentRequired(priceSats = 5)` |
| Dynamic pricing | `ExampleController.analyze()` with `pricingStrategy = "analysisPricer"` |
| Custom pricing strategy | `AnalysisPricingStrategy` implementing `PaygatePricingStrategy` |
| Unprotected endpoints alongside protected ones | `ExampleController.health()` with no annotation |
| Dual-protocol support (L402 + MPP) | `application.yml` / `application-dev.yml` with MPP challenge binding secret |
| Test mode configuration | `application-dev.yml` with `paygate.test-mode=true` |
| Spring Boot auto-configuration | No manual bean wiring -- the starter auto-configures everything |
| Integration testing | `ExampleAppIntegrationTest` exercising the full verification path |
| Docker deployment | Multi-stage Dockerfile and docker-compose.yml |

### Key patterns to adopt in your own application

1. **Annotate controller methods** with `@PaymentRequired` to mark them as payment-gated. The filter discovers them automatically.
2. **Implement `PaygatePricingStrategy`** and register it as a Spring `@Component` to create dynamic pricing. Reference it by bean name in the annotation.
3. **Use test mode during development**. Set `paygate.test-mode=true` and exercise the full flow without a Lightning node. Switch to a real backend (LNbits or LND) for staging and production.
4. **Enable dual-protocol support** by setting `paygate.protocols.mpp.challenge-binding-secret` to a secret of at least 32 bytes. Both L402 and MPP will be active simultaneously.

---

## Configuration

The example uses this configuration (`src/main/resources/application.yml`):

```yaml
spring:
  application:
    name: paygate-example-app
  profiles:
    active: dev

server:
  port: 8080

paygate:
  enabled: true
  service-name: example-api
  protocols:
    mpp:
      challenge-binding-secret: ${PAYGATE_MPP_SECRET:}
```

And the `dev` profile overrides (`src/main/resources/application-dev.yml`):

```yaml
paygate:
  test-mode: true
  protocols:
    mpp:
      challenge-binding-secret: dev-only-mpp-test-secret-do-not-use-in-production
```

> **Note:** Test mode is enabled via the `dev` profile in `application-dev.yml`, not in the base `application.yml`. The `dev` profile is activated by default via `spring.profiles.active: dev` above.

### Dual-Protocol Behavior

When the `dev` profile is active, both L402 and MPP protocols are enabled because:

1. L402 is enabled by default (`paygate.protocols.l402.enabled=true`)
2. MPP is in `auto` mode (default) and the `challenge-binding-secret` is provided in `application-dev.yml`

When a client requests a protected endpoint without credentials, the server returns a 402 response with **multiple `WWW-Authenticate` headers** -- one for each active protocol. The client can choose which protocol to use for payment and credential presentation. In production, set `PAYGATE_MPP_SECRET` as an environment variable with a secret of at least 32 bytes.

### Property Reference

| Property            | Value         | Effect                                                       |
|---------------------|---------------|--------------------------------------------------------------|
| `paygate.enabled`      | `true`        | Activates the payment filter and auto-configuration.         |
| `paygate.test-mode`    | `true`        | Uses `TestModeLightningBackend` instead of a real node.      |
| `paygate.service-name` | `example-api` | Appears in macaroon caveats (e.g., `services=example-api:0`).|
| `paygate.protocols.mpp.challenge-binding-secret` | `${PAYGATE_MPP_SECRET:}` | HMAC secret for MPP challenge binding. When present and non-blank, enables MPP protocol. |

### Additional Properties (defaults)

These properties are not set explicitly in the example but take effect via their defaults:

| Property | Default | Effect |
|----------|---------|--------|
| `paygate.default-price-sats` | `10` | Fallback price if `@PaymentRequired` does not specify one. |
| `paygate.default-timeout-seconds` | `3600` | Fallback credential lifetime (1 hour). |
| `paygate.root-key-store` | `file` | Root key persistence. Use `memory` for ephemeral keys (Docker, tests). |
| `paygate.root-key-store-path` | `~/.paygate/keys` | File system path for persisted root keys. |
| `paygate.credential-cache-max-size` | `10000` | Maximum cached credentials before eviction. |
| `paygate.health-cache.enabled` | `true` | Cache backend health check results. |
| `paygate.health-cache.ttl-seconds` | `5` | TTL for cached health check results. |

To connect to a real Lightning backend, disable test mode and configure one of the supported backends. See [Connecting a Real Lightning Backend](#connecting-a-real-lightning-backend).

---

## Project Structure

```
paygate-example-app/
  src/main/java/com/greenharborlabs/paygate/example/
    ExampleApplication.java         Main class (@SpringBootApplication)
    ExampleController.java          REST endpoints with @PaymentRequired
    AnalysisPricingStrategy.java    Dynamic pricing implementation (PaygatePricingStrategy)
    SecurityConfig.java             Security configuration
  src/main/resources/
    application.yml                 Base configuration (protocols, service name)
    application-dev.yml             Dev profile (test mode, MPP secret)
  src/test/java/
    ExampleAppIntegrationTest.java  Full payment flow integration tests
  Dockerfile                        Multi-stage Docker build
  .dockerignore                     Docker build exclusions
  build.gradle.kts                  Module build file
```

### Dependencies

Declared in `build.gradle.kts`:

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `paygate-spring-boot-starter` | implementation | Pulls in core, auto-configuration, and transitive dependencies |
| `paygate-lightning-lnbits` | implementation | LNbits backend (on the classpath for real deployments; test mode overrides it) |
| `spring-boot-starter-web` | implementation | Spring MVC, embedded Tomcat |
| `spring-boot-starter-test` | testImplementation | JUnit 5, MockMvc, AssertJ |
| `spring-boot-starter-webmvc-test` | testImplementation | `@AutoConfigureMockMvc` support |

---

## Running the Tests

The integration tests exercise the **complete L402 flow** including credential verification. They mint their own macaroons with known preimage/paymentHash pairs to test the full verification path programmatically.

From the project root:

```bash
./gradlew :paygate-example-app:test
```

### What the tests cover

| Test Class | Test | What It Verifies |
|------------|------|-----------------|
| `HealthEndpoint` | `returns200WithoutAuth` | `GET /api/v1/health` returns 200 without authentication. |
| `DataEndpointNoAuth` | `returns402WithChallenge` | `GET /api/v1/data` without credentials returns 402 with a `WWW-Authenticate` header containing `macaroon=` and `invoice=` fields. |
| `DataEndpointNoAuth` | `returns402JsonBody` | The 402 response body includes JSON with `code: 402` and `price_sats: 10`. |
| `FullL402Flow` | `validCredentialReturns200` | A self-minted macaroon with a matching preimage grants access. Verifies HTTP 200, `X-L402-Credential-Expires` header, and the expected JSON body. |
| `AnalyzeEndpointNoAuth` | `returns402WithDynamicPricing` | `POST /api/v1/analyze` with a small body returns 402 with `price_sats >= 50`. |

### How the full-flow test works

The test cannot use the preimage from a server-issued 402 challenge because the test backend generates a random payment hash for which the preimage cannot be derived (that would require inverting SHA-256). Instead, the test:

1. Generates a random 32-byte preimage.
2. Computes `paymentHash = SHA-256(preimage)`.
3. Generates a root key from the application's own `RootKeyStore` (using `root-key-store=memory` so the test shares the same store as the filter).
4. Mints a macaroon with an identifier containing the payment hash and token ID, signed with the root key.
5. Builds the `Authorization: L402 <macaroon>:<preimage>` header.
6. Sends the request and asserts HTTP 200 with the expected response.

This exercises the full verification path: root key lookup, macaroon signature check, preimage-to-paymentHash SHA-256 match, and credential caching.

---

## Docker Setup Details

### Dockerfile

The Dockerfile at `paygate-example-app/Dockerfile` uses a **multi-stage build**:

**Stage 1 -- Build** (`eclipse-temurin:25-jdk`):

1. Copies Gradle wrapper and all module build files first for Docker layer caching. Dependency downloads are cached unless build files change.
2. Copies proto files required by the `paygate-lightning-lnd` module for compilation.
3. Downloads dependencies in a separate layer (`./gradlew dependencies`).
4. Copies all source code and builds the example app boot JAR, skipping tests for faster image builds.

**Stage 2 -- Runtime** (`eclipse-temurin:25-jre`):

1. Runs as a non-root user (`appuser`) for security.
2. Copies only the built JAR from the build stage.
3. Exposes port 8080.
4. Starts the application with `java -jar app.jar`.

### docker-compose.yml

The Compose file at the project root defines one service:

```yaml
services:
  paygate-example-app:
    build:
      context: .
      dockerfile: paygate-example-app/Dockerfile
    ports:
      - "8080:8080"
    environment:
      L402_ENABLED: "true"
      L402_TEST_MODE: "true"
      L402_SERVICE_NAME: "example-api"
      L402_ROOT_KEY_STORE: "memory"
```

Key details:

- The build context is the **project root** (not `paygate-example-app/`) because the multi-module build needs access to all submodules.
- Environment variables use Spring Boot's relaxed binding (e.g., `L402_TEST_MODE` maps to `paygate.test-mode`).
- `L402_ROOT_KEY_STORE=memory` is used because file-based key storage would require a persistent volume. For production, mount a volume and use `file` instead.

### Building the Docker image manually

If you prefer to build the image without Compose:

```bash
docker build -f paygate-example-app/Dockerfile -t paygate-example-app .
```

Run it:

```bash
docker run -p 8080:8080 \
  -e L402_ENABLED=true \
  -e L402_TEST_MODE=true \
  -e L402_SERVICE_NAME=example-api \
  -e L402_ROOT_KEY_STORE=memory \
  paygate-example-app
```

---

## Connecting a Real Lightning Backend

To use this example with a real Lightning node instead of test mode, update the configuration:

### With LNbits

```yaml
paygate:
  enabled: true
  test-mode: false
  backend: lnbits
  service-name: example-api
  lnbits:
    url: https://your-lnbits-instance.com
    api-key: ${LNBITS_API_KEY}
```

See [`paygate-lightning-lnbits/README.md`](../paygate-lightning-lnbits/README.md) for details on obtaining an API key and configuring the LNbits backend.

### With LND

```yaml
paygate:
  enabled: true
  test-mode: false
  backend: lnd
  service-name: example-api
  lnd:
    host: localhost
    port: 10009
    tls-cert-path: /path/to/tls.cert
    macaroon-path: /path/to/invoice.macaroon
```

See [`paygate-lightning-lnd/README.md`](../paygate-lightning-lnd/README.md) for details on configuring the LND backend.

### Docker with a real backend

Pass the backend configuration as environment variables:

```bash
docker run -p 8080:8080 \
  -e L402_ENABLED=true \
  -e L402_TEST_MODE=false \
  -e L402_BACKEND=lnbits \
  -e L402_SERVICE_NAME=example-api \
  -e L402_LNBITS_URL=https://your-lnbits-instance.com \
  -e L402_LNBITS_API_KEY=your-api-key \
  paygate-example-app
```

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
