# Refresh stale signing keys and settle the held Spotless and OkHttp keys
Status: working
Base revision: 4eceb4190a10a7a13401e11ad91aaf166fbac718
Review scope: gradle/verification-keyring.keys, gradle/verification-metadata.xml, config/dependency-provenance-exceptions.tsv, .github/dependabot.yml, docs/security/DEPENDENCY-PROVENANCE-REVIEW-<UTC work date>.md, SECURITY.md, CHANGELOG.md

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

## Tasks
- [ ] Baseline: record the base SHA-256s and rerun the 18-key inventory. Check: the counts reproduce 18 keys, 74 artifacts and 68 without rows.
- [ ] Fetch all 18 keys from both keyservers, and from publisher channels where documented. Check: the review-document table lists each copy's SHA-256 and the expiry, revocation, UID and subkey differences.
- [ ] Build the refreshed blocks. Check: `gpg --check-sigs` verifies every added self-signature against the committed primary; the keyring diff is confined to refreshed blocks; the `--show-keys` fingerprint set equals the base set.
- [ ] Reclassify the 74 artifacts against a dearmored refreshed keyring, using `gpgv` and the signature creation times. Check: each artifact's outcome is recorded, and each retired row's artifact verifies within its refreshed validity.
- [ ] Apply the metadata and ledger changes: retire ignores and rows only for reclassified artifacts, and add rows for any artifact newly outside validity. Check: the field-level ledger diff and the metadata diff.
- [ ] Spotless: verify the subkey binding and the 8.10.4 plugin and library signatures, then apply the proposal if the bar is met. Check: `gpgv` results; `dependabot.yml` loads.
- [ ] OkHttp: record the evidence, **stop and ask**, then apply the owner's choice. Check: the decision is recorded here with its date.
- [ ] Documentation: write the review document, link it from SECURITY.md as the current review, and add a CHANGELOG `Security` entry. Check: relative links resolve.
- [ ] Controls: run `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, the `scripts/audit-dependency-provenance-sources.py` source-byte audit of all remaining rows, and `./gradlew --no-daemon -g <new empty home> spotlessCheck check --rerun-tasks` (baseline: 121 tasks, 3,448 tests). Then run `scripts/validate-agents-md.sh` with no Gradle running, and `git diff --check`.
- [ ] (optional) Gradle scratch control: delete one retired-row artifact's checksum in a scratch tree, and confirm strict verification passes on the refreshed key's signature.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved plan.
