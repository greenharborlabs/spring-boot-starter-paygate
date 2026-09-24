# Wavelength T7 Live Signet Proof
Status: planned
Base revision: pending approval
Review scope: paygate-integration-tests/src/wavelengthSpike/, paygate-integration-tests/src/wavelengthSpikeTest/, paygate-integration-tests/build.gradle.kts, docs/wavelength-spike/{setup.md,compatibility.json,contract-appendix.md}, plans/wavelength-phase-0.md

## Goal
Complete the still-unpassed [Phase 0 T7](../plans/wavelength-phase-0.md#implementation-tasks) live proof using the reviewed direct-vendor harness: exact 10-sat receive/BOLT11/hash, browser payment and hash-valid preimage, COMPLETE and custody evidence, bounded history, daemon restart and exact Invoice reconstruction, one reversible dependency fault/readiness observation, and lost-response/reload recovery with exactly one dispatch. Require fresh mandatory evidence through `restore`, or retain a sanitized partial report and stop.

## Constraints
- The prior [T7 preparation](2026-09-16-wavelength-t7-live-capability-durability.md) was reviewed but **abandoned without live proof**. The [manifest](../docs/wavelength-spike/compatibility.json) and [appendix](../docs/wavelength-spike/contract-appendix.md#t7-preparation-and-blocked-live-attempt--2026-09-16) still show no passed direct-vendor gate. As of planning, no `WAVELENGTH_SPIKE_*` variables are present, `waved` is absent from PATH, and retained evidence is preflight-only; no specific dedicated installation or funded wallet is verified. Do not infer either from past offline checks.
- Follow [AGENTS.md](../AGENTS.md) and the [operator setup and controller contract](../docs/wavelength-spike/setup.md#t7-bounded-direct-live-harness-operator-setup-required). Verify exact pinned binary/build/hash, owned PID/start identity, explicit preserved wallet data directory, loopback TLS endpoint, restrictive credential file, pinned browser assets/profile and funding, out-of-band unlock, fixed browser origin, authorized one 10-sat dispatch and two additional unpaid receives, and owned reversible dependency fault **before** lifecycle or payment operations. Do not expose secrets or operate an unowned process.
- Preserve [T1–T4 isolation/safety/acceptance](2026-09-11-wavelength-t4-acceptance-integrity.md), [T5 client/mapper](2026-09-11-wavelength-t5-vendor-contract-probe.md), and [T6 public browser recovery](2026-09-11-wavelength-t6-browser-recovery-harness.md). No automatic Recv or payer retry; never treat a timed-out payment as unpaid. Bound waits, emit allowlisted run/manifest evidence only and restore owned state on all exit paths.
- The [planned T8 record](2026-09-24-wavelength-t8-paygate-proof.md) remains blocked until this live proof actually passes; do not implement T8, perform T4's post-success cache experiment, or begin Phase 1. Preserve the existing unrelated dirty edit in [the abandoned T8 record](2026-09-16-wavelength-t8-paygate-integration-proof.md). The Phase 0 plan is ignored; resolve tracking scope explicitly before changing it.

## Decisions
- Reuse the reviewed T7 harness instead of rebuilding it or substituting synthetic success. Run the canonical opt-in task only after operator inventory and authorization; require a fresh run ID, matching reviewed manifest, every registered direct gate and an independently verified restoration. Compare received fields in memory, not in artifacts.
- Do not provide a generic restart/fault script or take over another daemon. The operator supplies and reviews the installation-specific controller, funding and unlock. If prerequisites remain unavailable, stop at blocked preflight/partial report and request the missing setup; if a hard capability gate fails, retain its sanitized failure and stop promotion rather than coding around it.
- Update the existing [setup guide](../docs/wavelength-spike/setup.md), [manifest](../docs/wavelength-spike/compatibility.json) and [contract appendix](../docs/wavelength-spike/contract-appendix.md) only with verified observed mode, lookup envelope and failure limits. Credit-backed receipt is server credits, not proof of self-custody. Check T7 in the plan only on complete current-run evidence; keep T4/T8 unchecked.

## Tasks
- [ ] Obtain operator-provided isolated daemon, wallet/profile, pinned runtime and credential **paths**, controller identity/ownership review, out-of-band unlock and explicit payment/history/fault confirmations; verify without displaying secret contents or mutating foreign state.
- [ ] Run focused offline Java/browser, Spotless/PMD and evidence-safety checks; confirm manifest pin and live-task opt-in/mandatory registry still match the deployed artifacts.
- [ ] Run the canonical fresh signet task once with authorized funds; validate exact in-memory amount/hash/preimage, browser replay without redispatch, receiver COMPLETE, history envelope, restart lookup, fault observation and verified restoration from current-run allowlisted evidence. Do not blindly rerun on an unknown outcome.
- [ ] On missing prerequisites or failed hard gate, retain only sanitized partial evidence, restore owned state if touched, and report blocked/failed without claiming T7 success. On success, document observed claims/limits and complete only T7's checkbox; run secret scans and commit all scoped changes before review.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan and provide the [operator setup prerequisites](../docs/wavelength-spike/setup.md#t7-bounded-direct-live-harness-operator-setup-required) before any live execution.
