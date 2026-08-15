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

### T058 — Quickstart sections 1–8

Date/time: 2026-08-15T12:15-04:00 through 2026-08-15T12:17-04:00 (EDT)

Commit: `5eae0b9dc370267917f535678cc33397c126b2d3`; documentation and changelog
updates for T056–T057 were uncommitted in the working tree.

Environment (JDK/Gradle/OS): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64). `NVD_API_KEY` was unset.

Expected result: Each targeted quickstart section passes; section 8 refreshes vulnerability data
and produces current scan evidence before any finding status is promoted.

| Section | Command | Exit status | Observed redacted result | Output/artifact reference |
| --- | --- | --- | --- | --- |
| 1 | `./gradlew :paygate-api:test --tests '*PaymentCredentialTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; lifecycle API regression passed. | `paygate-api/build/reports/tests/test/index.html` |
| 1 | `./gradlew :paygate-protocol-mpp:test --tests '*MppCredentialParserTest' --tests '*MppProtocolTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; parser and protocol ownership regressions passed. | `paygate-protocol-mpp/build/reports/tests/test/index.html` |
| 1 | `./gradlew :paygate-spring-autoconfigure:test --tests '*DualProtocolFilterTest' --tests '*PaygateSecurityFilterZeroizationTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; servlet ownership regressions passed. | `paygate-spring-autoconfigure/build/reports/tests/test/index.html` |
| 1 | `./gradlew :paygate-spring-security:test --tests '*PaygateAuthenticationProviderTest' --tests '*PaygateAuthenticationProviderDelegationTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; Spring Security ownership regressions passed. | `paygate-spring-security/build/reports/tests/test/index.html` |
| 2 | `./gradlew :paygate-core:test --tests '*InMemoryCredentialStoreTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; in-memory store removal and close regressions passed. | `paygate-core/build/reports/tests/test/index.html` |
| 2 | `./gradlew :paygate-spring-autoconfigure:test --tests '*CaffeineCredentialStoreTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; Caffeine store removal and close regressions passed. | `paygate-spring-autoconfigure/build/reports/tests/test/index.html` |
| 3 | `./gradlew :paygate-spring-autoconfigure:test --tests '*TestModeProductionGuardTest' --tests '*TestModeConfigTest' --tests '*BeanOverrideTest' --tests '*PaygateChallengeServiceTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; guarded test-mode matrix passed. | `paygate-spring-autoconfigure/build/reports/tests/test/index.html` |
| 4 | `./gradlew :paygate-lightning-lnd:test --tests '*LndChannelFactoryTest' --tests '*MacaroonClientInterceptorTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; locality and lifecycle regressions passed. Upstream Netty emitted an existing `sun.misc.Unsafe` deprecation warning only. | `paygate-lightning-lnd/build/reports/tests/test/index.html` |
| 4 | `./gradlew :paygate-spring-autoconfigure:test --tests '*AutoConfigurationTest' --tests '*PaygatePropertiesLndTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; Spring LND boundary checks passed. | `paygate-spring-autoconfigure/build/reports/tests/test/index.html` |
| 5 | `./gradlew :paygate-spring-autoconfigure:test --tests '*PaygateResponseWriterTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; servlet safe-header regressions passed. | `paygate-spring-autoconfigure/build/reports/tests/test/index.html` |
| 5 | `./gradlew :paygate-spring-security:test --tests '*PaygateAuthenticationEntryPointTest' --tests '*PaygateAuthenticationFilterTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; Spring Security safe-header regressions passed. | `paygate-spring-security/build/reports/tests/test/index.html` |
| 6 | `./gradlew :paygate-example-app:test --tests '*LocalWalletBootstrapInitializerTest' --tests '*ExampleApplicationPropertiesTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; example bootstrap unit regressions passed. | `paygate-example-app/build/reports/tests/test/index.html` |
| 6 | `cd integration-tests && COMPOSE_FILE=docker-compose-lnbits.yml bash scripts/setup-lnbits.sh` | `0` | LNbits became healthy on host loopback and wrote a disposable key only to ignored `integration-tests/.env`; no key is recorded here. Docker reported existing orphan LND containers without removing them. | Docker Compose service `integration-tests-lnbits-1`; ignored `integration-tests/.env` |
| 6 | `cd integration-tests && COMPOSE_FILE=docker-compose-lnbits.yml bash scripts/start-example-app.sh` | `0` | Example app started with the injected key and its health endpoint became ready. Docker reported the same existing orphan containers without removing them. | Docker Compose service `integration-tests-paygate-example-app-1` |
| 7 | `./gradlew :paygate-integration-tests:test -Pintegration --tests '*PublicSecurityContractTest' --console=plain` | `0` | `BUILD SUCCESSFUL`; public security documentation contract passed. | `paygate-integration-tests/build/reports/tests/test/index.html` |
| 8 | `./gradlew dependencyCheckAggregate --console=plain` | `1` | `dependencyCheckAggregate` failed closed while refreshing NVD data: the NVD client received an API key with length `0` even though `NVD_API_KEY` was unset. It refused to continue using stale local data. | `build/reports/problems/problems-report.html` |

Disposition/follow-up: Sections 1–7 succeeded. Section 8 did not produce current vulnerability
evidence, so T058 remains incomplete and all DeepSeek ledger rows remain `planned`; no row is
promoted to `verified` or `accepted`. Supply a valid nonempty NVD API key or correct the optional-key
configuration so an unset key is not passed as an empty key, then rerun section 8 and the remaining
Phase 7 gates.
