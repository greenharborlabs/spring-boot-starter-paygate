# Renew dependency provenance exceptions before the October 23 deadline
Status: working
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
- [ ] At approval, confirm that `gradle/`, the build files, and the ledger match base `4dbdbc7` except for the ledger rationale and header edits in `1b516c0` and `75f1da6`, and that there are 464 unique rows dated `2026-10-23`. Also confirm Java 25 and Gradle 9.4.1 start, and that `./gradlew validateDependencyProvenance` passes before any edit.
- [ ] Run `python3 scripts/audit-dependency-provenance-sources.py`. It must report 464/464 SHA-256 matches with the expected source split (462 Maven Central, 2 Plugin Portal). If the network is unavailable, record `blocked`; if the run fails partway, record `incomplete`.
- [ ] Set `review-date` to the approved deadline on all 464 rows. Then show with a field-level diff from the base that `review-date` is the only data field that changed, with 464 changes and no header changes.
- [ ] Write the dated review document and link it from `SECURITY.md`.
- [ ] Run the three required checks in Decisions and record the command, exit code, tasks executed, test totals, and omissions for each.
- [ ] (optional) Re-run the detached-signature audit with only the committed keyring in an isolated GPG home. Skip it if metadata and the keyring are byte-identical to the September 30 inputs, and say so.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved plan.
