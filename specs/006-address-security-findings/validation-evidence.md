# Validation Evidence: DeepSeek Security Review

This is the versioned command-evidence record for the remediation described in
[quickstart.md](quickstart.md). It records only commands actually run, their environment and
redacted results. A planned or skipped command is not successful evidence and cannot support a
`verified` finding disposition.

## Planned command map

| Quickstart section | Command | Expected result | Evidence record |
| --- | --- | --- | --- |
| 1–2 — Credential lifecycle and cache finality | `./gradlew :paygate-api:test :paygate-protocol-mpp:test :paygate-spring-autoconfigure:test :paygate-spring-security:test :paygate-core:test` | Credential ownership, parser, enforcement, cache removal, and close behavior pass without changing protocol semantics. | [Affected-module baseline](#affected-module-baseline) |
| 3 — Test-mode startup matrix | `./gradlew :paygate-spring-autoconfigure:test --tests "*TestModeProductionGuardTest" --tests "*TestModeConfigTest" --tests "*BeanOverrideTest" --tests "*PaygateChallengeServiceTest"` | Only the complete allowed local configuration enables test completion material. | Planned execution record |
| 4 — Plaintext LND locality | `./gradlew :paygate-lightning-lnd:test --tests "*LndChannelFactoryTest" --tests "*MacaroonClientInterceptorTest"` | Local-target and bounded-macaroon lifecycle checks pass. | Planned execution record |
| 5 — Error-response parity | `./gradlew :paygate-spring-autoconfigure:test --tests "*PaygateResponseWriterTest"` | Covered error responses preserve protocol behavior and include safe headers. | Planned execution record |
| 6 — Example bootstrap safety | `./gradlew :paygate-example-app:test --tests "*LocalWalletBootstrapInitializerTest" --tests "*ExampleApplicationPropertiesTest"` | Explicit, local, bounded bootstrap behavior passes; unsafe paths fail closed. | Planned execution record |
| 7 — Public security contract | `./gradlew :paygate-integration-tests:test -Pintegration --tests "*PublicSecurityContractTest"` | Security documentation and public contracts are consistent. | Planned execution record |
| 8 — Evidence and artifact gates | `./gradlew dependencyCheckAggregate verifyExampleArtifactSafety validateFindingDispositions validateAddressSecurityFindingDispositions` | Dependency, example, and both finding-ledger validators complete with current evidence. | Planned execution record |
| 9 — Affected modules and release gates | `./gradlew :paygate-api:test :paygate-core:test :paygate-protocol-mpp:test :paygate-spring-autoconfigure:test :paygate-spring-security:test :paygate-lightning-lnd:test :paygate-example-app:test` | All modules affected by this feature pass their current unit/context tests. | [Affected-module baseline](#affected-module-baseline) |

## Evidence record schema

Use this complete schema for every execution:

```text
Date/time:
Commit:
Environment (JDK/Gradle/OS):
Command:
Exit status:
Expected result:
Observed redacted result:
Output/artifact reference:
Disposition/follow-up:
```

## Affected-module baseline

### T002 — Pre-change affected-module baseline

Date/time: 2026-08-15T10:49-04:00 through 2026-08-15T10:50-04:00 (EDT)  
Commit: `03b094f485a7f110559465a60302320a0b332232`; executed with the uncommitted Phase 1
evidence artifacts in the working tree.  
Environment (JDK/Gradle/OS): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `./gradlew :paygate-api:test :paygate-core:test :paygate-protocol-mpp:test :paygate-spring-autoconfigure:test :paygate-spring-security:test :paygate-lightning-lnd:test :paygate-example-app:test --rerun-tasks --console=plain`  
Exit status: `0`  
Expected result: All current unit and context tests for modules affected by the feature pass before
remediation changes are made.  
Observed redacted result: Gradle reported `BUILD SUCCESSFUL` in 42 seconds; all seven requested
module `:test` tasks completed. The generated JUnit XML reports record 2,524 tests with 0 failures,
0 errors, and 0 skipped tests. Compilation emitted existing deprecation notices, and test execution
emitted upstream `sun.misc.Unsafe` deprecation warnings from Byte Buddy and Netty; neither failed
the build. No secret material appeared in the captured output.  
Output/artifact reference: Gradle console result for this execution; per-module HTML reports at
`paygate-api/build/reports/tests/test/index.html`,
`paygate-core/build/reports/tests/test/index.html`,
`paygate-protocol-mpp/build/reports/tests/test/index.html`,
`paygate-spring-autoconfigure/build/reports/tests/test/index.html`,
`paygate-spring-security/build/reports/tests/test/index.html`,
`paygate-lightning-lnd/build/reports/tests/test/index.html`, and
`paygate-example-app/build/reports/tests/test/index.html`.  
Disposition/follow-up: Baseline established before user-story remediation. The Phase 1 evidence
documents do not change implementation behavior; begin Phase 3 only when directed.

## Future validation records

No execution recorded yet.
