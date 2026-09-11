# Wavelength T4 Acceptance Integrity
Status: review
Base revision: 5bbf9673721e93619802608caf0200e84e20e590
Review scope: paygate-integration-tests/build.gradle.kts, paygate-integration-tests/src/wavelengthSpike/java/com/greenharborlabs/paygate/integration/wavelength/{WavelengthSpikeRun.java,WavelengthSpikeIT.java}, paygate-integration-tests/src/wavelengthSpikeTest/java/com/greenharborlabs/paygate/integration/wavelength/WavelengthSpikeRunTest.java, docs/wavelength-spike/setup.md

## Goal
Implement only [Phase 0 T4](../plans/wavelength-phase-0.md#implementation-tasks): every explicitly opted-in live invocation gets a new isolated run identity, executes a secret-safe mandatory preflight and the live test task afresh, and is accepted only when all currently registered mandatory tests and allowlisted evidence belong to that run and the current pinned manifest. Acceptance evidence is offline negative coverage for missing prerequisites, zero discovery, filtering, skips, missing evidence, and stale run/manifest identities, plus safe live-task failure when prerequisites are absent. The successful-run-then-daemon-stopped cache check remains explicitly deferred until T8 can produce a real successful invocation.

## Constraints
- Preserve the isolated task boundary established by [T1](2026-09-08-wavelength-t1-gradle-isolation.md), the credential policy from [T2](2026-09-08-wavelength-t2-credential-safety.md), and the bounded allowlisted evidence boundary from [T3](2026-09-09-wavelength-t3-transport-evidence-safety.md). `wavelengthSpikeTest` must remain credential-, funding-, daemon-, and live-preflight-independent.
- The [Phase 0 execution boundary](../plans/wavelength-phase-0.md#execution-boundary) and [setup prerequisites](../docs/wavelength-spike/setup.md#preflight-before-any-live-payment) require fixed path/value-free failures, a fresh ignored directory under `paygate-integration-tests/build/wavelength-spike/`, no up-to-date or build-cache reuse, and current-run evidence carrying both the run ID and current `compatibility.json` SHA-256.
- T0 records only paper/offline feasibility ([manifest](../docs/wavelength-spike/compatibility.json), [contract appendix](../docs/wavelength-spike/contract-appendix.md)); T4 must not call Wavelength APIs, exercise a wallet/browser, emit successful capability evidence, implement T5 probes, or imply Phase 0 acceptance.
- The repository is clean at planning time. Follow [AGENTS.md](../AGENTS.md), preserve unrelated work, and do not mark the Phase 0 T4 checkbox complete while its post-T8 live freshness obligation remains pending.

## Decisions
- Put run initialization and preflight ahead of JUnit rather than using assumptions: generate one UUID per actual invocation, create a never-reused run directory, validate the pinned manifest and declared path/authorization prerequisites (including the existing credential validator), and expose only fixed prerequisite names on failure.
- Keep a code-owned registry of currently implemented mandatory gates/tests. Capture discovery and terminal status, then run one fail-closed acceptance validator that requires each registry entry exactly once, rejects zero tests and any missing/filtered/skipped/failed mandatory entry, and validates one gate-scoped artifact per required gate against the invocation's run ID and manifest digest. Later tasks must extend the registry when they add mandatory gates; optional second-mode evidence stays outside it.
- Disable both up-to-date reuse and build-cache load/store for run preparation, preflight, live tests, acceptance validation, and the reserved live-browser subtask convention. Prior run directories are never searched to satisfy the current invocation. Reject relying only on Gradle's no-matching-test default or JUnit success because neither proves every mandatory gate executed.
- Exercise failure branches through deterministic `wavelengthSpikeTest` fixtures and validator inputs, not live credentials or fabricated capability passes. Document exact prerequisite names and artifact layout in the existing setup guide. Record, but do not claim completion of, the required post-T8 repetition of a real successful command with `waved` stopped and `--build-cache` enabled.

## Tasks
- [x] Add fresh-run Gradle orchestration, secret-safe mandatory preflight, manifest hashing, task/test result capture, and cache/up-to-date prohibitions without wiring the live task into ordinary lifecycles.
- [x] Add `WavelengthSpikeRun` acceptance validation and a minimal `WavelengthSpikeIT` integrity gate that emits only preflight/harness evidence; require exact current-run gate artifacts and add no vendor capability probe or synthetic capability success.
- [x] Add credential-free offline tests proving missing prerequisites, zero discovery, filtered/missing/skipped mandatory gates, stale run IDs, stale manifest digests, and absent/incomplete evidence fail; verify prior artifacts cannot satisfy a new run.
- [x] Update the setup guide with the concrete preflight contract, fresh run/evidence layout, fixed safe failure behavior, and the explicit post-T8 daemon-stopped/cache-enabled obligation; leave the Phase 0 T4 checklist unchecked.
- [x] Run focused and full `wavelengthSpikeTest` reruns, Spotless and Wavelength PMD, isolation dry runs, and repeated cache-enabled live invocations with deliberately missing prerequisites to confirm safe non-zero fresh failure.
- [ ] After T8 produces a genuinely successful live invocation, repeat it with `waved` stopped and `--build-cache`; require a new run ID, no `UP-TO-DATE`/`FROM-CACHE` live work, and non-zero current-gate failure.

## Review
Implementation commit: `d84f6880e4ebaee7a44a25ed6862b6e9c71a5d2b`. The full credential-free `wavelengthSpikeTest --rerun-tasks` suite passed 49 tests with 0 failures, errors, or skips; Spotless and both Wavelength PMD tasks passed. Offline coverage rejects absent prerequisites, zero discovery, filtered/skipped/duplicate mandatory tests, stale run and manifest identities, missing/incomplete evidence, and prior-run artifacts. An actual nonexistent `--tests` filter failed through both Gradle and the acceptance finalizer. Two `--build-cache` invocations with empty prerequisite environments each allocated distinct UUID directories, executed `prepareWavelengthSpikeRun` and `wavelengthSpike`, and failed non-zero without either live task reporting `UP-TO-DATE` or `FROM-CACHE`; the path-free preflight checklist was visible. Default, integration, Wavelength-check, and release-readiness dry-run graphs contained no live prepare/test/validation task, and `-Pintegration` alone still failed the opt-in guard. No daemon API, wallet, browser, payment, or T5 capability probe ran. The Phase 0 T4 checkbox and the successful-live daemon-stopped/cache-enabled verification remain deliberately pending until after T8. Awaiting fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan.
