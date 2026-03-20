# l402-spring-boot-starter

Dependency aggregator starter for the `spring-boot-starter-l402` project. This module contains **no source code** -- it exists solely to provide a single dependency that pulls in everything needed to add L402 payment-gated authentication to a Spring Boot application.

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
| `l402-core` | Macaroon V2 minting and verification, `LightningBackend` interface, credential stores, root key management |
| `l402-spring-autoconfigure` | Spring Boot auto-configuration: `L402SecurityFilter`, properties binding, health indicator, Caffeine caching, Micrometer metrics |

This starter does **not** include a Lightning backend module. You must add one separately -- see [Choosing a Lightning Backend](#choosing-a-lightning-backend) below.

---

## Installation

Add the starter to your project. The group ID is `com.greenharborlabs` and the current version is `0.1.0`.

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
```

**Gradle (Groovy DSL):**

```groovy
implementation 'com.greenharborlabs:l402-spring-boot-starter:0.1.0'
```

**Maven:**

```xml
<dependency>
    <groupId>com.greenharborlabs</groupId>
    <artifactId>l402-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

## Quick Start

1. Add the starter and a Lightning backend module to your dependencies:

    ```kotlin
    // build.gradle.kts
    implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
    implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0")  // or l402-lightning-lnd
    ```

2. Configure your `application.yml`:

    ```yaml
    l402:
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

        @L402Protected(priceSats = 10)
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
| `l402-lightning-lnbits` | [LNbits](https://lnbits.com/) | REST/JSON | Easiest setup. Works with hosted instances. Good for development and smaller deployments. |
| `l402-lightning-lnd` | [LND](https://github.com/lightningnetwork/lnd) | gRPC/Protobuf | Production deployments with your own Lightning node. Requires TLS cert and macaroon file. |

**Gradle examples:**

```kotlin
// Option A: LNbits backend
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:l402-lightning-lnbits:0.1.0")

// Option B: LND backend
implementation("com.greenharborlabs:l402-spring-boot-starter:0.1.0")
implementation("com.greenharborlabs:l402-lightning-lnd:0.1.0")
```

Both backends implement the same `LightningBackend` interface. Switching between them requires only changing dependencies and configuration properties -- no application code changes.

---

## Configuration Overview

All properties live under the `l402.*` prefix. The starter auto-configures defaults for most of them.

| Property | Default | Description |
|----------|---------|-------------|
| `l402.enabled` | `false` | Master switch. Must be `true` to activate L402 protection. |
| `l402.backend` | -- | `lnbits` or `lnd`. Determines which backend module is used. |
| `l402.service-name` | -- | Service name embedded in macaroon caveats. |
| `l402.default-price-sats` | `10` | Default price in satoshis when `@L402Protected` does not specify one. |
| `l402.default-timeout-seconds` | `3600` | Default credential validity period (1 hour). |
| `l402.root-key-store` | `file` | Root key storage: `file` (persistent) or `memory` (ephemeral). |
| `l402.root-key-store-path` | `~/.l402/keys` | File path for persistent root key storage. |
| `l402.credential-cache-max-size` | `10000` | Maximum cached credentials. |
| `l402.test-mode` | `false` | When `true`, uses a dummy Lightning backend that auto-settles invoices (development only). Full L402 verification still runs. |
| `l402.health-cache.enabled` | `true` | Cache `isHealthy()` results to avoid hammering the backend. |
| `l402.health-cache.ttl-seconds` | `5` | Health cache TTL. |

Backend-specific properties (`l402.lnbits.*`, `l402.lnd.*`) are documented in their respective module READMEs.

For the full property reference and auto-configuration details, see the `l402-spring-autoconfigure` module.

---

## Optional Add-Ons

These modules provide additional capabilities and can be added alongside the starter:

| Module | Purpose | Dependency |
|--------|---------|------------|
| `l402-spring-security` | Spring Security integration for L402 authentication | `com.greenharborlabs:l402-spring-security:0.1.0` |

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
| `l402-core` | [README](../l402-core/README.md) | Pure-Java macaroon and credential library (zero external dependencies) |
| `l402-lightning-lnbits` | [README](../l402-lightning-lnbits/README.md) | LNbits REST backend |
| `l402-lightning-lnd` | [README](../l402-lightning-lnd/README.md) | LND gRPC backend |
| `l402-spring-autoconfigure` | [README](../l402-spring-autoconfigure/README.md) | Auto-configuration, properties, filters, health indicators |
| `l402-spring-security` | [README](../l402-spring-security/README.md) | Spring Security integration |
| `l402-example-app` | [README](../l402-example-app/README.md) | Working reference application |

---

## License

This project is licensed under the [MIT License](../LICENSE).

Copyright (c) 2026 Green Harbor Labs
