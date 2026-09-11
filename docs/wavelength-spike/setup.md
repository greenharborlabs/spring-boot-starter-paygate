# Wavelength Phase 0 Spike Setup

This guide pins the prerequisites selected by T0. It does **not** authorize a payment or claim that any live capability gate passed. The eventual live command remains:

```bash
./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike
```

T1 added that task outside ordinary build and CI lifecycles. T4 adds mandatory preflight and fresh-evidence enforcement, but no vendor capability probe; a green T4 integrity gate is not a successful Phase 0 capability result.

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
