# Low-Security Fixture Mutations

This directory holds deliberately invalid, non-secret mutations used by the low-security security
regression tests. Each mutation must violate exactly one control so a failure identifies the
missing protection unambiguously.

## Rules

- Use only the marker values from
  `paygate-integration-tests/src/securityTest/resources/low-security/marker-secrets.txt`.
- Markers are test sentinels, never real API keys, macaroons, preimages, invoices, payment hashes,
  TLS material, credentials, or passwords.
- Keep a case minimal: modify one security-relevant field and preserve all unrelated valid input.
- Do not add an expected successful command, release evidence, or an approved finding disposition
  to a fixture. Fixtures only describe negative controls.
- Error output, screenshots, captured logs, and committed test data must replace marker values with
  `[REDACTED-MARKER]`.
- If a test needs a new marker, add a descriptive, non-secret sentinel to the shared corpus rather
  than embedding it in a fixture.
