# Dependency-Check Risk Dispositions

## CVE-2025-32013 — LNbits server

- Disposition: False positive for the Paygate LNbits Java client adapter.
- Scope: `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@0.1.7`
- Rationale: The advisory applies to server-side LNURL authentication handling in the Python
  LNbits server. The scoped artifact is Paygate's Java HTTP client adapter and does not contain or
  embed the LNbits server. Both integration fixtures now use the supported, digest-pinned LNbits
  `v1.6.2` image, independently of this Maven suppression.
- Owner: Mark Hammond, Green Harbor Labs maintainer
- Approval: Mark Hammond approved only CVE-2025-32013 on the exact 0.1.7 Maven package
  URL through 2026-10-31 UTC in the 2026-09-23 release-preparation conversation. The decision
  and scan evidence are recorded for maintainer review in PR #88:
  https://github.com/greenharborlabs/spring-boot-starter-paygate/pull/88
  PR #84 approved only the prior 0.1.7-SNAPSHOT package URL.
- Approval date: 2026-09-23
- Status: Approved
- Review date: 2026-09-23
- Compensating controls: The suppression matches only CVE-2025-32013 on the exact Paygate Maven
  package URL for version 0.1.7 and expires on 2026-10-31 UTC. It does not cover the
  LNbits Docker image, any external LNbits artifact, or later Paygate versions; a Paygate version
  bump requires fresh evidence and approval before a new exact PURL is suppressed. Dependency-Check
  continues to fail on every other advisory with a CVSS score above zero. Separate draft PR #85
  adds image-vulnerability scanning; this suppression does not apply to its reports.
- Scanner evidence: The 2026-09-19 dependency-advisory CI failure affected the snapshot
  build. A fresh Dependency-Check 13.0.0 aggregate scan on 2026-09-23, with the suppression
  removed, reproduced only CVE-2025-32013 on `paygate-lightning-lnbits-0.1.7.jar` through an
  LNbits server CPE. Its Maven package URL is
  `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@0.1.7`. The JAR contains Paygate
  Java client classes rather than the Python LNbits server. Reinstating the rule for this
  exact release PURL yields zero unsuppressed findings and one suppressed finding.
