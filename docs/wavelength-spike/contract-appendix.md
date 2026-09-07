# Wavelength v0.1.1 Contract Appendix

Evidence date: 2026-09-07  
Scope: Phase 0 T0 source/package review and an offline JVM decoder check. **No live gate is passed.**

## Evidence labels

- **Documented:** present in immutable v0.1.1 daemon/SDK source or published package types.
- **Offline checked:** executed without a daemon, browser wallet, or payment.
- **Live required:** cannot be promoted from source inspection.
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

No browser L402 package was approved. `l402-requests@0.8.0` has useful challenge/header APIs and a custom wallet interface, but its root export graph includes Node-only modules and its `payInvoice(): Promise<preimage>` abstraction cannot represent the required prepare/dispatch/recovery lifecycle. `l402@0.5.1` is browser-oriented but stale and directly couples payment to retry without durable unknown-outcome recovery. Do not write a local codec without the separate security review required by the design.

## Browser runtime inventory

Documented v0.1.1 requirements/candidates:

- self-host all eight hashed runtime files listed in `compatibility.json`; mutable CDN assets are prohibited;
- default dedicated Web Worker plus nested SQLite worker, WebAssembly, fetch, OPFS persistence, and Web Locks;
- encrypted seed remains in OPFS; app-level wallet markers may use local storage, but the L402 recovery record requires a separate access/metadata/expiry review;
- Cache Storage is optional; no `DecompressionStream` is acceptable when raw WASM is served;
- runtime assets and network endpoints may require `worker-src`, `script-src`, and `connect-src` allowances.

The selected SDK publishes no exact browser support matrix. Secure-context behavior, CSP directives, CORS/same-origin layout, quota/persistence, cross-origin isolation, declared browser versions, and recovery-record storage safety are live clean-profile gates, not documented successes.

## Promotion-gate disposition

| Gate | T0 disposition |
|---|---|
| Exact daemon/SDK/runtime pins | Resolved for spike: v0.1.1 set and immutable hashes recorded. |
| Unchanged `LightningBackend` fields expressible | Documented feasible; restart persistence and bounded lookup remain live required. |
| Acceptable maintained JVM decoder | Feasible and offline checked; final transitive/native and real-signet validation remain required. |
| Supported payer dispatch/activity APIs | Documented candidates exist. |
| Supported preimage after external payment | Live required. |
| Lost-response/reload recovery without second dispatch | Source-supported candidate via activity replay; live required and a hard stop if replay fails. |
| Explicit receive expiry | Absent; verified BOLT11 expiry is the only allowed source. |
| Exact receive idempotency key | Absent; `Recv` must not retry. |
| Least-privilege receiver credential | Not operationally available from a supported v0.1.1 baking surface found by T0; promotion blocker. |
| Browser L402 dependency | Unresolved; T6 blocker pending maintained browser-safe package or separate security review. |
| Receive asset/custody/exit claims | Live required per observed mode; credit receipt proves only server-credit settlement and L402 compatibility. |

T0 permits only the next explicitly approved Phase 0 work while these gates remain enforced. It does not approve T6, any payment, Phase 1 work, publication, mainnet use, or a custody claim.
