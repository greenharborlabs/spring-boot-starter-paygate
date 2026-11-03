# Dependency provenance exception review — 2026-11-03

This review renews `config/dependency-provenance-exceptions.tsv` through **2026-12-01 UTC**, as set out in [the validator tightening and renewal plan](../../keel/2026-11-01-tighten-dependency-provenance-validator-and-renew-ledger.md). The work was performed on 2026-11-03 UTC on plan base `651c43e`, which carries `main` at `c288ec6`. No trust was added: `gradle/verification-keyring.keys` and `<trusted-keys>` are unchanged.

## Renewal of the existing rows

- **Starting state.** The starting ledger had 511 unique rows, all with the 2026-11-22 deadline. Its SHA-256 was `f1f52ab501433d9017529e001cd4700e02fcec6f23df41d6a5c4084b58776858`. The metadata (`4983724fd46885f612ec2594710f9047bba13f90447a3cfaa295108d57328eec`) and keyring (`11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460`) are byte-identical to the state reconciled by the [November 1 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md). No dependency has changed since.
- **Pre-edit validator.** `./gradlew --no-daemon validateDependencyProvenance` passed before any edit.
- **Source-byte audit.** At 2026-11-03T02:57Z, `python3 scripts/audit-dependency-provenance-sources.py` fetched all 511 ledger URLs over HTTPS and hashed them with SHA-256. It exited 0 with 511/511 matches, 72,420,133 bytes in total: 500 from Maven Central and 11 from the Gradle Plugin Portal.
- **Edit.** `review-date` changed from `2026-11-22` to `2026-12-01` on all 511 rows. A field-level comparison with the starting ledger found 511 `review-date` changes, no other field changes and no header changes. The renewed ledger's SHA-256 is `af2c48c7a76dd415be411315e9647963d9a6b5eb43838cbaa71ef097824c5f93`.
- **Signatures.** The detached-signature classification for the existing rows was not repeated for this renewal step. Its inputs (metadata and keyring) are byte-identical to those of the November 1 review.

## Limits and interval

This renewal re-establishes artifact bytes, sources and metadata linkage. It does not authenticate publisher identity: no key was recovered, imported or trusted, and no fingerprint was confirmed out of band.

The owner approved the 2026-12-01 UTC deadline on 2026-11-01. It was proposed as 30 days from that date. Measured from this work date it is 28 days, which is shorter rather than longer than the 30-day intervals of the [September 23](DEPENDENCY-PROVENANCE-REVIEW-2026-09-23.md) and [October 15](DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md) renewals. The date falls the day after the CVE-2025-32013 suppression deadline (2026-11-30), so both monthly reviews happen in the same week. Publisher identity is still unauthenticated, so a longer interval would extend unauthenticated trust without new evidence.
