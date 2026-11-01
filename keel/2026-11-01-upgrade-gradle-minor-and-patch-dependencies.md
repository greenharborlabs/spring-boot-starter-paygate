# Upgrade Gradle minor and patch dependencies
Status: review
Base revision: faf58731b08719c943f0a97f2f0e1660e698c825
Review scope: build.gradle.kts, settings.gradle.kts, */build.gradle.kts, gradle/**, gradlew, gradlew.bat, config/dependency-provenance-exceptions.tsv, scripts/test-dependency-provenance.sh, paygate-*/src/**, docs/security/DEPENDENCY-PROVENANCE-REVIEW-*.md, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md, */README.md

## Goal
Land the dependency updates from Dependabot PR #92 (head `49bc5733`) and PR #11 on `main`, with the provenance controls intact. Close both PRs as superseded.

The versions:

| Dependency | From | To |
| --- | --- | --- |
| Spring Boot (BOM and plugin) | 4.0.8 | 4.1.2 |
| Log4j | 2.25.5 | 2.26.1 |
| Tomcat | 11.0.25 | 11.0.27 |
| Caffeine | 3.2.3 | 3.3.1 |
| gRPC | 1.80.0 | 1.84.1 (amended from #92's 1.85.1; see Decisions) |
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

**Amendment, 2026-11-01: gRPC 1.84.1 instead of 1.85.1.**
- Spring Boot 4.1.2 newly imports `grpc-bom` 1.84.1. With #92's explicit 1.85.1 pins, `grpc-api`, `grpc-core`, `grpc-util` and `grpc-protobuf-lite` resolved to 1.84.1 under 1.85.1 artifacts (confirmed in the `:paygate-lightning-lnd` runtime classpath), and gRPC supports only one version across its artifacts.
- `grpcVersion` follows Boot's managed version (1.84.1), which also drives `protoc-gen-grpc-java`. Boot users receive that version whatever Paygate declares, and it is the gRPC/Protobuf 4.36.2 pairing Boot ships.
- Rejected alternative: importing `grpc-bom` 1.85.1 after the Boot BOM. It would make the build consistent, but the suite would test a version Boot users never run.
- Future gRPC bumps follow Boot's managed version.

Amendment approved: 2026-11-01 by Mark Hammond.

## Tasks
- [x] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results.
- [x] Apply #92's version changes and the MockWebServer bump, and migrate the LNbits tests to `mockwebserver3`. Check: `./gradlew compileJava compileTestJava` with verification temporarily in lenient mode in a scratch Gradle home only, never committed.
- [x] Compatibility review of the Boot 4.1, Framework, Security, Gradle 9.8 and Protobuf plugin 0.10 notes. Check: each relevant change is listed in this record with its disposition.
- [x] Provenance: generate candidates, review them by hand, add or remove exception rows, and keep the keyring and trusted keys unchanged. Checks:
  - `python3 scripts/audit-dependency-provenance-sources.py` matches every row's source bytes;
  - a diff shows no `<trusted-key>` additions;
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls` passes.
- [x] Fix compile, test or behaviour regressions. Check: module tests.
- [x] Review document, SECURITY.md link, version references and CHANGELOG. Check by inspection: no stale version strings remain in the listed docs.
- [x] Final checks, then record the results:
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew spotlessCheck check --no-daemon --rerun-tasks`
  - `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`
  - `./gradlew buildHealth`
  - `./gradlew cyclonedxBom` (or the release SBOM task)
  - `./gradlew dependencyCheckAggregate --rerun`, which must show no new unsuppressed findings
  - `git diff --check`
- [ ] After merge: close #92 and #11 with a link to the merged PR. (optional)

## Review
Implementation complete at `16d6a62`; Attempt 1 rework complete at `4fc1f13`; awaiting fresh-context review.

Baseline at `5fed97c` (record-only over base `faf5873`): pass.
- `./gradlew check --continue --rerun-tasks` exited 0: 3,447 default-module tests, 0 failures or skips.
- `./gradlew :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` 154 and `securityTest` 71, 0 failures. XML timestamps (2026-11-01T19:17Z) confirm a fresh run.

WORK notes:
- **#92 head moved.** Dependabot rebased #92 from `49bc5733` to `bdcf093`, based on `4fd5125`, with identical versions. It was cherry-picked with `-x` as `aa7d850`, keeping Dependabot authorship.
- **Gradle wrapper inputs: pass.** The new `gradle-wrapper.jar` SHA-256 (`3b8a2577…68e98`) and `distributionSha256Sum` (`21bd5da2…89607`) match `services.gradle.org`'s published `gradle-9.8.2-wrapper.jar.sha256` and `gradle-9.8.2-bin.zip.sha256`.

**Compatibility review.** A research pass compared the published old and new POMs and jars (`javap`) against Paygate's code.
- **Versions Spring Boot 4.1.2 brings:**
  - Spring Framework 7.0.10 (still 7.0.x);
  - Spring Security 7.1.2: no breaking changes, and the filter order and APIs the chain guard uses are unchanged;
  - Jackson 3.1.7, Micrometer 1.17.2 (the meter APIs Paygate uses are unchanged), and Kotlin 2.3.21 for tests.
- **New BOM imports:** Boot 4.1.2 now imports `grpc-bom` 1.84.1 and `protobuf-bom` 4.36.2.
- **Changes needed:**
  - mixed gRPC versions, resolved by the approved amendment;
  - the `mockwebserver3` API.
- **No change needed:**
  - Boot 4.1 removals: none of Paygate's Boot imports or properties were deprecated in 4.0.8;
  - Tomcat 11.0.27, Log4j 2.26.1 (not used directly), Caffeine 3.3.1 (`compileOnly`);
  - gRPC channel settings: `forAddress`, the default TLS context and keepalive are unchanged;
  - Protobuf 4.36.2: gencode and runtime are equal;
  - protobuf-gradle-plugin 0.10.0: no removed DSL is used.
- **Gradle 9.6+ deprecation warnings only (optional fixes):** `by extra` and `by configurations.getting` delegates, and a `taskDependencies` read inside `doLast`.
- **Not read in full:** the Jackson 3.1.6/3.1.7, gRPC 1.82.1 and Protobuf v31–v33 notes. The test suites cover them.

**Changes made:**
- **gRPC:** set to 1.84.1 (`9ae2915`). All 48 gRPC resolutions across lnd, autoconfigure and integration-tests agree, and regenerated stubs still use only `@io.grpc.stub.annotations.GrpcGenerated`.
- **MockWebServer:** migrated to `mockwebserver3` 5.3.2 (`29445e4`). All 57 `MockResponse` builders were converted; `SocketPolicy.NO_RESPONSE` became `onResponseStart(SocketEffect.Stall.INSTANCE)`, and `DISCONNECT_AT_START` became `onRequestStart(new SocketEffect.CloseSocket())`. All 141 LNbits tests pass.

**Provenance (`198fc2d`, `698f5ac`, merged at `82e9b63`).**
- **Generation:** candidate metadata was generated with `sha256,pgp` from empty Gradle homes, plus a checksum-only pass to capture the full resolved set, then reconciled by hand.
- **Ledger:** 285 rows for superseded artifacts were removed and 332 exact rows (EX-465 to EX-796) added. 179 unchanged rows keep their entries, giving 511 rows, all at 2026-11-22.
- **Metadata:** 470 artifacts added and 1,005 removed.
- **New artifacts covered by an already-trusted key:** 138. That includes one Guava jar Gradle checks by checksum only; following the 33.5.0 precedent, it has no row, and its signature was verified separately with a key trusted for Guava.
- **Discarded:** Gradle's proposed 11 trusted-key scopes and 7 artifact key pins.
- **Ignored keys (each only forces checksum fallback):** 12 globally ignored keys added, one unused ignored key removed, and snakeyaml 2.6 artifact-scoped.
- **Other edits:** the Linux protoc 4.36.2 and protoc-gen-grpc-java 1.84.1 entries were added as for earlier versions; CI is their first Gradle check. `scripts/test-dependency-provenance.sh` hard-coded the removed EX-001 row, so it now points at the ledger's new line 3 (EX-020), and that mutation is still rejected. The path was added to `Review scope`.
- **Owner field:** new rows use `Green Harbor Labs maintainer`, matching all existing rows.
- **Evidence:** [DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md), linked from SECURITY.md as the current review (`16d6a62`).

Coordinator verification of the provenance claims: pass.
- `git diff 9ae2915` is empty for `gradle/verification-keyring.keys`.
- The `<trusted-keys>` block is byte-identical.
- No `+<trusted-key|trusting|pgp>` lines were added, and the `<configuration>` flags are unchanged.
- The four provenance controls (`validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`) exited 0.
- `python3 scripts/audit-dependency-provenance-sources.py` exited 0: 511 of 511 SHA-256 matches, 72,420,133 bytes (500 from Maven Central, 11 from the Plugin Portal).

**Final checks** at `16d6a62`, from an empty `GRADLE_USER_HOME` with strict verification:
- `./gradlew --no-daemon spotlessCheck check --rerun-tasks`: pass. 3,447 default-module tests, 0 failures or skips.
- `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun`: pass. `test` 154 and `securityTest` 71, 0 failures, with fresh XML timestamps (2026-11-01T22:11Z).
- `./gradlew --no-daemon buildHealth cyclonedxBom`: pass. Per-module SBOMs are at `*/build/reports/cyclonedx/bom.json`, the path `release.yml` collects.
- `./gradlew --no-daemon dependencyCheckAggregate --rerun`: pass, "Found 0 vulnerabilities". The CVE-2025-32013 suppression still applies.
- `git diff --check faf5873 HEAD`: clean except `gradlew.bat`. That file is Gradle-generated with CRLF line endings, before and after, which Windows batch requires; with carriage returns stripped, no line has trailing whitespace. `git diff --check faf5873 HEAD -- . ':!gradlew.bat'` is clean.

Points for the reviewer:
1. **gRPC amendment.** The amendment (`e7e90b4`, approved 2026-11-01) is the only deviation from #92's versions.
2. **Pre-existing validator gap, not introduced here; needs a separate decision.**
   - `scripts/validate-dependency-provenance.sh` demands a ledger row only for checksums annotated "A key couldn't be downloaded".
   - 46 committed, still-resolved artifacts verify by checksum alone with no row, among them logback 1.5.38, google-java-format, okhttp 4.12.0, okio 3.6.0 and 13 `junit-bom` 5.x files. 13 legacy unsigned artifacts are in the same position.
   - The validator also does not enforce the 111 new valid-signature and unsigned rows, though they exist.
   - Recommended for the 2026-11-22 renewal: add rows for those artifacts and tighten the validator rule.
3. **Removed hand-verified entries.** The 69 dependency sources jars (IDE downloads) and the old Linux protoc executables were removed because no CI task resolves them. IDE source downloads will need fresh entries.
4. **Limits.**
   - Publisher identity is still not authenticated.
   - The Linux protoc executables are first checked by Gradle in CI.
   - Gradle deprecation warnings are recorded but not fixed.
   - Live LND and LNbits and the Docker stacks were not exercised.

### Attempt 1
Reviewed revision: 0d0e7c2f502fc90e3f9753e1000e5a9e4cc506d1
Verdict: rework
Findings and dispositions:
1. **Stale Protobuf Gradle plugin version (rework).** `paygate-lightning-lnd/README.md:567` still says the `com.google.protobuf` Gradle plugin is v0.9.6, while `paygate-lightning-lnd/build.gradle.kts` declares 0.10.0. This is a stale version string in a document the record lists for update and fails the record's own check, "no stale version strings remain in the listed docs." Update it to 0.10.0.
2. **Stale MockWebServer example (rework).** `paygate-lightning-lnbits/README.md:429-452` documents a test example using the removed OkHttp 4 `okhttp3.mockwebserver` API: `new MockResponse()`, `setResponseCode`, `setHeader`, `setBody`, and `server.shutdown()`. The module now depends on `mockwebserver3` 5.3.2, where `MockResponse` has no no-arg constructor and requires `MockResponse.Builder`, and `MockWebServer.shutdown()` is `close()`. The example no longer compiles against the declared test dependency, so the documentation does not match the migration this task made. Update the example to the `mockwebserver3` API.
3. **No implementation findings.** The scoped implementation is otherwise consistent. The ledger has 511 rows (285 removed, 332 added, 179 retained byte-identical; all deadline 2026-11-22; unique IDs). The metadata has 546 components and 965 artifacts (470 added, 1,005 removed, 495 kept with unchanged checksums). There are no `<trusted-key>`, `<trusting>` or `<pgp>` additions; the `<trusted-keys>` block and `gradle/verification-keyring.keys` are byte-identical to the base. Every ledger row matches a metadata SHA-256. The review document's counts and hashes reconcile with the committed files.
4. **Pre-existing validator gap** (record "Points for the reviewer" 2) is unchanged by this task and remains a separate renewal decision; not rework here.

Verification:
- Scoped paths clean at HEAD; commit `0d0e7c2` changes only this record.
- `python3 scripts/audit-dependency-provenance-sources.py`: pass, 511/511 SHA-256 matches, 72,420,133 bytes (500 Maven Central, 11 Gradle Plugin Portal).
- `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`: pass, all four tasks executed.
- `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon spotlessCheck check --rerun-tasks`: pass, strict verification, 122/122 tasks executed; default-module test XML 3,447 tests with 0 failures, errors or skips (a raw count of 3,601 includes 154 stale integration results not run by this invocation).
- `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun`: pass; `test` 154 and `securityTest` 71, 0 failures.
- `./gradlew --no-daemon buildHealth cyclonedxBom`: pass (exit 0, non-fatal dependency advice only); SBOMs written under `*/build/reports/cyclonedx/bom.json`.
- `./gradlew --no-daemon dependencyCheckAggregate --rerun`: pass, "Found 0 vulnerabilities".
- `git diff --check base..HEAD`: fails only on the Gradle-generated CRLF `gradlew.bat`; excluding that path it is clean. `gradlew.bat` has CRLF at both base and HEAD.
- Wrapper inputs: pass. `gradle-wrapper.jar` SHA-256 and `distributionSha256Sum` match `services.gradle.org` for 9.8.2.
- gRPC resolution: pass. Every `io.grpc` artifact on the `:paygate-lightning-lnd` runtime classpath resolves to 1.84.1.
- Documentation check: fail, per findings 1 and 2.
- Optional post-merge task (close #92 and #11): skipped, not applicable before merge.

Rework after Attempt 1 (implementation at `4fc1f13`):
1. **Finding 1, fixed.** `paygate-lightning-lnd/README.md` now names `com.google.protobuf` Gradle plugin v0.10.0. A repository-wide search of the maintained Markdown found no other superseded version string. The search covered every from-version in the Goal table, the plugin versions, and `okhttp3.mockwebserver` / `mockwebserver:`. It excluded history in `keel/`, `CHANGELOG.md` and earlier review documents.
2. **Finding 2, fixed and widened.** Beyond the removed OkHttp 4 API the review cited, the `paygate-lightning-lnbits/README.md` test example also failed three of the backend's current checks:
   - `LnbitsBackend` takes Jackson 3 `JsonMapper`, not `ObjectMapper`;
   - the two-argument `LnbitsConfig` rejects the plain `http://localhost` URL without the `allowPlaintextHttp` opt-in;
   - the placeholder `payment_request` fails the I-16 BOLT11 payment-hash check.

   The example now uses `mockwebserver3`, the five-argument `LnbitsConfig` with the opt-in, `JsonMapper`, and the BOLT #11 specification invoice with its hash, with the reasons explained above the code. The new `LnbitsReadmeExampleTest` runs the example verbatim and passes, so it cannot drift from the declared test dependency again.

Rework verification:
- The four provenance controls: pass, exit 0.
- `python3 scripts/audit-dependency-provenance-sources.py`: pass, 511 of 511 SHA-256 matches, 72,420,133 bytes.
- From an empty `GRADLE_USER_HOME` with strict verification:
  - `./gradlew --no-daemon spotlessCheck check --rerun-tasks`: pass, 3,448 default-module tests (3,447 plus the README example test), 0 failures or skips.
  - The integration `spotlessCheck`, `test` and `securityTest`: pass, 154 and 71 tests, 0 failures, with fresh XML timestamps (2026-11-01T22:37Z).
  - `./gradlew --no-daemon buildHealth cyclonedxBom`: pass, 8 module SBOMs at `*/build/reports/cyclonedx/bom.json`.
  - `./gradlew --no-daemon dependencyCheckAggregate --rerun`: pass, "Found 0 vulnerabilities".
- `git diff --check faf5873 HEAD -- . ':!gradlew.bat'`: pass (clean). `gradlew.bat` is unchanged since Attempt 1 and keeps its required CRLF line endings.

## Outcome
Pending.

## Next action
Address the two documentation findings in keel-review Attempt 1, commit, and return to `Status: review` for a fresh-context review.
