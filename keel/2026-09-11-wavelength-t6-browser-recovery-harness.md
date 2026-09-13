# Wavelength T6 Browser Recovery Harness
Status: review
Base revision: c6a8866753ca06cdd496be5b2d444de4d526ac24
Review scope: paygate-integration-tests/build.gradle.kts, paygate-integration-tests/src/wavelengthSpike/browser/, paygate-integration-tests/src/wavelengthSpike/java/com/greenharborlabs/paygate/integration/wavelength/WavelengthSpikeRun.java, docs/wavelength-spike/{setup.md,compatibility.json,contract-appendix.md}

## Goal
Implement only [Phase 0 T6](../plans/wavelength-phase-0.md#implementation-tasks): a minimal pinned browser harness persists and validates the original L402 challenge before one Wavelength dispatch, verifies a recovered preimage, retries/reconstructs only that challenge, and reconciles delayed/lost-response/reload outcomes without redispatch. Acceptance is deterministic recovery coverage plus clean-profile execution from the shipped local server and documented headers; payer preimage availability remains an unpassed T7 live gate.

## Constraints
- Preserve T0–T5 decisions and evidence in the [setup guide](../docs/wavelength-spike/setup.md), [compatibility manifest](../docs/wavelength-spike/compatibility.json), [contract appendix](../docs/wavelength-spike/contract-appendix.md), and [T5 closeout](2026-09-11-wavelength-t5-vendor-contract-probe.md). Use Wavelength Web/Core `0.1.1`, `farrier-kit` `1.1.3`, immutable local runtime assets, and exact lockfile pins; do not use mutable CDN assets or private SDK state.
- Keep wallet create/unlock/fund/boarding and payer profile administration in the browser/operator boundary. Keep receiver create/unlock/restart outside Spring. Do not begin T7, send a live payment, add a production backend, or polish the Phase 2 application.
- Persist no preimage or assembled Authorization value. Storage failure prevents dispatch; an unresolved record blocks every new payment action. Invoice expiry alone is not non-payment evidence, timeouts are not cancellation, and lost responses/reloads may reconcile only through supported `subscribe`/`startActivity({includeExisting:true,kinds:["send"]})` and `list` calls—never a second `sendPrepared`.
- T0 rejected both evaluated browser L402 packages. Approval of this record explicitly authorizes a separately reviewed, narrow local parser/formatter limited to Paygate's bounded L402 challenge grammar and `L402 <macaroon>:<64-hex-preimage>` output; no generic codec, automatic payer, wallet adapter, or repayment fallback is authorized. If that surface or SDK recovery cannot satisfy the recorded requirements, stop and report the unsupported gate.
- Follow [AGENTS.md](../AGENTS.md), T3's no-trace/HAR/video/screenshot/raw-console policy, and T4's isolated/fresh browser-task convention. Preserve unrelated changes.

## Decisions
- Build framework-free TypeScript with an injected public `WavelengthClient` boundary. The production entry creates only the pinned browser client/runtime; lifecycle operations are exposed as operator actions and are not called by the payment state machine. Deterministic tests use a public-shape fake and real Web Crypto/storage semantics.
- Use same-origin `sessionStorage` for one schema-versioned, bounded recovery record: invoice, macaroon, exact GET target, payment hash, verified expiry, creation time, and conservative `dispatchMayHaveOccurred=true` written before dispatch. Do not persist `sendIntentId`; v0.1.1 documents it as short-lived/single-use, while payment-hash activity replay is the supported recovery candidate. Validate every stored field before use and fail blocked on corruption or storage loss.
- Sequence `prepareSend`, subscribe/start replay, durable record write, then exactly one `sendPrepared`. Match only send activity carrying the stored hash; require terminal `complete` plus a 32-byte preimage whose Web Crypto SHA-256 equals the invoice hash. Retain metadata through protected 200; clear only on 200, supported conclusive no-payment/cancellation evidence, or explicit warned abandonment.
- Pin a minimal Node/TypeScript/bundler/test/browser toolchain in the browser lockfile. Serve bundled code and the eight externally supplied hash-verified runtime files from loopback with explicit CSP, content types, cache policy, and any observed worker/OPFS/COOP/COEP requirements. Use a temporary clean Playwright Chromium profile with tracing, HAR, video, and screenshots disabled; document exactly what synthetic startup proves and leave real wallet/preimage behavior to T7.

## Tasks
- [x] Add the pinned browser package/lock manifest and focused dependency/protocol review; implement bounded Paygate challenge parsing/authorization formatting and farrier invoice/hash/amount/network/expiry checks.
- [x] Implement the recovery store and one-dispatch state machine over public Wavelength quote/dispatch/activity/list APIs, including 120-second unknown-outcome handling, local preimage validation/zeroization, original-challenge retry, blocked recovery, and cleanup.
- [x] Add deterministic tests for storage failure, lost dispatch response, reload before dispatch, after dispatch-before-response, pending, and settled-before-200, delayed completion, corrupted storage, and reconciliation with an exact one-dispatch assertion.
- [x] Add isolated browser build/serve/test tasks, serve only pinned local assets with documented headers, and verify synthetic harness startup/reload/storage behavior in a temporary clean browser profile with secret-bearing capture disabled.
- [x] Update setup and compatibility/contract documents with observed Node/browser/storage/worker/WASM/header requirements and exact supported SDK request/result/event shapes; retain every live preimage/reload claim as unpassed. Run focused/full T6 offline checks, formatting/static analysis, dependency/audit and secret/report scans, then mark only T6 complete if all acceptance criteria pass.

## Review
Implementation commit: `2a4894632b8b003a3f5492d626f5860708c7c00e`. The locked browser check passed 24 deterministic tests with no failures/skips; the pinned v0.1.1 archive/assets passed SHA-256 validation; and Playwright 1.63.0 loaded `createWebClient().ready()` in a temporary clean Chromium 153 profile with the documented headers and synthetic same-tab storage reload. The full Java `wavelengthSpikeTest` rerun passed 111 tests. Spotless, Wavelength PMD, dependency provenance, npm audit, default build, explicit-task isolation, manifest/lock pins, and report/browser-bundle scans passed. T6 is checked in the local Phase 0 plan; no wallet lifecycle, payment, T7 gate, private SDK API, receiver administration, or Phase 2 application work ran. Awaiting fresh-context review.

## Outcome
Pending fresh-context review. Live external-invoice preimage availability and real reload replay remain unpassed T7 gates.

## Next action
Review this implementation from the recorded base and scope.
