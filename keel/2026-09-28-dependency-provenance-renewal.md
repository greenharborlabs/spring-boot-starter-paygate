# Renew dependency provenance exceptions
Status: planned
Base revision: pending approval
Review scope: config/dependency-provenance-exceptions.tsv, SECURITY.md

## Goal
Restore the required Paygate root `./gradlew check` without weakening provenance controls. Independently re-review, replace with verified publisher trust, or remove each current exception; renew only entries supported by exact artifact/source evidence. Acceptance requires current exception coverage, provenance negative controls, a clean-home dependency resolution, and the root check, with actual results recorded here. The [health-refresh pilot](2026-09-28-health-refresh-contention.md) remains a separate working record until its own fresh review passes.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md), the [security policy](../SECURITY.md), and the [release checklist](../docs/RELEASE-CHECKLIST.md). The [validator](../scripts/validate-dependency-provenance.sh) rejects expired, duplicate, broad, orphaned, or checksum-mismatched exceptions; its [negative controls](../scripts/test-dependency-provenance.sh) must remain effective. A passing validator alone does not establish independent publisher or byte provenance.
- At clean HEAD `16453aad8c2695f7cf9bfac8d0cc6ccab7b2aefe` on `feature/wavelength-poc`, the [ledger](../config/dependency-provenance-exceptions.tsv) has 470 unique 11-field rows, all dated `2026-09-22` and now expired. All 470 coordinates and SHA-256 values appear in [verification metadata](../gradle/verification-metadata.xml), which has 1,680 artifact entries and 367 textual key-download-failure annotations. Classify the exceptions not explained by those annotations before renewal; matching two committed files does not independently verify artifact bytes. Direct validator execution currently exits 1, `exception review is expired`; the pilot's `./gradlew check` failed at the same gate before later tasks ran.
- The column named `review-date` is enforced as a future/current review deadline (`date -u +%F`), while [SECURITY.md](../SECURITY.md) calls it a review date. The renewal interval and evidence standard are not documented. Resolve and document those semantics before changing dates; do not infer a cadence from the old value.
- Keep changes to the ledger and the focused policy clarification in `SECURITY.md`. Publisher-key recovery or changed checksums, metadata, keyring, build logic, or dependencies require a new scope decision. Preserve the clean pilot test commit and unrelated work. This plan authorizes no release, integration environment, or credential use.

## Decisions
- Review entries by publisher/artifact family for efficiency, but disposition every exact row against its authoritative source, artifact bytes, metadata checksum, signing-key availability, rationale, owner, and next review deadline. Record actual review evidence and unresolved rows concisely in this record. Do not mass-advance dates or disable `validateDependencyProvenance`; those would only hide the failed trust gate.
- Use the existing [release checklist](../docs/RELEASE-CHECKLIST.md) and [CI gate](../.github/workflows/ci.yml). From the repository root, required checks after approved edits are `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`, then `GRADLE_USER_HOME=<new empty temporary directory> ./gradlew check --no-daemon` to re-resolve dependencies and run the root aggregate, plus `git diff --check`. The clean home needs network access and the supported Java/Gradle toolchain. Record task/test coverage and any short-circuit or unavailable network accurately. Default `check` excludes opt-in `paygate-integration-tests`; `releaseReadiness -Pintegration` is a separate release gate, not acceptance for this maintenance task.

## Tasks
- [ ] Confirm the approved base, Java/Gradle/GPG availability, and the 470-row inventory. Classify each row's trust reason, source, and metadata relationship; flag unresolved ownership, source, or deadline policy before edits.
- [ ] Independently inspect current artifact bytes and publisher evidence for every retained exception; document actual review date and justified next deadline in this record. Remove obsolete rows or renew only reviewed exact rows, and clarify the deadline semantics in `SECURITY.md`. Stop for scope approval if keyring, metadata, or another path must change.
- [ ] Run the required provenance, negative-control, clean-home, root aggregate, and whitespace checks from the repository root. Record command, result/exit, actual coverage, tested revision, omissions, and limits; do not call the root gate passed while any required task fails or remains unrun.
- [ ] Commit only scoped changes, obtain fresh independent review of this record and a valid close, then hand the unblocked root-check evidence to the separate health-refresh pilot. Do not close the pilot in this task.

## Review
Awaiting implementation and fresh-context review. Planning inventory and the direct validator failure above are baseline observations, not renewed provenance evidence.

## Outcome
Pending.

## Next action
Approve or revise this plan.
