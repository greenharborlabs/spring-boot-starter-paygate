# Wavelength v0.1.1 Contract Appendix

Evidence dates: 2026-09-07 through 2026-09-11

Scope: Phase 0 T0 source/package review, T5 receiver fixtures, and T6 deterministic/clean-profile browser checks. **No live payment capability gate is passed.**

## Evidence labels

- **Documented:** present in immutable v0.1.1 daemon/SDK source or published package types.
- **Offline checked:** executed without a daemon, browser wallet, or payment.
- **Clean-profile checked:** executed with the pinned local browser runtime but without starting/administering a wallet or paying.
- **Live required:** cannot be promoted from source, fixture, or runtime-startup inspection.
- **Rejected:** evaluated but not selected.

Primary immutable sources are Wavelength daemon commit `2e89c924e2e10095f7baf736bc9eefdabcd836ce`, SDK commit `cd136619838dd15aa74c11ab4545a5788c48ad94`, and ACINQ lightning-kmp commit `956299158b83023485001d8d6f66f6b5dc8ee366`. Exact package/archive identities are in `compatibility.json`.

## Receiver REST contract

The pinned grpc-gateway configuration documents `POST` plus `body: "*"` for every operation. Protobuf `uint64`/`int64` values are JSON strings under grpc-gateway's standard JSON mapping; enum values use their proto names. Authentication is `macaroon: <lowercase hex>` when enabled.

| Operation | Documented request | Required documented response evidence | T0 conclusion |
|---|---|---|---|
| `POST /v1/wallet/status` | `{}` | `ready`, `unlocked`, `network`, balance, pending count | Expressible. `ready` is documented as daemon-and-dependencies-up, but outage behavior is live required. |
| `POST /v1/wallet/recv` | `{"amt_sat":"10","memo":"<bounded synthetic memo>"}` | BOLT11 `invoice`, initial receive `entry`, optional `credit_receive` with operation ID, amount, and payment hash | Exact invoice/hash/amount/note/status/timestamps are expressible. No request ID/idempotency field and no explicit invoice-expiry field exist. Never retry `Recv`; decode expiry from verified BOLT11. |
| `POST /v1/wallet/inspect/activity` | `{"id":"<entry id>","ledger_limit":100}` | matching activity entry plus optional swap/VTXO/ledger traces | Exact-ID inspection is documented but searches only the daemon's current activity window, capped by its maximum list limit. A gRPC `NOT_FOUND`/HTTP 404 is the only fallback trigger. |
| `POST /v1/wallet/list` | `{"view":"LIST_VIEW_ACTIVITY","pending_only":false,"kinds":["ENTRY_KIND_RECV"],"limit":100,"cursor":"<opaque>"}` | `activity.entries`, page-local `total`, `has_more`, `next_cursor` | Cursor pagination is expressible and documented stable across concurrent inserts. Activity ignores `offset`; `total` is page count. Reject repeated/non-progressing cursors or contradictory metadata. |

A `WalletEntry` documents stable ID, kind, status, signed amount, fee, creation/update timestamps, note, original Lightning invoice/payment hash, progress payment hash/preimage, and optional failure code. Swap-backed receive IDs are payment hashes; credit-backed receive IDs are credit operation IDs, so payment-hash lookup requires bounded `List` matching. These fields can represent the unchanged Paygate `Invoice`, with BOLT11 supplying expiry. Whether all fields are actually persisted and returned after restart is live required.

The pinned receiver source defines machine-readable failure codes `TIMED_OUT`, `EXPIRED`, `REFUNDED`, `NEEDS_INTERVENTION`, and `FAILED`. HTTP errors use grpc-gateway `google.rpc.Status`; exact observed HTTP/gRPC pairs still belong to the live/fixture probe. Preserve the design authority's complete classification table rather than inferring semantics from free-text messages.

### Credential finding

The source has per-method permission taxonomy: `Status` is `info:read`, `Recv` is `address:write`, and `List`/`InspectActivity` are `activity:read`. It auto-generates admin and read-only macaroons, but T0 found no supported v0.1.1 CLI/API for baking the exact mixed scope. The read-only macaroon cannot call `Recv`; use of admin credentials is therefore limited to explicit loopback/signet experimentation and remains a production promotion blocker.

## Payer dispatch and activity contract

The supported public SDK sequence is:

```text
prepareSend({ invoice, note?, maxFeeSat? })
  -> PrepareSendResult { sendIntentId, paymentHash, expiresAt, quote/fee/rail fields }
sendPrepared(prepared)
  -> SendResult { initial entry, actualAmountSat, paymentHash folded from prepare }
subscribe(listener)
startActivity({ includeExisting: true, kinds: ["send"], cursor: 0 })
  -> activity events until terminal COMPLETE/FAILED
list({ view: "activity", kinds: ["send"], cursor? })
  -> current snapshots for reconciliation
```

**Documented:** `sendIntentId` is short-lived and single-use. `sendPrepared` dispatches and returns an initial persisted activity entry, not terminal settlement. `send()` merely composes `prepareSend` and `sendPrepared`; it offers no stronger recovery semantics.

**Documented:** terminal Lightning send activity may contain `progress.paymentHash` and a 32-byte hex `progress.preimage`. The SDK comments explicitly say the preimage is emitted on the settlement stream event, omitted from later `List` snapshots, cached only in the current `WalletEngine` memory, and replayed by `includeExisting`. The daemon source describes the subscription store as an append-only event log with resumable monotonic cursors.

**Recovery candidate, live required:** persist the original challenge, target/method, decoded payment hash, and a conservative pre-dispatch marker before calling `sendPrepared`. Start the activity stream before dispatch. If the response is lost or the page reloads, do not call `sendPrepared` again; open a new supported stream with `includeExisting: true`, match only the persisted payment hash, wait for terminal state, and verify `SHA-256(preimage) == paymentHash`. A successful protected retry clears the recovery record.

The candidate is source-supported but not yet proved for an externally supplied invoice, browser reload, SDK/runtime restart, retention limits, or a completely lost send response. `List` alone cannot recover the preimage. A timeout is not documented as conclusive cancellation. Failure to replay the terminal preimage after reload is a hard stop for the browser L402 experience.

## BOLT11 feasibility

### JVM

Selected for the isolated spike: `fr.acinq.lightning:lightning-kmp-core-jvm:1.13.0` (Apache-2.0). ACINQ released it on 2026-07-10, actively maintains the repository, uses the implementation in Phoenix, and includes extensive BOLT 11 specification vectors and malformed-invoice tests.

**Offline checked on Java 25:** `Bolt11Invoice.read` accepted the standard 250,000-sat BOLT 11 vector and exposed amount `250000000` msat, payment hash, timestamp `1496314658`, and explicit expiry `60`; changing the checksum caused parse failure. This proves basic API/JVM feasibility, not Wavelength compatibility. The library also supplies the BOLT default expiry of 3,600 seconds and cryptographic signature recovery.

Risks retained for review: this is a whole Lightning engine artifact with a broad Kotlin/Ktor graph; signature recovery loads ACINQ secp256k1 JNI and produced Java 25's native-access warning. T5 must isolate the decoder boundary, enable/assess native access deliberately, lock all transitive artifacts, and run signet/default-expiry/wrong-network/overflow vectors. BOLT11 encodes signet with the testnet-family `lntb` prefix and cannot distinguish signet from Bitcoin testnet by itself, so the adapter must additionally require `Status.network == "signet"`; it must not claim invoice-only signet proof.

### Browser

`farrier-kit@1.1.3` (MIT) is the current candidate because it has browser exports, one runtime dependency, checksum/network/amount/hash/timestamp/expiry parsing, BOLT vectors, differential tests, and published npm provenance. It is new and low-adoption, and intentionally does not verify invoice signatures. T6 therefore remains blocked on a focused dependency review and cross-check against a real v0.1.1 invoice; its role is only the browser checks required by the design, not receiver authority.

T0 approved no browser L402 package. `l402-requests@0.8.0` has useful challenge/header APIs and a custom wallet interface, but its root export graph includes Node-only modules and its `payInvoice(): Promise<preimage>` abstraction cannot represent the required prepare/dispatch/recovery lifecycle. `l402@0.5.1` is browser-oriented but stale and directly couples payment to retry without durable unknown-outcome recovery.

**T6 superseding spike-only decision:** the approved Keel scope authorizes a separately reviewed narrow local boundary rather than either rejected package. It accepts only Paygate's exact bounded `L402 version="0", token="...", macaroon="...", invoice="..."` output, requires identical canonical token/macaroon values and a verified signet-family amount-bearing BOLT11, and formats only `L402 <macaroon>:<64-hex-preimage>`. It is not a generic codec, automatic payment client, or production dependency. Fresh-context review must still approve this security-sensitive surface.

## Browser runtime inventory

Documented v0.1.1 requirements/candidates:

- self-host all eight hashed runtime files listed in `compatibility.json`; mutable CDN assets are prohibited;
- default dedicated Web Worker plus nested SQLite worker, WebAssembly, fetch, OPFS persistence, and Web Locks;
- encrypted seed remains in OPFS; app-level wallet markers may use local storage, but the L402 recovery record requires a separate access/metadata/expiry review;
- Cache Storage is optional; no `DecompressionStream` is acceptable when raw WASM is served;
- runtime assets and network endpoints may require `worker-src`, `script-src`, and `connect-src` allowances.

The selected SDK publishes no exact browser support matrix. Secure-context behavior, CSP directives, CORS/same-origin layout, quota/persistence, cross-origin isolation, declared browser versions, and recovery-record storage safety therefore require explicit observed evidence rather than inferred support.

## T6 browser harness contract and offline evidence

Evidence date: 2026-09-11. This section records deterministic and clean-profile capability checks only; it records no wallet payment or live preimage.

The implemented public SDK call/result boundary is:

| Step | Accepted v0.1.1 shape | Harness rule |
|---|---|---|
| Quote | `prepareSend({invoice}) -> PrepareSendResult` | Require non-empty `sendIntentId` and exact `paymentHash`, `amountSat`, and `expiresAtUnix` agreement with the decoded challenge. Quote does not dispatch and its intent is not persisted. |
| Activity replay | register `subscribe(listener)`, then `startActivity({includeExisting:true,kinds:["send"],cursor:0})` | Match only `Entry.kind == "send"` with every present request/progress payment hash equal to the stored hash. Stream starts before dispatch. |
| Snapshot | bounded `list({view:"activity",pendingOnly:false,kinds:["send"],limit:100,cursor?}) -> ListResult` | Validate tagged activity shape, page-local total, cursor progress, five-page bound, and uniqueness. Snapshot completion without a preimage is not payer proof. |
| Dispatch | exactly one `sendPrepared(PrepareSendResult) -> SendResult` | Persist conservative recovery metadata first. Treat response as an initial activity entry; a throw, timeout, or completely lost response never permits redispatch. |
| Completion | activity `Entry{status:"complete",progress:{paymentHash,preimage}}` | Require 32-byte lowercase hex and verify Web Crypto SHA-256 against the decoded invoice hash; zero temporary byte arrays where JavaScript permits. |
| Retry | same original GET target with ephemeral `Authorization: L402 ...` | Never persist the preimage/header. Keep metadata after 402, 503 exhaustion, or any non-200. Clear only after exactly 200 or explicit warned abandonment. |

`sessionStorage` was selected after classification: it is same-origin script-readable and carries confidential invoice, macaroon, target, hash, amount, and timing metadata, but no payer proof. It survives same-tab reload and normally ends with the tab/session; loss is outside automatic recovery. Access, quota, read-back, corruption, or cleanup failures fail closed. An unresolved record disables new dispatch. The harness deliberately treats SDK timeout/failed activity as unknown because v0.1.1 does not prove conclusive cancellation semantics.

Deterministic tests cover storage write/read/cleanup failures, malformed stored data, quote/dispatch/activity shape checks, loss of the complete dispatch response, reload after durable storage but before dispatch, immediately after dispatch, while pending, and after settlement before protected 200, delayed completion, invalid preimage, original target/header reconstruction, and exact one-dispatch reconciliation.

Clean-profile verification used Node 26.8.1/npm 11.19.0, Playwright 1.63.0, and Chrome for Testing 153.0.8010.12 (Chromium 1243) on macOS 26.6.2 arm64. The v0.1.1 archive and eight local assets were hash-verified. With the exact CSP/COEP/COOP/CORP/MIME/cache headers in `setup.md`, a temporary fresh persistent profile reported a secure and cross-origin-isolated context, WebAssembly, Worker, Web Crypto, Web Locks, and OPFS API availability; `createWebClient().ready()` reached `runtime_ready`, and synthetic session data survived reload. Tracing, HAR, video, screenshots, and raw console capture were disabled.

Limits: the check did not call wallet `start`, create, unlock, fund, board, or dispatch. It therefore does not establish SQLite nested-worker/OPFS database operation, quota/persistence under wallet load, supported browser versions beyond the observed Chromium build, whether each isolation header is individually necessary, external-invoice preimage availability, or post-reload replay of a real preimage. Those remain T7 live gates, and an unsupported replay must stop the work without private APIs or repayment.

## Promotion-gate disposition

| Gate | T0 disposition |
|---|---|
| Exact daemon/SDK/runtime pins | Resolved for spike: v0.1.1 set and immutable hashes recorded. |
| Unchanged `LightningBackend` fields expressible | Documented feasible; restart persistence and bounded lookup remain live required. |
| Acceptable maintained JVM decoder | Feasible and offline checked; final transitive/native and real-signet validation remain required. |
| Supported payer dispatch/activity APIs | Public v0.1.1 shapes are compiled and exercised against deterministic doubles; real wallet behavior remains live required. |
| Supported preimage after external payment | T6 validates synthetic preimages locally; real externally paid preimage remains T7 live required. |
| Lost-response/reload recovery without second dispatch | Deterministic T6 state-machine coverage passes through public activity replay/list shapes; real replay remains T7 live required and a hard stop if it fails. |
| Explicit receive expiry | Absent; verified BOLT11 expiry is the only allowed source. |
| Exact receive idempotency key | Absent; `Recv` must not retry. |
| Least-privilege receiver credential | Not operationally available from a supported v0.1.1 baking surface found by T0; promotion blocker. |
| Browser L402 dependency | T6 uses the separately approved narrow spike-local parser/formatter; fresh-context review is required and production dependency selection remains unresolved. |
| Receive asset/custody/exit claims | Live required per observed mode; credit receipt proves only server-credit settlement and L402 compatibility. |

T0 permits only the next explicitly approved Phase 0 work while these gates remain enforced. It does not approve T6, any payment, Phase 1 work, publication, mainnet use, or a custody claim.

## T7 preparation and blocked live attempt — 2026-09-16

**Disposition: operator prerequisites unavailable; T7 is not complete. No hard vendor capability has been disproved or passed.** The bounded harness is implemented for review, not evidence that the funded flow works.

The current canonical invocation with `--build-cache` failed nonzero before any daemon API, wallet lifecycle, browser wallet startup, dispatch, restart, or dependency fault. Its sanitized partial record is:

- run ID: `afa43183-16d9-4135-84bf-b0bf836d4198`;
- manifest SHA-256: `bcb7f3642830dd349e319731f72bccea7042c2c67560b573ee01f37e5a696ab9`;
- ignored artifact: `paygate-integration-tests/build/wavelength-spike/afa43183-16d9-4135-84bf-b0bf836d4198/preflight/evidence.json`;
- gate `preflight`, outcome `unavailable`; acceptance rejected missing T7 execution/evidence.

All eleven original setup variables were absent: daemon TLS endpoint, credential path, dedicated receiver data directory, payer profile, pinned runtime directory, dedicated controller, and the receiver-ready/payer-ready/payer-funded/fresh-profile/payment-authorization confirmations. No `waved` executable was found on PATH and no exact-name daemon process was found. This does not rule out an installation elsewhere. No dedicated daemon or wallet data directory has been identified, so no lifecycle operation is authorized by inference. T7 additionally requires the concrete binary/PID/controller identities, fixed payer origin, reviewed controller and unpaid-history/dependency-fault confirmations documented in [setup.md](setup.md#t7-bounded-direct-live-harness-operator-setup-required). Funding, boarding and out-of-band unlock remain operator prerequisites, not harness-created state.

Independent preparation verified 149 Java offline tests with zero failures/errors/skips and 28 browser tests plus typecheck/bundle. Clean-profile runtime/storage smoke and static live-route/worker CSP checks passed without starting a wallet. Spotless, both Wavelength PMD tasks, dependency provenance, report canary scans and default/check/release graph isolation passed. The opt-in-only guard rejected `-Pintegration` before live browser build; cache-enabled missing-prerequisite invocations allocated distinct run IDs and executed live preparation rather than reusing cached results. This is **not** T4's deferred successful-full-live-run-then-stopped-daemon proof.

Added synthetic coverage includes output-limit/timeout/nonzero subprocess failures; positive process identity/restart with wallet-file preservation; foreign PID and changed-controller rejection; partial fault restoration; ordered gate failure/stop behavior; fresh-client reconstruction; explicit fee versus missing fee; missing observation/integrity-only acceptance rejection; and real-response-drop adapter behavior over a test double. These tests cannot establish upstream persistence, live fee wire presence, actual receive asset, preimage replay, dependency readiness, or real wallet restoration.

The new direct browser flow deliberately uses a synthetic macaroon and an in-memory proof sink, not Paygate HTTP authorization. The live response-drop adapter invokes the real supported `sendPrepared` but withholds its entire result from the payment state machine, then reloads and reconciles through supported APIs; no live execution of that sequence occurred. T8 is untouched. All real receive/decode/payment/preimage/COMPLETE/custody/history/restart/outage/recovery gates, optional second mode and promotion decisions remain unpassed.

## T7 dedicated daemon setup observation — 2026-09-25

**Observed local setup only; no live capability gate passed.** The pinned `2e89c924…` checkout built with Go 1.26.3 using `make build-wavewalletrpc`; binary metadata confirms `dev,wavewalletrpc,swapruntime`. Its SHA-256 is `527f11ab81ca1f4c014e79ce18de1f4f316fdacec2aaa4379cb05b8269de1715`. The dedicated signet process uses an explicit preserved data directory and owns loopback listeners at gRPC `10029` and REST `10031`; generated TLS key, certificate, and macaroons are owner-restricted. Pinned `waved/gateway_server.go` serves REST via plaintext `http.Server.Serve`; only gRPC uses the generated TLS certificate. A certificate-verified HTTPS request to the REST port failed TLS negotiation; an unauthenticated HTTP Status request returned 500 before wallet creation/unlock. Neither response establishes authentication or readiness. An approved, dedicated `stunnel` 5.82 proxy now binds `127.0.0.1:10032` and forwards TLS to the loopback-only plaintext gateway, using the generated daemon certificate/key without disabling TLS verification. A dedicated owner-restricted PKCS12 truststore pins the public certificate for the live JVM (no system-wide trust change). Certificate-verified HTTPS and Java `HttpClient` with that store reached the gateway (HTTP 500 unauthenticated/uninitialized); Java default trust rejected the self-signed cert. The receiver wallet was then created and unlocked out of band by the operator. A no-input authenticated `wavecli getinfo` over daemon gRPC reported `network=signet`, `WALLET_STATE_READY`; a bounded authenticated HTTPS REST `Status` read through the scoped proxy returned HTTP 200 with `network=signet`, `ready=true`, `unlocked=true`. Only those allowlisted fields were reported; credential bytes and raw bodies were not captured. The pinned permissions map grants `Recv` `address:write`, but no invoice was created and actual Recv permission/amount/hash behavior remains a live T7 gate. No funded payment, restart, or dependency fault has occurred. Browser runtime assets (8/8) and the package lock match manifest hashes, but the spike's browser `/live` page supports only wallet start and operator-entered unlock; it does not create/fund/board a payer wallet. The operator then reported creating/backing up a dedicated same-origin signet browser wallet. A public faucet queued one funding request; an operator-reported explorer search showed one UTXO at 97 confirmations (the agent did not see the address or independently correlate it). The operator-reported SDK balance persisted at `confirmedSat=0`, `pendingInSat=63463`, `pendingOutSat=0`, `creditAvailableSat=0`: this is **not** available VTXO balance. A separate read-only, aggregate operator setup-page diagnostic built and passed synthetic-only tests and fresh read-only source review. The operator used it on the real payer: deposit activity counts were all zero, live VTXOs zero, and total on-chain history entries one; this did not identify the boarding stage. The operator confirmed the explorer's address and 63,463-sat amount matched the setup wallet without sharing identifiers. The operator used the reviewed follow-up diagnostic on the real payer: the single wallet-wide on-chain entry was `boarding` awaiting a round; boarding confirmed/recorded/other counts were zero, live VTXOs remained zero, Ark connection reported connected, and wallet tip height `323925` matched the independently read public signet tip. This localizes the unspent confirmed deposit's blocker to an incomplete boarding round **without** proving whether registration was attempted, accepted, or failed. The pinned public v0.1.1 browser facade exposes no `Board` operation. No manual board, additional funding, payer send or live T7 gate has occurred. No payer send, funded direct-vendor gate, restart, or fault has been demonstrated. See [setup.md](setup.md#t7-bounded-direct-live-harness-operator-setup-required).

## T8 blocked predecessor check — 2026-09-17

**Disposition: T8 did not proceed because the required T7 direct-vendor live gates still have no passed current-run evidence.** A cache-enabled canonical invocation was run only to confirm the hard predecessor state. It failed nonzero at preflight before any daemon API, wallet lifecycle, browser wallet startup, dispatch, payment, restart, dependency outage, Paygate adapter, or protected endpoint operation.

Sanitized current-run partial evidence:

- run ID: `8396a552-e01a-42a7-b263-8b061afd5b28`;
- manifest SHA-256: `bcb7f3642830dd349e319731f72bccea7042c2c67560b573ee01f37e5a696ab9`;
- ignored artifact: `paygate-integration-tests/build/wavelength-spike/8396a552-e01a-42a7-b263-8b061afd5b28/preflight/evidence.json`;
- gate `preflight`, outcome `unavailable`; acceptance rejected missing T7 execution/evidence.

The failure reported missing daemon endpoint, credential file, receiver data directory, fresh browser profile, pinned browser runtime assets, isolated restart executable, and the five explicit readiness/funding/fresh-profile/payment-authorization confirmations. No T8 test-only `LightningBackend` adapter or Spring `@TestConfiguration` harness was added. T4's deferred stopped-daemon/build-cache freshness check still cannot be executed because it requires a prior successful full live invocation.
