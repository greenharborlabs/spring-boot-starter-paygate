# Dependency provenance exception review — 2026-09-23

This review renews the existing exact-checksum exceptions in `config/dependency-provenance-exceptions.tsv` through **2026-10-23 UTC**. The exceptions remain scoped to the same 464 artifact coordinates and SHA-256 values. Gradle's verification metadata, signature setting, and committed keyring are unchanged. A dependency, key, checksum, or source change still triggers another review.

## Evidence

- The starting ledger had 464 unique exception IDs and coordinates, all with the 2026-09-22 review deadline. Its SHA-256 was `a6f259c6395c7f4057c626eeb223f23591454de9173e658146c5841a7d188d92`.
- On 2026-09-23 UTC, `python3 scripts/audit-dependency-provenance-sources.py` fetched each of the 464 artifact URLs over HTTPS and hashed the artifact bytes with SHA-256. All 464 hashes matched the corresponding ledger value; no artifact or checksum mismatch occurred. The combined downloaded size was 66,182,026 bytes. The script requires an exact repository coordinate path and can be rerun during future reviews.
- 462 artifacts resolved at Maven Central. Two entries, `EX-181` and `EX-182` for the OWASP Dependency-Check Gradle plugin JAR and module, returned 404 at their recorded Maven Central URLs. The same exact artifacts resolved at the [Gradle Plugin Portal](https://plugins.gradle.org/m2/org/owasp/dependency-check-gradle/13.0.0/); their downloaded SHA-256 values matched the ledger. Their authoritative-source URLs were corrected to that portal.
- `validateDependencyProvenance` passed after the URL correction. It checks exact checksum and metadata linkage, scoped trusted keys, keyring presence, and review dates. `verifyDependencyProvenanceNegativeControls` passed, including a new case that rejects an exception URL pointing to a different artifact coordinate.

The current exceptions are still needed for signatures whose publisher keys were unavailable in the reviewed bootstrap. This review confirms the existing exact artifact bytes and metadata linkage. Key recovery and migration to signature verification remain a separate task; a recovered publisher key should replace its checksum exception after validation.

This automated evidence was prepared for Green Harbor Labs maintainer review in the accompanying PR. The review date is limited to 30 days rather than extended indefinitely.
