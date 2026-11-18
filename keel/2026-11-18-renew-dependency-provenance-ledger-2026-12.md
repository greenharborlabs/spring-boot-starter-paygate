# Renew the dependency provenance ledger before 2026-12-01
Status: working
Base revision: 325814ea904c044eaaf4aab069c4e86a64eec05f
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

Approved: 2026-11-18 by Mark Hammond at plan revision 325814ea904c044eaaf4aab069c4e86a64eec05f.
- **Interval:** Option B, **2026-12-31 UTC**, aligned with the CVE-2025-32013 suppression deadline.
- **Key validity:** the valid-at-signing rule, as recommended.

## Tasks
- [x] Pre-edit state. Checks:
  - the metadata, ledger and keyring hashes match the starting state;
  - `./gradlew --no-daemon validateDependencyProvenance` passes.
  - **pass:** hashes `3fdbcdb6…`, `4079c494…` and `11d778c6…` match. All 575 rows were at 2026-12-01. The validator exited 0.
- [x] Source-byte audit. Check: `python3 scripts/audit-dependency-provenance-sources.py` exits 0 with 575/575 matches, before any edit.
  - **pass:** at 2026-11-18T14:31Z, exit 0 with 575/575 matches, 82,550,441 bytes (558 Maven Central, 17 Plugin Portal).
- [x] Key validity. For every artifact covered by one of the 14 keys, classify its signature creation time against the key's validity, and add an artifact-scoped ignore and exact row for each one signed outside validity. Evidence is in [the November 18 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity).
  - **pass:** the classification covers every committed artifact in the 14 keys' scopes and pins. 4 already had rows. Of the 66 classified:
    - 23 are signed by other trusted keys;
    - 26 were signed while their key was valid, including all 12 under the superseded Sonatype key;
    - 17 were signed after their key's recorded expiry.

    The 17 got artifact-scoped `<ignored-key>` entries and rows EX-875..EX-891 (commit `cef9292`).
  - **pass:** `cmp` of `<configuration>` against base is identical, and the keyring diff is empty. The metadata diff is purely additive: 17 artifact-level `<ignored-keys>` blocks, with 0 `<trusted-key>`, `<trusting>` or `<pgp>` additions.
  - **pass:** the new rows are part of the final 592/592 audit.
  - **pass (controls):**
    - with EX-882 removed from a scratch ledger, the validator fails with `checksum-only artifact lacks an exact exception for org.hamcrest:hamcrest:3.0:hamcrest-3.0.jar`;
    - with `hamcrest-3.0.module`'s checksum deleted in scratch trees resolved from empty Gradle homes, dependency verification fails when the new ignore is present and passes on the expired signature when it is absent.
- [x] Renew `review-date` on every row to the approved deadline. Check: a field-level comparison with the starting ledger shows only `review-date` changes on existing rows, plus any new key-validity rows.
  - **pass:** 575 existing rows changed only `review-date` (2026-12-01 → 2026-12-31). The 17 added rows are the only additions, with 0 removals and an unchanged header. 592 rows, one distinct `review-date` (commit `f539506`).
- [x] Review document and SECURITY.md (current-review link and key-validity rule). Check: links resolve, and `./scripts/validate-agents-md.sh` passes.
  - **pass:** [DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md) is added. [SECURITY.md](../SECURITY.md#dependency-provenance-and-local-credentials) links it as current and states the key-validity rule and its offline limit (commit `c508696`). A relative-link and anchor check over both and this record found 20 links and 0 broken. `validate-agents-md.sh` exited 0.
- [x] Final checks, then record the results:
  - **pass:** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, exit 0.
  - **pass:** `python3 scripts/audit-dependency-provenance-sources.py` on the final ledger, exit 0 with 592/592 matches, 83,876,666 bytes (575 Maven Central, 17 Plugin Portal).
  - **pass:** every row's `review-date` is 2026-12-31, checked field by field above. The provenance validator reads `date -u` and has no date override, so the expiry boundary can't be exercised directly.
  - **pass:** the metadata changed, so `GRADLE_USER_HOME=<new empty home> ./gradlew --no-daemon spotlessCheck check --rerun-tasks` ran with strict verification. Exit 0, 121/121 tasks, 3,448 tests with 0 failures, errors or skips.
  - **pass (optional):** `GRADLE_USER_HOME=<new empty home> ./gradlew --no-daemon -Pintegration :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`, exit 0. `test` 154 and `securityTest` 71, with 0 failures.
  - **pass:** `git diff --check 325814e`, exit 0.
  - **Limits:**
    - publisher identity is not authenticated;
    - key validity uses the committed keyring's possibly stale expiry data;
    - the rule is not enforced offline;
    - Dependabot #113 is not reconciled here;
    - macOS arm64 host, so the Linux protoc executables are first verified by Gradle in CI (they are unchanged).

## Review

### Attempt 1
Reviewed revision: a7c6c9c04e8bc3aa26a8b27307f8db95755fbbd5
Verdict: rework
Findings and dispositions:
1. **The key inventory and the "14 keys" claim are incomplete and wrong.** As of 2026-11-18 the committed metadata has **18** trusted, non-globally-ignored keys that are revoked or expired, not 14. Four are referenced by a subkey fingerprint in `<trusted-key>` and are absent from both this record and the review document:
   - `07A4FA67A8222F1B39F9EDAEF8052E4E1BD0DB31` (`^org[.]neo4j…`), principal `76DBA0D1…89AF105CEEC705A4`, expired 2026-02-12;
   - `2518174F4111F02779592A6F9757D7E7E06DD2AC` (`io.prometheus`), principal `55C895C9…3C9B410084525C69`, expired 2024-12-31;
   - `2B34821418CF19CF1F2A8352953E02E4F573B46F` (`jakarta.platform`), principal `71C52468…049FE94F2D5DAD9D`, expired 2024-02-13;
   - `E7DC75FC24FB3C8DFE8086AD3D5839A2262CBBFB` (`org.jetbrains.kotlinx`), principal `BC900CD2…E3822B59020A349D`, expired 2026-09-29.

   Their scopes contain 8 committed artifacts (`org.neo4j`: none; `io.prometheus`: 4 POMs; `jakarta.platform`: 2 POMs; `org.jetbrains.kotlinx`: 2 BOMs). The reviewer fetched each `.asc` and checked the issuer and creation time: all 8 are signed while their key was valid (prometheus `9757D7E7E06DD2AC` 2022-06-15; jakarta `953E02E4F573B46F` 2021-04-27; kotlinx `3D5839A2262CBBFB` 2025-04-08 and 2026-04-09) or by the globally ignored `502001558D63CB9A` (2026-06-04), so no additional rows or `<ignored-key>` entries are required. The security outcome is unchanged, but the method's premise ("every committed artifact in the 14 keys' scopes") and the evidence in the review document do not cover these keys. **Disposition: rework** — correct the inventory and counts, record the classification of the 8 artifacts (or justify their exclusion), and update the review document and SECURITY.md wording that relies on "14".
2. **Minor: the size estimate is wrong.** The record's Constraint says "at most 69 committed artifacts fall in these scopes", but the 14-key selection is 4 pre-existing rows + 66 classified = 70, and adding the four omitted keys gives 78. **Disposition: rework** — correct the figure while revising.

Verification:
- Scoped diff (`325814e..a7c6c9c`): **pass** — exactly `SECURITY.md`, `config/dependency-provenance-exceptions.tsv`, `docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md` and `gradle/verification-metadata.xml`, plus this record; no dependency changes; working tree clean at HEAD.
- Ledger edit: **pass** — field-by-field comparison: 575 existing rows changed only `review-date` (2026-12-01 → 2026-12-31); 17 additions (EX-875..EX-891), 0 removals, header unchanged; 592 rows, one distinct review date; no duplicate IDs or coordinates.
- Metadata: **pass** — `<configuration>` byte-identical to base; keyring unchanged; 17 artifact-scoped `<ignored-keys>` only, with no `<trusted-key>`, `<trusting>` or `<pgp>` additions. All 17 ignore ids match the actual `.asc` issuer ids and creation dates; all map to their ledger rows and existing checksums.
- Key-validity inventory: **fail** — finding 1.
- Review document and SECURITY.md: **pass** — new document added; SECURITY.md links it and states the rule and its offline limit; 24 local links and anchors across the record, SECURITY.md and the review document resolve; `./scripts/validate-agents-md.sh` exits 0.
- Validator and negative controls (`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`): **pass** — exit 0.
- Source-byte audit (`python3 scripts/audit-dependency-provenance-sources.py`): **pass** — 592/592 SHA-256 matches, 83,876,666 bytes (575 Maven Central, 17 Plugin Portal).
- Strict empty-home `spotlessCheck check --rerun-tasks`: **pass** — 121 tasks, 3448 default-module tests (3673 including a stale integration-results tree), 0 failures, errors or skips.
- `git diff --check 325814e`: **pass** — exit 0.
- Pre-edit validator run: **skipped** — historical base-state claim, not rerun; the post-edit validator and audit are the material checks.
- Optional integration tests: **skipped** — record marks them pass; not rerun.
- Material limits: the 26 "signed while valid" and 23 "signed by other keys" artifacts under the 14 keys were not re-fetched individually; the 8 artifacts under the four omitted keys were checked. The three unchanged `<pgp>` pins whose keys are absent from the committed keyring (`gson` 2.3.1, `apache` 16, `commons-parent` 39) are pre-existing and outside this diff.

## Outcome
Pending.

## Next action
Address keel-review Attempt 1 (rework): correct the revoked/expired key inventory and counts, record the classification of the eight artifacts under the four omitted keys, commit the scoped corrections, and return the record to `Status: review`. The implementation is commits `cef9292` (key validity), `f539506` (renewal) and `c508696` (review document and SECURITY.md), on branch `chore/renew-provenance-ledger-2026-12`. Review and merge must happen before 2026-12-01 UTC, when the current rows expire on `main`.
