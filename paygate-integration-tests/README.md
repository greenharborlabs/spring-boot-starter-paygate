# paygate-integration-tests

Cross-module integration tests verifying protocol behavior end-to-end. This module boots the full example application with Spring Boot's `@SpringBootTest` and exercises the complete HTTP request pipeline -- from unauthenticated challenge through credential presentation to authenticated access.

These tests validate that the paygate modules work correctly together in a running Spring Boot application, covering both L402 and MPP (Modern Payment Protocol) flows.

All tests boot the example application on a random port using `@SpringBootTest(webEnvironment = RANDOM_PORT)` and make real HTTP requests via `java.net.http.HttpClient`. Configuration is overridden per-test using `@TestPropertySource`.

---

## Test Classes

| Class | Description |
|-------|-------------|
| `LnbitsHappyPathIT` | Full L402 happy path in test mode: unauthenticated request receives 402 challenge, extracts macaroon and test preimage, presents L402 credential, and receives 200 with premium content. Also verifies unprotected endpoints remain accessible and 402 response body structure. |
| `DualProtocolIntegrationTest` | Dual-protocol (L402 + MPP) integration: verifies both `WWW-Authenticate` headers appear on 402, both protocols share a single Lightning invoice, full L402 flow grants access, and full MPP flow grants access with a `Payment-Receipt` header. Includes a nested test class for L402-only backward compatibility when no MPP secret is configured. |
| `FailClosedIT` | Fail-closed security property: when the Lightning backend is unhealthy or throws on invoice creation, protected endpoints return 503 (never 200). Uses a controllable stub backend to simulate failure conditions. Verifies unprotected endpoints remain accessible during backend outages. |
| `TamperDetectionIT` | Tamper detection: verifies that tampered macaroons, wrong preimages, and malformed Authorization headers are rejected with appropriate 4xx status codes. Exercises HMAC signature verification in the full request pipeline. |
| `GoInteropIT` | Go interoperability: mints Macaroon V2 in Java and shells out to the Go `go-macaroon` library to verify byte-level deserialization compatibility. Tests macaroons with caveats, without location, and without caveats. Skips gracefully when the Go toolchain is unavailable. |

---

## Running the Tests

From the project root:

```bash
./gradlew :paygate-integration-tests:test
```

This module is excluded from the default `./gradlew build`. All test classes are tagged with `@Tag("integration")` and the Gradle test task is configured to include only that tag.

No real Lightning backend is required. Tests use either test mode (which provides a dummy backend that auto-settles invoices) or a controllable stub injected via `@TestConfiguration`. The `GoInteropIT` test requires a Go toolchain and skips gracefully when one is not available.

JaCoCo coverage verification is disabled for this module since it contains only integration tests.

---

## Dependencies

| Scope | Dependency | Purpose |
|-------|------------|---------|
| test | `paygate-example-app` | Provides the Spring Boot application under test |
| test | `paygate-core` | Macaroon minting and verification for Go interop tests |
| test | `paygate-api` | Protocol abstraction API |
| test | `paygate-protocol-l402` | L402 protocol implementation |
| test | `paygate-protocol-mpp` | MPP protocol implementation |
| test | `paygate-spring-boot-starter` | Starter dependency aggregator |
| test | `paygate-spring-autoconfigure` | Auto-configuration for filter, properties, and health |
| test | Spring Boot Starter Test | `@SpringBootTest`, JUnit 5, AssertJ |
| test | Spring Boot Starter Web | Embedded web server for integration tests |
| test | OkHttp MockWebServer | HTTP mocking utilities |

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
