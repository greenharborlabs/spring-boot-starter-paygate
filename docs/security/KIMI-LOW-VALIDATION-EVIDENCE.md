# Kimi Low Security Validation Evidence

This append-only ledger records only completed, redacted executions at a reviewed Git revision.
Do not add planned, skipped, cancelled, or failed work as a passing record. Current records must use
a 40-character revision, UTC timestamps, exit status `0`, a durable artifact/report reference, and
a redaction attestation. The absence of records below is intentional until Phase 11 completes.

| Evidence ID | Implementation revision | Started (UTC) | Finished (UTC) | Command or scenario | Environment | Result | Exit/status | Findings | Artifact/report | Redaction attestation | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Required evidence groups

`LOW-EV-CORE` covers L-1–L-8; `LOW-EV-WEB` L-9–L-16; `LOW-EV-LIGHTNING` L-17–L-21;
`LOW-EV-PROVENANCE` L-22; `LOW-EV-IGNORE` L-23; `LOW-EV-FIXTURE-STATIC` and
`LOW-EV-FIXTURE-RUNTIME` L-24–L-28; `LOW-EV-ADVISORY` L-28; `LOW-EV-COMPAT` L-4, L-7, L-13,
L-15, L-16, and L-21; `LOW-EV-QUALITY`, `LOW-EV-RELEASE`, and `LOW-EV-LEDGER` all findings.

Records must not include root keys, credentials, proofs, full token IDs or payment hashes, generated
passwords, backend text, descriptions, secret paths, or attacker-controlled exception messages.
