# Tighten the dependency-provenance validator and renew the exception ledger
Status: review
Base revision: 651c43e985d223a72b9c2c0b3acf7f69635d981d
Review scope: scripts/validate-dependency-provenance.sh, scripts/test-dependency-provenance.sh, scripts/test-fixtures/dependency-provenance/cases.tsv, scripts/audit-dependency-provenance-sources.py, config/dependency-provenance-exceptions.tsv, gradle/verification-metadata.xml (`<sha256 reason>` attributes only), docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md, SECURITY.md

## Goal
Keep root `check` passing after 2026-11-22 UTC. Close the validator gap recorded in the [November 1 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#limits): every artifact that Gradle accepts on its checksum alone must have an exact ledger row, and the validator must enforce that.

Acceptance evidence:
- **The rule is enforced.** The validator rejects each new negative-control fixture and accepts the committed inputs.
- **Every checksum-only artifact has a row.** Each gap artifact is either given an exact row that `scripts/audit-dependency-provenance-sources.py` confirms against its source bytes, or removed because nothing resolves it.
- **Every row is renewed** on re-verified source bytes, to the deadline Mark approves below. The field-level diff of the existing rows changes `review-date` only.
- **Evidence.** A new dated review document records the evidence and justifies the interval. SECURITY.md links it as the current review and describes the enforced rule.
- **No new trust.** The keyring and `<trusted-keys>` are byte-identical to base. No `<trusted-key>`, `<trusting>` or `<pgp>` is added or widened.
- The required checks in Tasks pass.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - Generated metadata, a key-server identity, or a valid signature from an unauthenticated key is never publisher approval.
  - The owner must justify each interval explicitly.
  - Changing dates alone is not a renewal.
  - Re-resolve from an isolated Gradle user home after any checksum or exception change.
- **Base state.** `main` is at `c288ec6`. The metadata (`4983724f…`), keyring (`11d778c6…`) and ledger (`f1f52ab5…`) are byte-identical to the reviewed November 1 state. So that review's enumeration of the resolved set (546 components, 965 artifacts) still holds and does not need regenerating. Confirm these hashes at approval.
- **What a static scan of the base shows.** A static classification of the 965 artifacts, made while planning and to be re-run in WORK, finds rowless artifacts in four places:
  - 34 artifacts are covered by no trusted-key scope, or only by a scope whose key is ignored. These include logback 1.5.38, jackson-annotations 2.21 and its parents, okhttp 4.12.0, okio 3.6.0, opentelemetry-bom 1.49.0, checker-qual, and the 13 junit-bom files.
  - 14 are annotated `Artifact is not signed`: the 13 legacy unsigned artifacts and the Guava `-android` jar.
  - 3 are hand-verified (`origin="Verified against Maven Central"`): the two Linux protoc executables and the `apiguardian-api` 1.1.2 POM.
  - 6 have artifact-level `<pgp>` pins.

  The prompt's 46 includes about 11 more artifacts, such as google-java-format 1.24.0 and 1.35.0, error_prone 2.21.1, guava-parent 26.0-android and failureaccess 1.0.1. These sit under a broad, non-ignored scope such as `^com[.]google(...)` but are signed by a different key.

  **So a rule based only on coordinates and scopes cannot detect every checksum-only artifact.** Only the detached signature identifies the signer.
- **Conflict with the November 1 review.** The `org.codehaus.plexus:plexus:8` POM has been pinned to `FA77…012579464D01C06A` since `70fb917` (2026-08-22). The November 1 reconciliation (`198fc2d`) added `012579464D01C06A` to the global `<ignored-keys>`. If Gradle lets a global ignore override an artifact pin, this POM has passed on its checksum alone since then. That would contradict the review's statement that no signature-covered artifact is signed by the newly ignored keys. WORK treats the POM as checksum-only (fail closed) and records which way Gradle actually behaves.
- **Validator implementation.**
  - Stay in bash and awk, with no new CI tool dependency, and never resolve dependencies.
  - Gradle's trusted-key `regex` scopes are Java regular expressions. The current ones are ERE-compatible. If the validator cannot evaluate a scope with the same meaning, that scope must count as not covering the artifact, which requires a row (fail closed).
- **ID space.** Exception IDs match `EX-[0-9]{3}`. New rows start at EX-797, which leaves about 140 free IDs. Widening the format is out of scope; it is recorded as a limit.
- **Timing.** The CVE-2025-32013 suppression renewal (due 2026-11-30) is a separate task. Key recovery, trusting keys and narrowing scopes are also out of scope.
- **Long runs.** Runs from an empty Gradle home are slow. Run them in the background with explicit timeouts, and check the processes directly rather than waiting blindly. Avoid the known zsh pitfalls: no loop variable named `path`, write `"${D}:file"`, quote grep globs, and don't start an echo with `====`.

## Decisions
- **The validator rule: a row is required for each checksum-only artifact, decided by three conditions.** Each `<component>`/`<artifact>` is parsed as a unit, and the artifact needs an exact row (group, module, version, file and SHA-256 all matching) if any of these holds:
  1. **Annotation.** Its `<sha256>` has any `reason` attribute, which covers `A key couldn't be downloaded`, `Artifact is not signed`, the publisher-controlled reason, and the new reason below.
  2. **No covering key.** No `<trusted-key>`/`<trusting>` scope matches its coordinates with a key that is not ignored globally or on the artifact.
  3. **Ignored pin.** Its only `<pgp>` pins name ignored keys.

  `origin="Verified against Maven Central"` is no exemption.
  - **The residual class is annotated.** The roughly 11 artifacts that sit under a non-ignored scope but are signed by another key get one new informational `<sha256 reason="Signature is not from a key trusted for these coordinates">`. Condition 1 then enforces their rows. This changes only the metadata `reason` attribute, so it adds no trust.
  - **Row linkage gets stricter.** The existing per-row checks are three independent global greps (component, artifact name, checksum). They become a single exact match on the same component, artifact and checksum.
  - **Rejected: coordinates and scopes alone,** the prompt's parenthetical rule. It misses the residual class above.
  - **Rejected: dropping checksums from signature-covered artifacts** so that Gradle itself proves coverage. It shifts those artifacts to trust by key alone and churns about 500 entries just before a deadline.
  - **Rejected: committing a signer map per artifact.** The offline validator could not verify the map any better than a `reason`.
- **Residual limit, documented.** A future upgrade could add an unannotated checksum-only artifact under a broad scope. The validator cannot detect that offline. The per-upgrade detached-signature classification already required by policy remains the control, and SECURITY.md says so.
- **Artifact-level `<pgp>` pins count as coverage only under two conditions:** the pinned fingerprint is a 40-hex key present in the committed keyring (a new validator check), and it is not ignored. WORK confirms that each of the 5 non-ignored pins verifies with its pinned key, so they get no row.
  - `plexus-8.pom` (ignored pin) gets a row.
  - Rejected: rows for valid pins. A pin is the narrowest trust already reviewed (`70fb917`), so a row would only duplicate it.
- **The Guava `-android` jar gets a row.** It is checksum-only (`Artifact is not signed`) even though its signature verifies out of band. The validator and the audit allow exactly one source mapping: component version `<v>-android` to source version `<v>-jre`, only when the filename is `<module>-<v>-jre.<ext>`. No other source/version mismatch is accepted.
  - Rejected: a separate exemption list, which would be a second ledger.
  - Rejected: no row, the 33.5.0 precedent, which violates the rule.
- **Rows for the gap artifacts.** About 61 exact rows are expected, to be confirmed in WORK: 46 + 14 + 1, which is the 46 gap artifacts, the 13 legacy unsigned artifacts and the Guava jar, and `plexus-8.pom`.
  - IDs are EX-797 upward. The owner is `Green Harbor Labs maintainer`, with the existing trigger. Existing rationales are reused where they fit, plus one new rationale each for unsigned Maven Central artifacts, the Guava variant redirect and the ignored pin.
  - Plugin markers use the bytes Gradle resolved, from the Plugin Portal where applicable.
  - Any metadata entry that nothing resolves is removed rather than given a row. None is expected, given the November 1 enumeration.
- **Renewal.** All 511 existing rows are renewed only after `scripts/audit-dependency-provenance-sources.py` re-verifies their source bytes. Gap rows take the same deadline, so the ledger keeps a single renewal cycle.
- **Proposed deadline: 2026-12-01 UTC**, 30 days from 2026-11-01, **for Mark's explicit approval.**
  - **Why this date:** it falls the day after the CVE-2025-32013 suppression deadline (Mon 2026-11-30), so both monthly reviews happen in the same week. It keeps the 30-day interval of the September 23 and October 15 renewals. Publisher identity is still unauthenticated, so a longer interval would extend unauthenticated trust without new evidence.
  - **Alternatives:** 2026-12-02 or 2026-12-03 (31–32 days), if the work date slips past 2026-11-02.
- **Two separable commits, renewal first.**
  - **Commit R:** the date renewal of the 511 existing rows, plus the review document's renewal section and the SECURITY.md current-review link.
  - **Commit V:** the validator rule, fixtures, audit-script mapping, metadata `reason` annotations, gap rows, the evidence section, and the SECURITY.md rule text.
  - R passes the current validator alone. If V is not review-passed by 2026-11-18 UTC, R ships alone in its own PR (cherry-picked onto `main`), and V follows.
- **Documentation.** A new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md` in the form of the October 15 and November 1 reviews. It takes a `-2` suffix if that date already has a review, as 2026-11-01 does. It records:
  - the base hashes and the static and signature classification counts;
  - the gap rows by category, and the plexus finding;
  - the audit results, the field-level renewal diff and the checks;
  - the limits and the justified interval.

  SECURITY.md updates the current-review sentence and states the enforced rule and its residual limit.

Approved: 2026-11-01 by Mark Hammond at plan revision 651c43e985d223a72b9c2c0b3acf7f69635d981d. Deadline confirmed: 2026-12-01 UTC (30 days), as proposed.

## Tasks
- [x] At approval, confirm that `main` is `c288ec6` and that the metadata, keyring and ledger hashes match those in Constraints. Run `./gradlew --no-daemon validateDependencyProvenance` before any edit.
- [x] **Commit R.**
  1. Run `python3 scripts/audit-dependency-provenance-sources.py`. It must report 511/511 matches (500 Maven Central, 11 Plugin Portal). Record `blocked` if the network is unavailable and `incomplete` if the run fails partway.
  2. Set `review-date` to the approved deadline on all 511 rows. A field-level diff against base must show 511 `review-date` changes and nothing else.
  3. Write the review document's renewal section and the SECURITY.md current-review link, then commit.
- [x] **Classification.** Re-run the static classification. Then run the detached-signature classification, using HTTPS `.asc` files, an isolated GPG home holding only the committed keyring, and `--no-auto-key-retrieve`, over every checksum-carrying artifact without a row. It must confirm:
  - the residual class;
  - the 5 valid pins;
  - the protoc executables' coverage.

  Record the count for each category.
- [x] (optional, skipped) Gradle cross-check. From an empty home, run `--write-verification-metadata sha256,pgp` on a scratch copy with `<configuration>` kept and `<components>` emptied, then discard the output. The goal is to observe Gradle's checksum-only set and whether a global ignore overrides the `plexus-8` pin.
- [x] **Commit V.**
  1. Implement the rule, the strict row linkage, the pin keyring check and the Guava mapping, in both the validator and the audit.
  2. Add the residual `reason` annotations and the gap rows.
  3. Add fixtures to `cases.tsv` and `test-dependency-provenance.sh`. Each case must be rejected:
     - a missing row for an unscoped artifact;
     - a missing row for an `Artifact is not signed` artifact;
     - a missing row for a `reason`-annotated residual artifact;
     - a missing row under an ignored-key scope (okio or Jackson);
     - a missing row for the ignored `plexus-8` pin;
     - a pin to a fingerprint absent from the keyring;
     - a row whose checksum belongs to a different artifact;
     - a Guava-style source mapping on a non-`-android` component, or to a different version.

     `clean-resolution` must still pass.
  4. Write the review document's evidence section and the SECURITY.md rule text, then commit.
- [x] **Checks after V,** with each command, exit code, task count, test totals and omissions recorded. The baseline is 3,448 default-module tests, 154 integration `test` and 71 `securityTest`.
  - `python3 scripts/audit-dependency-provenance-sources.py`: every row matches.
  - `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon spotlessCheck check --rerun-tasks` (strict verification)
  - `./gradlew -Pintegration :paygate-integration-tests:test :paygate-integration-tests:securityTest`
  - `git diff c288ec6 -- gradle/verification-keyring.keys` must be empty. In `gradle/verification-metadata.xml`, the `<configuration>` block must be byte-identical to base, and the diff must contain no `<trusted-key|trusting|pgp|ignored-key>` lines, only changes to `<sha256>` `reason` attributes.
  - `git diff --check c288ec6 HEAD -- . ':!gradlew.bat'` must be clean. `gradlew.bat` is CRLF by design.

## Review
Implementation complete at `cee9480` on base `651c43e` (approval record `0accf22`); Attempt 1 rework complete at `e446650`. The implementation has two commits:
- **Commit R** (`df3b296`): the date renewal, the review document's renewal section, and the SECURITY.md current-review link. It passed the unchanged validator and negative controls on its own.
- **Commit V** (`cee9480`): the rest of the scope.

The work date is 2026-11-03 UTC, so the review document is [DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md). Awaiting fresh-context review.

WORK evidence (Java 25.0.1 Temurin, Gradle 9.8.2, GnuPG 2.5.20, macOS arm64):
- **Approval checks: pass.** `origin/main` = `c288ec6`. The metadata `4983724f…`, keyring `11d778c6…` and ledger `f1f52ab5…` hashes match. The pre-edit `validateDependencyProvenance` exited 0.
- **Commit R audit: pass.** `python3 scripts/audit-dependency-provenance-sources.py` at 2026-11-03T02:57Z exited 0: 511/511 matches, 72,420,133 bytes, 500 from Maven Central and 11 from the Plugin Portal.
- **Commit R field diff: pass.** 511 `review-date` changes (`2026-11-22` to `2026-12-01`), with no other field or header changes. Ledger `af2c48c7…`.
- **Classification: pass.** All 454 rowless checksum-carrying artifacts matched their metadata SHA-256 at HTTPS sources. Their signatures were checked in an isolated GPG home with the committed keyring only (187 keys processed, 116 imported, 52 without user IDs) and `--no-auto-key-retrieve`. The results:
  - 391 are covered;
  - 63 are checksum-only: 31 with a valid signature from a key that isn't trusted for them or is ignored, 19 `NO_PUBKEY`, and 13 unsigned;
  - the Guava `-android` jar is checksum-only in Gradle;
  - all 10 existing rows under a non-ignored scope are signed by a different key.

  The validator's static conditions require 49 of the 64 checksum-only artifacts. The other 15, plus those 10 rows, form the residual class of 25 that was annotated.
- **Optional Gradle cross-check: skipped.** The GPG classification against the committed keyring is the stricter test, and every artifact it does not cover now has a row.
- **Commit V audit: pass.** At 2026-11-03T03:06Z: 575/575 matches, 82,476,457 bytes, 558 from Maven Central and 17 from the Plugin Portal.
- **Ledger: pass.** The 511 base rows are kept, with only `review-date` changed; 64 rows were added as EX-797 through EX-860; none were removed. Every deadline is 2026-12-01. Ledger `5f67e564…`, metadata `769da5ba…`.
- **Negative controls: pass.** The 12 new cases are each rejected with their expected message, and a no-op mutation fails the harness. Against the previous validator, extended only with the Guava mapping, all 9 rule cases were accepted, so the fixtures detect a regression. The validator and harness pass with macOS awk, and with mawk and gawk on Ubuntu 24.04 (Docker).
- **Four provenance controls: pass.** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls` exited 0, with 4/4 tasks executed.
- **Strict check: pass.** `GRADLE_USER_HOME=<new empty scratch dir> ./gradlew --no-daemon spotlessCheck check --rerun-tasks` exited 0 in 2m 9s, with 122/122 tasks executed. The default-module test XML has 3,448 tests, with 0 failures, errors or skips.
- **Integration tests: pass.** `./gradlew --no-daemon -Pintegration :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun` exited 0. `test` ran 154 and `securityTest` 71, with 0 failures, errors or skips. The XML timestamps are 2026-11-03T03:14Z. Stale September `wavelengthSpike*` XML is present but was not run by this invocation.
- **Trust diffs: pass.**
  - `git diff c288ec6 -- gradle/verification-keyring.keys` is empty.
  - `<configuration>` is byte-identical to base.
  - The metadata diff has no `<trusted-key|trusting|pgp|ignored-key>` lines; it is exactly 25 `<sha256>` lines gaining the residual `reason` attribute.
- **Whitespace: pass.** `git diff --check c288ec6 HEAD -- . ':!gradlew.bat'` is clean.

Deviations from the plan's expectations. None changes the rule, the scope or the acceptance:
1. **Pins.** A pin covers only when its fingerprint is in the committed keyring and is not ignored. Of the 7 `<pgp>` pins, 3 signatures verify with the committed keyring, but only 2 give coverage: `jvnet-parent-1.pom` and `velocity-master-7.pom`. The `all-1.2.0.pom` pin key `4F7E…79C4` is in the keyring but globally ignored, so that pin covers nothing and its pre-existing EX-440 row stays required. The keys for the `gson-2.3.1`, `apache-16` and `commons-parent-39` pins are absent from the keyring, and so is the key for the `plexus-8` pin. These 4 artifacts got rows (EX-810, EX-836, EX-837, EX-844), giving 64 rows rather than about 61. The plexus "conflict" therefore predates the November 1 ignore: that pin never had keyring-backed coverage.
2. **Pin keyring check.** It is implemented as a coverage condition, not a hard metadata failure. A pin whose key is absent or ignored gives no coverage, so its artifact requires a row. This keeps the change within scope: rows only, with no pin removal. The `pin-key-not-in-keyring` fixture is rejected.
3. **Annotations.** The residual annotation covers 25 artifacts, not about 11. The extra 10 are existing rows whose enforcement would otherwise have been lost under the new rule.
4. **Interval.** The approved 2026-12-01 deadline is 28 days from the 2026-11-03 UTC work date, shorter than the proposed 30. It was kept as approved and recorded in the review document.

Limits:
- No publisher identity is authenticated.
- An unannotated artifact under a covering scope but signed by another key is undetectable offline; the per-change signature classification remains the control.
- Gradle's key-server lookup for keys absent from the keyring is not disabled. That is outside this task, and the affected artifacts now have rows.
- 139 three-digit exception IDs remain.
- Default `check` omits live Lightning, advisory freshness and release readiness.

### Attempt 1
Reviewed revision: 26b92d7d5cc8b1e351521131b15a7c7a5fc182ca
Verdict: rework
Findings and dispositions:
1. **Rework: the pin evidence in the review document is inaccurate and incomplete.** [DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md) says "Only 2 of the 7 artifact-level `<pgp>` pins verify with the committed keyring: `jvnet-parent-1.pom` and `velocity-master-7.pom`", then lists 4 pins whose keys are absent. Those 6 pins do not account for the seventh: `com.sun.activation:all:1.2.0` (`all-1.2.0.pom`), pinned to `4F7E32D440EF90A83011A8FC6425559C47CC79C4`. That key is in the committed keyring and the detached signature verifies (isolated GPG home containing only the committed keyring, `--no-auto-key-retrieve`), but the key is globally ignored (`<ignored-key id="6425559C47CC79C4" .../>`), so the pin gives no coverage and the pre-existing EX-440 row is required. Correct the document to state that 3 of 7 signatures verify, that only `jvnet-parent-1.pom` and `velocity-master-7.pom` give coverage, that the `gson-2.3.1`, `apache-16`, `commons-parent-39` and `plexus-8` keys are absent, and that the `all-1.2.0` key is present but ignored. Make the Rule bullet's pin condition explicit: a pin covers only when its fingerprint is in the committed keyring and is not ignored. Add the same clarification to the WORK "Pins" paragraph. No validator, audit, ledger or metadata change is implied: the implemented rule already treats the ignored pin as no coverage, and EX-440 already existed at base.

Verification:
- `bash scripts/validate-dependency-provenance.sh`: pass.
- `bash scripts/test-dependency-provenance.sh`: pass, including the 12 new rule cases and `clean-resolution`.
- `python3 scripts/audit-dependency-provenance-sources.py`: pass (575/575 matches, 82,476,457 bytes, 558 Maven Central / 17 Plugin Portal).
- Audit of the Commit R ledger (`git show df3b296:config/dependency-provenance-exceptions.tsv`): pass (511/511 matches, 72,420,133 bytes, 500/11).
- `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`: pass, 4/4 tasks executed.
- `./gradlew --no-daemon spotlessCheck check --rerun-tasks`: pass, 122/122 tasks executed.
- `./gradlew --no-daemon -Pintegration :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun`: pass, 154 `test` and 71 `securityTest`, 0 failures/errors/skips.
- Trust state: pass. `git diff 651c43e..HEAD -- gradle/verification-keyring.keys` is empty; `<configuration>` is byte-identical; the metadata diff has no `<trusted-key|trusting|pgp|ignored-key>` line and is exactly 25 `<sha256>` lines gaining the residual `reason` (50 diff lines).
- Ledger: pass. 511 rows changed `review-date` only, 64 added as EX-797..EX-860, none removed; all 575 deadlines are 2026-12-01; the additions are contiguous and in coordinate order, 58 Maven Central / 6 Plugin Portal. The base, Commit R and HEAD hashes match the review document.
- `git diff --check 651c43e..HEAD -- . ':!gradlew.bat'`: pass.
- Pin spot check: `all-1.2.0.pom` has a good detached signature by the pinned key `4F7E32D440EF90A83011A8FC6425559C47CC79C4` with the committed keyring, contradicting the document's "2 of 7" count (finding 1).
- Not re-run: the 454-artifact detached-signature classification (network-heavy; WORK evidence was used) and the mawk/gawk portability run (Docker). The optional Gradle cross-check remains skipped as recorded.

Rework after Attempt 1 (implementation at `e446650`; documentation only):
1. **Finding 1, fixed.** [DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md#artifact-pins) now says the following:
   - 3 of the 7 pin signatures verify, and only `jvnet-parent-1.pom` and `velocity-master-7.pom` give coverage.
   - The `all-1.2.0` key is present but globally ignored, so EX-440 stays required.
   - The `gson-2.3.1`, `apache-16`, `commons-parent-39` and `plexus-8` keys are absent.

   The Rule bullet now states the pin condition explicitly: a 40-hex fingerprint in the committed keyring that is not ignored, globally or on the artifact. The WORK "Pins" deviation above carries the same clarification. The SECURITY.md rule sentence now says "an artifact pin to a non-ignored key in the committed keyring". No validator, audit, ledger or metadata change was made.
2. **New observation, documented and not acted on.** Re-verifying all 7 pins showed that the 2 covering pins are not currently valid keys:
   - `jvnet-parent-1` is signed by a key revoked in the committed keyring (`REVKEYSIG`, `2BCBDD0F…0374CF2E8DD1BDFD`);
   - `velocity-master-7` is signed by a key that expired on 2026-06-05 (`EXPKEYSIG`, `6A814B1F…1A2A1C94BDE89688`).

   Gradle accepts both, since `verify-signatures` is true and the strict `check` passes. This is broader than pins: 14 trusted, non-ignored keyring keys are expired (13) or revoked (1), and both keys above are also trusted by scope. Whether such keys should still give coverage is a trust decision. Constraints put that out of scope, and the approved plan rejected rows for valid pins. So the review document records it under the pins and as a new "Key validity" limit, and it is left for Mark to decide in a follow-up.

Rework verification (2026-11-08 UTC):
- Pin re-verification: pass, matching finding 1. All 7 POMs were fetched over HTTPS from Maven Central, and each matches its metadata SHA-256. They were checked with `gpgv` against a dearmored copy of the committed keyring, with no agent and no key retrieval. Results: `all-1.2.0` `GOODSIG` by `4F7E…79C4`; `jvnet-parent-1` `REVKEYSIG`/`VALIDSIG`; `velocity-master-7` `EXPKEYSIG`/`VALIDSIG`; `gson-2.3.1`, `apache-16`, `commons-parent-39` and `plexus-8` `NO_PUBKEY`. A first attempt with `gpg --homedir` under the scratchpad hung on the agent socket ("File name too long") and was killed; its output was discarded.
- `python3 scripts/audit-dependency-provenance-sources.py`: pass, exit 0, at 2026-11-08T01:06Z. 575/575 matches, 82,476,457 bytes, 558 from Maven Central and 17 from the Plugin Portal.
- `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls spotlessCheck`: pass, exit 0. All four controls reported passed.
- `GRADLE_USER_HOME=<new empty scratch dir> ./gradlew --no-daemon spotlessCheck check --rerun-tasks`: pass, exit 0, in 2m 15s, with 122/122 tasks executed. 3,448 default-module tests, 0 failures, errors or skips.
- `./gradlew --no-daemon -Pintegration :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun`, with the same Gradle home: pass, exit 0, 36 tasks with 3 executed. `test` ran 154 and `securityTest` 71, with 0 failures, errors or skips, all in fresh result XML.
- Trust state: pass.
  - `git diff 651c43e HEAD -- gradle/verification-keyring.keys` is empty.
  - `<configuration>` is byte-identical to base.
  - The metadata diff has no `<trusted-key|trusting|pgp|ignored-key>` lines, and its 50 changed lines are unchanged from Attempt 1.
  - The ledger `5f67e564…` and metadata `769da5ba…` are unchanged.
- `git diff --check 651c43e HEAD -- . ':!gradlew.bat'`: pass (clean).
- Not re-run: the 454-artifact classification and the mawk/gawk portability run. The rework touched no script or data file.

## Outcome
Pending.

## Next action
Run the fresh-context keel review, then open a PR before 2026-11-22 UTC. If commit V needs rework past 2026-11-18 UTC, ship commit R (`df3b296`) alone.
