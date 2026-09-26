# Wavelength Phase 0 Spike Setup

This guide pins the prerequisites selected by T0. It does **not** authorize a payment or claim that any live capability gate passed. The eventual live command remains:

```bash
./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike
```

T1 added that task outside ordinary build and CI lifecycles. T4 adds mandatory preflight and fresh-evidence enforcement, and T5 adds the offline receiver probe. Neither a green integrity gate nor synthetic receiver/browser checks are a successful Phase 0 live capability result.

## Pinned compatibility set

Use the complete identities and hashes in `compatibility.json`:

- receiver: Wavelength `v0.1.1`, commit `2e89c924e2e10095f7baf736bc9eefdabcd836ce`;
- browser SDK: `@lightninglabs/wavelength-core` and `@lightninglabs/wavelength-web` `0.1.1`, SDK commit `cd136619838dd15aa74c11ab4545a5788c48ad94`;
- browser runtime: `Wavewalletdk.wasm.tar.gz` from the Wavelength `v0.1.1` release, self-hosted under a versioned URL;
- JVM decoder candidate: `fr.acinq.lightning:lightning-kmp-core-jvm:1.13.0`;
- browser decoder candidate: `farrier-kit@1.1.3`.

Do not substitute an RC, mutable documentation build, unpinned CDN URL, or default `waved` release binary.

## Receiver daemon

The pinned `go.mod` requires Go 1.26.0. Although the release `INSTALL.md` still says 1.25.5 or later, the module directive is authoritative for this checkout.

```bash
git clone https://github.com/lightninglabs/wavelength.git
cd wavelength
git checkout 2e89c924e2e10095f7baf736bc9eefdabcd836ce
test "$(git rev-parse HEAD)" = 2e89c924e2e10095f7baf736bc9eefdabcd836ce
make build-wavewalletrpc
```

`make build-wavewalletrpc` adds both `wavewalletrpc` and `swapruntime`. The published platform archives use an empty `RELEASE_TAGS` value and cannot serve `Status`, `Recv`, `List`, or `InspectActivity`; the spike must build the pinned source variant.

Run a dedicated signet instance with a dedicated data directory. Use `lwwallet`, system-trusted TLS, and the pinned source's built-in signet Ark, swap, and Esplora endpoints unless explicit endpoints are being tested. Keep wallet `Create`/`Unlock` outside Paygate. The daemon creates TLS material plus `admin.macaroon` and `readonly.macaroon` under its network data directory; the receiver operations include the write-scoped `Recv`, so the generated read-only credential is insufficient. No supported custom macaroon-baking CLI/API was found in v0.1.1, making admin credentials an experimental-only limitation and a production promotion blocker.

Pass the credential **path** to the future spike through an environment variable, never its bytes or a Gradle property. The file must be readable, regular, non-symlinked, owner-restricted, and outside the repository. The REST client sends its lowercase hexadecimal bytes in the `macaroon` header over TLS.

## Browser payer and runtime

Create a separate browser wallet/profile and host these eight files from the verified runtime archive at one immutable same-origin or explicitly reviewed CORS-enabled base URL:

```text
wavewalletdk.wasm
wavewalletdk.wasm.gz
wasm_exec.js
sqlite-bridge.js
sqlite-worker.js
sqlite3.js
sqlite3.wasm
sqlite3-opfs-async-proxy.js
```

Install exact SDK versions with a committed lockfile; verify npm integrity values from `compatibility.json`. `runtimeBaseUrl` must point at the versioned asset directory and `defaultConfig("signet")` selects the public signet services. The package defaults to a dedicated Web Worker; the runtime also requires WebAssembly, nested workers, fetch, OPFS for persistent encrypted wallet databases, and Web Locks for reliable cross-tab exclusion. Cache Storage is an optional performance cache. `DecompressionStream` is optional because the raw WASM file is the fallback. Secure-context, CSP (`worker-src`, `script-src`, and `connect-src`), same-origin/CORS, storage quota/persistence, browser versions, and whether cross-origin isolation is required remain clean-profile live gates; v0.1.1 does not publish a supported-browser matrix.

Create or unlock the payer wallet in the browser/operator boundary. Fund it with signet bitcoin using its boarding/deposit flow, wait for confirmation and boarding into spendable Ark value, and verify enough spendable balance for the invoice plus fees. The receiver wallet must also be created/unlocked out of band and connected to the public signet Ark and swap services. Whether a receive uses server credits or a client-claimable swap path must be observed rather than assumed; funding and boarding requirements may differ by mode.

## T6 browser capability and recovery harness

The minimal harness is under `paygate-integration-tests/src/wavelengthSpike/browser/`. It pins Wavelength Web/Core 0.1.1, farrier-kit 1.1.3, TypeScript 5.9.3, esbuild 0.28.2, and Playwright 1.63.0 in `package-lock.json`. Node 20 or later is supported by the selected browser tooling; T6 was observed with Node 26.8.1 and npm 11.19.0 on macOS 26.6.2 arm64.

Run deterministic checks without a daemon, wallet, runtime archive, or payment:

```bash
./gradlew :paygate-integration-tests:wavelengthSpikeBrowserCheck -Pintegration --no-daemon
```

Fetch the immutable v0.1.1 runtime archive and verify the archive plus every extracted asset against `compatibility.json`, install the Playwright-pinned Chromium build, then run the clean-profile smoke check:

```bash
./gradlew :paygate-integration-tests:wavelengthSpikeBrowserRuntime -Pintegration --no-daemon
cd paygate-integration-tests/src/wavelengthSpike/browser
npx playwright install chromium
cd ../../../..
./gradlew :paygate-integration-tests:wavelengthSpikeBrowserSmoke -Pintegration --no-daemon
```

Alternatively set `WAVELENGTH_SPIKE_RUNTIME_DIR` for the smoke task to an existing directory containing the exact eight hash-verified files. The smoke server uses loopback HTTP, which Chromium treats as a secure context, and serves only local bundles/runtime files. It sends:

```text
Content-Security-Policy: default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'; img-src 'self'; style-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
Origin-Agent-Cluster: ?1
Permissions-Policy: camera=(), microphone=(), geolocation=()
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
```

Runtime assets receive `public, max-age=31536000, immutable`; the page and bundle receive `no-store`. WASM is `application/wasm`, the compressed WASM is `application/gzip` without `Content-Encoding`, and JavaScript is `text/javascript`. T6 observed `createWebClient().ready()` reach `runtime_ready` in Chrome for Testing 153.0.8010.12 (Playwright Chromium 1243), with `isSecureContext`, WebAssembly, Worker, Web Crypto, Web Locks, the OPFS API, and `crossOriginIsolated` available. A synthetic `sessionStorage` marker survived a same-tab reload in the temporary clean profile. Because T6 deliberately does not call `start`, create/unlock a wallet, or pay, this does not yet prove nested SQLite-worker behavior, quota/persistence, individual-header necessity, or preimage replay.

The recovery state machine uses only public v0.1.1 APIs:

```text
prepareSend({ invoice })
  -> validate paymentHash, amountSat, expiresAtUnix, and non-empty sendIntentId
subscribe(listener)
startActivity({ includeExisting: true, kinds: ["send"], cursor: 0 })
list({ view: "activity", pendingOnly: false, kinds: ["send"], limit: 100, cursor? })
  -> persist original challenge with dispatchMayHaveOccurred=true
sendPrepared(prepared) exactly once
  -> wait up to 120 seconds for matching terminal activity
reload/lost response
  -> repeat subscribe/startActivity/list for the stored paymentHash; never redispatch
```

`sendIntentId` is short-lived and single-use, so it is deliberately not persisted or reused. `SendResult` is treated as an initial activity result, not settlement. List snapshots can establish status but may omit the preimage; only a supported activity event/replay carrying `progress.preimage` can unlock. A timeout or failed entry remains blocked because v0.1.1 does not establish conclusive cancellation semantics for this harness.

The same-origin `sessionStorage` record contains schema version, original invoice and macaroon, exact fragment-free GET target, payment hash, amount, verified invoice expiry, creation time, and the conservative dispatch marker. Same-origin scripts can read this confidential challenge/payment metadata. It contains neither the preimage nor an assembled Authorization value and cannot authorize alone. Storage access failure or corruption prevents dispatch or leaves recovery blocked. The complete record is cleared only after the original protected request returns exactly 200 or after an explicit operator abandonment that may forfeit a paid unlock. Session/tab/profile storage loss is outside automatic recovery and is never evidence that no payment occurred.

T0 rejected the evaluated browser L402 packages because they either bundle Node-only wallet modules or cannot represent this recovery lifecycle. T6 therefore uses only the separately approved spike-local narrow boundary: strict parsing of Paygate's exact bounded `version="0"` header, identical canonical `token`/`macaroon`, verified BOLT11 fields, and formatting of `L402 <macaroon>:<64-hex-preimage>`. It is not a generic codec or auto-paying client and remains subject to fresh-context security review. No private Wavelength API or repayment fallback is used.

## Browser evidence capture policy

Before any live browser run, configure the harness to emit only reviewed structured event codes through the allowlisted evidence writer. Do not retain raw console message text or arguments. Console events may identify a fixed harness state such as `runtime_ready`, `payment_required`, `paying_over_lightning`, `unlocked`, or `payment_outcome_unknown`; wallet/SDK objects and exception payloads are not evidence fields.

Disable browser and Playwright tracing, HAR/network recording, network request or response payload capture, video, and screenshots whenever a real wallet or payment is present. In particular, do not capture the wallet UI, browser developer tools, invoices, payment hashes, preimages, macaroons, authorization values, or upstream REST bodies. A later screenshot exception requires synthetic data or explicitly masked content plus separate reviewed secret-scanning tests before capture is enabled. T3 establishes this policy only; browser tooling and clean-profile verification remain T6 work.

## Preflight before any live payment

The live task fails before any test method or payment operation unless these environment variables satisfy the fixed contract:

| Variable | Required value |
|---|---|
| `WAVELENGTH_SPIKE_DAEMON_URL` | Authenticated daemon HTTPS endpoint with no user info or fragment |
| `WAVELENGTH_SPIKE_CREDENTIAL_PATH` | Readable regular non-symlink file satisfying the restrictive credential policy |
| `WAVELENGTH_SPIKE_RECEIVER_DATA_DIR` | Readable, writable, non-symlink dedicated receiver data directory |
| `WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR` | Readable, writable, non-symlink dedicated browser profile directory |
| `WAVELENGTH_SPIKE_RUNTIME_DIR` | Directory containing all eight runtime assets with the hashes in `compatibility.json` |
| `WAVELENGTH_SPIKE_RESTART_EXECUTABLE` | Executable regular non-symlink wrapper that restarts only the dedicated daemon while preserving wallet data |
| `WAVELENGTH_SPIKE_RECEIVER_READY` | Literal `confirmed`: receiver wallet initialized/unlocked on signet |
| `WAVELENGTH_SPIKE_PAYER_READY` | Literal `confirmed`: payer wallet initialized/unlocked on signet |
| `WAVELENGTH_SPIKE_PAYER_FUNDED` | Literal `confirmed`: spendable balance covers the 10-sat principal and fees |
| `WAVELENGTH_SPIKE_BROWSER_PROFILE_FRESH` | Literal `confirmed`: the dedicated profile and persistent storage were prepared for this run |
| `WAVELENGTH_SPIKE_PAYMENT_AUTHORIZED` | Literal `confirmed`: operator authorizes one bounded 10-sat signet payment |

The task computes the current `compatibility.json` SHA-256, verifies it against T4's reviewed pin, allocates a UUID directory at `paygate-integration-tests/build/wavelength-spike/<run-id>/`, and writes gate-scoped `evidence.json` files there. Preflight failures report prerequisite names only, never environment values or paths. Each invocation runs with Gradle up-to-date reuse, build-cache load/store, and configuration-cache reuse disabled for live work. Acceptance requires exactly one successful execution of every currently registered mandatory test and exact evidence identities for that run and manifest; zero discovery, filtering, skipping, stale artifacts, and missing or malformed evidence fail non-zero. Previous run directories are retained only for local review and are never searched to satisfy the current invocation.

T4 itself emits only `preflight` and acceptance-harness evidence. Status, receive, payment, recovery, restart, Paygate authorization, and outage evidence remain unimplemented until T5–T8 and must not be inferred from a T4 run.

Never place a mnemonic, password, macaroon, preimage, full invoice, full payment hash, full L402 credential, browser network trace, or raw daemon response in checked-in files or test output.

### Deferred live freshness verification

After T8 produces the first genuinely successful full live invocation, rerun the same canonical command with `waved` stopped and Gradle build caching enabled (for example, add `--build-cache`). The second invocation must allocate a different run ID, execute rather than report `UP-TO-DATE` or `FROM-CACHE`, and fail non-zero on a current mandatory daemon-dependent gate. This obligation is not satisfied by T4's offline tests or by repeated missing-prerequisite failures.

## Stop conditions retained from the design

Stop before harness expansion or promotion if the supported APIs cannot provide a payer preimage, lost-response/reload recovery without redispatch, exact receiver reconstruction after restart, or an acceptable JVM decoder. T0 found a viable JVM candidate and documented SDK candidates, but all payer/restart behavior remains unproved until the funded live spike. The browser L402 package choice also remains blocked pending a browser-safe maintained package or a separate security review of a narrow local codec.

## T7 bounded direct live harness (operator setup required)

**Observed local TLS boundary (2026-09-25, pinned daemon):** the verified `wavewalletrpc,swapruntime` binary was started as a dedicated signet `lwwallet` with an explicit owner-restricted data directory and loopback gRPC (`127.0.0.1:10029`) and gateway (`127.0.0.1:10031`) listeners. The pinned `waved/gateway_server.go` uses `http.Server.Serve(listener)` without TLS: its **REST gateway is plaintext HTTP**, even when the gRPC listener has an auto-generated self-signed TLS certificate. An unauthenticated loopback REST Status probe returned HTTP 500 while the wallet remained uninitialized; this does not prove authenticated Status or wallet readiness. T7's `WAVELENGTH_SPIKE_DAEMON_URL` validator requires loopback **HTTPS**, and its JDK client uses default certificate trust. Do not point it at the plaintext gateway, disable TLS validation, or assume the generated certificate is trusted. An operator-approved, dedicated `stunnel` 5.82 proxy now binds only `127.0.0.1:10032` and forwards to `127.0.0.1:10031`; its owner-restricted configuration uses the dedicated daemon's generated TLS certificate/key. A dedicated owner-restricted PKCS12 truststore under the receiver's `tls-proxy/` directory contains only that public certificate, with no system-wide trust change. A certificate-verified HTTPS probe reached the gateway (HTTP 500, still unauthenticated/uninitialized); a Java `HttpClient` check rejected default trust but reached the gateway with the scoped truststore. This proves TLS transport and loopback binding, not a live T7 capability gate. The operator subsequently created/unlocked the dedicated receiver wallet out of band; authenticated gRPC `getinfo` reported `WALLET_STATE_READY` on signet and authenticated HTTPS `Status` returned `ready=true`, `unlocked=true`, `network=signet` with HTTP 200. The credential bytes and raw response were not recorded. The pinned permissions map requires `address:write` for `Recv`; actual invoice creation is still untested. Configure only the live JVM to use the scoped truststore after reviewing its startup environment; verify the proxy and receiver PID/port ownership before preflight. If the daemon rotates its certificate, re-pin the public cert and reverify hostname/trust. This is an operator setup boundary, not proof of payment or recovery.

T7 preparation adds an ordered direct-vendor runner; it does **not** establish any live capability by itself. A dedicated receiver has been identified and its wallet is ready, but no dedicated funded browser payer or reviewed restart/fault controller exists. Do not guess a default wallet directory, start another operator's process, or run a controller until the inventory below is verified. Missing prerequisites are a blocked run, not a disproved vendor capability. T7 remains unchecked until current-run live evidence passes.

First run the offline Java/browser checks and install the pinned Chromium/runtime as above. The canonical live command rebuilds the local browser bundle afresh (no dependency downloads in that substep); dependencies must already have been installed with the committed lockfile. It launches headed Chromium, not a headless wallet administrator. Keep the dedicated payer profile and its fixed loopback origin; prepare/create/fund/board that wallet separately. The live page permits **operator-entered unlock only**, never wallet creation, import, funding, or boarding. No dedicated funded browser profile has yet been prepared. The runtime files and package lock match the pinned manifest, but a wallet on a different origin does not populate this harness origin's OPFS. A separate operator-only `/setup` page is now implemented in the same loopback server, enabled **only** by the standalone `setup:operator` launcher; the payment runner cannot serve it. Review its seed-handling flow before use. Do not improvise with devtools output or run wallet creation through the evidence-capturing harness. Do not put the password in environment variables, command arguments, scripts, or this guide. The operator must be present to unlock initially and again after reload. There is no automatic repayment if this misses the deadline.

#### Browser payer provisioning (operator only; review before use)

The standalone setup launcher and payment runner must run **sequentially**, never at the same time, with the **same fixed port and persistent Chromium profile**. The server binds only `127.0.0.1` and checks the Host header, serves local pinned bundles/runtime assets with restrictive CSP and no cache, and never exposes `/setup` or its JavaScript during the live payment runner. The setup page cannot send payments: it can create a fresh wallet using the pinned SDK, show the one-time seed for offline backup, unlock an existing wallet, generate a signet deposit address, and display bounded balance fields. It never writes the seed or password to storage, browser automation, screenshots, scripts, logs, evidence, or stdout. No real browser wallet has been created by the agent or the tests.

**After code review, in the operator's own terminal only**, create an empty owner-restricted profile outside this repository and use the existing hash-verified runtime files. Reserve a fixed unoccupied loopback port (example `18732`) and keep it unchanged for every setup and live invocation. From `paygate-integration-tests/src/wavelengthSpike/browser`:

```bash
install -d -m 700 "$HOME/.local/share/paygate-wavelength-spike/payer-profile"
WAVELENGTH_SPIKE_RUNTIME_DIR="$PWD/build/runtime-v0.1.1" \
WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR="$HOME/.local/share/paygate-wavelength-spike/payer-profile" \
WAVELENGTH_SPIKE_BROWSER_PORT=18732 npm run setup:operator
```

Only click **Create** after checking the correct signet network and dedicated profile. Back up all 24 words privately in order **before** clicking the acknowledgement that clears them; do not paste or photograph them. If creation or seed display fails, is interrupted, or backup is uncertain, **do not retry creation or fund the wallet** until privately investigated. The address button allocates an address but does not send funds. Independently verify the `tb1` signet deposit address and funding source before transferring signet funds out of band. Wait for confirmation/boarding and verify **spendable** balance is greater than 10 sats plus fees; `pendingInSat` or available server credits alone are not proof of spendable funds or custody. Close the setup browser before starting the live runner. Never share seed, password, address, invoice, or raw browser output with the agent; report only safe ready/not-ready status. The operator will unlock again after the live runner's reload.

In addition to the eleven preflight variables above, supply:

| Variable | T7 requirement |
|---|---|
| `WAVELENGTH_SPIKE_DAEMON_EXECUTABLE` | Canonical absolute non-symlink executable of the dedicated source-built v0.1.1 daemon |
| `WAVELENGTH_SPIKE_DAEMON_PID_FILE` | Canonical, restrictive regular file containing only the dedicated PID; controller atomically updates it after restart |
| `WAVELENGTH_SPIKE_DAEMON_SHA256` | Lowercase SHA-256 of that verified binary |
| `WAVELENGTH_SPIKE_CONTROLLER_SHA256` | Lowercase SHA-256 of the reviewed controller executable supplied as `WAVELENGTH_SPIKE_RESTART_EXECUTABLE` |
| `WAVELENGTH_SPIKE_BROWSER_PORT` | Fixed unoccupied loopback port, 1024–65535, matching the prepared payer profile's origin |
| `WAVELENGTH_SPIKE_CONTROL_REVIEWED` | `confirmed` only after the controller and process/network/data-directory inventory are reviewed |
| `WAVELENGTH_SPIKE_HISTORY_AUTHORIZED` | `confirmed` authorizes exactly two additional **unpaid** 10-sat receive requests, no retry |
| `WAVELENGTH_SPIKE_DEPENDENCY_FAULT_AUTHORIZED` | `confirmed` authorizes one reversible, dedicated-daemon-only Ark or swap dependency fault |

The receiver endpoint must be loopback HTTPS with trusted TLS; no certificate-validation bypass is installed. Identify the executable, pinned source commit and `wavewalletrpc,swapruntime` build tags, PID/start time, exact explicit data-directory argument, endpoint/listener mapping, controller identity, and dedicated signet configuration **out of band before setting confirmations**. No concrete deployment paths are published here because none have been supplied or verified. The harness additionally checks the process executable, PID/start identity, binary/controller hashes, explicit directory argument, and directory file identity before mutation and across restart. These checks complement operator review; they do not make an arbitrary script safe.

### Reviewed controller contract

This is an installation-specific external executable, **not** an arbitrary shell string embedded in Gradle. No generic lifecycle script is supplied because the daemon installation, service manager, TLS trust and fault proxy have not been identified. Every action gets a single fixed argument, has a 30-second deadline, exits nonzero on failure, and produces no stdout. Stderr is discarded. It must never log wallet material elsewhere either.

- `verify`: read-only verification of the above inventory, source/build pins, dedicated endpoint/data-directory mapping, and normal dependency routing; verify no pre-existing foreign fault would be overwritten.
- `restart`: stop only the verified dedicated instance, start the same pinned binary/config against the **same existing wallet directory**, and update the PID file. Do not delete/recreate the directory or wallet files. Redirect daemon output to a reviewed secret-safe destination, not inherited command pipes. Unlock remains out of band.
- `fault-on`: enable a fault exclusively on an owned proxy/routing boundary used by that daemon and one named dependency. Never stop a public service or change a machine-wide firewall.
- `fault-verify`: independently confirm that the targeted dependency traffic is blocked and that the receiver RPC listener remains available. A no-op controller does not prove an outage.
- `fault-off`: undo only that owned fault, idempotently.
- `restore`: idempotently remove the owned fault and restore the original running daemon configuration, including after partial restart/fault failure. Preserve the wallet and payer profile. Do not affect unrelated processes or routing.

Mutation is marked before command dispatch because a lost response can hide a successful mutation. Cleanup executes even on hard-gate failure. The JVM polls receiver readiness after restoration; out-of-band unlock may still be required. If ownership changes or restoration cannot be verified, the run fails and the operator must inspect the dedicated installation. The subprocess runner bounds input/output and kills only its own command descendants on timeout; the controller remains responsible for its managed daemon/service state.

### Declared workload and evidence

One principal payment of 10 sats plus payer fees is authorized; two extra receiver invoices remain unpaid and are never retried. The live workload is intentionally small and sequential after settlement, **not** a claimed live page-boundary/concurrency/retention soak. Synthetic T5 coverage remains the evidence for large histories, concurrent pagination, page boundaries, ambiguity and exhaustion.

The live sequence is Status → Recv and JVM cross-check → actual browser dispatch with its entire response withheld → same-tab reload → supported replay with local preimage hashing and exactly-one-dispatch assertion → receiver COMPLETE → custody observation → two unpaid receives/history scan → dedicated restart/out-of-band unlock → fresh-client exact reconstruction → dependency fault/readiness observation → restoration.

The browser receives the invoice through a bounded private subprocess pipe, never an argument, file, or console. Its dispatch counter lives in the supervising Node process across reload; the second dispatch is refused. Initial settlement cannot clear the original recovery record. Only successful post-reload proof clears it. The synthetic macaroon and in-browser proof sink are explicitly **not** Paygate 402/200 evidence; no protected HTTP endpoint or T8 adapter is implemented.

Browser initialization/unlock is bounded to 120 seconds. Payment/reload/re-unlock/recovery must finish within 120 seconds from dispatch; timeout leaves the outcome unknown. The Node watchdog is 300 seconds, outer command deadline 330 seconds, termination grace three seconds. Controller actions allow 30 seconds; receiver settlement/readiness polling allows 120 seconds plus at most one already-started bounded RPC (10-second Status or 15-second lookup). Receiver calls retain the 256-KiB limit, no Recv retries, 100-entry pages, five-page/500-entry and 15-second overall lookup budget. Restoration has its own bounded command/readiness budget. A single fresh receiver client/mapper is used after restart; only the payment hash is supplied to lookup, with prior fields retained solely for comparison.

Evidence records actual scan counts, rank, age, lookup path, receive shape, confirmed/credit balance deltas and the activity's explicit `fee_sat` (missing fee fails the custody observation rather than becoming an assumed zero). The daemon's internal inspection-window capacity remains unverified; no retention duration is promised. Credit mode reports `server_credits`, server control/redemption dependency and unknown unilateral exit. Swap mode remains asset/control/redemption `unknown` until separate live custody evidence establishes more; a shape alone does not imply self-custodial receipt. Optional second mode is not exercised. Readiness during a verified dependency outage may remain true; that is recorded without claiming dependency-sensitive health or an affected-operation outage matrix (T8).

Each successful gate has a current-run/manifest completion record and an allowlisted `observation/evidence.json`. Failed preflight produces `outcome: unavailable`; later failed gates retain partial records with fixed outcomes. Raw invoice/hash/preimage, credentials, paths, upstream messages, browser traces/HAR/video/screenshots and SDK console are absent. Mandatory gates now include the T7 chain; integrity-only test success can no longer pass acceptance.

The live server retains the T6 headers and adds only these pinned signet origins to `connect-src` (including worker-script responses): `https://signet.wavelength-rest.lightning.finance`, `https://signet.swapd-rest.lightning.finance`, and `https://mempool-signet.testnet.lightningcluster.com`. Wallet startup, actual dependency CORS, OPFS persistence, funding, preimage replay and restoration still require live verification; static header checks do not prove them.

On timeout/failure the harness closes its owned browser context while preserving the encrypted wallet profile. It does not promise sessionStorage recovery after tab/process closure, and a failure is **never** evidence of non-payment. Inspect payer/receiver activity out of band before explicitly authorizing another run; do not blindly rerun or claim an unpaid unlock was recovered. This T7 direct probe has no valuable Paygate credential to preserve; the real original-challenge HTTP lifecycle remains T8.
