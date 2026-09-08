# Wavelength Pinned Contract Feasibility
Status: closed
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

### Attempt 1
Reviewed revision: 4a7c4688c83728cde78838ca22033b4d8fa34c10
Verdict: pass
Findings and dispositions: No findings; no rework required.
Verification: `git rev-parse HEAD` -> `80c7ab54350f777472a84c49ca02abba118e0eb5`; scoped paths are unchanged from implementation commit `4a7c4688c83728cde78838ca22033b4d8fa34c10`. `python3 -m json.tool docs/wavelength-spike/compatibility.json` parsed successfully. Temporary Gradle/Java check using `fr.acinq.lightning:lightning-kmp-core-jvm:1.13.0` decoded the BOLT 11 specification vector for amount/hash/timestamp/expiry and rejected a checksum-corrupted invoice, with the expected Java 25 native-access warning. `git diff --name-only 901de77496b3d3f683649b1ade7734b6d94e73a1..4a7c4688c83728cde78838ca22033b4d8fa34c10` listed only the three scoped docs. Review confirmed exact version pins, JVM BOLT11 feasibility assessment, documented-vs-live capability separation, retained live/promotion gates, and no harness, payment, or Phase 1 task implementation.

## Outcome
Completed T0 in implementation revision `4a7c4688c83728cde78838ca22033b4d8fa34c10`. The [setup guide](../docs/wavelength-spike/setup.md), [pinned compatibility manifest](../docs/wavelength-spike/compatibility.json), and [contract appendix](../docs/wavelength-spike/contract-appendix.md) pin Wavelength daemon/SDK/runtime artifacts, select the ACINQ JVM BOLT11 decoder for the isolated spike, and separate source-documented payer/receiver capabilities from funded live gates. Offline verification parsed the manifest and decoded the BOLT11 amount, hash, timestamp, and expiry while rejecting checksum corruption on Java 25. Fresh-context review passed with no findings and confirmed the reviewed scope is unchanged at current `HEAD`. Material limits remain explicit: no payment or live recovery/restart proof occurred, Java native access and decoder transitives need spike isolation, the browser L402 dependency is unresolved, and every Phase 0 and production promotion gate remains in force.

## Next action
Plan T1 Gradle isolation from the [Phase 0 implementation tasks](../plans/wavelength-phase-0.md#implementation-tasks).
