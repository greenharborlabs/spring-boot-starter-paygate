# paygate-spring-boot-starter

Dependency aggregator starter for the `spring-boot-starter-paygate` project. This module contains **no source code** -- it exists solely to provide a single dependency that pulls in everything needed to add L402 payment-gated authentication to a Spring Boot application.

This follows the [standard Spring Boot starter pattern](https://docs.spring.io/spring-boot/reference/using/build-systems.html#using.build-systems.starters): one dependency gives you auto-configuration, core library, and sensible defaults.

---

## Table of Contents

- [What It Includes](#what-it-includes)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Choosing a Lightning Backend](#choosing-a-lightning-backend)
- [Configuration Overview](#configuration-overview)
- [Optional Add-Ons](#optional-add-ons)
- [Module Reference](#module-reference)

---

## What It Includes

Adding this starter transitively pulls in:

| Module | Purpose |
|--------|---------|
| `paygate-core` | Macaroon V2 minting and verification, `LightningBackend` interface, credential stores, root key management |
| `paygate-api` | Protocol abstraction API (zero external dependencies) |
| `paygate-protocol-l402` | L402 protocol implementation |
| `paygate-protocol-mpp` | MPP (Modern Payment Protocol) implementation |
| `paygate-spring-autoconfigure` | Spring Boot auto-configuration: `PaygateSecurityFilter`, properties binding, health indicator, Caffeine caching, Micrometer metrics |

This starter does **not** include a Lightning backend module. You must add one separately -- see [Choosing a Lightning Backend](#choosing-a-lightning-backend) below.

---

## Installation

Add the starter to your project. The group ID is `com.greenharborlabs` and the current version is `0.1.0`.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
```

**Gradle (Groovy DSL):**

```groovy
implementation 'com.greenharborlabs:paygate-spring-boot-starter:0.1.0'
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>paygate-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

## Quick Start

1. Add the starter and a Lightning backend module to your dependencies:

    ```kotlin
    // build.gradle.kts
    implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
    implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.0")  // or paygate-lightning-lnd
    ```

2. Configure your `application.yml`:

    ```yaml
    paygate:
      enabled: true
      backend: lnbits        # or "lnd"
      service-name: my-api
      lnbits:
        url: https://your-lnbits-instance.com
        api-key: ${LNBITS_API_KEY}
    ```

3. Annotate endpoints you want to gate behind Lightning payments:

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

4. Run your application. Requests to `/api/v1/premium` without valid L402 credentials receive HTTP 402 with a Lightning invoice. After paying and presenting the credential, the client receives the response.

---

## Choosing a Lightning Backend

The starter provides the framework, but you need exactly one Lightning backend module on the classpath. The auto-configuration detects which one is present and creates the appropriate `LightningBackend` bean.

| Module | Backend | Protocol | When to Choose |
|--------|---------|----------|----------------|
| `paygate-lightning-lnbits` | [LNbits](https://lnbits.com/) | REST/JSON | Easiest setup. Works with hosted instances. Good for development and smaller deployments. |
| `paygate-lightning-lnd` | [LND](https://github.com/lightningnetwork/lnd) | gRPC/Protobuf | Production deployments with your own Lightning node. Requires TLS cert and macaroon file. |

**Gradle examples:**

```kotlin
// Option A: LNbits backend
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:paygate-lightning-lnbits:0.1.0")

// Option B: LND backend
implementation("com.greenharborlabs:paygate-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:paygate-lightning-lnd:0.1.0")
```

Both backends implement the same `LightningBackend` interface. Switching between them requires only changing dependencies and configuration properties -- no application code changes.

---

## Configuration Overview

All properties live under the `paygate.*` prefix. The starter auto-configures defaults for most of them.

| Property | Default | Description |
|----------|---------|-------------|
| `paygate.enabled` | `false` | Master switch. Must be `true` to activate L402 protection. |
| `paygate.backend` | -- | `lnbits` or `lnd`. Determines which backend module is used. |
| `paygate.service-name` | -- | Service name embedded in macaroon caveats. |
| `paygate.default-price-sats` | `10` | Default price in satoshis when `@PaygateProtected` does not specify one. |
| `paygate.default-timeout-seconds` | `3600` | Default credential validity period (1 hour). |
| `paygate.root-key-store` | `file` | Root key storage: `file` (persistent) or `memory` (ephemeral). |
| `paygate.root-key-store-path` | `~/.paygate/keys` | File path for persistent root key storage. |
| `paygate.credential-cache-max-size` | `10000` | Maximum cached credentials. |
| `paygate.test-mode` | `false` | When `true`, uses a dummy Lightning backend that auto-settles invoices (development only). Full L402 verification still runs. |
| `paygate.health-cache.enabled` | `true` | Cache `isHealthy()` results to avoid hammering the backend. |
| `paygate.health-cache.ttl-seconds` | `5` | Health cache TTL. |

Backend-specific properties (`paygate.lnbits.*`, `paygate.lnd.*`) are documented in their respective module READMEs.

### Protocol Configuration

The starter supports dual-protocol operation (L402 + MPP). Protocol behavior is controlled under the `paygate.protocols.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `paygate.protocols.l402.enabled` | `true` | Enable or disable the L402 protocol. |
| `paygate.protocols.mpp.enabled` | `auto` | MPP activation mode: `auto` enables MPP when a challenge-binding secret is present, `true` requires the secret (fails if missing), `false` disables MPP entirely. |
| `paygate.protocols.mpp.challenge-binding-secret` | -- | HMAC secret for MPP challenge binding. Must be at least 32 bytes. When set, MPP challenges appear alongside L402 in 402 responses. |

For the full property reference and auto-configuration details, see the `paygate-spring-autoconfigure` module.

---

## Optional Add-Ons

These modules provide additional capabilities and can be added alongside the starter:

| Module | Purpose | Dependency |
|--------|---------|------------|
| `paygate-spring-security` | Spring Security integration for L402 authentication | `com.greenharborlabs:paygate-spring-security:0.1.0` |

Optional libraries detected by auto-configuration:

| Library | Effect When Present |
|---------|-------------------|
| [Caffeine](https://github.com/ben-manes/caffeine) | Used for credential caching (recommended, included by Spring Boot) |
| [Micrometer](https://micrometer.io/) | Exposes L402 metrics (challenge count, verification count, latency) |
| [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/) | Adds `/actuator/health/lightning` endpoint for backend health checks |

---

## Module Reference

| Module | README | Description |
|--------|--------|-------------|
| `paygate-core` | [README](../paygate-core/README.md) | Pure-Java macaroon and credential library (zero external dependencies) |
| `paygate-api` | [README](../paygate-api/README.md) | Protocol abstraction API (zero external dependencies) |
| `paygate-protocol-l402` | [README](../paygate-protocol-l402/README.md) | L402 protocol implementation |
| `paygate-protocol-mpp` | [README](../paygate-protocol-mpp/README.md) | MPP (Modern Payment Protocol) implementation |
| `paygate-lightning-lnbits` | [README](../paygate-lightning-lnbits/README.md) | LNbits REST backend |
| `paygate-lightning-lnd` | [README](../paygate-lightning-lnd/README.md) | LND gRPC backend |
| `paygate-spring-autoconfigure` | [README](../paygate-spring-autoconfigure/README.md) | Auto-configuration, properties, filters, health indicators |
| `paygate-spring-security` | [README](../paygate-spring-security/README.md) | Spring Security integration |
| `paygate-example-app` | [README](../paygate-example-app/README.md) | Working reference application |

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
