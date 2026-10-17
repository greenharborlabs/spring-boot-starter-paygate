# Renew dependency provenance exceptions before the October 23 deadline
Status: closed
Base revision: ef4777c932ed9abb442a5195c59d7bfc544c51b3
Review scope: config/dependency-provenance-exceptions.tsv, docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md, SECURITY.md

## Goal
Keep root `./gradlew check` passing after 2026-10-23 UTC by renewing the 464 exact exceptions in the [ledger](../config/dependency-provenance-exceptions.tsv) on fresh evidence. The [validator](../scripts/validate-dependency-provenance.sh) compares each `review-date` with `date -u +%F`, so every row expires on 2026-10-24 UTC. Acceptance has four parts:
- all 464 artifact bytes are re-verified against their HTTPS sources and the metadata checksums;
- only the `review-date` field changes, and it changes to the deadline approved below;
- a dated review document records the evidence and justifies the interval;
- the provenance controls and a root check from an empty Gradle home pass.

The change must merge before 2026-10-24 UTC.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md), and the [security policy](../SECURITY.md#dependency-provenance-and-local-credentials). Changing dates without re-checking the evidence is not a renewal. The owner must justify the interval explicitly; there is no automatic cadence. A validator pass or matching committed files is not a review.
- Since the [September 30 reconciliation](2026-09-28-dependency-provenance-renewal.md) (base `4dbdbc7`), the only commits touching `gradle/`, the ledger, or build files are that task's own rationale and header edits (`1b516c0`, `75f1da6`). Metadata, keyring, and dependency versions should therefore be unchanged. Confirm this at approval rather than assuming it.
- Its signature audit found 146 valid signatures and 318 `NO_PUBKEY` results, and it authenticated no publisher identities. That audit's scripts were kept only under `/tmp` and are not repository inputs. Key recovery, trusting fingerprints, and removing exceptions need separate review and are out of scope.
- Evidence fetching needs network access to `repo1.maven.org` and `plugins.gradle.org`. A sandboxed host may block the fetch or the Gradle wrapper lock under `~/.gradle`. The [abandoned pilot](2026-09-28-health-refresh-contention.md) hit exactly that. Record such outcomes as `blocked`; they do not count as a pass.
- Preserve unrelated work. The task changes no keyring, metadata, build logic, dependency, checksum, source URL, or rationale. If evidence requires any of those, stop and get a scope decision.

## Decisions
- **Re-verify, then renew every row together.** All 464 rows share one deadline, unchanged inputs, and the same trust gap. A row whose bytes, source, or metadata linkage fail verification is not renewed, and work stops for a scope decision.
- **Proposed deadline: 2026-11-22 UTC (30 days), to be confirmed or changed by the owner at approval.** This keeps the interval the [September 23 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-09-23.md) chose. The publisher-identity gap is unresolved, so a longer interval would lengthen unauthenticated trust without new evidence.
- **Rejected: a longer interval now.** The inputs are unchanged, but nothing new establishes publisher identity. Reconsider a longer interval only after key recovery reduces the exception set.
- **Rejected: recovering keys or trusting the 146 valid-signature keys.** That reduces renewals in future but changes signature trust, so it needs its own reviewed task. It is not something to fit in before a deadline.
- **Evidence destination:** a new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md` in the same form as the September 23 review. It records the actual review date, the commands and results, the byte and source matches, the unchanged metadata and keyring, the publisher-authentication limits, and the justified deadline. `SECURITY.md` gets a one-sentence link to it as the current review.
- **Required checks, run from the repository root after the edits:**
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew check --no-daemon --rerun-tasks`
  - `git diff --check`

  Default `check` excludes `paygate-integration-tests`. `releaseReadiness -Pintegration` is not acceptance for this task.

Approved: 2026-10-15 by Mark at plan revision ef4777c932ed9abb442a5195c59d7bfc544c51b3. Deadline confirmed: 2026-11-22 UTC (30 days).

## Tasks
- [x] At approval, confirm that `gradle/`, the build files, and the ledger match base `4dbdbc7` except for the ledger rationale and header edits in `1b516c0` and `75f1da6`, and that there are 464 unique rows dated `2026-10-23`. Also confirm Java 25 and Gradle 9.4.1 start, and that `./gradlew validateDependencyProvenance` passes before any edit.
- [x] Run `python3 scripts/audit-dependency-provenance-sources.py`. It must report 464/464 SHA-256 matches with the expected source split (462 Maven Central, 2 Plugin Portal). If the network is unavailable, record `blocked`; if the run fails partway, record `incomplete`.
- [x] Set `review-date` to the approved deadline on all 464 rows. Then show with a field-level diff from the base that `review-date` is the only data field that changed, with 464 changes and no header changes.
- [x] Write the dated review document and link it from `SECURITY.md`.
- [x] Run the three required checks in Decisions and record the command, exit code, tasks executed, test totals, and omissions for each.
- [x] (optional) Re-run the detached-signature audit with only the committed keyring in an isolated GPG home. Skip it if metadata and the keyring are byte-identical to the September 30 inputs, and say so.

## Review
Implementation commit `f53941bff1e3e03b73cf5d65aaec52fa2c560be0` on approved base `ef4777c932ed9abb442a5195c59d7bfc544c51b3` (approval record `85f0ff6`). It changes only the three scoped paths.

WORK evidence on 2026-10-15 UTC (Java 25.0.1 Temurin, Gradle 9.4.1):
- Approval checks: `git log 4dbdbc7..ef4777c` over `gradle/`, `*.gradle.kts`, `gradle.properties`, and the ledger lists only `1b516c0` and `75f1da6`, and the diff is ledger-only. `gradle/verification-metadata.xml` and `gradle/verification-keyring.keys` are byte-identical to `4dbdbc7`. The ledger had 464 unique IDs and rows, all dated `2026-10-23`. Pre-edit `./gradlew validateDependencyProvenance`: exit 0.
- `python3 scripts/audit-dependency-provenance-sources.py`: exit 0, 464/464 SHA-256 matches, 66,182,026 bytes, `{repo1.maven.org: 462, plugins.gradle.org: 2}`.
- Ledger edit: a field-level comparison with `ef4777c` found 464 rows, 464 `review-date` changes from `2026-10-23` to `2026-11-22`, no other field changes, and no header changes. Ledger SHA-256 went from `68c43e11…be73be` to `450ba022…612bd8`.
- Added `docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md` and a one-sentence current-review link in `SECURITY.md`.
- Four-control Gradle command from Decisions (`--no-daemon`): exit 0, 4 of 4 tasks executed and passed.
- `GRADLE_USER_HOME=<new empty scratch dir> ./gradlew check --no-daemon --rerun-tasks`: exit 0 in 2m 23s, 120 of 120 actionable tasks executed. Default-module test XML totals 2,921 tests, 0 failures, 0 errors, 0 skipped (integration XML excluded).
- `git diff --check` (working tree and staged): exit 0.
- Optional signature audit skipped: metadata and keyring are byte-identical to the September 30 inputs.

Limits: default `check` omits opt-in integration tests, live Lightning, advisory freshness, and release readiness. No publisher identity was authenticated. No independent review has run yet.

### Attempt 1
Reviewed revision: 0dd85c2c54e5eddc2c681730aa1cfdcc7f2355d2
Verdict: pass
Findings and dispositions:
1. No findings. Scoped diff from `ef4777c` is exactly the three declared paths; `HEAD` (`0dd85c2`) is a record-only commit and every scoped path is clean. An independent field-level comparison of the ledger found 464 changed rows, each with only field 9 (`review-date`) differing (`2026-10-23` to `2026-11-22`), 464 unique IDs, and no header change; no coordinate, checksum, source, rationale, owner, or trigger changed. `gradle/verification-metadata.xml` and `gradle/verification-keyring.keys` are byte-identical to base `4dbdbc7`, so the optional signature-audit skip is justified. Ledger SHA-256s match the record (`68c43e11…be73be` to `450ba022…612bd8`). `SECURITY.md` adds only the current-review link, and the dated review document and its relative links resolve.
Verification:
- `git diff --check` (working tree and staged): pass (exit 0).
- `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`: pass, exit 0, 4 of 4 tasks executed.
- `python3 scripts/audit-dependency-provenance-sources.py`: pass, exit 0, 464/464 SHA-256 matches, 66,182,026 bytes, `{repo1.maven.org: 462, plugins.gradle.org: 2}`.
- `GRADLE_USER_HOME=<fresh scratch dir> ./gradlew check --no-daemon --rerun-tasks`: pass, exit 0, 120 of 120 actionable tasks executed. Default-module test XML totals 2,921 tests, 0 failures, 0 errors, 0 skipped; `paygate-integration-tests` was not run.
- Optional detached-signature audit: skipped, correctly, because metadata and keyring are byte-identical to the September 30 inputs.
- Material limits: default `check` omits opt-in integration tests, live Lightning, advisory freshness, and release readiness; no publisher identity was authenticated or claimed. Reviewed revision recorded on 2026-10-16 UTC before the 2026-10-24 merge deadline.

## Outcome
Renewed all 464 exceptions in the [ledger](../config/dependency-provenance-exceptions.tsv) through 2026-11-22 UTC (30 days, owner-confirmed at approval) in implementation commit `f53941b`; review Attempt 1 passed at reviewed revision `0dd85c2`, a record-only commit over it.

- Change: only `review-date` changed (`2026-10-23` to `2026-11-22`) on 464 of 464 rows, with no header, coordinate, checksum, source, rationale, owner, or trigger change. Added the dated [October 15 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md) and linked it from the [security policy](../SECURITY.md#dependency-provenance-and-local-credentials) as the current review.
- Evidence: the source audit re-verified 464/464 SHA-256 matches over HTTPS (462 Maven Central, 2 Plugin Portal). `gradle/verification-metadata.xml` and `gradle/verification-keyring.keys` are byte-identical to base `4dbdbc7`, so the optional signature audit was skipped.
- Verification (implementation and review both passed): the four provenance controls (exit 0, 4/4 tasks); `check` from an empty Gradle home with `--rerun-tasks` (exit 0, 120/120 tasks, 2,921 default-module tests, 0 failures/errors/skipped); `git diff --check` (exit 0). Review found no issues.
- Limits: no publisher identity is authenticated; the 318 `NO_PUBKEY` trust gap from the [September 30 reconciliation](2026-09-28-dependency-provenance-renewal.md) remains. Default `check` omits integration tests, live Lightning, advisory freshness, and release readiness. The renewal protects `check` only once merged, and the old dates expire on 2026-10-24 UTC.

## Next action
Merge `chore/renew-dependency-provenance-2026-10` (implementation `f53941b`, record in this file) to `main` before 2026-10-24 UTC.
