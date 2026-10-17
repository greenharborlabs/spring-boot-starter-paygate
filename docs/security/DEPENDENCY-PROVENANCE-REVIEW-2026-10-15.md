# Dependency provenance exception review — 2026-10-15

This review renews the existing exact-checksum exceptions in `config/dependency-provenance-exceptions.tsv` through **2026-11-22 UTC**. The work was performed on 2026-10-15 UTC. The exceptions remain scoped to the same 464 artifact coordinates, SHA-256 values, and authoritative sources. Gradle's verification metadata, signature setting, and committed keyring are unchanged. A dependency, key, checksum, or source change still triggers another review before the deadline.

## Evidence

- The starting ledger at plan revision `ef4777c` had 464 unique exception rows, all with the 2026-10-23 deadline. Its SHA-256 was `68c43e117589778f8885de2c7004520d00da6494020ac7c54f3a82f9e0be73be`.
- Since the [September 30 reconciliation](../../keel/2026-09-28-dependency-provenance-renewal.md) base `4dbdbc7`, the only commits touching `gradle/`, build files, or the ledger are that task's rationale and header edits (`1b516c0`, `75f1da6`). `gradle/verification-metadata.xml` and `gradle/verification-keyring.keys` are byte-identical to that base. No dependency version changed.
- `./gradlew validateDependencyProvenance` passed before any edit (Java 25.0.1, Gradle 9.4.1).
- On 2026-10-15 UTC, `python3 scripts/audit-dependency-provenance-sources.py` fetched each of the 464 artifact URLs over HTTPS and hashed the bytes with SHA-256. It exited 0: 464/464 hashes matched the ledger, 66,182,026 bytes in total, 462 from Maven Central and 2 from the Gradle Plugin Portal. No artifact, checksum, or source mismatch occurred.
- Only `review-date` changed, from `2026-10-23` to `2026-11-22`, on all 464 rows. A field-level comparison with the starting ledger found 464 `review-date` changes, no other field changes, and no header changes. The renewed ledger's SHA-256 is `450ba0228865d2145d306b36b2fdb579850e2a358b09119a65d7d62e71612bd8`.
- The detached-signature audit was not repeated. Its inputs (metadata and keyring) are byte-identical to the September 30 inputs, where it found 146 valid signatures, 318 `NO_PUBKEY` results, and no bad signatures.

## Limits and interval

This review re-establishes artifact bytes, sources, and metadata linkage. It does not authenticate publisher identity: no key was recovered, no fingerprint was trusted, and no exception was removed. Those changes need their own reviewed task.

The owner approved a 30-day interval on 2026-10-15. It matches the September 23 renewal. A longer interval would extend unauthenticated publisher trust without new evidence, so it should be reconsidered only after key recovery reduces the exception set.

## Checks after renewal

- `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`: exit 0, all four tasks executed and passed.
- `GRADLE_USER_HOME=$(mktemp -d) ./gradlew check --no-daemon --rerun-tasks`: exit 0 in 2m 23s, 120 of 120 actionable tasks executed. Default-module test XML totals 2,921 tests with no failures, errors, or skips. Default `check` excludes the opt-in `paygate-integration-tests`, live Lightning, advisory freshness, and release readiness.
- `git diff --check`: exit 0.

This evidence awaits fresh-context review before merge, which must happen before 2026-10-24 UTC.
