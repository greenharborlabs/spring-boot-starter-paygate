# Wavelength T2 Credential Safety
Status: closed
Base revision: 9d1fe48b02c9b2f473350848503cb368dd78bee9
Review scope: paygate-integration-tests/src/wavelengthSpike/java/com/greenharborlabs/paygate/integration/wavelength/SpikeCredentialFileValidator.java, paygate-integration-tests/src/wavelengthSpikeTest/java/com/greenharborlabs/paygate/integration/wavelength/SpikeCredentialFileValidatorTest.java

## Goal
Implement only [Phase 0 T2](../plans/wavelength-phase-0.md#implementation-tasks): a source-set-local Wavelength spike credential-file validator accepts only readable, regular, non-symlink files satisfying the repository's restrictive POSIX policy. Acceptance evidence is deterministic temporary-file coverage run solely through `wavelengthSpikeTest`, with fixed failures that expose neither credential values nor paths, followed by marking T2 complete only after those checks pass.

## Constraints
- T1's [closed record](2026-09-08-wavelength-t1-gradle-isolation.md) and reviewed [integration-module build](../paygate-integration-tests/build.gradle.kts) establish the isolated offline task: it discovers only `wavelengthSpikeTest` output, compiles against spike helpers, has no live/preflight dependency, and previously ran credential-free as `NO-SOURCE`. Revalidate that boundary before relying on it; do not invoke live Wavelength work.
- Mirror the applicable strict rules from the package-private [LND validator](../paygate-lightning-lnd/src/main/java/com/greenharborlabs/paygate/lightning/lnd/LndCredentialFileValidator.java) locally: readable, regular, non-symlink, no other-user access, and no group write/execute access. Do not expose or change the LND production API and do not add a dependency on the LND module.
- Follow [AGENTS.md](../AGENTS.md), use temporary test entries only, fail closed when POSIX permission assessment is unsupported or fails, and keep paths, credential bytes, and test markers out of exception messages, logs, and assertion output.
- Preserve T0's signet-only and secret-safe boundaries in the [setup guide](../docs/wavelength-spike/setup.md) and [contract appendix](../docs/wavelength-spike/contract-appendix.md). Do not begin T3 transport/evidence work or later Phase 0 tasks. The T1 record relocation under `keel/` is pre-existing task-external work; preserve it and any other unrelated changes.

## Decisions
- Add a package-private final helper under `wavelengthSpike` with a minimal path-only validation entry point and fixed, path-free configuration failures; T2 validates metadata but does not read or return credential contents.
- Apply strict enforcement unconditionally for the spike. Accept restrictive owner-readable and group-readable regular files; reject missing paths, directories and other non-regular entries, symlinks even when their targets are safe, unreadable files, other-user permissions, group write/execute permissions, and unassessable metadata.
- Put all deterministic coverage in the matching `wavelengthSpikeTest` package using JUnit 5, AssertJ, and temporary files. Permission-specific cases will require a POSIX-capable temporary filesystem and restore permissions needed for cleanup. Reject sharing or making `LndCredentialFileValidator` public because that widens production API for spike-only reuse.

## Tasks
- [x] Re-run `./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration --no-daemon` before implementation to verify T1 still provides the isolated credential-free offline task and no live dependency.
- [x] Implement `SpikeCredentialFileValidator` with strict no-follow file-type, readability, and POSIX-permission checks plus fixed safe failures.
- [x] Add temporary-file tests for accepted restrictive modes and rejected missing/non-regular/symlink/unreadable/permissive cases; assert failures contain neither path nor credential-value markers and produce no logging.
- [x] Run the focused validator tests and full `wavelengthSpikeTest` task, inspect output for secret/path markers, and update [T2](../plans/wavelength-phase-0.md#implementation-tasks) to complete only if all acceptance criteria pass; stop before T3.

## Review
Implementation commit: `76f31efec39df6cc21735ddb362db6e20e94cc18`. Before implementation, the isolated offline task succeeded credential-free as `NO-SOURCE`, confirming T1 remained available without live work. The focused validator suite and full `wavelengthSpikeTest` task each passed with 13 tests and no skips; result/report scans found no temporary credential path or value marker. `spotlessCheck -Pintegration` passed. T2 is checked in the local Phase 0 plan, and T3 was not started. Awaiting fresh-context review.

### Attempt 1
Reviewed revision: ed7f8ee8226a8d9628cd10bdc60a9a3dbfb2daf1
Verdict: pass
Findings and dispositions: No findings; no rework required.
Verification: `./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration --no-daemon` succeeded (initially UP-TO-DATE); `./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration --no-daemon --rerun-tasks` succeeded with 13 tests, 0 failures, 0 errors, 0 skipped in the wavelengthSpikeTest XML reports. Scoped paths were clean before review; only the unrelated staged T1 record relocation was present.

## Outcome
Completed T2 in implementation revision `76f31efec39df6cc21735ddb362db6e20e94cc18`. The package-private [spike validator](../paygate-integration-tests/src/wavelengthSpike/java/com/greenharborlabs/paygate/integration/wavelength/SpikeCredentialFileValidator.java) mirrors the LND safety policy without widening production API: it fails closed for unavailable, unreadable, non-regular, symlinked, permissive, or unassessable credential files and emits only fixed path-free errors. The [temporary-file tests](../paygate-integration-tests/src/wavelengthSpikeTest/java/com/greenharborlabs/paygate/integration/wavelength/SpikeCredentialFileValidatorTest.java) passed through the isolated offline task with 13 tests and no skips; rerun verification also passed with 0 failures/errors/skips, and `spotlessCheck -Pintegration` passed. Fresh-context review at `ed7f8ee8226a8d9628cd10bdc60a9a3dbfb2daf1` passed with no findings and confirmed the review scope remained clean. Permission coverage requires a POSIX-capable filesystem; the implementation conservatively rejects unsupported POSIX assessment. T2 is complete in the local [Phase 0 plan](../plans/wavelength-phase-0.md#implementation-tasks), and no T3 implementation was started.

## Next action
Plan [Phase 0 T3 transport and evidence safety](../plans/wavelength-phase-0.md#implementation-tasks).
