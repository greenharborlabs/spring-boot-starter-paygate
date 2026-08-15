# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.x     | Yes       |

## Reporting a Vulnerability

**Do NOT open a public issue for security vulnerabilities.**

Email [security@greenharborlabs.com](mailto:security@greenharborlabs.com) with:

- Description of the vulnerability
- Steps to reproduce
- Potential impact

## Response Timeline

- **48 hours**: Acknowledgment of your report
- **7 days**: Initial assessment and remediation plan
- Fixes will be released as patch versions with a coordinated disclosure

## Operational Security Limitations

Paygate's controls are deliberately scoped. Deployments must account for these boundaries:

- **MPP is an exact-request reusable bearer, not replay prevention.** The authenticated binding covers method, application-relative path, exact raw-query presence and value (absent and empty differ), bounded body digest, and expiry. The credential can be presented repeatedly until expiry for only that request identity and is transferable to anyone who obtains it; Paygate has no single-use ledger.
- **Required L402 boundaries are fail closed.** Fresh and cached authorization requires root-key state plus authenticated service, route, actual method, `valid_until`, and issued capability ceiling. A cache hit may avoid signature recomputation but still revalidates authoritative root-key state and request boundaries; cache eviction alone is neither revocation nor proof of invalidity.
- **Rate-limit identities are not identities.** `paygate.rate-limit.ipv6-prefix-length` defaults to 64 and accepts 0–128 bits. Subnet rotation and eviction from the bounded bucket cache can restore burst capacity, so this remains abuse control rather than an identity or authorization boundary.
- **Forwarded client addresses depend on proxy trust.** `X-Forwarded-For` is considered only when `paygate.trust-forwarded-headers=true` and the direct peer is listed in `paygate.trusted-proxy-addresses`. Trusted-proxy resolution occurs before rate identity and `client_ip` evaluation. Built-in `client_ip` caveats use literal exact-string comparison; they perform no DNS lookup or CIDR interpretation.
- **Protected request bodies are bounded, not streaming support.** `paygate.request-body.max-bytes` defaults to 8192 and accepts 1–16,777,216 bytes (16 MiB). An over-limit body is rejected before protected handler work, but bounded capture does not make arbitrary streaming or upload workloads suitable.
- **Presented invalid credentials do not create recovery artifacts.** Malformed credentials return a stable safe 400; structurally valid but invalid, expired, or insufficient credentials return 402 without a replacement invoice or root key. Rate limits return 429. Lightning outages and unexpected server failures return 503, do not consume client-fault penalties, and never permit protected work.
- **Filter coverage depends on supported integration paths and deployment wiring.** Spring MVC policy resolution is deterministic, mapping-equivalent, and redispatch-aware; unresolved policy conflicts fail startup. In Spring Security mode, each effective chain serving paid routes must contain Paygate enforcement. Verify REQUEST, ASYNC, FORWARD, and ERROR dispatches against the deployed routing topology.
- **Test mode is restricted to safe profiles.** Every active profile must be one of `test`, `dev`, `local`, or `development`; production-like or unrecognized active profiles cause startup failure. Never package test credentials or enable test mode as a production fallback.
- **Only verified authorization data is trusted.** Only values from a uniquely owned caveat key whose verifier succeeds become authentication attributes or authorities. Authenticated state is credential-free: it retains no raw header, parsed bearer, or payment preimage. Components receiving `SensitiveBytes` or other destroyable values must follow their ownership-transfer and deterministic `close()`/`destroy()` contracts; best-effort zeroization cannot erase immutable string copies.
- **Parsing and delegation support are deliberately strict.** Noncanonical/bounded macaroon, MPP JSON, UTF-8, and base64url inputs fail closed. Third-party caveats and additional/discharge macaroons are unsupported and rejected, not exposed as partially verified metadata.
- **Provider trust stops at verified payment data.** LND and LNbits boundaries reject wrong-length hashes/preimages and a paid response whose preimage does not match the queried payment hash. Plaintext opt-ins are local-only, and ambiguous numeric loopback forms are evaluated by canonical address bytes rather than trusted by spelling.
- **Filesystem secrets rely on host controls.** File-backed root-key storage requires protections equivalent to `0700` directories and `0600` files and refuses unsafe or symlinked root-key paths. LND secret/certificate symlink mounts are supported only as a documented trusted-orchestrator case. Protect ownership, parent directories, and mounts at the host layer.
- **Observability is bounded and redacted.** Logs, fixed client details, metrics, and health output exclude bearer headers, complete token IDs, preimages, root keys, backend credentials, and binding secrets. Metrics use bounded registered-route labels; conditional health and the opt-in actuator endpoint are operational signals, not an authorization audit trail.

## Security-Relevant Configuration

| Property | Default | Accepted values | Failure and operator impact |
|----------|---------|-----------------|-----------------------------|
| `paygate.request-body.max-bytes` | `8192` | `1..16777216` bytes | Invalid configuration is rejected; an oversized protected request fails before handler execution. Size this for the largest paid request you intentionally support. |
| `paygate.rate-limit.ipv6-prefix-length` | `64` | `0..128` bits | Invalid configuration is rejected. Smaller prefixes group more clients; larger prefixes make subnet rotation easier. Trusted-proxy resolution runs first. |

## Upgrade and Rollback Guidance

Before rollout, inventory live credentials and custom issuers, exact MPP query construction, trusted proxies, protected body sizes, root-key/LND mounts, and every servlet or Spring Security chain that can reach a paid route. Canary the production dispatch topology and confirm safe 400/402/429/503 responses, zero replacement artifacts for presented invalid credentials, fail-closed backend outages, and secret-free diagnostics.

This release intentionally rejects legacy identifier-v0 or boundary-incomplete L402 credentials, noncanonical credential encodings, MPP credentials without authenticated expiry, requests whose exact raw-query identity differs, third-party caveats, and additional macaroons. Drain old credentials where possible; otherwise clients must obtain and pay a new challenge. A rollback can re-enable the rejected legacy surface and does not make already rejected credentials safe. Preserve root keys and current/previous MPP binding secrets only for the planned migration window, and never move them through logs, support tickets, or release evidence.
