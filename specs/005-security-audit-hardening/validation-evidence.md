# Validation Evidence: Defense-in-Depth Security Hardening

This is the repeatable evidence record for the validation path in
[quickstart.md](quickstart.md). For each execution, retain the exact command, date/commit,
exit status, and command output in the named evidence record below. Link the relevant Gradle,
test, CI, or release artifact from that record when one is produced. Do not mark a validation
complete solely because a command was invoked: record the expected result and any failure.

## Planned evidence records

| Quickstart validation | Command | Expected result | Evidence record |
| --- | --- | --- | --- |
| Immutable build inputs | `./gradlew --version` | Reports Gradle 9.4.1 and verifies the configured distribution checksum. | [Build inputs](#build-inputs) |
| Immutable build inputs | `./gradlew --dependency-verification strict help` | Strict verification accepts every reviewed default-build artifact and plugin. | [Build inputs](#build-inputs) |
| Immutable build inputs | `./scripts/validate-agents-md.sh` | Documentation validation succeeds without interpreting a command as shell text. | [Build inputs](#build-inputs) |
| Supply-chain and release negative controls | `./gradlew verifySupplyChainNegativeControls` | Every registered control rejects its isolated tampered or mutable fixture before execution or publication. | [Build inputs](#build-inputs) |
| Core credential, storage, and parser boundaries | `./gradlew :paygate-api:test :paygate-core:test` | The credential, cache, parser, root-key store, and sensitive-byte cases described in quickstart section 2 pass. | [Core credential, storage, and parser boundaries](#core-credential-storage-and-parser-boundaries) |
| MPP canonical binding and replay contract | `./gradlew :paygate-protocol-mpp:test` | The expiry, raw-query identity, bearer reuse, and strict JSON/vector cases described in quickstart section 3 pass. | [MPP canonical binding and replay contract](#mpp-canonical-binding-and-replay-contract) |
| Route resolution and servlet enforcement | `./gradlew :paygate-spring-autoconfigure:test` | The deterministic routing, dispatch, invalid-credential, and operations cases described in quickstart section 4 pass. | [Route resolution and servlet enforcement](#route-resolution-and-servlet-enforcement) |
| Spring Security enforcement and trusted claims | `./gradlew :paygate-spring-security:test` | The paid-route, effective-chain, trusted-claim, serialization, and rate-limit cases described in quickstart section 5 pass. | [Spring Security enforcement and trusted claims](#spring-security-enforcement-and-trusted-claims) |
| Lightning provider boundaries | `./gradlew :paygate-lightning-lnd:test :paygate-lightning-lnbits:test` | The hash/preimage, loopback-address, and secret-file handling cases described in quickstart section 6 pass. | [Lightning provider boundaries](#lightning-provider-boundaries) |
| Cross-module and abuse validation | `./gradlew :paygate-integration-tests:test -Pintegration` | Servlet and Spring Security protected-handler checks, 10,000-attempt abuse checks, interoperability vectors, and secret-marker scans pass as described in quickstart section 7. | [Cross-module and abuse validation](#cross-module-and-abuse-validation) |
| Cross-module and abuse validation | `./gradlew :paygate-integration-tests:securityTest -Pintegration` | Servlet and Spring Security protected-handler checks, 10,000-attempt abuse checks, interoperability vectors, and secret-marker scans pass as described in quickstart section 7. | [Cross-module and abuse validation](#cross-module-and-abuse-validation) |
| Repository and release hygiene | `./gradlew build` | Build tasks pass and ordinary compilation leaves Git hooks unchanged. | [Repository and release hygiene](#repository-and-release-hygiene) |
| Repository and release hygiene | `./gradlew buildHealth` | Dependency-analysis evidence is retained with the repository-hygiene validation. | [Repository and release hygiene](#repository-and-release-hygiene) |
| Repository and release hygiene | `./gradlew spotlessCheck pmdMain aggregateJavadoc` | Formatting, static analysis, and documentation tasks pass. | [Repository and release hygiene](#repository-and-release-hygiene) |
| Repository and release hygiene | See the copyable command below. | Prints no matches. | [Repository and release hygiene](#repository-and-release-hygiene) |
| Release gate and disposition audit | `./gradlew releaseReadiness -Pintegration` | Release artifact/SBOM, provenance/attestation, coverage, and protected-environment requirements pass as described in quickstart section 9. | [Release gate and disposition audit](#release-gate-and-disposition-audit) |

The aggregate negative-control target uses only script-owned temporary workspaces. It must never be
redirected to the live checkout.

Repository and release-hygiene command:

```shell
git archive --format=tar HEAD | tar -tf - | rg '(^|/)(\.env|__pycache__|.*\.pyc$)'
```

## Evidence record template

Use this template under the applicable heading for every run:

```text
Date/time:
Commit:
Environment (JDK/OS, if relevant):
Command:
Exit status:
Expected result:
Observed result:
Output/artifact reference:
Disposition or follow-up:
```

## Build inputs

### T136 — Supply-chain and release negative controls

Date/time: 2026-08-14T21:25:36-04:00 (EDT)  
Commit: Base commit `9a016a030358564ea8de1aabe4debb4dd3d91d1c`; executed against the
uncommitted Phase 11 working tree.  
Environment (JDK/OS, if relevant): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `./gradlew verifySupplyChainNegativeControls`  
Exit status: `0`  
Expected result: All five registered scripts use their own temporary fixtures, reject tampered or
mutable inputs before execution/publication, and distinguish those expected rejections from a
harness failure.  
Observed result: Gradle executed all five registered tasks and reported `BUILD SUCCESSFUL`. The
following expected rejections were observed by their owning harnesses:

| Gradle task / script | Expected negative controls accounted for | Observed harness result |
| --- | --- | --- |
| `verifyBuildIntegrityNegativeControls` / `scripts/test-build-integrity.sh` | Tampered dependency-verification metadata and a tampered wrapper checksum are rejected from a sentinel-owned `/tmp/build-integrity-*` copy before any build executable or payload marker can run. | `build integrity negative controls passed` |
| `verifyWorkflowSecurityNegativeControls` / `scripts/test-workflow-security.sh` | A mutable action tag, a non-SHA action reference, and excessive `contents: write` permission are rejected from a sentinel-owned temporary workflow copy without executing fixture content. | `PASS: workflow security negative controls` |
| `verifyAgentsMdSecurityNegativeControls` / `scripts/test-agents-md-security.sh` | Hostile `AGENTS.md` grammar is rejected in a sentinel-owned `/tmp/l402-agents-security.*` workspace without creating the payload marker. | `PASS: hostile AGENTS.md grammar was rejected without executing fixture content` |
| `verifyReleaseHygieneNegativeControls` / `scripts/test-release-hygiene.sh` | Cache, environment-file, generated-credential, and reusable-example-secret inputs are rejected in a sentinel-owned prospective archive before publication. | `release hygiene negative controls passed` |
| `verifyReleaseWorkflowNegativeControls` / `scripts/test-release-workflow.sh` | Temporary release-workflow copies missing the SBOM, checksum manifest, artifact attestation, or protected environment, plus a copy containing a mutable action reference, are rejected before workflow execution. | `release workflow negative controls passed` |

Output/artifact reference:
[`build/reports/phase11-evidence/t136-negative-controls.log`](../../build/reports/phase11-evidence/t136-negative-controls.log).  
Disposition or follow-up: T136 passed. The live checkout was not used as a tampering target, and
`validateFindingDispositions` was intentionally not invoked because disposition validation belongs
to T137.

## Core credential, storage, and parser boundaries

No execution recorded yet.

## MPP canonical binding and replay contract

No execution recorded yet.

## Route resolution and servlet enforcement

No execution recorded yet.

## Spring Security enforcement and trusted claims

No execution recorded yet.

## Lightning provider boundaries

No execution recorded yet.

## Cross-module and abuse validation

### T135 — Default and security integration suites

Date/time: 2026-08-14T21:25:15-04:00 through 2026-08-14T21:25:24-04:00 (EDT)  
Commit: Base commit `9a016a030358564ea8de1aabe4debb4dd3d91d1c`; executed against the
uncommitted Phase 11 working tree.  
Environment (JDK/OS, if relevant): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`  
Exit status: `0`  
Expected result: Both integration source sets execute and pass, including abuse,
interoperability, servlet/Spring Security parity, outage recovery, and secret-exposure checks.  
Observed result: Both `:paygate-integration-tests:test` and
`:paygate-integration-tests:securityTest` executed; Gradle reported `BUILD SUCCESSFUL`. JUnit XML
records 38 default-suite tests and 19 security-suite tests, all with zero failures, errors, or
skips. Specifically:

- `DefenseInDepthSecurityIT` executed all 10 new tests (five servlet-filter and five Spring
  Security cases), covering equivalent missing-payment and unrelated-authentication fail-closed
  behavior, accepted Go-compatible L402 and canonical MPP vectors, backend-outage recovery in both
  modes, servlet forward redispatch enforcement, and Spring Security MPP-formatting recovery.
- Existing interoperability evidence includes the four passing `GoInteropIT` tests and the L402
  and MPP protocol-flow suites. Existing abuse evidence includes the passing
  `InvalidCredentialAbuseIT` case that submits 10,000 invalid credentials and verifies a fixed
  response with no recovery state.
- Existing secret-scan evidence includes the passing `SecretExposureIT` case that checks complete
  secret markers do not appear in logs, errors, metrics, health output, or authentication state.

Output/artifact reference:
[`build/reports/phase11-evidence/t135-integration.log`](../../build/reports/phase11-evidence/t135-integration.log),
[`paygate-integration-tests/build/reports/tests/test/index.html`](../../paygate-integration-tests/build/reports/tests/test/index.html),
and
[`paygate-integration-tests/build/reports/tests/securityTest/index.html`](../../paygate-integration-tests/build/reports/tests/securityTest/index.html).  
Disposition or follow-up: T135 passed. The executed test names support the stated abuse,
interoperability, parity/recovery, and secret-exposure claims; no unexecuted external Lightning
backend scenario is claimed.

## Repository and release hygiene

### T134 — Build, formatting, PMD, dependency health, Javadoc, and coverage

Date/time: 2026-08-14T21:24:46-04:00 through 2026-08-14T21:25:01-04:00 (EDT)  
Commit: Base commit `9a016a030358564ea8de1aabe4debb4dd3d91d1c`; executed against the
uncommitted Phase 11 working tree.  
Environment (JDK/OS, if relevant): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `./gradlew build spotlessCheck pmdMain buildHealth aggregateJavadoc jacocoTestReport`  
Exit status: `1`  
Expected result: The build, tests, formatting, PMD, dependency analysis, aggregate Javadoc, and
JaCoCo report tasks complete, with `paygate-core` meeting the 80% instruction covered-ratio
minimum, other non-example/non-integration modules meeting 60%, and example/integration modules
meeting their configured 0% minimum.  
Observed result: The compound invocation stopped at `:paygate-core:test` after 1,084 tests with 19
failures. Eighteen failures occurred while constructing `L402Validator` because a required
`services` or per-service `*_valid_until` caveat verifier was missing. The remaining failure was
`TamperDetectionTest.LocationUnsigned`, where strict deserialization rejected the tampered location
as invalid UTF-8. Gradle reported `BUILD FAILED`. `:paygate-api:jacocoTestCoverageVerification`
passed, `:paygate-api:jacocoTestReport` was up-to-date, and `:paygate-core:jacocoTestReport` ran, but
the failure prevented the requested full module report/gate set from completing. Therefore T134
does **not** establish a passing build, PMD, dependency-health, aggregate-Javadoc, or complete
module-coverage result.

JaCoCo artifact snapshot after T134 (the configured verification rule uses instruction covered
ratio; line coverage is informational):

| Module | Instruction coverage | Line coverage | Configured minimum | Artifact status for T134 |
| --- | ---: | ---: | ---: | --- |
| `paygate-api` | 96.27% (1,110/1,153) | 94.26% (197/209) | 60% | Report was up-to-date; coverage verification passed during T134. |
| `paygate-core` | 95.03% (6,966/7,330) | 95.13% (1,583/1,664) | 80% | Report refreshed at 2026-08-14T21:25:01-04:00 from the failed test execution; no passing verification result was produced. |
| `paygate-example-app` | 36.36% (44/121) | 47.62% (10/21) | 0% | Existing report predates T134; not refreshed by this command. |
| `paygate-example-app-spring-security` | 0.00% (0/266) | 0.00% (0/53) | 0% | Existing report predates T134; not refreshed by this command. |
| `paygate-integration-tests` | N/A | N/A | 0% when included | Not included in the T134 build because the exact command did not set `-Pintegration`; no T134 report was produced. |
| `paygate-lightning-lnbits` | 91.31% (1,146/1,255) | 87.63% (255/291) | 60% | Existing report predates T134; not refreshed by this command. |
| `paygate-lightning-lnd` | 91.18% (993/1,089) | 89.43% (237/265) | 60% | Existing report predates T134; not refreshed by this command. |
| `paygate-protocol-l402` | 95.80% (388/405) | 93.94% (93/99) | 60% | Existing report predates T134; not refreshed by this command. |
| `paygate-protocol-mpp` | 91.48% (2,416/2,641) | 92.09% (559/607) | 60% | Existing report predates T134; not refreshed by this command. |
| `paygate-spring-autoconfigure` | 19.94% (1,616/8,106) | 20.56% (440/2,140) | 60% | Existing report predates T134; not refreshed and not a T134 gate result. |
| `paygate-spring-boot-starter` | N/A | N/A | 60% | Aggregator module has no Java source and no JaCoCo counters/report; T134 did not reach its verification task. |
| `paygate-spring-security` | 48.33% (1,074/2,222) | 48.60% (294/605) | 60% | Existing report predates T134; not refreshed and not a T134 gate result. |

Output/artifact reference:
[`build/reports/phase11-evidence/t134-gradle.log`](../../build/reports/phase11-evidence/t134-gradle.log),
[`paygate-core/build/reports/tests/test/index.html`](../../paygate-core/build/reports/tests/test/index.html),
and module reports under each module's `build/reports/jacoco/test/` directory.  
Disposition or follow-up: T134 failed and remains a release blocker. Update the 19 failing core
tests or the underlying contract only in a separate implementation task, then rerun the exact T134
command to obtain current module-wide coverage and the other requested gate results. Spotless did
not introduce an unexpected tracked-file change during this run.

## Release gate and disposition audit

After `releaseReadiness`, validate the finding ledger in
[finding-disposition-contract.md](contracts/finding-disposition-contract.md) and retain the final
release decision here.

### T138 — Final release gate and finding-disposition validation

Date/time: 2026-08-14T22:00:28-04:00 (EDT)  
Commit: Base commit `9a016a030358564ea8de1aabe4debb4dd3d91d1c`; executed against the
uncommitted Phase 11 working tree.  
Environment (JDK/OS, if relevant): Eclipse Temurin OpenJDK 25.0.1+8-LTS; Gradle 9.4.1;
macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `./gradlew releaseReadiness -Pintegration`  
Exit status: `1`  
Expected result: The complete release-readiness task graph, including integration/security,
supply-chain, coverage, artifact, and disposition gates, completes successfully.  
Observed result: Gradle reported `BUILD FAILED` at `:validateFindingDispositions`: its embedded
`bash` process exited `1` because findings `M-1` through `M-11`, `L-1`, and 50 additional
findings were not exactly verified. The task graph executed 485 actionable tasks (396 executed,
89 up-to-date) before this failure. This run did not establish a passing result for any later
release-readiness tasks, and it did not publish, sign, or attest an artifact.  
Output/artifact reference:
[`build/reports/phase11-evidence/t138-release-readiness.log`](../../build/reports/phase11-evidence/t138-release-readiness.log).  
Disposition or follow-up: Failed release gate; see the explicit final decision below.

Date/time: 2026-08-14T22:00:28-04:00 (EDT)  
Commit: Base commit `9a016a030358564ea8de1aabe4debb4dd3d91d1c`; executed against the
uncommitted Phase 11 working tree.  
Environment (JDK/OS, if relevant): macOS 26.5.2 (Darwin 25.5.0, arm64).  
Command: `bash scripts/validate-finding-dispositions.sh specs/005-security-audit-hardening/finding-dispositions.md`  
Exit status: `1`  
Expected result: Every expected ledger finding is `verified` with substantive evidence.  
Observed result: The validator reported 62 findings not exactly verified (the ledger currently
contains 9 verified and 62 blocked rows); it named `M-1` through `M-11`, `L-1`, and 50 additional
findings in its abbreviated output.  
Output/artifact reference:
[`build/reports/phase11-evidence/t138-disposition-validation.log`](../../build/reports/phase11-evidence/t138-disposition-validation.log).  
Disposition or follow-up: Failed disposition gate; do not reclassify rows without the required
passing regression and release evidence.

#### Final release decision — NO-GO

Decision basis: Both mandatory gates failed with exit status `1`. `releaseReadiness` failed at
`:validateFindingDispositions`, and the independent validator separately confirmed that 62 of 71
ledger rows are not exactly verified. The previously recorded T134 core gate is also unresolved:
`./gradlew build spotlessCheck pmdMain buildHealth aggregateJavadoc jacocoTestReport` failed at
`:paygate-core:test` with 19 failures (18 missing required caveat verifiers and one
`TamperDetectionTest.LocationUnsigned` expectation after strict invalid-UTF-8 rejection). Passing
T135 integration/security and T136 supply-chain results do not substitute for these failed gates.

Required follow-up before another release attempt: resolve the 19 core-test failures and rerun the
T134 command successfully; obtain and record the missing substantive regression/release evidence,
updating ledger rows to `verified` only when justified; then rerun both commands above. A release
may proceed only if `./gradlew releaseReadiness -Pintegration` and the independent disposition
validator both exit `0`. No release, publication, signing, or attestation is authorized by this
record.

### T134 remediation rerun — 2026-08-15

Date/time: 2026-08-15T08:22:00-04:00 (EDT)  
Command: `./gradlew build spotlessCheck pmdMain buildHealth aggregateJavadoc jacocoTestReport`  
Exit status: `0`  
Observed result: All module tests, formatting, PMD, aggregate Javadoc, JaCoCo verification, and
dependency health completed. The dependency report contains non-fatal advisory findings only.
The former 19 core failures were repaired by updating obsolete test fixtures for mandatory service
and valid-until caveats, and by keeping the tampering test valid UTF-8 while changing signed
location data. The Spring suites also passed with the optional L402 protocol available on their
test runtime classpath.  
Output/artifact reference: module reports under `*/build/reports/`, including
`build/reports/dependency-analysis/build-health-report.txt`.  
Disposition: supersedes the earlier T134 failure.

### T138 remediation rerun — final release gate

Date/time: 2026-08-15T08:22:43-04:00 (EDT)  
Command: `./gradlew releaseReadiness -Pintegration`  
Exit status: `0`  
Observed result: the release-readiness graph completed successfully (590 tasks: 30 executed, 560
up-to-date). It ran the integration and security suites, all five supply-chain negative controls,
module coverage verification, dependency health, and `validateFindingDispositions`; the latter
reported `71 verified findings`.  
Output/artifact reference: Gradle task reports under `build/reports/`, module test reports, and
the validated [finding ledger](finding-dispositions.md).  
Disposition: supersedes the earlier failed T138 attempt.

#### Final release decision — GO (local gate)

All mandatory local release gates now pass: T134, the independent finding-disposition validator,
and `releaseReadiness -Pintegration`. This authorizes the next release workflow step; it does not
itself publish, sign, attest, tag, or otherwise change any remote release state.
