# Dependency-Check Risk Dispositions

## CVE-2025-32013 — LNbits server

- Disposition: False positive for the Paygate LNbits Java client adapter.
- Scope: `com.greenharborlabs:paygate-lightning-lnbits:0.1.6-SNAPSHOT` only.
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

## CVE-2026-66299 — Tomcat WebSocket chat example

- Disposition: False positive for the embedded artifacts used by Paygate.
- Scope: `org.apache.tomcat.embed:tomcat-embed-core:11.0.24` and
  `org.apache.tomcat.embed:tomcat-embed-websocket:11.0.24` only.
- Rationale: The advisory applies to the WebSocket chat example shipped in a full Tomcat
  distribution. Inspection of both embedded JARs confirms that neither contains the examples web
  application or its chat code.
- Owner: Green Harbor Labs maintainer.
- Approval: Maintainer review and merge of the security-hardening pull request containing this
  disposition.
- Status: Proposed for maintainer approval.
- Review date: 2026-09-15, or immediately when Tomcat 11.0.25 becomes available.
- Compensating controls: Paygate packages only the embedded artifacts, its example applications do
  not copy Tomcat's examples web application, and Dependency-Check continues to fail on every
  other advisory with a CVSS score above zero.
- Scanner evidence: Dependency-Check 13.0.0 report generated 2026-08-15 identified only
  CVE-2026-66299 after the dependency updates; `jar tf` inspection found no `examples/` entries in
  either scoped artifact.
