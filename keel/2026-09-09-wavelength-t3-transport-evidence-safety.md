# Wavelength T3 Transport and Evidence Safety
Status: working
Base revision: 00a6c92a9bc0b161b9bd8a179a834d31431e6659
Review scope: paygate-integration-tests/src/wavelengthSpike/java/com/greenharborlabs/paygate/integration/wavelength/{BoundedBodyHandler.java,SanitizedEvidenceWriter.java}, paygate-integration-tests/src/wavelengthSpikeTest/java/com/greenharborlabs/paygate/integration/wavelength/{BoundedBodyHandlerTest.java,SanitizedEvidenceWriterTest.java}, docs/wavelength-spike/setup.md, plans/wavelength-phase-0.md

## Goal
Implement only [Phase 0 T3](../plans/wavelength-phase-0.md#implementation-tasks): Wavelength spike responses are capped at exactly 256 KiB before complete buffering or later JSON parsing and aborted on an overall body deadline, while generated evidence contains only validated allowlisted metadata. Acceptance requires deterministic exact-boundary, 256 KiB + 1, slow/stalled-body, and canary-secret tests proving no secret reaches artifacts, captured logs, or assertion diagnostics, plus a documented browser artifact policy suitable for later live work.

## Constraints
- Preserve T0's pinned, signet-only, no-live-claims boundary and secret exclusions in the [setup guide](../docs/wavelength-spike/setup.md), [manifest](../docs/wavelength-spike/compatibility.json), [contract appendix](../docs/wavelength-spike/contract-appendix.md), and [closed T0 record](2026-09-07-wavelength-pinned-contract-feasibility.md). Raw daemon bodies, wallet/network traffic, credentials, preimages, full invoices/hashes, and L402 credentials must never enter evidence or diagnostics.
- Retain the isolated, credential-free `wavelengthSpikeTest` boundary established by [T1](2026-09-08-wavelength-t1-gradle-isolation.md) and the source-set-local safety posture verified by [T2](2026-09-08-wavelength-t2-credential-safety.md). Do not add live preflight/freshness work assigned to T4, vendor mapping from T5, browser execution from T6, or any production module/API.
- Fail closed with fixed messages: the handler must cancel upstream demand before buffering byte 262,145, including unknown/chunked lengths, and its absolute body deadline must stop both stalled and continuously slow bodies. Declared length is only an early rejection hint, never proof that an observed body fits.
- Follow [AGENTS.md](../AGENTS.md), use local fixture servers and temporary artifact directories only, and preserve the pre-existing staged T1-record relocation and all other unrelated changes.

## Decisions
- Implement a package-private JDK `HttpResponse.BodyHandler<byte[]>`/`BodySubscriber<byte[]>` boundary with a fixed 256 KiB cap, incremental byte accounting before copying, early oversized `Content-Length` rejection, subscription cancellation on overflow/deadline, and fixed body-too-large/timeout failures. Return bytes only after bounded completion; JSON parsing remains T5 work. Reject `ofString()`, post-buffer length checks, per-chunk idle timers, and caller-configurable larger limits because they permit memory or slow-drip bypasses.
- Implement deterministic JSON evidence as an atomic write from a closed field allowlist with per-field type/value validation, stable ordering, and fixed path/value-free failures. Unknown fields and arbitrary strings in constrained fields are rejected before publication rather than copied, redacted heuristically, or silently accepted; no raw-response or free-form browser-console field exists.
- Record the durable browser policy in the existing setup guide: later live browser runs may capture only allowlisted structured console codes without raw text/arguments; Playwright/browser tracing, HAR/network payload capture, video, and screenshots are disabled while real wallet/payment material is present. Any future screenshot exception requires synthetic or explicitly masked content and separate reviewed tests; T3 does not add browser tooling.
- Exercise the subscriber directly for deterministic cancellation/boundary proof and use a loopback fixture server for end-to-end exact-boundary, oversized, stalled-after-headers, and slow-drip deadline behavior. Canary tests inspect temporary artifacts, fixed exceptions/assertion diagnostics, and captured root logs without printing the canary on failure.

## Tasks
- [ ] Re-run the current credential-free `wavelengthSpikeTest` suite, then add `BoundedBodyHandler` and deterministic direct-subscriber/loopback tests for exact 256 KiB acceptance, declared and streamed 256 KiB + 1 rejection before complete buffering/parsing, cancellation, and absolute deadlines for stalled and slow-drip bodies.
- [ ] Add `SanitizedEvidenceWriter` with atomic allowlisted output and tests for accepted deterministic metadata plus unknown-field, invalid-value, path/error, and canary rejection; verify artifacts, captured logs, and assertion diagnostics contain no canary or raw input.
- [ ] Update `docs/wavelength-spike/setup.md` with the browser console/trace/HAR/video/screenshot policy required before T6/live use, explicitly prohibiting raw wallet and network capture.
- [ ] Run focused handler/writer tests and the full `./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration --no-daemon --rerun-tasks`, inspect reports/artifacts for the canary, run `spotlessCheck -Pintegration`, and mark only T3 complete in the Phase 0 plan after every acceptance criterion passes; stop before T4.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan.
