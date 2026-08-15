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

- **Rate-limit identities are not identities.** IPv6 challenge-rate limiting groups addresses by the configurable `paygate.rate-limit.ipv6-prefix-length` prefix (0–128 bits). This is an abuse-control bucket, not a user identity or authorization boundary.
- **Forwarded client addresses depend on proxy trust.** `X-Forwarded-For` is considered only when `paygate.trust-forwarded-headers=true` and the direct peer is listed in `paygate.trusted-proxy-addresses`. Do not treat forwarded headers from untrusted peers as authoritative; configure every trusted proxy correctly.
- **Cache eviction is not credential revocation.** The bounded credential cache is an optimization. Eviction, expiry, or capacity removal does not itself revoke a still-valid credential; authoritative credential and root-key validation must remain server-side.
- **Protected request bodies are bounded, not streaming support.** `paygate.request-body.max-bytes` is constrained by `SecurityBounds` to 1–16,777,216 bytes. An over-limit body is rejected before protected handler work, but this bounded capture does not make arbitrary streaming or upload workloads suitable.
- **Filter coverage depends on supported integration paths and deployment wiring.** Enforcement selects payment-required endpoints only through the supported servlet and Spring Security paths. Filter placement, redispatch behavior, and application routing configuration affect that coverage and must be verified in the deployed application.
- **Filesystem secrets rely on host controls.** File-backed root keys and LND TLS-certificate/macaroon mounts depend on secure host ownership, permissions, and trusted mount behavior. Paygate does not make cross-platform filesystem-security guarantees; protect these paths at the operating-system and deployment layers.
