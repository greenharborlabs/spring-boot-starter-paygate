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
  - **Correction (Attempt 1 rework).** The inventory above matched `<trusted-key>` IDs against primary fingerprints only, so the key count of 14 is wrong.
    - **18 keys:** the four missing keys are trusted by subkey fingerprint:
      - `07A4FA67…F8052E4E1BD0DB31` (`^org.neo4j`, expired 2026-02-12);
      - `2518174F…9757D7E7E06DD2AC` (`io.prometheus`, 2024-12-31);
      - `2B34821418…953E02E4F573B46F` (`jakarta.platform`, 2024-02-13);
      - `E7DC75FC…3D5839A2262CBBFB` (`org.jetbrains.kotlinx`, 2026-09-29).
    - **80 artifacts:** the selection, including pins, is 80 committed artifacts, not "at most 69".
    - **Rule unchanged:** it applies to all 18, which is what "revoked or expired trusted keys" in the Goal and Decisions means.
- **Gradle accepts signatures from expired or revoked keys.** A rule about key validity therefore has to be expressed with existing metadata constructs. An artifact-scoped `<ignored-key>` plus an exact row, as used for `snakeyaml` 2.6 in the [November 1 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#metadata-representation-of-exceptions), narrows trust without adding any.
- **ID space:** 125 IDs remain (EX-875..EX-999). After this task, 106 remain (EX-894..EX-999).
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
- [x] Key validity. For every artifact covered by a revoked or expired trusted key (18 keys after the Attempt 1 correction), classify its signature creation time against the key's validity, and add an artifact-scoped ignore and exact row for each one signed outside validity. Evidence is in [the November 18 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity).
  - **pass (rework):** the classification was rerun against the base metadata and ledger for all 18 keys. Every `<trusted-key>` and `<pgp>` reference was matched against primary and subkey fingerprints. It covers all 80 committed artifacts in those scopes and pins; 6 already had rows. Of the 74 classified:
    - 23 are signed by other trusted keys;
    - 32 were signed while their key was valid, including all 12 under the superseded Sonatype key, and the Prometheus, Jakarta and kotlinx BOMs under the subkey-referenced keys;
    - 19 were signed after their key's recorded expiry.
  - **Versus Attempt 1:**
    - The 17 artifacts from the 14-key run are unchanged (EX-875..EX-891, commit `cef9292`).
    - Two `org.neo4j` BOMs (`neo4j-bolt-connection-bom` 11.0.2 and `neo4j-java-driver-bom` 6.1.0) were signed in 2026-04/05 by trusted subkey `07A4FA67…F8052E4E1BD0DB31`, which expired 2026-02-12. They got subkey ignores and rows EX-892..EX-893 (commit `dd3de26`).
  - **pass:** `cmp` of `<configuration>` against base is identical, and the keyring diff is empty. The metadata diff is purely additive: 19 artifact-level `<ignored-keys>` blocks, with 0 `<trusted-key>`, `<trusting>` or `<pgp>` additions.
  - **pass:** the new rows are part of the final audit (see Final checks).
  - **pass (controls):**
    - **Validator:** with EX-882 or EX-893 removed from a scratch ledger, it fails with `checksum-only artifact lacks an exact exception for` that artifact.
    - **Gradle, primary key:** with `hamcrest-3.0.module`'s checksum deleted, in scratch trees resolved from empty Gradle homes, verification fails when the ignore is present and passes on the expired signature when it is absent.
    - **Gradle, subkey:** the same holds for `neo4j-java-driver-bom-6.1.0.pom`.
- [x] Renew `review-date` on every row to the approved deadline. Check: a field-level comparison with the starting ledger shows only `review-date` changes on existing rows, plus any new key-validity rows.
  - **pass:** 575 existing rows changed only `review-date` (2026-12-01 → 2026-12-31), in commit `f539506`. After the rework, the 19 key-validity rows (EX-875..EX-893, all 2026-12-31) are the only additions, with 0 removals and an unchanged header: 594 rows, one distinct `review-date`.
- [x] Review document and SECURITY.md (current-review link and key-validity rule). Check: links resolve, and `./scripts/validate-agents-md.sh` passes.
  - **pass:** [DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md) is added and corrected to the 18-key inventory. [SECURITY.md](../SECURITY.md#dependency-provenance-and-local-credentials) links it as current, states the key-validity rule and its offline limit, and now says to match references against subkeys (commits `c508696` and `813ae73`). The link check is recorded in the Attempt 1 rework below.
- [x] Final checks, then record the results:
  - **pass:** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, exit 0.
  - **pass:** `python3 scripts/audit-dependency-provenance-sources.py` on the final ledger, exit 0 with 594/594 matches, 83,881,439 bytes (577 Maven Central, 17 Plugin Portal).
  - **pass:** every row's `review-date` is 2026-12-31, checked field by field above. The provenance validator reads `date -u` and has no date override, so the expiry boundary can't be exercised directly.
  - **pass:** the metadata changed, so `GRADLE_USER_HOME=<new empty home> ./gradlew --no-daemon spotlessCheck check --rerun-tasks` ran with strict verification at the rework revision. Exit 0, 121/121 tasks, 3,448 tests with 0 failures, errors or skips.
  - **pass (optional):** `GRADLE_USER_HOME=<new empty home> ./gradlew --no-daemon -Pintegration :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`, exit 0. `test` 154 and `securityTest` 71, with 0 failures. In the rework rerun this took 1 h 18 m, against about 1 min before. The cause was not investigated; the likely explanation is that the host slept, or that dependency resolution from the empty home was slow. Every test still executed and passed.
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

### Attempt 1 rework
Rework revision: 813ae73ab39da2a62cbcc61d56e769785d8c71bd. The implementation commit is `dd3de26`, which adds 2 artifact-scoped ignores and 2 rows. `813ae73` corrects the review document and SECURITY.md. All changed paths are in Review scope.

Dispositions:
1. **Fixed, with an outcome change the review did not predict.** The root cause was my inventory script, which matched `<trusted-key>` IDs against primary fingerprints only. I rebuilt the inventory to match every `<trusted-key>` and `<pgp>` reference against primary and subkey fingerprints. It finds the same 18 keys the review lists.
   - **Classification rerun:** I reran the classification against the base metadata and ledger for all 18 keys, not just the four.
   - **Larger scope than the review counted:** the four keys' scopes hold **10** committed artifacts, not 8. The `^org[.]neo4j($|([.].*))` scope also matches subgroups `org.neo4j.bolt` and `org.neo4j.driver`, which hold one BOM POM each. Two Prometheus POMs already have rows (EX-543, EX-544).
   - **Six signed while valid,** matching the review: Prometheus `parent` and `simpleclient_bom` 0.16.0, the two Jakarta EE 9.1.0 POMs, and the two kotlinx BOMs.
   - **Two signed after validity:** `neo4j-bolt-connection-bom` 11.0.2 and `neo4j-java-driver-bom` 6.1.0 were signed 2026-04-30 and 2026-05-01 by the trusted subkey `07A4FA67…F8052E4E1BD0DB31`, which expired 2026-02-12. Under the approved rule, each got an artifact-scoped ignore for that subkey and an exact row (EX-892, EX-893).
   - **Controls:** a new Gradle control shows the subkey ignore takes effect. With the checksum deleted, verification fails with the ignore and passes without it. The validator rejects the ledger without EX-893.
   - **Documentation:** the review document and SECURITY.md now state 18 keys, 80 in-scope artifacts, 19 rows, 594 rows in total and 106 IDs left. SECURITY.md tells future reviews to match references against subkeys. The approved Constraints and Decisions text is kept, with a labelled correction beside the wrong count. The approved rule is unchanged: it always covered every revoked or expired trusted key.
2. **Fixed.** The size estimate is corrected in a labelled Constraints note: 80 committed artifacts across the 18 keys, of which 6 have rows and 74 were classified. The review's own estimate of 78 also missed the two neo4j BOMs.

Verification after rework (details in Tasks):
- **pass:** validator and negative controls.
- **pass:** source-byte audit, 594/594.
- **pass:** field-level ledger check: 575 `review-date` changes, 19 additions, 0 removals, unchanged header, one distinct date.
- **pass:** `<configuration>` `cmp` identical, keyring unchanged, 0 trust-element additions.
- **pass:** strict empty-home `spotlessCheck check`, 121/121 tasks and 3,448 tests.
- **pass (optional):** integration suites, 154 and 71 tests. The run took 1 h 18 m, which is noted in Tasks.
- **pass:** link and anchor check over the review document and SECURITY.md (13 links, 0 broken), and `validate-agents-md.sh`.
- **pass:** `git diff --check 325814e`.

### Attempt 2
Reviewed revision: 037b4ec16e8654356e796463f088513168802b4a
Verdict: rework
Findings and dispositions:
1. **The key-validity artifact counts are inflated by six out-of-scope artifacts.** Gradle narrows a `<trusted-key>` by its `name` and `version` attributes, and the expired `com.diffplug.durian` key is scoped to `durian-swt.os` 4.3.0 ([`gradle/verification-metadata.xml:112`](../gradle/verification-metadata.xml#L112)). The six `durian-collect`, `durian-core` and `durian-io` 1.2.0 artifacts are therefore outside that key's scope; they are signed 2016-06-27 by the separate, valid group-wide key `8756C4F7…379CE192D401AB61`. Counting them makes the inventory too large:
   - the selection is **74** committed artifacts, not 80;
   - **68** were classified, not 74;
   - **17** were signed by a different trusted key, not 23;
   - the "signed while valid" count of 32 and the 19 post-expiry rows are correct, and still sum to 68 with the corrected 17.

   The security outcome is unchanged: the 19 exact rows cover every correctly scoped artifact signed after its key's recorded expiry, the keyring is still unchanged, and no trust was added. Only the documented evidence counts are wrong. **Disposition: rework** — correct `80`/`74`/`23` to `74`/`68`/`17` in the Constraints correction, the Key validity task, the Attempt 1 rework dispositions and the review document's method step and result table. Keep 32 and 19. No ledger or metadata change is indicated by this finding.
2. No other findings. The scoped implementation, the 19 classifications and the acceptance criteria otherwise hold.

Verification:
- Preconditions: **pass** — `Status: review`, full base revision, concrete scope, record outside scope; working tree clean at HEAD `037b4ec`; only the four scoped paths plus this record changed since base.
- Scoped diff (`325814e..037b4ec`): **pass** — exactly `SECURITY.md`, `config/dependency-provenance-exceptions.tsv`, `docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md` and `gradle/verification-metadata.xml`, plus this record; no dependency changes.
- Ledger edit: **pass** — field-by-field comparison: 575 existing rows changed only `review-date` (2026-12-01 → 2026-12-31); 19 additions (EX-875..EX-893), 0 removals, header byte-identical, one distinct review date; no duplicate IDs; new rows use the existing owner and trigger and a 64-hex SHA-256.
- Hashes: **pass** — base ledger `4079c494…`, metadata `3fdbcdb6…` and keyring `11d778c6…`; head ledger `873d19bd…` and metadata `cd0b29ed…`; all match the record and review document; keyring unchanged.
- Metadata: **pass** — `<configuration>` byte-identical to base; full diff adds only 19 artifact-scoped `<ignored-keys>` blocks (EX-875..EX-893), each pairing a valid checksum; no `<trusted-key>`, `<trusting>`, `<pgp>` or global `<ignored-key>` change; every ignore id is the actual signer key id and is expired or revoked in the committed keyring.
- Key inventory: **pass** — exactly 18 trusted, non-globally-ignored key references are revoked or expired (14 primary, 4 subkey), with the recorded expiry dates.
- Signature classification of the 19 rows: **pass** — all 19 artifacts and `.asc` files fetched from Maven Central, SHA-256 matches the ledger, signer key ids match the added ignores, and signature creation dates match the review document; each is after its signing key's recorded expiry (or, for `org.neo4j`, the subkey's expiry).
- Key-validity scope and counts: **fail** — finding 1.
- Validator and negative controls (`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`): **pass** — BUILD SUCCESSFUL.
- Source-byte audit (`python3 scripts/audit-dependency-provenance-sources.py`): **pass** — first pass matched 586/594 with 8 transient timeouts or resets; each failed row was retried individually and matched, so 594/594 match, 83,881,439 bytes (577 Maven Central, 17 Plugin Portal), identical to the record.
- Strict empty-home `spotlessCheck check --rerun-tasks`: **pass** — 121/121 tasks executed, 3,448 default-module tests across 508 result files with 0 failures, errors or skips. A first attempt was interrupted at a 60-minute tool timeout while still running; the rerun from the same empty home completed in about 9 minutes.
- Links and anchors: **pass** — 23 local links across the record, `SECURITY.md` and the review document resolve, including the `#key-validity` anchor.
- `./scripts/validate-agents-md.sh`: **pass** — exit 0.
- `git diff --check 325814e`: **pass** — exit 0.
- Optional integration tests: **skipped** — marked optional in the record; not rerun.
- Material limits: the manual scratch-tree Gradle signature controls were not independently rerun; publisher identity remains unauthenticated as documented; the empty-home first attempt was interrupted.

## Outcome
Pending.

## Next action
Run a fresh-context `keel-review` of this record (Attempt 2). The implementation is commits `cef9292` (key validity, 17 rows), `f539506` (renewal), `c508696` (review document and SECURITY.md), `dd3de26` (subkey key validity, 2 rows) and `813ae73` (18-key documentation correction), on branch `chore/renew-provenance-ledger-2026-12`. Review and merge must happen before 2026-12-01 UTC, when the current rows expire on `main`.
