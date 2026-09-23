# Dependency-Check Risk Dispositions

## CVE-2025-32013 LNbits server

- Disposition: False positive for the Java client adapter.
- Scope: `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@0.1.7-SNAPSHOT`
- Owner: Green Harbor Labs maintainer
- Approval: Maintainer review PR #123.
- Approval date: 2026-09-20
- Status: Approved
- Review date: 2026-09-20
- Compensating controls: Exact package URL and ongoing scan.
- Scanner evidence: Dependency-Check 13.0.0 reports the server CPE against this Java adapter.
