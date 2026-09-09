# Wavelength T1 Gradle Isolation
Status: closed
Base revision: 2421432504eb3e519d8e14006affe61f777afa05
Review scope: settings.gradle.kts, paygate-integration-tests/build.gradle.kts

## Goal
Implement only T1 from the [Phase 0 execution plan](../wavelength-phase-0.md#implementation-tasks): the isolated `wavelengthSpike` and `wavelengthSpikeTest` source sets/tasks resolve through their documented opt-ins, while ordinary build, check, integration, CI-equivalent, and release graphs cannot execute the funded live spike. Acceptance requires the specified graph/guard checks, credential-free offline task execution, and successful ordinary default/integration checks.

## Constraints
- Treat the [Phase 0 execution boundary and accepted decisions](../wavelength-phase-0.md#execution-boundary) as authoritative; do not begin T2, acceptance-integrity work assigned to T4, browser work, live payment work, or a production backend.
- Preserve the T0 pins and unresolved gates recorded in the [setup guide](../../docs/wavelength-spike/setup.md), [compatibility manifest](../../docs/wavelength-spike/compatibility.json), [contract appendix](../../docs/wavelength-spike/contract-appendix.md), and [closed T0 record](../../keel/2026-09-07-wavelength-pinned-contract-feasibility.md). T1 must require no daemon, funding, or credential material.
- Keep live classes outside ordinary `test` discovery. `wavelengthSpikeTest` may compile against spike helper output but must discover only its own tests and have no live-task/preflight dependency. Both tasks remain outside `test`, `check`, CI, and `releaseReadiness` lifecycles.
- The repository is currently clean; preserve unrelated work. Follow [AGENTS.md](../../AGENTS.md) and use existing prerequisites for ordinary default/integration verification.

## Decisions
- Include `paygate-integration-tests` from root settings when either `integration` or `wavelengthSpike` is present; inclusion alone performs no live work.
- Register explicit `Test` tasks over dedicated source-set outputs. Guard `wavelengthSpike` at execution on `-PwavelengthSpike`, so invoking it with only `-Pintegration` fails before test or network actions. Keep `wavelengthSpikeTest` credential-free and invokable with `-Pintegration`.
- Extend only the minimum configurations/classpaths needed for isolated compilation and later helper reuse. Reject tagging Wavelength tests into existing `test`/`securityTest`, wiring either new task into `check`, or broadening CI/release lifecycle dependencies.
- Leave forced-fresh execution, mandatory preflight/evidence validation, and cache controls to T4 as assigned by the authoritative task sequence; T1 proves isolation and the opt-in boundary without implementing later gates.

## Tasks
- [x] Update `settings.gradle.kts` and `paygate-integration-tests/build.gradle.kts` with the two isolated source sets, explicit tasks, classpaths/configuration inheritance, module inclusion condition, and live opt-in guard.
- [x] Verify task resolution and dry-run graphs for default `test`, `build -Pintegration`, `check -PwavelengthSpike`, and `releaseReadiness -Pintegration`; assert no graph contains `:paygate-integration-tests:wavelengthSpike` unless explicitly requested.
- [x] Verify the canonical live command resolves with `-PwavelengthSpike`, direct live invocation with only `-Pintegration` fails the opt-in guard before live work, and `wavelengthSpikeTest -Pintegration` succeeds without credentials, daemon, or funding.
- [x] Run the ordinary `./gradlew build` and `./gradlew build -Pintegration` checks, record concise results in this record, and mark T1 complete in the local Phase 0 checklist only after every acceptance criterion passes.

## Review
Implementation commit: `f6b2d4fdf8825d62d91d32891a77b268fd07f009`. Task listing with `-PwavelengthSpike` resolved both dedicated tasks and source sets. Dry runs of default `test`, `build -Pintegration`, `check -PwavelengthSpike`, and `releaseReadiness -Pintegration` contained neither explicit Wavelength task nor the live opt-in guard; the canonical live dry run contained the guard and live task. An actual live-task request with only `-Pintegration` failed at `requireWavelengthSpikeOptIn`, before the live test task started. `wavelengthSpikeTest -Pintegration --no-daemon` succeeded in an empty environment with no credentials or Wavelength daemon (currently `NO-SOURCE`, as expected before T2). `./gradlew build` and `./gradlew build -Pintegration` both passed. T1 is checked in the local Phase 0 plan. Fresh-context review passed in Attempt 1.

### Attempt 1
Reviewed revision: 39668b5111793a6d7860d73a55d0bead0ec6be75
Verdict: pass
Findings and dispositions: No findings; no rework required.
Verification: Inspected scoped diff from base 2421432504eb3e519d8e14006affe61f777afa05 through reviewed revision for `settings.gradle.kts` and `paygate-integration-tests/build.gradle.kts`; scoped implementation paths were clean before review. Ran `./gradlew test --dry-run --no-daemon`, `./gradlew build -Pintegration --dry-run --no-daemon`, `./gradlew check -PwavelengthSpike --dry-run --no-daemon`, and `./gradlew releaseReadiness -Pintegration --dry-run --no-daemon`; none included `:paygate-integration-tests:wavelengthSpike` or `:paygate-integration-tests:requireWavelengthSpikeOptIn`. Ran `./gradlew :paygate-integration-tests:wavelengthSpike --dry-run -PwavelengthSpike --no-daemon`; canonical live task and opt-in guard resolved. Ran `./gradlew :paygate-integration-tests:wavelengthSpike -Pintegration --no-daemon`; failed with exit 1 at `:paygate-integration-tests:requireWavelengthSpikeOptIn` before the live test task. Ran `./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration --no-daemon`; succeeded credential-free with `NO-SOURCE`. Ran `./gradlew build --no-daemon` and `./gradlew build -Pintegration --no-daemon`; both succeeded.

## Outcome
Completed T1 in implementation revision `f6b2d4fdf8825d62d91d32891a77b268fd07f009`. Root settings now include the integration module for either explicit integration or Wavelength-spike opt-in, and the integration module defines isolated live/offline source sets, explicit test tasks, helper-output reuse, and a failing live opt-in guard. Ordinary default, integration, check, and release dry-run graphs excluded both explicit Wavelength tasks and the guard; canonical live resolution included them, while `-Pintegration` alone failed before the live test task. Credential-free offline execution and both ordinary builds passed. Fresh-context review at `39668b5111793a6d7860d73a55d0bead0ec6be75` passed with no findings, and the reviewed [root settings](../../settings.gradle.kts) and [integration-module build](../../paygate-integration-tests/build.gradle.kts) remain unchanged. The offline task is intentionally `NO-SOURCE` until T2 adds deterministic tests; forced-fresh execution, preflight/evidence integrity, and every live capability gate remain deferred to their assigned [Phase 0 tasks](../wavelength-phase-0.md#implementation-tasks).

## Next action
Plan T2 credential safety from the [Phase 0 implementation tasks](../wavelength-phase-0.md#implementation-tasks).
