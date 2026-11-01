# Upgrade Gradle minor and patch dependencies
Status: working
Base revision: faf58731b08719c943f0a97f2f0e1660e698c825
Review scope: build.gradle.kts, settings.gradle.kts, */build.gradle.kts, gradle/**, gradlew, gradlew.bat, config/dependency-provenance-exceptions.tsv, paygate-*/src/**, docs/security/DEPENDENCY-PROVENANCE-REVIEW-*.md, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md, */README.md

## Goal
Land the dependency updates from Dependabot PR #92 (head `49bc5733`) and PR #11 on `main`, with the provenance controls intact. Close both PRs as superseded.

The versions:

| Dependency | From | To |
| --- | --- | --- |
| Spring Boot (BOM and plugin) | 4.0.8 | 4.1.2 |
| Log4j | 2.25.5 | 2.26.1 |
| Tomcat | 11.0.25 | 11.0.27 |
| Caffeine | 3.2.3 | 3.3.1 |
| gRPC | 1.80.0 | 1.85.1 |
| Protobuf | 4.29.3 | 4.36.2 |
| AssertJ | 3.27.3 | 3.27.7 |
| Byte Buddy | 1.18.8 | 1.18.16 |
| Gradle wrapper | 9.4.1 | 9.8.2 |
| CycloneDX plugin | 3.2.2 | 3.5.1 |
| JMH plugin | 0.7.2 | 0.7.4 |
| Protobuf plugin | 0.9.6 | 0.10.0 |
| build-health plugin | 3.6.1 | 3.20.0 |
| MockWebServer (#11) | 4.12.0 | 5.3.2 |

Acceptance evidence:
- **Dependency verification:** passes from an empty Gradle user home, with no unreviewed trust added.
- **Provenance review record:** every new or removed exception row is recorded in a new review document with source-byte evidence.
- **Builds and tests:**
  - root `check` passes;
  - the integration `test` and `securityTest` suites pass;
  - `buildHealth` and the SBOM task pass.
- **Advisory scan:** a fresh advisory scan has no new unsuppressed findings.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [dependency provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - Never accept generated verification metadata, a key-server identity, or a valid signature from an unauthenticated key as publisher approval by itself.
  - Re-resolve from an isolated Gradle user home after any dependency, checksum, key or exception change.
  - A dependency change needs review before the ledger deadline.
- **CI failure on #92:** every failing check on #92 is dependency verification. The first missing entry is `com.autonomousapps.build-health.gradle.plugin-3.20.0.pom`, which has no `verification-metadata.xml` entry.
- **Ledger expiry:** the 464 existing exception rows expire on 2026-11-22 UTC ([October 15 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md)). This upgrade removes the rows for superseded versions and may add rows for new ones.
- **What the previous reviews established:** they re-verified source bytes but authenticated no publisher identity.
  - The keyring holds 118 group-scoped trusted keys.
  - 146 exception artifacts have signatures that verify, but their keys are unauthenticated.
  - 318 exception artifacts have `NO_PUBKEY`.
- **Advisory suppression:** the CVE-2025-32013 suppression is tied to `0.1.8-SNAPSHOT` and is unaffected unless the project version changes ([disposition](../config/dependency-check-risk-dispositions.md)).
- **MockWebServer:** used only by `paygate-lightning-lnbits` tests.

## Decisions
- **Land on our own branch; close #92 and #11.** Apply #92's version changes at head `49bc5733` and the MockWebServer update on a branch from `main`, then add the provenance and compatibility changes. Rejected alternative: pushing to the Dependabot branch. Dependabot rebases and force-pushes over manual commits, and the provenance changes need review that Dependabot can't carry.
- **No new trust in this task.** Generate candidate entries with `--write-verification-metadata sha256,pgp` from an isolated Gradle user home, then review the diff by hand:
  - **Keep without new trust:** a new artifact signed by a key already trusted for its group keeps its checksum entry.
  - **Never accepted:** any generated `<trusted-key>` addition.
  - **Exact exception row:** an artifact whose signing key is not already trusted, or that is unsigned, gets an exact exception row instead, with its HTTPS source bytes verified by `scripts/audit-dependency-provenance-sources.py`.
  - **Removed:** metadata entries and exception rows for artifacts no longer resolved.
  - Rejected alternative: trusting new publisher keys now. That changes signature trust and needs its own reviewed key-recovery task.
- **New exception rows share the 2026-11-22 deadline**, to be confirmed or changed at approval. One renewal event then covers the whole ledger and is due in three weeks anyway. Rejected alternative: a 30-day deadline only for new rows, which splits the ledger into two renewal cycles.
- **Evidence destination.** A new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md`, in the form of the October 15 review, records the following, and SECURITY.md links it as the current review:
  - the PR #92 head;
  - the added, removed and unchanged row counts;
  - the source-byte audit result;
  - the absence of trusted-key additions.
- **Migrate to `mockwebserver3`.** OkHttp 5 keeps the old `okhttp3.mockwebserver` API only as a deprecated JUnit 4 shim. The two LNbits test files move to `com.squareup.okhttp3:mockwebserver3`. Its test-only OkHttp, Okio and Kotlin stdlib dependencies go through the same provenance review. Rejected alternative: staying on 4.12.0, which is the end of the 4.x line and which Dependabot keeps reopening.
- **Compatibility review, not refactoring.**
  - Read the Spring Boot 4.1.0–4.1.2 release notes and the matching Spring Framework and Spring Security minor-release notes for removed or changed APIs, properties and auto-configuration that Paygate uses.
  - Also check Gradle 9.8 and Protobuf plugin 0.10 for build-script changes.
  - Fix only what fails, or what the notes flag as changed behaviour Paygate relies on. Record each finding in this record.
- **Documentation.** Update the version references in [CLAUDE.md](../CLAUDE.md), the root [README](../README.md) badge and version table, the [LND README](../paygate-lightning-lnd/README.md) dependency table and protoc versions, and the [Spring Security README](../paygate-spring-security/README.md) requirement. Add a CHANGELOG `Changed` entry for the dependency updates.

Approved: 2026-11-01 by Mark Hammond at plan revision faf58731b08719c943f0a97f2f0e1660e698c825. Deadline for new exception rows confirmed: 2026-11-22 UTC (option A, shared with the existing ledger). The plan was rebased unchanged from `20164c7` onto `main` after #101 (LNbits setup-script fix, unrelated paths) merged.

## Tasks
- [ ] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results.
- [ ] Apply #92's version changes and the MockWebServer bump, and migrate the LNbits tests to `mockwebserver3`. Check: `./gradlew compileJava compileTestJava` with verification temporarily in lenient mode in a scratch Gradle home only, never committed.
- [ ] Compatibility review of the Boot 4.1, Framework, Security, Gradle 9.8 and Protobuf plugin 0.10 notes. Check: each relevant change is listed in this record with its disposition.
- [ ] Provenance: generate candidates, review them by hand, add or remove exception rows, and keep the keyring and trusted keys unchanged. Checks:
  - `python3 scripts/audit-dependency-provenance-sources.py` matches every row's source bytes;
  - a diff shows no `<trusted-key>` additions;
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls` passes.
- [ ] Fix compile, test or behaviour regressions. Check: module tests.
- [ ] Review document, SECURITY.md link, version references and CHANGELOG. Check by inspection: no stale version strings remain in the listed docs.
- [ ] Final checks, then record the results:
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew spotlessCheck check --no-daemon --rerun-tasks`
  - `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`
  - `./gradlew buildHealth`
  - `./gradlew cyclonedxBom` (or the release SBOM task)
  - `./gradlew dependencyCheckAggregate --rerun`, which must show no new unsuppressed findings
  - `git diff --check`
- [ ] After merge: close #92 and #11 with a link to the merged PR. (optional)

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved plan, record each check result, and set `Status: review`.
