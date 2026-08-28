# Dependency-Check Risk Dispositions

## CVE-2025-32013 — LNbits server

- Disposition: False positive for the Paygate LNbits Java client adapter.
- Scope: `com.greenharborlabs:paygate-lightning-lnbits` at the current build version only.
- Rationale: The advisory applies to server-side LNURL authentication handling in the Python
  LNbits server. The scoped artifact is Paygate's Java HTTP client adapter and does not contain or
  embed the LNbits server.
- Owner: Green Harbor Labs maintainer.
- Approval: Maintainer review and merge of the security-hardening pull request containing this
  disposition.
- Status: Proposed for maintainer approval.
- Review date: 2026-09-15, and whenever the project version changes.
- Compensating controls: The exact package URL prevents the rule from applying to any external
  LNbits artifact or later Paygate version, and Dependency-Check continues to fail on every other
  advisory with a CVSS score above zero.
- Scanner evidence: Dependency-Check 13.0.0 identifies a low-confidence LNbits server CPE solely
  from the client adapter's artifact/package name; the report's package URL points to the distinct
  `com.greenharborlabs` Maven coordinate.
