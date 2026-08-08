# Contributing to spring-boot-starter-paygate

Thank you for your interest in contributing! This guide will help you get started.

## Prerequisites

- Java 25 (LTS)
- Gradle (wrapper included, no separate install needed)

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test                   # Modules in the default build
./gradlew :paygate-core:test    # Core module only
./gradlew build -Pintegration   # Include the opt-in integration-test module
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `paygate-api` | JDK-only payment-protocol SPI and shared records |
| `paygate-core` | Pure Java macaroon and L402 logic (zero external dependencies) |
| `paygate-lightning-lnd` | LND gRPC lightning backend |
| `paygate-lightning-lnbits` | LNbits REST lightning backend |
| `paygate-protocol-l402` | L402 protocol adapter |
| `paygate-protocol-mpp` | Modern Payment Protocol adapter |
| `paygate-spring-autoconfigure` | Spring Boot auto-configuration |
| `paygate-spring-security` | Spring Security integration (optional) |
| `paygate-spring-boot-starter` | Dependency aggregator (no source) |
| `paygate-example-app` | Reference implementation |
| `paygate-example-app-spring-security` | Spring Security reference implementation |
| `paygate-integration-tests` | Opt-in Gradle integration-test suites |
| `integration-tests` | Docker Compose integration test environments |

## Submitting Changes

1. Fork the repository and create a branch from `main`.
2. Keep pull requests focused on a single change.
3. Write tests for new or changed behavior.
4. Ensure `./gradlew build` passes before submitting.
5. Follow the repository conventions in `AGENTS.md`.

## Reporting Issues

- Use the bug report or feature request templates when opening issues.
- For security vulnerabilities, see [SECURITY.md](SECURITY.md).
