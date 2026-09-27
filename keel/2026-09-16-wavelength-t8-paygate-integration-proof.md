# Wavelength T8 Paygate Integration Proof
Status: abandoned
Base revision: pending approval
Review scope: paygate-integration-tests/build.gradle.kts, paygate-integration-tests/src/wavelengthSpike/, paygate-integration-tests/src/wavelengthSpikeTest/, docs/wavelength-spike/{setup.md,compatibility.json,contract-appendix.md}, plans/wavelength-phase-0.md

## Goal
Execute only [Phase 0 T8](../plans/wavelength-phase-0.md#implementation-tasks) after current-run T7 direct-vendor evidence passes: add a test-only `LightningBackend` and `@TestConfiguration`/`@Primary` Spring harness proving a real Paygate 402, browser payment, original L402 retry, protected 200, and original-challenge recovery after reload. Verify the request-specific outage matrix, status/codes, backend calls, handler execution, and T4's deferred stopped-daemon/cache freshness check.

## Constraints
- T7 is a hard predecessor. Its [closed preparation record](2026-09-16-wavelength-t7-live-capability-durability.md), [manifest](../docs/wavelength-spike/compatibility.json), and [partial report](../docs/wavelength-spike/contract-appendix.md#t7-preparation-and-blocked-live-attempt--2026-09-16) explicitly say no direct live gate passed.
- A current canonical `--build-cache` invocation at base `da7f1172ac88f74e05c4d8c41d58552938e64367` failed preflight non-zero. Sanitized ignored evidence is retained under run `d8658cd8-4973-4531-8471-e741c322f671`, manifest `bcb7f3642830dd349e319731f72bccea7042c2c67560b573ee01f37e5a696ab9`, with only `preflight: unavailable`; no daemon API, wallet, payment, restart, outage, or T8 operation ran. This is a blocked prerequisite, not evidence disproving a vendor capability.
- Follow [AGENTS.md](../AGENTS.md), preserve T0–T7 safety/isolation decisions, use the existing tested client/mapper and test-only Spring override pattern, and use deterministic credentials for negative/legacy cases. Add no production module or core API change.
- T4 freshness requires a genuinely successful full live invocation before the daemon-stopped repetition; missing-prerequisite failures do not satisfy it. Stop promotion on any failed mandatory gate and do not begin Phase 1.

## Decisions
- Do not begin T8 while T7 lacks actual current-run evidence for every registered direct-vendor gate (`status` through `restore`). The present environment exposes no `WAVELENGTH_SPIKE_*` prerequisites, so constructing the adapter or claiming a funded Paygate flow would violate the phase gate.
- Retain the current allowlisted preflight artifact as the partial report. Do not fabricate T7/T8 observations, weaken mandatory-gate validation, substitute deterministic fixtures for the funded positive flow, or perform T4's stopped-daemon check without a successful baseline.
- Once T7 is independently completed, implement only spike-source test code: adapt `WavelengthVendorProbe` to `LightningBackend`, inject it with the established `@TestConfiguration`/`@Primary` pattern, reuse the original browser challenge across reload, and instrument backend calls and protected-handler executions for the exact outage matrix.

## Tasks
- [ ] Provision and complete the outstanding T7 live proof; require current-run acceptance for every direct-vendor mandatory gate before changing T8 implementation paths.
- [ ] Add the test-only backend adapter and Spring integration harness, with deterministic offline protocol fixtures for negative and legacy credentials.
- [ ] Prove the funded 402 → browser payment → original L402 retry → protected 200 flow and original-challenge reload recovery without redispatch.
- [ ] Verify outage statuses/codes, backend call counts, protected-handler execution, modern first-use/cached behavior, invalid cases, and legacy lookup-dependent rejection.
- [ ] Repeat the successful canonical invocation with `waved` stopped and `--build-cache`; require a fresh run and non-zero daemon-dependent failure, then restore the dedicated daemon.
- [ ] Record sanitized current-run results, update only T4/T7/T8 statuses supported by evidence, run focused offline/static checks, and stop before T9/Phase 1.

## Review
No implementation or review occurred. This record is abandoned because the required T7 live proof remains incomplete; abandonment is not a successful T8 result.

## Outcome
Abandoned without implementation. The predecessor [T7 record](2026-09-16-wavelength-t7-live-capability-durability.md) closed only the reviewed harness preparation, not the required funded live proof. A fresh cache-enabled invocation at `da7f1172ac88f74e05c4d8c41d58552938e64367` failed preflight non-zero and retained allowlisted `preflight: unavailable` evidence for run `d8658cd8-4973-4531-8471-e741c322f671`; no daemon API, browser wallet, payment, restart, outage, T8 adapter, or T4 post-success freshness check ran.

A new 2026-09-17 predecessor check again found no `WAVELENGTH_SPIKE_*` prerequisites and ran `./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike --build-cache`. It failed nonzero at `WavelengthSpikeRun.preflight`, allocated run `8396a552-e01a-42a7-b263-8b061afd5b28`, and wrote allowlisted `preflight: unavailable` evidence at `paygate-integration-tests/build/wavelength-spike/8396a552-e01a-42a7-b263-8b061afd5b28/preflight/evidence.json` for manifest `bcb7f3642830dd349e319731f72bccea7042c2c67560b573ee01f37e5a696ab9`. No daemon API, wallet lifecycle, browser wallet startup, dispatch, payment, restart, dependency outage, Paygate adapter, protected endpoint, or stopped-daemon freshness operation ran.

The [compatibility manifest](../docs/wavelength-spike/compatibility.json) and [partial report](../docs/wavelength-spike/contract-appendix.md#t8-blocked-predecessor-check--2026-09-17) continue to identify every live capability as unpassed. T4, T7, and T8 remain incomplete, and no production/core or Phase 1 change was made.

## Next action
Complete the outstanding T7 live proof using the [operator setup](../docs/wavelength-spike/setup.md#t7-bounded-direct-live-harness-operator-setup-required).
