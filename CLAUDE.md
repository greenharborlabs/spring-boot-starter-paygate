# spring-boot-starter-paygate Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-06-06

## Active Technologies
- Java 25 (LTS) + Spring Boot 4.0.5, Spring Framework 7.x, Jakarta EE 11, Caffeine 3.2.3, gRPC 1.80.0, Protobuf 4.29.3, Jackson, Micrometer (optional) (002-dual-protocol-mpp)
- File-based root key store (default), in-memory credential cache (Caffeine) (002-dual-protocol-mpp)
- Delegation caveat verifiers: path/method/client_ip (paygate-core, zero-dep); Jakarta Servlet API for context population (003-delegation-caveat-verifiers)

- Java 25 (LTS) + Spring Boot 4.0.5 + Spring Framework 7.x + Jakarta EE 11
- Spring Security (optional, for `paygate-spring-security` module)
- Gradle 9.4.1 (Kotlin DSL), multi-module
- Caffeine 3.2.3, gRPC 1.80.0, Protobuf 4.29.3, Jackson, Micrometer (optional)
- AssertJ 3.27.3, OkHttp MockWebServer 4.12.0 (test)

## Project Structure

```text
spring-boot-starter-paygate/
├── paygate-api/                      # Protocol abstraction API (JDK only, zero external deps)
├── paygate-core/                     # Pure Java core; JDK-only production crypto plus paygate-api
├── paygate-protocol-l402/            # L402 protocol implementation
├── paygate-protocol-mpp/             # MPP (Modern Payment Protocol) implementation
├── paygate-lightning-lnd/            # LND gRPC backend
├── paygate-lightning-lnbits/         # LNbits REST backend
├── paygate-spring-autoconfigure/     # Spring Boot auto-configuration
├── paygate-spring-security/          # Spring Security integration (optional)
├── paygate-spring-boot-starter/      # Dependency aggregator (no source)
├── paygate-example-app/              # Reference implementation
├── paygate-example-app-spring-security/ # Spring Security reference implementation
├── paygate-integration-tests/        # Gradle integration tests; included with -Pintegration
└── integration-tests/                # Docker Compose integration test environments
```

Package roots:
- `com.greenharborlabs.paygate.core` (core)
- `com.greenharborlabs.paygate.lightning.lnd` and `com.greenharborlabs.paygate.lightning.lnbits` (lightning backends)
- `com.greenharborlabs.paygate.spring` and `com.greenharborlabs.paygate.spring.security` (Spring modules)
- `com.greenharborlabs.paygate.api` (paygate-api)
- `com.greenharborlabs.paygate.protocol.l402` (paygate-protocol-l402)
- `com.greenharborlabs.paygate.protocol.mpp` (paygate-protocol-mpp)

### Module Dependency Graph

```text
paygate-spring-boot-starter
  └── paygate-spring-autoconfigure
        ├── paygate-protocol-l402
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

## Commands

```bash
./gradlew build          # Build default modules; excludes paygate-integration-tests
./gradlew test           # Run tests across default modules
./gradlew :paygate-core:test  # Test core module only
./gradlew build -Pintegration # Include paygate-integration-tests
./gradlew :paygate-integration-tests:test -Pintegration
./gradlew :paygate-integration-tests:securityTest -Pintegration
./gradlew jacocoTestReport
./gradlew aggregateJavadoc
./gradlew check
./gradlew buildHealth
./gradlew releaseReadiness -Pintegration
./gradlew spotlessCheck
./gradlew spotlessApply
./gradlew pmdMain
./gradlew :paygate-core:jmh
```

Docker integration environments:

```bash
cd integration-tests && docker-compose -f docker-compose-lnd.yml up --build
cd integration-tests && docker-compose -f docker-compose-lnbits.yml up --build
cd integration-tests && docker-compose -f docker-compose-lnbits-lnd.yml up --build
cd integration-tests && docker-compose -f docker-compose-lnd-two-node.yml up --build
```

## Code Style

- Java 25: Use records, sealed classes, pattern matching where appropriate
- paygate-api: MUST have zero external production dependencies -- JDK only
- paygate-core: MUST have zero external production dependencies outside project modules. It may depend on `paygate-api`; production crypto code stays JDK-only (`javax.crypto`, `java.security`, `java.util`, etc.). Tests may use test-only libraries such as JUnit, AssertJ, and Jackson fixtures.
- All secret comparisons: constant-time XOR accumulation (never `Arrays.equals`)
- Never log full macaroon values -- only token IDs
- L402 macaroon encoding uses standard base64 with padding; MPP uses base64url without padding
- For JCA objects such as `Mac`, call `Mac.getInstance()` fresh per operation rather than caching in a `ThreadLocal`

## Key Constraints

- Macaroon V2 binary format must be byte-level compatible with Go `go-macaroon`
- Key derivation: `HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`
- Identifier layout: `[version:2 bytes BE][payment_hash:32][token_id:32]` = 66 bytes
- Fail closed: Lightning unreachable → 503, never 200
- `paygate-api`: MUST have zero external production dependencies -- JDK only
- `paygate-protocol-mpp`: depends on `paygate-api` only (NO `paygate-core`)
- `paygate-protocol-l402`: depends on `paygate-api` + `paygate-core`
- MPP challenge binding uses HMAC-SHA256 with constant-time comparison
- MPP uses base64url encoding without padding (NOT standard base64 with padding)
- MPP uses RFC 8785 JCS (JSON Canonicalization Scheme) for deterministic serialization

## Authority Model (Spring Security)

- `ROLE_PAYMENT` -- granted to all authenticated payment tokens (both L402 and MPP)
- `ROLE_L402` -- granted to L402 credentials only
- `PAYGATE_CAPABILITY_*` -- protocol-agnostic capability authority, recommended for `@PreAuthorize` expressions
- `L402_CAPABILITY_*` -- L402-specific capability authority (backward compatible, still emitted)

L402 tokens dual-emit both `L402_CAPABILITY_*` and `PAYGATE_CAPABILITY_*` for each capability.
MPP tokens emit only `PAYGATE_CAPABILITY_*`. Use `PAYGATE_CAPABILITY_*` in all new authorization rules.

## Configuration Properties

All properties are under the `paygate.*` prefix. Key properties include:

Configuration metadata lives at `paygate-spring-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json`; also check `docs/` for deeper references.

**Core properties:**

- `paygate.enabled` (boolean, default `false`) -- master switch
- `paygate.backend` (string) -- `lnbits` or `lnd`
- `paygate.service-name` (string) -- service name in caveats
- `paygate.default-price-sats` (long, default `10`)
- `paygate.default-timeout-seconds` (long, default `3600`)
- `paygate.root-key-store` (string, default `file`) -- `file` or `memory`
- `paygate.root-key-store-path` (string, default `~/.paygate/keys`)
- `paygate.credential-cache-max-size` (int, default `10000`)
- `paygate.security-mode` (string, default `auto`) -- `auto`, `servlet`, or `spring-security`
- `paygate.test-mode` (boolean, default `false`)
- `paygate.trust-forwarded-headers` (boolean, default `false`)

**Delegation caveat** (`paygate.caveat.*`, `paygate.trusted-proxy-addresses`):

- `paygate.trusted-proxy-addresses` (List<String>, default empty) -- trusted reverse proxy IPs for X-Forwarded-For resolution
- `paygate.caveat.max-values-per-caveat` (int, default `50`) -- max comma-separated values per caveat

**Rate limiting** (`paygate.rate-limit.*`):

- `paygate.rate-limit.requests-per-second` (double, default `10.0`)
- `paygate.rate-limit.burst-size` (int, default `20`)
- `paygate.rate-limit.max-buckets` (int, default `100000`) -- maximum number of tracked IP rate-limit buckets

**Health cache** (`paygate.health-cache.*`):

- `paygate.health-cache.enabled` (boolean, default `true`)
- `paygate.health-cache.ttl-seconds` (int, default `5`)

**Lightning** (`paygate.lightning.*`):

- `paygate.lightning.timeout-seconds` (int, default `5`) -- global lightning backend timeout

**LNbits** (`paygate.lnbits.*`):

- `paygate.lnbits.url` (string) -- LNbits instance URL
- `paygate.lnbits.api-key` (string) -- LNbits admin API key
- `paygate.lnbits.request-timeout-seconds` (Integer) -- per-request timeout override
- `paygate.lnbits.connect-timeout-seconds` (Integer) -- connection timeout override

**LND** (`paygate.lnd.*`):

- `paygate.lnd.host` (string, default `localhost`)
- `paygate.lnd.port` (int, default `10009`)
- `paygate.lnd.tls-cert-path` (string) -- path to LND TLS certificate
- `paygate.lnd.macaroon-path` (string) -- path to LND admin macaroon
- `paygate.lnd.allow-plaintext` (boolean, default `false`) -- dev only
- `paygate.lnd.rpc-deadline-seconds` (Integer) -- per-call gRPC deadline
- `paygate.lnd.keep-alive-time-seconds` (int, default `60`)
- `paygate.lnd.keep-alive-timeout-seconds` (int, default `20`)
- `paygate.lnd.idle-timeout-minutes` (int, default `5`)
- `paygate.lnd.max-inbound-message-size` (int, default `4194304`)

**Protocol configuration** (`paygate.protocols.*`):

- `paygate.protocols.l402.enabled` (boolean, default `true`) -- enable/disable L402 protocol
- `paygate.protocols.mpp.enabled` (string, default `auto`) -- `auto` enables MPP when secret is present, `true` requires secret, `false` disables
- `paygate.protocols.mpp.challenge-binding-secret` (string) -- HMAC secret for MPP challenge binding, minimum 32 bytes

**Metrics** (`paygate.metrics.*`):

- `paygate.metrics.max-endpoint-cardinality` (int, default `100`)
- `paygate.metrics.overflow-tag-value` (string, default `_other`)

## Recent Changes
- 003-delegation-caveat-verifiers: Added Java 25 (LTS) + None for verifiers (paygate-core is zero-dep); Jakarta Servlet API for context population in paygate-spring-autoconfigure and paygate-spring-security
- 002-dual-protocol-mpp: Added paygate-api, paygate-protocol-l402, paygate-protocol-mpp modules for dual-protocol support (L402 + MPP). Added protocol configuration properties under `paygate.protocols.*`. MPP uses HMAC-SHA256 challenge binding with base64url encoding and RFC 8785 JCS.

- Rebranded from `spring-boot-starter-l402` to `spring-boot-starter-paygate` with `paygate-*` module names

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
