# spring-boot-starter-l402 Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-03-09

## Active Technologies

- Java 25 (LTS) + Spring Boot 4.0.3 + Spring Framework 7.x + Jakarta EE 11 (001-l402-core-impl)
- Gradle 8.12.x (Kotlin DSL), multi-module
- Caffeine 3.1.8, gRPC 1.68.1, Protobuf 4.29.3, Jackson 2.18.2, Micrometer (optional)

## Project Structure

```text
spring-boot-starter-l402/
├── l402-core/                     # Pure Java, ZERO external deps (JDK only)
├── l402-lightning-lnd/            # LND gRPC backend
├── l402-lightning-lnbits/         # LNbits REST backend
├── l402-spring-autoconfigure/     # Spring Boot auto-configuration
├── l402-spring-boot-starter/      # Dependency aggregator (no source)
└── l402-example-app/              # Reference implementation
```

Package root: `com.greenharborlabs.l402`

## Commands

```bash
./gradlew build          # Build all modules
./gradlew test           # Run all tests
./gradlew :l402-core:test  # Test core module only
```

## Code Style

- Java 25: Use records, sealed classes, pattern matching where appropriate
- l402-core: MUST have zero external dependencies — only JDK `javax.crypto`, `java.security`, `java.util`
- All secret comparisons: constant-time XOR accumulation (never `Arrays.equals`)
- Never log full macaroon values — only token IDs
- Standard base64 with padding (not base64url) for macaroon encoding

## Key Constraints

- Macaroon V2 binary format must be byte-level compatible with Go `go-macaroon`
- Key derivation: `HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`
- Identifier layout: `[version:2 bytes BE][payment_hash:32][token_id:32]` = 66 bytes
- Fail closed: Lightning unreachable → 503, never 200

## Recent Changes

- 001-l402-core-impl: Initial feature plan created

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
