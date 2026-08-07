# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- **C-1 — paygate-spring-autoconfigure** and **paygate-spring-security**: Bind L402 credentials to the canonical application-relative route and HTTP method, preventing replay across protected request boundaries.
- **C-2 — paygate-spring-autoconfigure** and **paygate-spring-security**: Resolve policy against the application-relative current dispatch so deployment prefixes cannot bypass payment enforcement.
- **H-1 — paygate-core**: Enforce immutable issuance ceilings during capability attenuation so delegated credentials cannot self-escalate beyond their originally issued capabilities.
- **H-2 — paygate-spring-autoconfigure** and **paygate-spring-security**: Apply protected `GET` payment policy to `HEAD` requests while keeping issued credentials bound to the actual `HEAD` method.
- **H-3 — paygate-spring-autoconfigure** and **paygate-spring-security**: Redact bearer credentials from diagnostic output.

### Changed

- **Credential schema compatibility**: Newly issued L402 credentials use signed identifier version 1. Identifier v0 credentials are rejected even after valid holder attenuation; clients must obtain and pay a new challenge, and custom issuers must mint v1. The 66-byte identifier layout, HTTP challenge `version="0"`, Macaroon V2 encoding, and Go parser compatibility are unchanged.
- **Unsigned location semantics**: Macaroon location remains an authorization-neutral V2 serialization hint and participates in object equality, hashing, and cache variant replacement, while `Macaroon.toString()` now omits null, ordinary, and attacker-controlled location values from diagnostics.
- **Compatibility**: Intentionally reject legacy L402 credentials that lack the required reserved `route`, `method`, or `{serviceName}_capabilities` caveats; affected clients must obtain newly issued credentials. The context-less `L402Validator.validate(String)` descriptor remains callable but fails closed for boundary-bound credentials, so integrations must use a context-bearing overload with trusted `REQUEST_ROUTE` and `REQUEST_METHOD` metadata. The public `ErrorCode` enum now includes `MISSING_REQUEST_CONTEXT` (HTTP 401); downstream exhaustive source switches must be updated and recompiled when upgrading.
- **Delegation compatibility**: Pre-existing cross-application credentials that used generic `route`, `method`, or the active service capability key are now evaluated as this application's reserved first-party boundaries once those verifiers are registered; only truly unregistered caveat keys retain pass-through behavior.
- **Route compatibility**: The retained `PaygateChallengeService` challenge overload derives route identity through the registration `PathPattern` parser helper. Signed route comparison remains exact, including whitespace and trailing slash; no new case or percent-encoding normalization is promised.

## [0.1.4] - 2026-07-01

### Security

- **paygate-lightning-lnbits**: Require HTTPS LNbits URLs by default; plaintext HTTP now needs an explicit local/test opt-in and is limited to loopback or the known Docker Compose LNbits service host.
- **paygate-spring-security**: Fail startup in Spring Security mode when no `PaygateAuthenticationFilter` is present in the effective filter chain, unless advanced custom filter-chain wiring is explicitly acknowledged.
- **paygate-spring-autoconfigure**: Reject the committed MPP sample challenge-binding secret outside test mode.
- **integration-tests**: Bind local Lightning and Bitcoin service ports to loopback and narrow regtest Bitcoin RPC access to the Docker subnet.

### Changed

- **paygate-spring-autoconfigure**: Disable the Paygate actuator endpoint by default; enable it explicitly with `paygate.actuator.enabled=true`.
- **paygate-example-app** and **paygate-example-app-spring-security**: Stop activating the dev profile from default `application.yml`; local test mode now stays in the explicit dev profile.

### Fixed

- **paygate-protocol-mpp**: Convert malformed echoed challenge-binding fields into `INVALID_CHALLENGE_BINDING` validation failures instead of leaking delimiter parsing exceptions.

## [0.1.3] - 2026-06-06

### Security

- **paygate-protocol-mpp**: Removed `MppMetadata.rawCredentialJson()` and the three-argument canonical constructor so parsed MPP metadata no longer retains decoded credential JSON containing `payload.preimage`; downstream callers should use `echoedChallenge()` and `source()`.
- **paygate-protocol-mpp**: Require request digest binding for all MPP challenges and credential validation, so Payment credentials are bound to the exact method, path, and request body.
- **paygate-protocol-mpp**: Zeroize local preimage, payment-hash, and computed-hash validation copies after MPP credential validation.
- **paygate-protocol-l402**: Zeroize the root-key defensive copy after macaroon challenge minting.
- **paygate-lightning-lnd**: Store LND macaroon credentials as defensive byte-array copies and zeroize interceptor-owned macaroon bytes when factory-created channels shut down.
- **paygate-lightning-lnbits**: Stop including LNbits non-2xx response bodies in warning logs and exception messages.
- **paygate-spring-autoconfigure**: Rate-limit unauthenticated MPP challenge requests before request-body digest capture.
- **paygate-spring-autoconfigure**: Reject malformed forwarded client IP identities instead of treating them as trusted proxy entries.
- **paygate-spring-autoconfigure**: Create Lightning invoices before root-key generation so invoice failures do not allocate root-key material.

### Fixed

- **paygate-lightning-lnd**: Validate invoice amounts and lookup payment-hash lengths before making LND backend calls.
- **paygate-lightning-lnbits**: Validate invoice amounts before making LNbits backend calls.
- **paygate-protocol-mpp**: Avoid previous-secret HMAC verification when the current challenge-binding secret already validates.
- **paygate-spring-autoconfigure**: Require the Paygate Spring Security integration module before `auto` mode switches from servlet filtering to Spring Security.
- **paygate-spring-autoconfigure**: Fail explicit `paygate.security-mode=spring-security` startup when either Spring Security or the Paygate Spring Security integration module is missing.
- **paygate-spring-autoconfigure**: Avoid double-charging challenge creation after the rate-limit token has already been consumed.

### Changed

- **paygate-lightning-lnd**: Added a preferred byte-array constructor path for `MacaroonClientInterceptor` while preserving the existing `String` constructor for source compatibility.
- **paygate-protocol-mpp**: Documented the required digest validation flow for Payment challenge handling.
- **release**: Updated the release checklist around the current Maven Central release flow.

## [0.1.2] - 2026-06-05

### Changed

- **paygate-core**: Clarified credential cache ownership boundaries so stores retain and return caller-owned copies.
- **paygate-core**: Reused precomputed caveat verifier maps for fresh and cached L402 credential validation paths.

### Security

- **paygate-core**: Reject non-canonical V2 macaroons with trailing bytes after the signature.
- **paygate-core**: Zeroize temporary payment preimage bytes decoded from hex.
- **paygate-core**: Destroy retained cache-owned preimages on revoke, expiry, replacement, capacity eviction, and close.
- **paygate-protocol-l402**: Clean up parsed credential material when validation fails.
- **paygate-spring-autoconfigure**: Apply credential ownership cleanup to the Caffeine-backed credential store.

### Fixed

- **paygate-core**: Preserve usable validation results after credential cache revocation.
- **paygate-core**: Keep cached credential validation fail-closed while avoiding repeated verifier-map construction.

## [0.1.1] - 2026-05-27

### Security

- Removed checked-in local L402 integration credential material from `integration-tests/.l402-credential.env`.
- Added `*.env` ignore coverage while still allowing `*.env.example` templates.
- Hardened release publishing so `v*` release tags must point to commits already on `main`.

## [0.1.0] - 2026-05-27

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
- CI/CD: Aggregate Javadoc generation and upload as a GitHub Actions artifact
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

[0.1.4]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/greenharborlabs/spring-boot-starter-l402/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/greenharborlabs/spring-boot-starter-l402/releases/tag/v0.1.0
