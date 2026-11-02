# Tighten the dependency-provenance validator and renew the exception ledger
Status: planned
Base revision: pending approval
Review scope: scripts/validate-dependency-provenance.sh, scripts/test-dependency-provenance.sh, scripts/test-fixtures/dependency-provenance/cases.tsv, scripts/audit-dependency-provenance-sources.py, config/dependency-provenance-exceptions.tsv, gradle/verification-metadata.xml (`<sha256 reason>` attributes only), docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md (with a `-2` suffix if that date's review already exists), SECURITY.md

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

## Tasks
- [ ] At approval, confirm that `main` is `c288ec6` and that the metadata, keyring and ledger hashes match those in Constraints. Run `./gradlew --no-daemon validateDependencyProvenance` before any edit.
- [ ] **Commit R.**
  1. Run `python3 scripts/audit-dependency-provenance-sources.py`. It must report 511/511 matches (500 Maven Central, 11 Plugin Portal). Record `blocked` if the network is unavailable and `incomplete` if the run fails partway.
  2. Set `review-date` to the approved deadline on all 511 rows. A field-level diff against base must show 511 `review-date` changes and nothing else.
  3. Write the review document's renewal section and the SECURITY.md current-review link, then commit.
- [ ] **Classification.** Re-run the static classification. Then run the detached-signature classification, using HTTPS `.asc` files, an isolated GPG home holding only the committed keyring, and `--no-auto-key-retrieve`, over every checksum-carrying artifact without a row. It must confirm:
  - the residual class;
  - the 5 valid pins;
  - the protoc executables' coverage.

  Record the count for each category.
- [ ] (optional) Gradle cross-check. From an empty home, run `--write-verification-metadata sha256,pgp` on a scratch copy with `<configuration>` kept and `<components>` emptied, then discard the output. The goal is to observe Gradle's checksum-only set and whether a global ignore overrides the `plexus-8` pin.
- [ ] **Commit V.**
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
- [ ] **Checks after V,** with each command, exit code, task count, test totals and omissions recorded. The baseline is 3,448 default-module tests, 154 integration `test` and 71 `securityTest`.
  - `python3 scripts/audit-dependency-provenance-sources.py`: every row matches.
  - `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`
  - `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon spotlessCheck check --rerun-tasks` (strict verification)
  - `./gradlew -Pintegration :paygate-integration-tests:test :paygate-integration-tests:securityTest`
  - `git diff c288ec6 -- gradle/verification-keyring.keys` must be empty. In `gradle/verification-metadata.xml`, the `<configuration>` block must be byte-identical to base, and the diff must contain no `<trusted-key|trusting|pgp|ignored-key>` lines, only changes to `<sha256>` `reason` attributes.
  - `git diff --check c288ec6 HEAD -- . ':!gradlew.bat'` must be clean. `gradlew.bat` is CRLF by design.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Mark approves or revises this plan, including the 2026-12-01 UTC deadline.
