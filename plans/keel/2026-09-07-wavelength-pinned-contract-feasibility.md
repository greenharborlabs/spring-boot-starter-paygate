# Wavelength Pinned Contract Feasibility
Status: review
Base revision: 901de77496b3d3f683649b1ade7734b6d94e73a1
Review scope: docs/wavelength-spike/setup.md, docs/wavelength-spike/compatibility.json, docs/wavelength-spike/contract-appendix.md

## Goal
Resolve T0 from `plans/wavelength-phase-0.md`: determine the exact Wavelength daemon/SDK/runtime and payment-decoder dependency feasibility before any harness work. Acceptance evidence is a concise setup guide, pinned compatibility manifest, and contract appendix that separate documented capabilities from unresolved questions requiring the live Phase 0 spike, while preserving all Phase 0 and promotion gates.

## Constraints
Use `plans/wavelength-phase-0.md` as design authority. Do not implement the harness, send payments, create Phase 1 tasks, or mark live gates passed from paper review. Preserve the unchanged `LightningBackend` gate, payer preimage/recovery gate, restart lookup gate, JVM BOLT11 decoder gate, secret-safe evidence rules, signet-only/experimental posture, and no false self-custody claims for credit-backed receipt. Pre-existing dirty paths are unrelated: `TODOS.md` and `plans/wavelength-phase-0.md`.

## Decisions
Perform a version-specific contract/dependency review only: inspect official Wavelength release/source/schema and SDK package types, select candidate pinned daemon release/commit plus browser/runtime assets, and evaluate maintained JVM BOLT11 and browser BOLT11/L402 packages for license, maintenance, conformance, and supply-chain fit. Record supported payer dispatch/activity/recovery API candidates separately from live-spike questions. Reject starting Gradle/source-set work or coding around missing APIs because T0 is the promotion gate that decides whether substantial Phase 0 harness work is viable.

## Tasks
- [x] Inspect pinned Wavelength daemon/API/SDK/runtime artifacts and record documented Status, Recv, InspectActivity/List, payer dispatch, activity, preimage, and recovery capabilities plus unresolved live-spike questions.
- [x] Evaluate candidate JVM BOLT11 decoder and browser BOLT11/L402 packages with license, maintenance, conformance-vector, asset-hash, and runtime-requirement evidence; stop if no acceptable JVM decoder exists.
- [x] Write `docs/wavelength-spike/setup.md`, `compatibility.json`, and `contract-appendix.md` with funding/boarding prerequisites, package paths, asset hashes, supported API candidates, and explicit live gates not yet passed.
- [x] Verify by paper review and decoder-vector checks only; confirm no harness code, payments, Phase 1 tasks, secrets, or live-gate pass claims were added.

## Review
Implementation commit: `4a7c4688c83728cde78838ca22033b4d8fa34c10`. `compatibility.json` parsed successfully. ACINQ `Bolt11Invoice` 1.13.0 decoded the BOLT 11 specification amount/hash/timestamp/expiry vector and rejected checksum corruption on Java 25; the native-access warning is recorded. Scope inspection found documentation only, no harness/payment/Phase 1 work, and all live gates remain explicit. Awaiting fresh-context review.

## Outcome
Pending fresh-context review.

## Next action
Run fresh-context Keel review of the recorded scope and commit.
