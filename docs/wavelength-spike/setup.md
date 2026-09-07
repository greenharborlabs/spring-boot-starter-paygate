# Wavelength Phase 0 Spike Setup

This guide pins the prerequisites selected by T0. It does **not** authorize a payment or claim that any live capability gate passed. The eventual live command remains:

```bash
./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike
```

That task does not exist until the separately approved T1 work.

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

## Preflight before any live payment

The future task must fail before network activity unless all of these are present:

1. exact daemon and SDK/runtime manifest identities;
2. dedicated receiver daemon, wallet data directory, TLS endpoint, and credential path;
3. fresh browser profile with the exact local assets and supported persistent storage;
4. initialized/unlocked receiver and payer wallets on signet;
5. payer spendable balance sufficient for principal and fees;
6. operator authorization to make one bounded 10-sat signet payment;
7. an isolated daemon restart mechanism that preserves its wallet data;
8. an allowlisted evidence directory with a new run ID.

Never place a mnemonic, password, macaroon, preimage, full invoice, full payment hash, full L402 credential, browser network trace, or raw daemon response in checked-in files or test output.

## Stop conditions retained from the design

Stop before harness expansion or promotion if the supported APIs cannot provide a payer preimage, lost-response/reload recovery without redispatch, exact receiver reconstruction after restart, or an acceptable JVM decoder. T0 found a viable JVM candidate and documented SDK candidates, but all payer/restart behavior remains unproved until the funded live spike. The browser L402 package choice also remains blocked pending a browser-safe maintained package or a separate security review of a narrow local codec.
