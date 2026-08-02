<!--
  Sync Impact Report
  Version change: 1.0.0 -> 2.0.0
  Modified principles:
    - I. Zero-Dependency Core -> I. Dependency-Bounded Foundations
    - II. Interoperability First -> II. Protocol Compatibility and Explicit Deviations
    - III. Fail Closed -> III. Fail Closed and Bound Resource Use
    - IV. Spring Boot Conventions -> IV. Idiomatic, Replaceable Spring Integration
    - V. Test-Driven with Reference Vectors -> V. Verification Is Part of the Contract
  Added sections: None
  Removed sections: None
  Renamed sections:
    - Technology Constraints -> Engineering Constraints and Quality Gates
  Follow-up TODOs: None
-->

# spring-boot-starter-paygate Constitution

## Core Principles

### I. Dependency-Bounded Foundations

`paygate-api` MUST have no external production dependencies. `paygate-core` MAY depend on
`paygate-api` but MUST otherwise use only JDK production APIs. Test-only dependencies do not count
against these boundaries. Cryptography, sensitive-memory handling, and protocol-neutral public
types MUST remain outside Spring and backend-specific modules. `paygate-protocol-mpp` MUST depend
only on `paygate-api`; the L402 implementation MAY depend on both `paygate-api` and `paygate-core`.
New dependencies MUST be placed in the narrowest module that requires them and MUST NOT leak into
framework-neutral APIs. These boundaries keep the security-critical foundation portable,
auditable, and resistant to dependency conflicts.

### II. Protocol Compatibility and Explicit Deviations

Wire behavior MUST be deterministic and covered by compatibility tests. L402 Macaroon V2
serialization MUST remain byte-compatible with Go `go-macaroon`; key derivation MUST use
`HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`; and identifiers MUST use
`[version:2 bytes big-endian][payment_hash:32 bytes][token_id:32 bytes]`. The MPP implementation
MUST follow the supported `draft-ryan-httpauth-payment` behavior, use RFC 8785 canonical JSON and
unpadded base64url where specified, and authenticate challenge fields with HMAC-SHA256. Changes to
either wire format, authentication scheme, canonicalization rule, challenge, credential, or
receipt MUST include reference or contract vectors. A deliberate specification deviation MUST be
documented with its rationale and tested; an undocumented deviation is a defect. Draft-protocol
changes MUST be isolated behind `PaymentProtocol` rather than weakening stable L402 behavior.

### III. Fail Closed and Bound Resource Use

Protected content MUST reach application handlers only after an enabled protocol validates the
presented credential. Missing credentials MAY produce a payment challenge only when the Lightning
backend can create a valid invoice. Backend unavailability, invoice creation failure, unexpected
validation failure, or ambiguous security state MUST produce a non-success response and MUST NOT
continue the protected filter chain; infrastructure failures MUST map to HTTP 503. Malformed and
invalid credentials MUST use stable, protocol-appropriate errors without exposing sensitive
validation details. Inputs not understood by the implementation MUST be rejected unless the
governing protocol explicitly defines them as ignorable; every such exception MUST be documented
and tested. Challenge issuance, authentication failures, request bodies, cache growth, and
credential lifetimes MUST remain bounded by configured limits.

### IV. Idiomatic, Replaceable Spring Integration

Spring integration MUST use the `paygate.*` configuration namespace and typed
`@ConfigurationProperties`. Payment enforcement MUST remain disabled unless
`paygate.enabled=true`. Auto-configuration MUST be conditional on the required classes,
properties, and beans, and user-facing collaborators MUST be replaceable through
`@ConditionalOnMissingBean`. Servlet and Spring Security modes MUST be selected through
`L402SecurityModeResolver` (or its documented successor) and MUST NOT install duplicate enforcement
paths. Protocols MUST integrate through the `PaymentProtocol` SPI, and Lightning providers MUST
integrate through `LightningBackend`; adding one implementation MUST NOT require changes to
unrelated implementations. Optional integrations such as Actuator, Micrometer, Caffeine, protocol
modules, and Lightning backends MUST remain optional at runtime. Every public configuration change
MUST update configuration metadata, documentation, defaults, and binding tests together.

### V. Verification Is Part of the Contract

Every behavior change MUST include automated tests that fail without the change and pass with it.
Security-sensitive code MUST test malformed input, tampering, expiry, wrong secrets or preimages,
error mapping, and concurrency where shared state exists. Cryptographic and serialization changes
MUST include known-good cross-language or standards-derived vectors and binary edge cases. Module
contract changes MUST include tests at the module boundary; Spring wiring changes MUST include
application-context or request-level tests; and backend changes MUST include failure-path tests.
Coverage MUST remain at least 80% for `paygate-core` and 60% for other non-example,
non-integration modules. Example and integration modules MAY retain a 0% coverage gate, but their
behavioral tests MUST still pass. A release is not ready until `releaseReadiness -Pintegration`
passes.

## Security Requirements

- Secret-dependent comparisons MUST use constant-time XOR accumulation, never `Arrays.equals()`.
- Sensitive byte arrays MUST be owned explicitly, exposed only as defensive copies, and zeroized
  with `SensitiveBytes`, `KeyMaterial`, or `CryptoUtils` as soon as their use ends.
- JCA primitives such as `Mac` MUST be obtained fresh for each operation. They MUST NOT be cached in
  `ThreadLocal` state, which is unsafe for virtual-thread-heavy applications.
- Full macaroons, authorization headers, preimages, root keys, backend credentials, and MPP binding
  secrets MUST NOT be logged. If a macaroon must be correlated, diagnostics MUST use a sanitized
  correlation identifier or at most the first eight bytes of its token ID.
- File-backed root-key storage MUST use atomic writes and restrictive permissions: `0700` for key
  directories and `0600` for key files. Secret configuration MUST support environment variables or
  an equivalent external secret source.
- Test mode MUST refuse to start when either `prod` or `production` is active. Plaintext Lightning
  transport MUST be rejected outside explicitly configured local or test scenarios.
- Client-supplied paths, headers, caveats, JSON, forwarded addresses, and error details MUST be
  bounded, normalized, and sanitized before comparison, reflection, or logging. Forwarded headers
  MUST be trusted only when the request originates from a configured trusted proxy.

## Engineering Constraints and Quality Gates

- Production code MUST target Java 25 and use the repository's configured Spring Boot 4.0.x and
  Spring Framework 7.x baselines. Builds MUST use the Gradle Kotlin DSL multi-module structure.
- Public APIs MUST have Javadoc. Java code MUST follow Google Java Format, compile without warnings
  introduced by the change, and pass Spotless, PMD, dependency analysis, and applicable JaCoCo
  verification.
- Immutable data carriers SHOULD be records when record semantics fit. Bounded hierarchies SHOULD
  use sealed types, and clear local inference SHOULD use `var`; deviations MAY be used when they
  improve API compatibility, readability, or framework behavior.
- Changes MUST preserve module direction: framework-neutral modules cannot depend on Spring,
  protocol implementations cannot depend on one another, and backend implementations cannot be
  required by core or API modules.
- The default build MUST remain usable without integration infrastructure. Integration suites MUST
  stay opt-in through `-Pintegration`, while the release gate MUST run them explicitly.
- Public behavior, configuration, compatibility claims, and operational requirements MUST be kept
  synchronized across code, README documentation, configuration metadata, and release notes.

## Governance

This constitution is the highest-priority project development policy. More specific repository
guidance MAY add constraints but MUST NOT weaken these principles. An amendment MUST include a
documented rationale, identify affected specifications and plans, describe any required migration,
and update the Sync Impact Report. Maintainers MUST approve amendments through the normal review
process before dependent work is considered compliant.

Constitution versions follow semantic versioning: MAJOR for removal or incompatible redefinition
of a principle or governance rule, MINOR for a new principle or materially expanded obligation,
and PATCH for a clarification that does not change required behavior. The ratification date remains
the original adoption date; the last-amended date changes whenever constitution content changes.

Every specification and implementation plan MUST include a constitution check before work begins.
Every code review MUST verify applicable dependency boundaries, protocol contracts, fail-closed
behavior, security handling, and test evidence. Any necessary exception MUST be explicit, narrowly
scoped, risk-assessed, and recorded in the plan's Complexity Tracking section before implementation.
Release review MUST reject unresolved violations unless a constitution amendment has already been
approved.

**Version**: 2.0.0 | **Ratified**: 2026-03-08 | **Last Amended**: 2026-08-02
