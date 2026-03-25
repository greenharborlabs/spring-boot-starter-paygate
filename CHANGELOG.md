# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - Unreleased

### Added

- **paygate-core**: Macaroon V2 binary serialization/deserialization, byte-level compatible with Go `go-macaroon`
- **paygate-core**: HMAC-SHA256 crypto chain with key derivation (`HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`)
- **paygate-core**: Macaroon identifier layout: `[version:2B BE][payment_hash:32B][token_id:32B]` (66 bytes)
- **paygate-core**: First-party caveat support with built-in verifiers (service, capabilities, expiry)
- **paygate-core**: Capabilities caveat verifier for fine-grained access control
- **paygate-core**: Monotonic restriction validation for caveats
- **paygate-core**: Constant-time equality checks for all secret comparisons (XOR accumulation)
- **paygate-core**: `SensitiveBytes` wrapper for root key zeroization on close
- **paygate-core**: `RootKeyStore` implementations: file-based (with caching/eviction) and in-memory
- **paygate-core**: `InMemoryCredentialStore` with configurable max size and eviction policies
- **paygate-core**: L402 protocol flow: 402 challenge issuance and credential validation with preimage verification
- **paygate-core**: Multi-token `Authorization` header parsing
- **paygate-core**: LSAT backward compatibility in header parsing
- **paygate-core**: Delegation caveat verifiers for path, method, and client_ip restrictions
- **paygate-api**: Protocol abstraction API for multi-protocol support (JDK only, zero dependencies)
- **paygate-protocol-l402**: L402 protocol implementation module
- **paygate-protocol-mpp**: MPP (Modern Payment Protocol) dual-protocol support with HMAC-SHA256 challenge binding, base64url encoding, and RFC 8785 JCS deterministic serialization
- **paygate-lightning-lnd**: LND gRPC Lightning backend with invoice creation and settlement verification
- **paygate-lightning-lnbits**: LNbits REST Lightning backend with invoice creation and settlement verification
- **paygate-spring-autoconfigure**: Spring Boot auto-configuration for all paygate components
- **paygate-spring-autoconfigure**: `@PaymentRequired` annotation for declarative endpoint protection
- **paygate-spring-autoconfigure**: Servlet filter for L402 challenge/validation lifecycle
- **paygate-spring-autoconfigure**: Pluggable `PaygatePricingStrategy` for dynamic per-request pricing
- **paygate-spring-autoconfigure**: Credential caching with Caffeine-backed store and dynamic TTL from `valid_until` caveats
- **paygate-spring-autoconfigure**: `CachingLightningBackendWrapper` for health check result caching
- **paygate-spring-autoconfigure**: `TokenBucketRateLimiter` for rate-limiting challenge issuance
- **paygate-spring-autoconfigure**: Micrometer metrics integration (challenge count, validation count, latency)
- **paygate-spring-autoconfigure**: Spring Boot Actuator health indicator (`PaygateLightningHealthIndicator`)
- **paygate-spring-autoconfigure**: Test mode with auto-settle invoices (blocked in `prod` profiles)
- **paygate-spring-autoconfigure**: IDE autocomplete via `additional-spring-configuration-metadata.json`
- **paygate-spring-security**: `PaygateAuthenticationProvider` for Spring Security integration
- **paygate-spring-security**: `PaygateAuthenticationFilter` for servlet-based authentication
- **paygate-spring-security**: `PaygateAuthenticationToken` for the Spring Security authentication model
- **paygate-spring-boot-starter**: Dependency aggregator module for single-dependency adoption
- **paygate-example-app**: Reference application demonstrating dynamic pricing and protected endpoints
- **paygate-example-app-spring-security**: Reference application demonstrating dual-protocol support with Spring Security
- Docker support: `Dockerfile` and `docker-compose.yml` for containerized deployment
- CI/CD: GitHub Actions workflows for CI, release (Sonatype staging), and snapshot publishing
- CI/CD: Dependabot configuration for automated dependency updates
- CI/CD: CodeQL analysis workflow for security scanning
- CI/CD: Gradle wrapper validation in CI
- CI/CD: Integration test CI stage
- CI/CD: Javadoc publishing to GitHub Pages
- Testcontainers-based integration test module
- Go interop test automation for cross-platform macaroon compatibility
- `.editorconfig` for consistent formatting across editors
- Smoke test script for manual Lightning validation
- Configuration properties under `paygate.*` prefix with sensible defaults

### Security

- Fail-closed design: Lightning backend unreachable returns 503, never 200
- All secret comparisons use constant-time XOR accumulation (never `Arrays.equals`)
- Root keys wrapped in `SensitiveBytes` with explicit zeroization
- Macaroon values never logged in full; only token IDs appear in logs
- `FileBasedRootKeyStore` returns defensive copies of root keys
- Sig byte array zeroization in `MacaroonMinter` prevents key material leakage
- LND macaroon file size guard rejects unexpectedly large credential files

### Fixed

- Synchronized `InMemoryRootKeyStore` to prevent race conditions under concurrent access
- Health gauge uses cached value instead of blocking `isHealthy()` call
- `TokenBucketRateLimiter` bucket count race condition resolved
- `LndBackend.close()` properly awaits channel termination instead of fire-and-forget
- LNbits response timestamps parsed from actual API response instead of fabricated
- Standardized logging to `System.Logger` across all modules, replacing mixed SLF4J usage
- Shared L402 header parsing contract via `L402HeaderComponents` eliminates divergent regex implementations
- Bounded metrics cardinality prevents unbounded tag explosion in Micrometer metrics
- Unknown caveat handling: skip unknown caveats per specification instead of rejecting
- Unknown caveat handling documentation corrected to match implementation behavior
- `WWW-Authenticate` header format corrected to `L402 version="0", token=`
- `MacaroonSerializer` validation for field types and lengths

[0.1.0]: https://github.com/greenharborlabs/spring-boot-starter-l402/releases/tag/v0.1.0
