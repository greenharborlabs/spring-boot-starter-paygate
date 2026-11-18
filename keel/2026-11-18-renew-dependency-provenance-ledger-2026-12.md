# Renew the dependency provenance ledger before 2026-12-01
Status: planned
Base revision: pending approval
Review scope: config/dependency-provenance-exceptions.tsv, gradle/verification-metadata.xml, docs/security/DEPENDENCY-PROVENANCE-REVIEW-*.md, SECURITY.md

## Goal
Renew all 575 rows of `config/dependency-provenance-exceptions.tsv` before they expire on **2026-12-01 UTC**. Once they expire, `validateDependencyProvenance` fails root `check` on every branch. Every row's source bytes are re-verified first. The task also settles the open trust decision on revoked and expired keyring keys.

Acceptance evidence:
- **Source bytes:** the source-byte audit matches every row.
- **Ledger edit:** the only change to existing rows is `review-date`, set to the approved deadline, confirmed by a field-level diff.
- **Key validity:** every artifact covered by one of the 14 revoked or expired trusted keys is classified by signature creation time. Any artifact signed outside its key's validity loses that coverage and gets an exact row.
- **No new trust:** no trusted key, trust scope or pin is added, and the keyring is unchanged.
- **Review document:** a new review document records the evidence, the key-validity result and the justified interval, and SECURITY.md links it as the current review.
- **Validator:** the validator and its negative controls pass. If the metadata changes, a strict empty-home `check` also passes.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [dependency provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - renewal is a fresh review with an owner-justified interval, not a date bump;
  - a passed validator is not a review.
- **Starting state** is `main` after #111 (`327b3a7`):
  - metadata `3fdbcdb6…`, ledger `4079c494…`, keyring `11d778c6…`;
  - 575 rows, all at 2026-12-01;
  - reconciled by the [November 15 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md).
- **Method** follows the [November 3 renewal](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md#renewal-of-the-existing-rows): a pre-edit validator run, a full HTTPS source-byte audit, then a `review-date`-only edit verified field by field.
- **Key validity, left open by the [November 3 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md#limits-and-interval).** The committed keyring has 14 trusted, non-ignored keys that are revoked or expired, checked from the keyring on 2026-11-18:
  - **Revoked (1):** `2BCBDD0F23EA1CAFCC11D4860374CF2E8DD1BDFD`, the Sonatype release key. It was revoked 2024-01-04 with reason `0x01` (key superseded). It is trusted for `^org.sonatype` and pinned for `jvnet-parent-1.pom`.
  - **Expired (13):**
    - `org.antlr` (2018-12-31);
    - `com.google.android` (2011-08-28);
    - `org.mockito` (2023-02-14);
    - `org.jetbrains` (2023-12-08);
    - `com.diffplug.durian` (2025-01-11);
    - `com.querydsl` (2021-11-19);
    - `org.codehaus.plexus` (2026-06-05, also pinned);
    - `org.apache.velocity` (2025-09-10);
    - `javax.xml.bind` (2023-03-21);
    - `org.latencyutils` (2017-11-23);
    - `io.rsocket` (2023-06-09);
    - `org.hamcrest` (2020-11-20);
    - `org.json` (2025-06-17).
  - **Size:** at most 69 committed artifacts fall in these scopes, and some are covered by other keys.
- **Gradle accepts signatures from expired or revoked keys.** A rule about key validity therefore has to be expressed with existing metadata constructs. An artifact-scoped `<ignored-key>` plus an exact row, as used for `snakeyaml` 2.6 in the [November 1 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#metadata-representation-of-exceptions), narrows trust without adding any.
- **ID space:** 125 IDs remain (EX-875..EX-999).
- **No dependency changes during this task.** If a Dependabot PR merges first, this task must reconcile it before review.

## Decisions
- **Renew only after re-verification.** All 575 rows have their source bytes re-audited before the `review-date` edit. Signature classification for the unchanged metadata is not repeated, except for the key-validity check below.
- **Key validity: signatures count only if made while the key was valid** (recommended, for approval).
  - For each artifact Gradle accepts on a signature from one of the 14 keys, fetch the `.asc` over HTTPS and verify it with `gpgv` against the committed keyring. Then compare the signature's creation time with the key's expiry, or for Sonatype the 2024-01-04 revocation.
  - **Signed while the key was valid:** coverage stands. Expiry and supersession don't invalidate earlier signatures, and these are historical releases.
  - **Signed after expiry or revocation:** add an artifact-scoped `<ignored-key>` for that key and an exact row (EX-875 upward), with source bytes audited.
  - SECURITY.md states the rule. The validator is unchanged, so the rule is enforced by this classification at each review, which is a documented limit.
  - Rejected: **blanket-ignore all 14 keys.** That turns up to 69 historically well-signed artifacts into checksum-only rows, using half the remaining ID space, for no gain on pre-expiry signatures.
  - Rejected: **defer again.** The decision has been open since November 3, and the check is small.
  - Rejected: **a validator change** to read key validity offline. It would need the validator to parse the keyring, which is a larger, separate task.
- **Interval** (for approval). Publisher identity is still unauthenticated, so the precedent is about 30 days.
  - **Option A (recommended): 2026-12-18 UTC**, 30 days from the 2026-11-18 work date. This matches the 30-day interval of the September 23 and October 15 renewals.
  - **Option B: 2026-12-31 UTC**, 43 days. This lines the ledger up with the CVE-2025-32013 suppression deadline again, as you chose on November 1, at the cost of a longer interval than precedent.
  - Rows added by the key-validity check take the same deadline, so the ledger keeps one cycle.
- **Documentation:**
  - a new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md`, in the form of the November 3 review, recording:
    - the starting hashes and the audit result;
    - the field-level renewal diff;
    - the key-validity classification table and any rows;
    - the limits and the justified interval;
  - SECURITY.md updates the current-review sentence and states the key-validity rule and its limit.

## Tasks
- [ ] Pre-edit state. Checks:
  - the metadata, ledger and keyring hashes match the starting state;
  - `./gradlew --no-daemon validateDependencyProvenance` passes.
- [ ] Source-byte audit. Check: `python3 scripts/audit-dependency-provenance-sources.py` exits 0 with 575/575 matches, before any edit.
- [ ] Key validity. For every artifact covered by one of the 14 keys, classify its signature creation time against the key's validity, and add an artifact-scoped ignore and exact row for each one signed outside validity. Checks:
  - the classification table covers every in-scope artifact;
  - a diff shows no `<trusted-key>`, `<trusting>` or `<pgp>` additions, and an empty keyring diff;
  - any new rows pass the audit.
- [ ] Renew `review-date` on every row to the approved deadline. Check: a field-level comparison with the starting ledger shows only `review-date` changes on existing rows, plus any new key-validity rows.
- [ ] Review document and SECURITY.md (current-review link and key-validity rule). Check: links resolve, and `./scripts/validate-agents-md.sh` passes.
- [ ] Final checks, then record the results:
  - `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`;
  - `python3 scripts/audit-dependency-provenance-sources.py` on the final ledger;
  - every row's `review-date` equals the approved deadline, checked field by field. The provenance validator reads `date -u` and has no date override, so the expiry boundary can't be exercised directly;
  - if `gradle/verification-metadata.xml` changed: `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon spotlessCheck check --rerun-tasks` with strict verification;
  - `git diff --check`.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan. In particular, choose the interval (A: 2026-12-18, or B: 2026-12-31) and confirm the key-validity rule.
