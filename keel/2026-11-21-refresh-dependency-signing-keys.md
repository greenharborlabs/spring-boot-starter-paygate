# Refresh stale signing keys and settle the held Spotless and OkHttp keys
Status: closed
Base revision: 4eceb4190a10a7a13401e11ad91aaf166fbac718
Review scope: gradle/verification-keyring.keys, gradle/verification-metadata.xml, config/dependency-provenance-exceptions.tsv, .github/dependabot.yml, docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-21.md, SECURITY.md, CHANGELOG.md

## Goal
Bring the committed keyring up to date before the 2026-12-31 UTC renewal, so the renewal carries fewer rows and the two held updates can be unblocked.

1. **Refresh.** Update the expiry and revocation data for the 18 revoked or expired trusted keys ([November 18 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity)). Then remove the exact rows and artifact ignores that only the stale data required.
   - **Planning probe** (scratch only, unverified until implementation): keyservers hold newer self-signatures for 4 of the 8 keys behind EX-875..EX-893:
     - Mockito `147B691A…689CBE64F4BC997F`, to 2030-10-11;
     - Neo4j signing subkey `07A4FA67…F8052E4E1BD0DB31`, to 2027-09-20;
     - org.json `FB35C8D0…FD116C1969FCCFF3`, to 2035-12-22;
     - ANTLR `B01128C8…347CD0EAED975C5F`, to 2023-02-08 (keys.openpgp.org only).
   - **Expected result:** 12 rows retire (EX-879..EX-881 and EX-885..EX-893). 7 stay: Android `annotations`, `querydsl-bom`, `rsocket-bom` and Hamcrest, whose keys show no extension.
2. **Held keys.** Decide both held key changes on recorded evidence.
   - **Spotless 8.10.3 and later:** these are signed by subkey `2820DE9F95EC4043E616B735FA5C18CFB74EC7C6`. The probe shows it is bound by the same DiffPlug primary key (`27294482…1AAAA7AB37293D3E`, already in the keyring) that bound the trusted subkey `A31DDE88…2C7F998F4272C851`.
   - **OkHttp 5.5.1:** this is signed by `5DD80B0DDCCBE005F0A47FD66751E1A6E2001B8D`, with UID "lysine.dev <project-lysine@commonhaus.org>". The key is self-signed only. The new steward's own CHANGELOG announces both the key and the move to the Commonhaus Foundation.

Acceptance evidence:
- **Fetched copies:** for each of the 18 keys, the review document records each fetched copy (source and SHA-256) and the change against the committed copy.
- **Self-signatures:** every signature the refresh adds verifies against a primary fingerprint already in the committed keyring.
- **Keyring diff:** confined to the refreshed blocks. The fingerprint set (primaries and subkeys) is unchanged, apart from the approved Spotless subkey.
- **Reclassification:** all 74 in-scope artifacts are reclassified against the refreshed keyring, with subkey and full-scope matching. Every artifact that loses its row verifies with `gpgv` and was signed within its key's refreshed validity.
- **Metadata and ledger:** the metadata diff removes only those artifact-scoped ignores, plus any approved part 2 entry. The field-level ledger diff removes only those rows, plus any row the rule newly requires.
- **Part 2:** the evidence and the owner's decision for each held key are recorded. Any added trust is exactly the approved entry. A Dependabot ignore is removed only when its key is settled.
- **Controls:** the validator and its negative controls, the source-byte audit of the remaining rows, and a strict empty-home `spotlessCheck check` all pass.
- **Documentation:** the review document exists, SECURITY.md links it as the current review, and the CHANGELOG has an entry.

## Constraints
- **Policy.** Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [dependency provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - a key change requires review;
  - never accept generated metadata, a key-server identity, or a valid signature from an unauthenticated key as approval by itself;
  - match every key reference against both primary and subkey fingerprints, and apply every scope attribute.
- **Starting state** is `main` at `61a64b8`:
  - keyring `11d778c6…`, metadata `e3eff36c…`, ledger `9a6b2a80…`;
  - 594 rows, all at 2026-12-31;
  - 77 IDs left (EX-923..EX-999);
  - 18 revoked or expired trusted keys covering 74 artifacts, 6 of which already had rows.
- **Why a refresh is safe.** A keyserver can withhold a self-signature but cannot forge one. A newer expiry or a revocation that verifies against an already-trusted fingerprint is therefore authenticated by that key. The remaining risk is a withheld revocation, so every key is fetched from both keys.openpgp.org and keyserver.ubuntu.com, plus the publisher's own channel where one is documented. keys.openpgp.org strips unverified UIDs.
- **What a refresh may add.** It adds no UID, no subkey and no third-party certification. A new subkey would widen what a primary-fingerprint trust accepts. The only exception is the Spotless subkey, which is a separate decision.
- **Fail closed.** If a fresh copy shows a revocation or a shorter validity, apply it. Artifacts signed after it get rows under the key-validity rule.
- **No renewal and no version changes.** No `review-date` changes. Landing Spotless 8.10.4 and OkHttp 5.5.1 is follow-up Dependabot work, because transitive artifacts are undercounted before resolution (#113: 10 estimated, 29 actual; [record](2026-11-18-land-gradle-dependency-updates-113.md)).
- **OkHttp context.** The Square key `793FD5751A0F0780` is already globally ignored, so OkHttp 5.3.2 is accepted today by exact rows ([November 15 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md)). Okio (`com.squareup.okio`, trusted under the Square key) joined Commonhaus too, so its next release will hit the same key change.
- **Tooling.** Use `gpg --show-keys` and `gpgv` with an absolute path to a dearmored scratch keyring. Do not use `gpg --homedir` imports, which hang. Run `scripts/validate-agents-md.sh` only when no Gradle run is going.

## Decisions
- **Refresh method.** Merge the fetched copies into a scratch copy of each key, keep only the existing primary, UIDs and subkeys with their newest valid self-signatures, and replace that one block in the keyring's existing header-and-armor format.
  - Rejected: regenerating the keyring with Gradle `--export-keys`. It rewrites all 95 blocks from keyservers, so the diff cannot be reviewed.
  - Rejected: refreshing all 95 keys. A full revocation sweep is worthwhile but is a separate task.
- **Refresh scope.** All 18 keys are checked. A block changes only where a newer valid self-signature or a revocation exists. The 32 artifacts signed while their key was valid keep their coverage either way.
- **Spotless (proposed).** Add the subkey `2820DE9F95EC4043E616B735FA5C18CFB74EC7C6`, with its binding, to the committed DiffPlug block. Add `<trusted-key id="2820DE9F95EC4043E616B735FA5C18CFB74EC7C6" group="com.diffplug.spotless"/>`, and remove the `>= 8.10.3` Dependabot ignore.
  - Evidence bar: the binding verifies against the committed primary, the 8.10.4 artifacts' `.asc` files verify with the refreshed block, and no revocation exists.
  - Rejected: trusting the primary `27294482…`, which would accept any future subkey without review.
- **OkHttp (evidence, then stop and ask).** Record the evidence first:
  - the GitHub transfer of `square/okhttp`, which only a Square org owner could make;
  - any announcement on a Square-controlled channel;
  - Commonhaus's own project listing;
  - every place the fingerprint is published.

  Then ask the owner to choose:
  - **A.** Trust the key for `com.squareup.okhttp3`. This needs a Square- or Commonhaus-controlled publication of the fingerprint.
  - **B.** Lift the hold and land 5.5.1 later on exact rows, as 5.3.2 is today. No trust is added.
  - **C.** Keep the hold.

  Proposed: B, if a Square-controlled channel confirms the transfer.

Approved: 2026-11-21 by Mark Hammond at plan revision 4eceb4190a10a7a13401e11ad91aaf166fbac718.

**OkHttp decision (checkpoint, 2026-11-21): Mark Hammond chose B.**
- **Evidence:** the repository transfer from `square` and the Commonhaus announcement and `PROJECTS.yaml` confirm the stewardship move. The fingerprint is published only on lysine-dev channels.
- **Result:** the hold is lifted and no trust is added. Option A's bar was not met.

## Tasks
Implementation commit: `a6b68e4`. Scratch tooling and fetched copies stayed in the session scratchpad and were not committed. The method and evidence are in the [November 21 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-21.md).

- [x] Baseline: record the base SHA-256s and rerun the 18-key inventory. Check: the counts reproduce 18 keys, 74 artifacts and 68 without rows.
  - **pass:** base keyring `11d778c6…`, metadata `e3eff36c…`, ledger `9a6b2a80…`.
  - The inventory parsed `<trusted-key>`/`<trusting>` scopes with every attribute and regex, matched primary and subkey fingerprints, and excluded globally ignored keys. It found 18 keys (4 by subkey) and 74 artifacts.
  - On `main` today, 25 of the 74 have rows: the original 6 plus EX-875..EX-893. The other 49 have none, which equals the November 18 count of 68 minus those 19.
- [x] Fetch all 18 keys from both keyservers, and from publisher channels where documented. Check: the review-document table lists each copy's SHA-256 and the expiry, revocation, UID and subkey differences.
  - **pass:** 36 requests were made on 2026-11-21. Ubuntu served all 18; keys.openpgp.org served 13 and returned 404 for 5. The table lists every copy's SHA-256 and the committed and refreshed validity.
  - No copy carries a revocation that is not already committed.
  - Publisher channels were not consulted for the 18. Every added signature is self-certified by the committed primary, so they would not change the outcome.
- [x] Build the refreshed blocks. Check: `gpg --check-sigs` verifies every added self-signature against the committed primary; the keyring diff is confined to refreshed blocks; the `--show-keys` fingerprint set equals the base set.
  - **pass:** the merge is packet-level. For each existing UID or subkey, it adds only the newest self-signature newer than the committed one, plus any revocation; no new UID, subkey or third-party certification. Six keys changed (ANTLR, Mockito, org.json, Neo4j, Michael Osipov, Kotlin Libraries).
  - **pass:** `gpg --show-keys --with-sig-check` reports every self-signature on the 18 merged keys as good.
  - **pass:** a separate packet-comparison check against the base file shows:
    - all Gradle-generated (headed) blocks are byte-identical, and only blocks 74, 91 and 94 changed;
    - key order and fingerprints are unchanged;
    - every base packet is retained in order;
    - every added packet is a self-issued signature, apart from the approved Spotless subkey.
  - **pass:** the self-certified fingerprint set is 237 at base and equals base plus `2820DE9F…FA5C18CFB74EC7C6` at the end.
  - The armor writer round-trips the base keyring byte for byte.
  - Limit: the 93-key block is re-armored, so its text diff spans the whole block.
- [x] Reclassify the 74 artifacts against a dearmored refreshed keyring, using `gpgv` and the signature creation times. Check: each artifact's outcome is recorded, and each retired row's artifact verifies within its refreshed validity.
  - **pass:** all 74 artifacts and their `.asc` files were fetched from Maven Central. The SHA-256s match the metadata, 74/74.
  - **Base keyring:** reproduces November 18 (17 other key, 32 valid, 19 after validity, 6 prior rows).
  - **Refreshed keyring:** 17 other key, 44 valid, 7 after validity, 6 prior rows.
  - The 12 that moved are EX-879..EX-881 and EX-885..EX-893. Each verifies with `VALIDSIG` and was signed before its refreshed validity end.
  - No artifact newly fell outside validity.
- [x] Apply the metadata and ledger changes: retire ignores and rows only for reclassified artifacts, and add rows for any artifact newly outside validity. Check: the field-level ledger diff and the metadata diff.
  - **pass, ledger:** the field-level comparison shows only the 12 IDs removed and no row or header changed. 594 → 582 rows, all at 2026-12-31. Final SHA-256 `2c5b3bd4…`.
  - **pass, metadata:** the diff removes only the 12 artifact-scoped `<ignored-keys>` blocks naming those IDs, and adds one `<trusted-key>` line for Spotless. Components and artifacts stay at 547/964; trusted keys go from 118 to 119. Final SHA-256 `e9245bee…`.
- [x] Spotless: verify the subkey binding and the 8.10.4 plugin and library signatures, then apply the proposal if the bar is met. Check: `gpgv` results; `dependabot.yml` loads.
  - **pass, binding:** the binding of `2820DE9F…FA5C18CFB74EC7C6` is issued by the committed primary `1AAAA7AB37293D3E` and checks good (`!`). No revocation is present.
  - **pass, signatures:** `gpgv` reports `VALIDSIG 2820DE9F…` for the jar, POM and module of `spotless-plugin-gradle` 8.10.4 and 8.10.3, and of `spotless-lib` and `spotless-lib-extra` 4.10.4. All give `NO_PUBKEY` with the base keyring.
  - **Applied:** the subkey and its binding were added to the full DiffPlug copy, with `<trusted-key … group="com.diffplug.spotless"/>`, and the ignore was removed.
  - **pass:** `dependabot.yml` loads (Ruby YAML). The Gradle ignores are exactly `io.grpc:*` and `com.google.protobuf` `0.10.1`.
- [x] OkHttp: record the evidence, **stop and ask**, then apply the owner's choice. Check: the decision is recorded here with its date.
  - **pass:** the evidence is recorded in the review document. The owner chose B on 2026-11-21 (Decisions). The `com.squareup.okhttp3:*` ignore was removed, and nothing was added to the keyring or metadata for OkHttp.
- [x] Documentation: write the review document, link it from SECURITY.md as the current review, and add a CHANGELOG `Security` entry. Check: relative links resolve.
  - **pass:** relative links and anchors resolve in the review document, SECURITY.md, CHANGELOG and this record (0 problems).
- [x] Controls: run `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, the `scripts/audit-dependency-provenance-sources.py` source-byte audit of all remaining rows, and `./gradlew --no-daemon -g <new empty home> spotlessCheck check --rerun-tasks` (baseline: 121 tasks, 3,448 tests). Then run `scripts/validate-agents-md.sh` with no Gradle running, and `git diff --check`.
  - **pass:** validator and negative controls, exit 0.
  - **pass:** the source-byte audit matches 582/582 (565 from Maven Central, 17 from the Plugin Portal).
  - **pass:** strict empty-home `spotlessCheck check --rerun-tasks` (2026-11-21T19:06Z), exit 0. 121/121 tasks executed. Default-module test XML: 508 files and 3,448 tests, with 0 failures, errors or skips.
  - **pass:** `scripts/validate-agents-md.sh`, run after all Gradle runs.
  - **pass:** `scripts/test-dependency-provenance.sh`.
  - **pass:** `git diff --check`.
  - Process note: `/verify` was run after, not before, the implementation commit. It reported that the repo declares no checks (`CHECKS: none`).
- [x] (optional) Gradle scratch control: delete one retired-row artifact's checksum in a scratch tree, and confirm strict verification passes on the refreshed key's signature.
  - **pass, Spotless subkey control (replaces the planned control):** a scratch project with the final keyring and metadata and no checksum for `spotless-lib` 4.10.4 resolves the jar. With the new `<trusted-key>` removed, it fails dependency verification for the jar and module.
  - The control shows Gradle picks up the subkey from the full DiffPlug copy, even though a Gradle-generated copy of the same primary exists.
  - The planned retired-row control was not run separately. The strict build verified those artifacts with no ignore present.

## Review
### Attempt 1
Reviewed revision: e112e1b95dd7ee2906b051d7092be9984c4daa8b
Verdict: pass
Findings and dispositions:
1. (non-blocking, documentation) The review document's keyring-structure counts state 71 headed and 24 headerless blocks; the committed keyring has 70 headed and 25 headerless blocks (the 25th is the 93-key block). The record's "blocks 74, 91 and 94 changed" is consistent only with a 0-based index; the 1-based positions are 75, 92 and 95. The packet-level facts are correct and the refresh evidence is unaffected. Correct opportunistically; no rework required.
Verification:
- Scoped history: pass. HEAD `e112e1b` differs from implementation commit `a6b68e4` only in this record; `a6b68e4` changes exactly the seven scoped files. Base hashes match the record.
- Keyring: pass. 95 blocks; only the ANTLR full copy, the Mockito full copy and the 93-key block changed. The fingerprint set is 237 -> 238 with only `2820DE9F95EC4043E616B735FA5C18CFB74EC7C6` added. Every base packet is retained in order; every added packet is a self-signature by a primary already in the base keyring, plus the approved Spotless subkey and its binding. No new revocation.
- Key validity: pass. Refreshed expiries match the record: ANTLR 2023-02-08, Mockito 2030-10-11, org.json 2035-12-22, Neo4j 2027-09-20, Osipov 2028-10-14, Kotlin 2028-09-26; the Spotless subkey is created 2026-09-17 and expires 2030-05-27.
- Retired-row reclassification: pass. All 12 retired artifacts' SHA-256s match the ledger; each gives `VALIDSIG` with the refreshed keyring and `NO_PUBKEY`/`ERRSIG` with the base keyring; each signature time is inside the refreshed validity.
- Spotless: pass. The subkey binding verifies against the committed DiffPlug primary `27294482…1AAAA7AB37293D3E`. `spotless-plugin-gradle` 8.10.3 and 8.10.4 and `spotless-lib`/`spotless-lib-extra` 4.10.4 jars give `VALIDSIG` with the new subkey and `NO_PUBKEY` with the base keyring.
- Metadata, ledger and Dependabot: pass. Metadata removes exactly the 12 artifact-scoped ignores and adds only the approved `<trusted-key>`; components/artifacts stay 547/964 and trusted keys go 118 -> 119. The ledger removes exactly the 12 IDs; 582 rows remain, all at 2026-12-31, and EX-923..EX-999 are free. `dependabot.yml` loads; only the gRPC and protobuf ignores remain.
- Documentation: pass. The review document exists, SECURITY.md links it as current, the CHANGELOG has a Security entry, and the referenced files and anchors resolve.
- Controls: pass. `validateDependencyProvenance` and `verifyDependencyProvenanceNegativeControls` passed; the source-byte audit matched 582/582 (565 Maven Central, 17 Plugin Portal); strict empty-home `spotlessCheck check --rerun-tasks` passed with 121/121 tasks and 508 default-module test XML files / 3,448 tests, 0 failures, errors or skips; `scripts/test-dependency-provenance.sh`, `scripts/validate-agents-md.sh` and `git diff --check` passed.
- Optional control: skipped as planned; the recorded Spotless-subkey scratch control passed. No blocking impact.
- Material limits: I did not refetch the keyserver copies or the external OkHttp evidence; I relied on the record's recorded SHA-256s and links. A first strict empty-home run produced partial test output; an immediate repeat with the same home produced the full counts above.

## Outcome
Closed after review Attempt 1 passed at `e112e1b` (implementation `a6b68e4`). The [November 21 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-21.md) holds the evidence, and [SECURITY.md](../SECURITY.md#dependency-provenance-and-local-credentials) links it as the current review.

- **Key refresh:** 6 of the 18 revoked or expired trusted keys gained newer self-signatures from their own committed primaries: ANTLR, Mockito, org.json, Neo4j, Michael Osipov and Kotlin Libraries. No UID, third-party certification or revocation was added.
- **Rows retired:** 12 artifacts were reclassified as signed while valid, so their rows and artifact-scoped ignores were retired (EX-879..EX-881, EX-885..EX-893). The ledger goes from 594 to 582 rows, all still at 2026-12-31. The other 7 rows stay, because their keys (Android annotations, querydsl, rsocket, Hamcrest) show no extension.
- **Spotless:** the subkey `2820DE9F…FA5C18CFB74EC7C6`, bound by the committed DiffPlug primary, is trusted for `com.diffplug.spotless`. The `>= 8.10.3` Dependabot hold is removed.
- **OkHttp:** under the owner's decision B, the `com.squareup.okhttp3` hold is removed and no trust is added for the lysine.dev key. 5.5.x will land on exact rows.
- **Verification:** every required check passed. These were:
  - the provenance validator and its negative controls;
  - the source-byte audit, 582/582;
  - the strict empty-home `spotlessCheck check --rerun-tasks`, 121/121 tasks and 3,448 tests with 0 failures;
  - packet-level keyring comparison and `gpgv` reclassification of all 74 in-scope artifacts;
  - `scripts/test-dependency-provenance.sh`, `scripts/validate-agents-md.sh` and `git diff --check`.

  The reviewer independently repeated the keyring, reclassification, Spotless, metadata, ledger and control checks.
- **Limits:**
  - The keyserver copies and the OkHttp evidence were fetched once, on 2026-11-21. The reviewer relied on their recorded SHA-256s.
  - Only 18 of the 116 keys were checked for revocations.
  - Landing Spotless 8.10.4 and OkHttp 5.5.1 is separate Dependabot work.
  - The review finding about block counts is unresolved and does not block. The reviewer counted 70 headed and 25 headerless blocks. A closeout recount of blocks whose header has a `pub` line gives 71 and 24, which matches the review document. The block positions in Tasks are 0-based.

## Next action
Open a PR from `chore/refresh-signing-keys-2026-11` to `main` and merge it before the 2026-12-31 UTC renewal in [the renewal record](2026-11-18-renew-dependency-provenance-ledger-2026-12.md).
