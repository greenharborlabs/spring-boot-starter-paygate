# Wavelength T1 Gradle Isolation
Status: working
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
- [ ] Update `settings.gradle.kts` and `paygate-integration-tests/build.gradle.kts` with the two isolated source sets, explicit tasks, classpaths/configuration inheritance, module inclusion condition, and live opt-in guard.
- [ ] Verify task resolution and dry-run graphs for default `test`, `build -Pintegration`, `check -PwavelengthSpike`, and `releaseReadiness -Pintegration`; assert no graph contains `:paygate-integration-tests:wavelengthSpike` unless explicitly requested.
- [ ] Verify the canonical live command resolves with `-PwavelengthSpike`, direct live invocation with only `-Pintegration` fails the opt-in guard before live work, and `wavelengthSpikeTest -Pintegration` succeeds without credentials, daemon, or funding.
- [ ] Run the ordinary `./gradlew build` and `./gradlew build -Pintegration` checks, record concise results in this record, and mark T1 complete in the local Phase 0 checklist only after every acceptance criterion passes.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan.
