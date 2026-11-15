# Land Gradle dependency updates from Dependabot #105 and #104
Status: working
Base revision: 230b3600ea724f9b4908830822ba82a7125dfc8e
Review scope: build.gradle.kts, settings.gradle.kts, */build.gradle.kts, gradle/verification-metadata.xml, config/dependency-provenance-exceptions.tsv, .github/dependabot.yml, docs/security/DEPENDENCY-PROVENANCE-REVIEW-*.md, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md, */README.md

## Goal
Land the safe subset of Dependabot PRs #105 (gradle-minor-and-patch group) and #104 (Spotless) on our own branch, with the provenance controls intact. Close both PRs as superseded.

| Dependency | From | To | In scope |
| --- | --- | --- | --- |
| Byte Buddy (`byte-buddy`, `byte-buddy-agent`) | 1.18.16 | 1.18.17 | yes |
| build-health plugin | 3.20.0 | 3.20.1 | yes |
| Protobuf Gradle plugin | 0.10.0 | 0.10.1 | yes |
| Spotless plugin (#104) | 7.2.1 | 8.10.4 | yes |
| gRPC (5 artifacts) | 1.84.1 | 1.85.1 | **no**: stays on Boot's managed 1.84.1 |
| `mockwebserver3` | 5.3.2 | 5.5.1 | **no**: held at 5.3.2 (see Decisions) |

Acceptance evidence:
- **Dependency verification:** passes from an empty Gradle user home, with no trust added.
- **Provenance review record:** every new or removed exception row is recorded with source-byte evidence.
- **Builds and tests:**
  - root `spotlessCheck check` passes, and Spotless 8 reports no reformatting of existing sources;
  - the integration `test` and `securityTest` suites pass;
  - `buildHealth` and the SBOM task pass.
- **Advisory scan:** a fresh advisory scan has no new unsuppressed findings.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [dependency provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - Never accept generated verification metadata, a key-server identity, or a valid signature from an unauthenticated key as publisher approval.
  - Re-resolve from an isolated Gradle user home after any dependency, checksum, key or exception change.
- **Why #105 and #104 fail:** every failing check is dependency verification. The first missing entry is `com.autonomousapps.build-health.gradle.plugin-3.20.1.pom` (#105 run `42575781427`). Dependabot can't update `verification-metadata.xml`.
- **gRPC follows Spring Boot's managed version** ([2026-11-01 amendment](2026-11-01-upgrade-gradle-minor-and-patch-dependencies.md#decisions)). Boot 4.1.2 is still the latest release and manages gRPC 1.84.1, so #105's 1.85.1 pins would reintroduce the mixed-version classpath that amendment removed.
- **Validator rule** ([2026-11-01 tighten record](2026-11-01-tighten-dependency-provenance-validator-and-renew-ledger.md#decisions)):
  - every checksum-only artifact needs an exact row;
  - a dependency change needs a per-artifact detached-signature classification;
  - an artifact under a covering scope but signed by another key gets `reason="Signature is not from a key trusted for these coordinates"`.
- **ID space:** IDs must match `EX-[0-9]{3}`. The ledger ends at EX-860, so 139 IDs remain (EX-861..EX-999). Spotless 8 may bring many new transitive artifacts.
- **Ledger expiry:** all 575 rows expire on 2026-12-01 UTC. Renewing them is a separate task, but this task's new rows are part of that renewal.
- **Open trust decision, unchanged here:** 14 trusted keyring keys are revoked or expired and still count as coverage ([November 3 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md)).
- **Spotless pins `googleJavaFormat("1.35.0")`**, so the major plugin bump should not by itself change formatting output.

## Decisions
- **Land on our own branch; close #105 and #104 after merge.** This follows the [#92 precedent](2026-11-01-upgrade-gradle-minor-and-patch-dependencies.md#decisions): Dependabot force-pushes over manual commits and can't carry provenance review.
- **Hold `mockwebserver3` at 5.3.2.** This is a finding from planning, checked against Maven Central and GitHub on 2026-11-15:
  - **Signing key changed:** 5.3.2's signature is from `793FD5751A0F0780`, the Square key the keyring already trusts for `com.squareup.okio`. 5.5.1's signature is from a different key, `6751E1A6E2001B8D`.
  - **Source moved:** in the same release, the POM's SCM and site moved from `github.com/square/okhttp` to `github.com/lysine-dev/okhttp` and `lysine.dev`.
  - **Repo transferred:** GitHub redirects `square/okhttp` (created 2012) to `lysine-dev/okhttp`, owned by an unverified org created 2026-03-02.
  - **Why hold:** the move may be a legitimate change of maintainers, but nothing reviewed authenticates the new key or the new steward. The dependency is test-only (LNbits tests) and the update brings no fix Paygate needs.
  - **Rejected: take 5.5.1 with exact exception rows.** Policy permits it, since rows record source bytes and don't claim publisher identity. But it would knowingly adopt artifacts from an unauthenticated new key on a transferred project, for no benefit.
  - **Revisit:** a separate key-recovery task can authenticate the transfer and the new key.
- **Keep gRPC at 1.84.1.** Drop #105's gRPC and `protoc-gen-grpc-java` changes.
- **Stop Dependabot reproposing held updates.** Add `ignore` entries to the Gradle block of `.github/dependabot.yml`, each with a comment giving the reason and a link here:
  - `io.grpc:*`, since Boot bumps carry gRPC;
  - `com.squareup.okhttp3:*`, until the key-recovery task.
  - Rejected alternative: leaving Dependabot unchanged. It reopens the same failing PRs every Monday.
- **No new trust.** Same method as the [November 1 upgrade](2026-11-01-upgrade-gradle-minor-and-patch-dependencies.md#decisions):
  - generate candidates with `--write-verification-metadata sha256,pgp` from an isolated Gradle home, then review the diff by hand;
  - discard every generated `<trusted-key>`, `<trusting>` and `<pgp>` addition;
  - a new artifact signed by an already-trusted key for its group keeps only its checksum entry;
  - a new unsigned artifact, or one signed by an untrusted key, gets an exact row with its HTTPS source bytes verified by `scripts/audit-dependency-provenance-sources.py`;
  - remove metadata entries and rows for artifacts that nothing resolves any more.
- **New rows:** IDs from EX-861 upward, with the existing owner and trigger. They share the **2026-12-01 UTC** deadline, so the ledger keeps one renewal cycle.
  - **Stop and ask** if more than 139 IDs are needed. Widening the ID format is a validator change outside this scope.
- **Spotless 8 is conditional.**
  - Read the 8.0.0–8.10.4 changelog for breaking changes to the APIs this build uses (`googleJavaFormat`, `removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`, `targetExclude`), and adapt only the build script.
  - **Stop and ask** if `spotlessCheck` would reformat any existing source, or if Spotless needs a newer `googleJavaFormat`. Rejected alternative: reformatting the codebase in a dependency task.
- **Compatibility review, not refactoring.** Read the Byte Buddy 1.18.17, build-health 3.20.1 and Protobuf plugin 0.10.1 release notes, record each relevant item and its disposition here, and fix only what fails. `paygate-*/src/**` is out of scope. A needed source change means a revised plan.
- **Documentation:**
  - a new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md`, in the form of the November 1 review, recording:
    - the PR heads;
    - the row counts added, removed and unchanged;
    - the audit result;
    - that no trusted key was added;
    - the OkHttp stewardship and key finding;
  - SECURITY.md links it as the current review;
  - a CHANGELOG `Changed` entry;
  - version references (the [LND README](../paygate-lightning-lnd/README.md) Protobuf plugin version, and any others found).

Approved: 2026-11-15 by Mark Hammond at plan revision 230b3600ea724f9b4908830822ba82a7125dfc8e. This confirms holding `mockwebserver3` at 5.3.2, the two Dependabot ignores, and the shared 2026-12-01 UTC deadline for new rows.

## Tasks
- [ ] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results.
- [ ] Apply the in-scope version changes. Check: `./gradlew compileJava compileTestJava`, using lenient verification only in a scratch Gradle home and never committing it.
- [ ] Spotless 8 changelog review and build-script adaptation. Checks:
  - `spotlessCheck` passes;
  - `git diff --stat -- 'paygate-*/src/**'` is empty.
- [ ] Compatibility review of the other three release notes. Check: each relevant item is listed here with its disposition.
- [ ] Provenance: generate candidates, review them by hand, classify each new artifact's detached signature, and add or remove rows. Checks:
  - `python3 scripts/audit-dependency-provenance-sources.py` matches every row's source bytes;
  - a diff of `gradle/verification-keyring.keys` and the `<trusted-keys>` block is empty;
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls` passes.
- [ ] Add the Dependabot `ignore` entries. Check: `.github/dependabot.yml` parses (YAML load) and lists only the two intended ignores.
- [ ] Review document, SECURITY.md link, version references and CHANGELOG. Check by inspection: no stale version strings remain in the listed docs.
- [ ] Final checks, then record the results:
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew spotlessCheck check --no-daemon --rerun-tasks`
  - `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`
  - `./gradlew buildHealth`
  - `./gradlew cyclonedxBom`
  - `./gradlew dependencyCheckAggregate --rerun`, which must show no new unsuppressed findings
  - `git diff --check`
- [ ] After merge: close #105 and #104 with a link to the merged PR, noting what was held and why. (optional)

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved tasks.
