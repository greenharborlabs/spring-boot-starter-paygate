# Dependency-Check Risk Dispositions

## CVE-2025-32013 — LNbits server

- Disposition: False positive for the Paygate LNbits Java client adapter.
- Scope: `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@0.1.8-SNAPSHOT`
- Rationale: The advisory applies to server-side LNURL authentication handling in the Python
  LNbits server. The scoped artifact is Paygate's Java HTTP client adapter and does not contain or
  embed the LNbits server. Both integration fixtures now use the supported, digest-pinned LNbits
  `v1.6.2` image, independently of this Maven suppression.
- Owner: Mark Hammond, Green Harbor Labs maintainer
- Approval: Mark Hammond approved renewing only CVE-2025-32013 on the exact
  0.1.8-SNAPSHOT Maven package URL through 2026-11-30 UTC on 2026-10-31, after the prior
  2026-10-31 deadline expired and failed the scheduled dependency advisory run:
  https://github.com/greenharborlabs/spring-boot-starter-paygate/actions/runs/41189735815
  The previous approval through 2026-10-31 was recorded in PR #89.
- Approval date: 2026-10-31
- Status: Approved
- Review date: 2026-10-31
- Compensating controls: The suppression matches only CVE-2025-32013 on the exact Paygate Maven
  package URL for version 0.1.8-SNAPSHOT and expires on 2026-11-30 UTC. It does not cover the
  LNbits Docker image, any external LNbits artifact, or later Paygate versions; a Paygate version
  bump requires fresh evidence and approval before a new exact PURL is suppressed. Dependency-Check
  continues to fail on every other advisory with a CVSS score above zero. Separate draft PR #85
  adds image-vulnerability scanning; this suppression does not apply to its reports.
- Scanner evidence: A fresh Dependency-Check 13.0.0 aggregate scan on 2026-10-31, forced to
  rerun with the suppression removed, analyzed 126 dependencies and reproduced only
  CVE-2025-32013 (CVSS 7.5) on `paygate-lightning-lnbits-0.1.8-SNAPSHOT.jar` through the LNbits
  server CPE `cpe:2.3:a:lnbits:lnbits:0.1.8:snapshot`. Its Maven package URL is
  `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@0.1.8-SNAPSHOT`. The JAR contains only
  Paygate Java client classes under `com.greenharborlabs.paygate.lightning.lnbits` rather than
  the Python LNbits server. The exact rule for this snapshot PURL yields zero unsuppressed
  findings and one suppressed finding.
