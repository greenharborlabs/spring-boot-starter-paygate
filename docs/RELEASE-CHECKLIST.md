# Release Checklist

Step-by-step process for publishing a new Paygate release to Maven Central.

Use the Codex `paygate-release` skill when working with Codex. The skill can
prepare branches, verify builds, tag releases, and watch workflows, but the
release flow intentionally keeps GitHub PR merge approval as a manual stop point.

## Prerequisites

- [ ] You have write access to the GitHub repository.
- [ ] GitHub Actions CI is green on `main`.
- [ ] The previous public release is visible in Maven Central metadata.
- [ ] These GitHub Actions secrets are configured:
  - `SONATYPE_USERNAME` -- Sonatype OSSRH username
  - `SONATYPE_PASSWORD` -- Sonatype OSSRH password
  - `GPG_SIGNING_KEY` -- ASCII-armored GPG private key
  - `GPG_SIGNING_PASSWORD` -- passphrase for the GPG key
- [ ] `main` currently has a snapshot version in `gradle.properties`, such as
  `version=X.Y.Z-SNAPSHOT`.

## Phase 1: Prepare And Merge The Release PR

### 1. Pick the release version

Check Maven Central metadata for at least the core and starter artifacts:

```bash
curl -fsSL https://repo.maven.apache.org/maven2/com/greenharborlabs/paygate-core/maven-metadata.xml
curl -fsSL https://repo.maven.apache.org/maven2/com/greenharborlabs/paygate-spring-boot-starter/maven-metadata.xml
```

If the latest release is `X.Y.(Z-1)`, the next patch release is usually `X.Y.Z`.

### 2. Sync `main` and create a release branch

```bash
git checkout main
git pull --ff-only origin main
git checkout -b release/X.Y.Z
```

### 3. Set the release version

Edit `gradle.properties`:

```properties
version=X.Y.Z
```

The release branch must not use a `-SNAPSHOT` version.

### 4. Update `CHANGELOG.md`

- [ ] Add `## [X.Y.Z] - YYYY-MM-DD` immediately below `[Unreleased]`.
- [ ] Move or summarize relevant changes since the previous tag.
- [ ] Keep security entries explicit, but never include secret values.
- [ ] Add a compare link at the bottom:

```markdown
[X.Y.Z]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/vX.Y.(Z-1)...vX.Y.Z
```

### 5. Run local verification and security release gates

```bash
./gradlew build
```

- [ ] Build passes.
- [ ] Spotless runs before Java compilation as part of the Gradle build.

For the defense-in-depth release, run the gates in this order so failures remain attributable. Record the command, commit, environment, and redacted result for each; checking this list is not evidence that an unexecuted command passed.

1. Prove the build/release safeguards reject their isolated negative fixtures:

```bash
./gradlew verifySupplyChainNegativeControls
```

- [ ] Modified checksums, unsafe workflow/documentation inputs, and release-hygiene violations fail safely without executing fixture payloads.

2. Enforce every configured per-module JaCoCo threshold:

```bash
./gradlew verifyModuleCoverage
```

- [ ] `paygate-core` meets at least 80%; each other non-example/non-integration module meets its configured minimum of at least 60%.

3. Run both opt-in cross-module suites:

```bash
./gradlew :paygate-integration-tests:test -Pintegration
./gradlew :paygate-integration-tests:securityTest -Pintegration
```

- [ ] Servlet and Spring Security enforcement, invalid-credential resource bounds, backend fail-closed behavior, interoperability, and redaction checks pass.

4. Verify that every supported audit finding has complete evidence:

```bash
./gradlew validateFindingDispositions
```

- [ ] Every supported finding row names verified implementation, regression, documentation, and command evidence. This gate intentionally fails while supported ledger rows remain `planned` or otherwise incomplete; update evidence only from executed checks.

5. Run the composed final local gate last:

```bash
./gradlew releaseReadiness -Pintegration
```

- [ ] The final gate passes with `-Pintegration`; it composes module builds, dependency health, aggregate Javadoc, integration/security suites, negative controls, disposition validation, and module coverage.
- [ ] No local command is described as provenance, attestation, protected-environment approval, or proof of the bytes later published by GitHub Actions.

Optional consumer-resolution check after the required gates:

```bash
./gradlew publishToMavenLocal
```

### 6. Commit and push the release branch

```bash
git add gradle.properties CHANGELOG.md
git commit -m "chore: prepare X.Y.Z release"
git push -u origin release/X.Y.Z
```

### 7. Open and merge the release PR

Open a PR from `release/X.Y.Z` into `main`.

Include in the PR description:

- Release version.
- Changelog highlights.
- Local verification result from `./gradlew build`.
- Any security-sensitive notes, redacted.

Manual stop point:

- [ ] Wait for GitHub CI.
- [ ] Review the PR.
- [ ] Merge the PR into `main` through GitHub.

Do not create or push the release tag until the release PR has merged.

## Phase 2: Tag, Publish, And Start The Next Snapshot

### 8. Sync merged `main`

```bash
git checkout main
git pull --ff-only origin main
```

### 9. Verify the release commit

```bash
grep '^version=' gradle.properties
git log --oneline --decorate -5
```

- [ ] `gradle.properties` is exactly `version=X.Y.Z`.
- [ ] The current commit is on `main`.

### 10. Create and push the annotated release tag

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
git push origin vX.Y.Z
```

The release workflow requires the tag to point to a commit already on `main`.

### 11. Watch the GitHub release workflow

```bash
gh run list --limit 8
gh run watch <release-run-id> --exit-status
```

The release workflow should:

- Verify the tag is on `main`.
- Verify the release version matches the tag.
- Build every module in the default build. The opt-in integration module is covered by CI and by the required local `releaseReadiness -Pintegration` gate.
- Verify publishing secrets.
- Publish to Sonatype and release to Maven Central.
- Generate and attach SBOMs.
- Produce workflow provenance/attestation only where the protected release environment and workflow support it; local verification is not a substitute.
- Create the GitHub Release.

### 12. Verify release output

```bash
gh release view vX.Y.Z --json url,tagName,isDraft,isPrerelease,publishedAt
```

Check Maven Central metadata after indexing completes:

```bash
curl -fsSL https://repo.maven.apache.org/maven2/com/greenharborlabs/paygate-core/maven-metadata.xml
curl -fsSL https://repo.maven.apache.org/maven2/com/greenharborlabs/paygate-spring-boot-starter/maven-metadata.xml
```

Maven Central indexing can take up to 30 minutes.

### 13. Start the next snapshot PR

```bash
git checkout -b chore/start-X.Y.N-snapshot
```

Edit `gradle.properties`:

```properties
version=X.Y.N-SNAPSHOT
```

Then commit and push:

```bash
git add gradle.properties
git commit -m "chore: start X.Y.N snapshot development"
git push -u origin chore/start-X.Y.N-snapshot
```

Manual stop point:

- [ ] Open a PR from `chore/start-X.Y.N-snapshot` into `main`.
- [ ] Wait for CI.
- [ ] Merge the PR through GitHub.

## Failure Handling

- If local build fails, stop and fix before pushing a release branch.
- If a negative control, module coverage, integration/security suite, or disposition validation fails, stop before `releaseReadiness`; preserve the redacted failure evidence and fix forward. Do not mark a finding verified from a planned or skipped command.
- If the hardened release rejects legacy/noncanonical L402 or MPP credentials in canary traffic, keep the release fail closed and migrate clients to newly issued exact-request credentials. Rolling back may re-enable the rejected legacy surface; require an explicit security decision and preserve current root keys/binding secrets until the migration window is resolved.
- If GitHub CI fails on the release PR, fix the PR branch and wait for CI again.
- If the release workflow fails before publishing, fix forward and only retag if
  the tag has not been published publicly.
- If Sonatype publishing fails after staging, inspect workflow logs and Sonatype
  state before retrying.
- If GitHub Release creation fails after Maven publish succeeds, create or repair
  the GitHub Release for the same tag. Do not republish Maven artifacts.
- If Maven Central already has the version, artifacts are immutable. Publish a
  patch release instead.
