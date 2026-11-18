# Land Gradle dependency and wrapper updates from Dependabot #113
Status: working
Base revision: 66886db0a98b20f35efb90cc8899e0c2a8ef9a78
Review scope: build.gradle.kts, settings.gradle.kts, gradle/wrapper/gradle-wrapper.jar, gradle/wrapper/gradle-wrapper.properties, gradle/verification-metadata.xml, config/dependency-provenance-exceptions.tsv, docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md

## Goal
Land Dependabot #113 (head `3702e70`) on our own branch from `main` at `5d9eebe`, with the provenance controls intact. Then close #113 as superseded.

| Dependency | From | To | In scope |
| --- | --- | --- | --- |
| Tomcat embed `core`, `el`, `websocket` (explicit pin) | 11.0.27 | 11.0.29 | yes (see Decisions) |
| CycloneDX Gradle plugin | 3.5.1 | 3.6.1 | yes |
| build-health plugin | 3.20.1 | 3.20.3 | yes |
| Gradle wrapper (jar and distribution) | 9.8.2 | 9.9.1 | yes |

Acceptance evidence, mirroring the [November 15 task](2026-11-15-land-gradle-dependency-updates-105-104.md#tasks):
- **Builds and tests:**
  - strict empty-home `spotlessCheck check` passes;
  - the integration `test` and `securityTest` suites pass with the baseline's test counts (154 and 71);
  - `buildHealth` and `cyclonedxBom` pass;
  - `dependencyCheckAggregate --rerun` reports no unsuppressed findings, and only CVE-2025-32013 is suppressed.
- **Provenance controls:** the validator, its negative controls and the source-byte audit all pass. Trust-state diffs show no added trust.
- **Wrapper:** the wrapper jar and distribution checksums match services.gradle.org.
- **Review document:** a new provenance review document records every added and removed row with its source-byte evidence, and SECURITY.md links it as the current review.
- **Whitespace:** `git diff --check` is clean.

## Constraints
- Follow [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) and the [dependency provenance policy](../SECURITY.md#dependency-provenance-and-local-credentials):
  - never accept generated metadata, a key-server identity, or a signature from an unauthenticated key as publisher approval;
  - re-resolve from an isolated Gradle home after any dependency, checksum, key or exception change.
- **Why #113 fails:** every failing check is dependency verification. The first missing entry is `com.autonomousapps.build-health.gradle.plugin-3.20.3.pom` (runs `44319258142` and `44319258114`). Dependabot can't update `verification-metadata.xml`.
- **No new trust.** Discard every generated `<trusted-key>`, `<trusting>` and `<pgp>` entry. Each new artifact is either covered by a key already trusted for its exact coordinates or gets an exact row.
- **Key validity** ([November 18 rule](2026-11-18-renew-dependency-provenance-ledger-2026-12.md#outcome)): a trusted key covers only signatures made before its recorded expiry (or its signing subkey's) or revocation. When finding keys:
  - match each `<trusted-key>` and `<pgp>` reference against both primary and subkey fingerprints;
  - apply every scope attribute (`group`, `name`, `version`, `file`) together with `regex`.

  Both mistakes were caught in review last time.
- **Rows and IDs:**
  - new rows start at EX-894; 106 IDs remain, and IDs must match `EX-[0-9]{3}`;
  - new rows share the ledger deadline of **2026-12-31 UTC**, the same day as the CVE-2025-32013 suppression, which is scoped to `0.1.8-SNAPSHOT`.
- **Planning pre-check** (2026-11-18). Each artifact was fetched over HTTPS and its `.asc` verified with `gpgv` against a dearmored copy of the committed keyring (`11d778c6…`) only. This sets expectations; the full pass decides.
  - **Tomcat 11.0.29:** all six jars and POMs give GOODSIG from `A9C5DF4D…2F6059E7`, the key trusted for `org.apache.tomcat.embed` and the same one that signed 11.0.27. They were signed around 2026-11-09, and the keyring records no expiry or revocation for the key. Expected result: covered, no rows.
  - **build-health 3.20.3:**
    - `dependency-analysis-gradle-plugin` 3.20.3 and its new transitive `graph-support` 0.15.0 (from 0.13.0) give NO_PUBKEY `95962C5E716C39AA`, the same globally ignored key as their predecessors.
    - Plugin Portal and Maven Central serve identical bytes.
    - The marker POM is unsigned.
    - Expected: 5 rows replace EX-861..EX-865.
  - **CycloneDX 3.6.1:**
    - The plugin jar, module and marker are unsigned and published only on the Portal, like 3.5.1.
    - Its transitive `cyclonedx-core-java` moves from 13.2.0 to 13.4.0 and gives NO_PUBKEY `6FD54E1F311B47E9`, the same ignored key as 13.2.0, with identical bytes on Central and the Portal.
    - It adds `packageurl-java` 1.5.0, which is already committed (EX-022, EX-023).
    - Expected: 5 rows replace EX-597..EX-601.
  - **Expected totals:** about 10 rows replaced (EX-894..EX-903), with the ledger staying at 594 rows and 96 IDs left.
- **Wrapper pre-check:** both values match services.gradle.org's published `.sha256` files:
  - #113's `distributionSha256Sum` is `d8a18a7a…ba228`;
  - its `gradle-wrapper.jar` is `621008af…3cdaf8`.
- **Gradle 9.9 release notes** ([9.9.1](https://docs.gradle.org/9.9.1/release-notes.html)):
  - **Security fixes in 9.9.1:**
    - two daemon-registry advisories: GHSA-gqrr-4j89-5267 (High) and GHSA-pcp8-98xm-c6pr (High);
    - a version-catalog advisory, GHSA-3cfg-jpcr-gfp3 (Critical). It doesn't apply, because this build has no version catalog.
  - **Behaviour changes:** `--tests` filtering, the Problems API, the `wrapper` task's property handling, Sync, and Kotlin DSL compilation. A grep shows the build uses no test filters, Sync tasks, custom `Wrapper` task or Problems API.
- **Existing Dependabot ignores stay:** `io.grpc:*`, `com.squareup.okhttp3:*`, `com.google.protobuf` `0.10.1`, and `com.diffplug.spotless` `>= 8.10.3`.
- **Out of scope:** `paygate-*/src/**`. A needed source change means a revised plan.

## Decisions
- **Land on our own branch, and close #113 after merge**, following the [#92 and #105 precedent](2026-11-15-land-gradle-dependency-updates-105-104.md#decisions). Take Dependabot's wrapper jar and properties as-is after verifying them. Rejected: regenerating the wrapper with `./gradlew wrapper`. 9.9 changed that task's property handling, and nothing here needs new scripts.
- **Tomcat keeps its explicit pin and moves to 11.0.29** (recommended, for approval).
  - **Why it differs from gRPC:**
    - The [gRPC amendment](2026-11-01-upgrade-gradle-minor-and-patch-dependencies.md#decisions) removed a pin because it produced a mixed-version classpath.
    - Tomcat's `dependencySet` moves all three artifacts together, so it can't split.
    - The pin exists to adopt Tomcat security fixes ahead of Boot. It was introduced in `b5b2152` to retire CVE-2026-66299 exceptions.
    - `dependencyCheckAggregate` scans the example apps' runtime classpath, which includes Tomcat, with `failBuildOnCVSS = 0`, so a Tomcat CVE blocks the gate until a fixed version lands.
  - **Boot today:**
    - Boot 4.1.2 is still the latest 4.x release, and it manages Tomcat 11.0.27, so the current pin is a no-op.
    - Tomcat reaches only test classpaths and the two example apps, not the runtime of the published library modules.
  - **Security content of 11.0.29** (released 2026-11-09):
    - No CVE has been published for 11.0.28 or 11.0.29 yet. Tomcat publishes CVEs after the release; 11.0.27's appeared eight days after it shipped.
    - 11.0.28 was never released, and by Tomcat's convention its changes ship in 11.0.29. They include hardening:
      - detection of HTTP/2 SETTINGS-ACK floods;
      - HTTP/2 flow-control fixes;
      - stricter upgrade handling;
      - `ProxyErrorReportValve` restrictions.
    - The task confirms the roll-up from the changelog and records it.
  - **Small additions:**
    - a one-line comment at `val tomcatVersion` saying that the pin may lead Boot's managed version for security fixes, and should be dropped once Boot manages that version or a later one;
    - the same note in CLAUDE.md, beside the existing "gRPC follows Boot" note.
  - Rejected: **follow Boot and remove the pin.** It's simpler, but it forgoes 11.0.29's hardening and removes the lever the advisory gate depends on, for no gain while Boot 4.1.2 is current.
- **Provenance method**, from the [November 15 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md):
  - **Generate:** make two scratch copies of the tree, at base and after, each with a byte-identical `<configuration>` block and an emptied `<components>` block. Run each with `--write-verification-metadata sha256`, `-Pintegration` and CI's task set (`build buildHealth cyclonedxBom aggregateJavadoc spotlessCheck pmdMain :paygate-core:jmhJar :paygate-integration-tests:test :paygate-integration-tests:securityTest dependencyCheckAggregate`), from a new, empty `GRADLE_USER_HOME`. Base runs on its own 9.8.2 wrapper and after on 9.9.1. Copy only the NVD data directory into each home.
  - **Diff:** added = after − base; removed = base − after.
  - **Calibrate first:** classify the removed set and confirm the procedure reproduces the committed representation for every removed artifact.
  - **Classify each added artifact:** fetch its detached signature over HTTPS, then verify with `gpgv --keyring` against a dearmored copy of the committed keyring. Never import into a GPG home; `gpg --homedir` imports hang on the agent socket.
    - **Covered:** a key trusted for the artifact's exact coordinates signed it while valid. It keeps a checksum entry only.
    - **Otherwise:** it gets an exact row whose HTTPS source bytes are verified by `scripts/audit-dependency-provenance-sources.py`.
  - **Key-validity sweep:** re-run the scope selection for the 18 revoked or expired trusted keys on the after metadata, and confirm no added artifact falls in their scopes uncovered.
  - **Remove** rows and metadata entries for artifacts that nothing resolves any more.
  - Script files under `$CLAUDE_JOB_DIR/tmp/scripts` hold any loop that mentions git or calls Gradle, because the sandbox rejects such inline loops.
- **Hold rather than add rows, and stop and ask, if the full pass finds any of these:**
  - Plugin Portal bytes that differ from Central's, or a BADSIG, as happened with the Protobuf plugin 0.10.1;
  - a signing key that differs from the predecessor's, as with Spotless 8.10.3+ and OkHttp 5.5.1;
  - a POM SCM or URL change suggesting an ownership transfer;
  - an added artifact not attributable to these four bumps;
  - a need for more than 20 new IDs.
- **Compatibility review, not refactoring.** Read the release notes for CycloneDX 3.6.x, build-health 3.20.2–3.20.3 and Tomcat 11.0.28–11.0.29, and record each relevant item and its disposition. For Tomcat, check the example apps and tests for direct `upgrade()` calls, `server.compression`, custom valves and HTTP/2 use. Fix only what fails.
- **Documentation:**
  - a new `docs/security/DEPENDENCY-PROVENANCE-REVIEW-<work date>.md` in the November 15 form, suffixed `-2` if that name exists (2026-11-18 already does). It records:
    - the PR head;
    - the rows added, removed and unchanged, with their source bytes;
    - the calibration and audit results;
    - the key-validity sweep;
    - that no trusted key was added;
  - SECURITY.md links it as the current review;
  - a CHANGELOG `[Unreleased]` entry;
  - Gradle version references: [CLAUDE.md](../CLAUDE.md) line 12 and the [README](../README.md) compatibility table. Historical records stay as they are.

Approved: 2026-11-18 by Mark Hammond at plan revision 66886db0a98b20f35efb90cc8899e0c2a8ef9a78. This confirms that Tomcat keeps its explicit pin, moved to 11.0.29.

**Proposed amendment, 2026-11-18: the change needs 29 replacement rows, not about 10.**

These are findings from the full generation pass, which hits the plan's "more than 20 new IDs" stop condition.
- **Generation:** base (9.8.2 wrapper) and after (9.9.1 wrapper) each ran from a new, empty Gradle home with `-Pintegration` and CI's task set. Each exited 0 with 594 tasks executed and resolved 962 artifacts. 40 were added and 40 removed, and no checksum changed. Every base artifact is in the committed metadata.
- **24 added artifacts the pre-check did not predict.** Each is attributable to one of the four bumps (from `buildEnvironment` on both trees, and the cached module metadata):
  - **Jackson 2.22.2 → 2.22.3 (17 artifacts) and `json-schema-validator` 2.0.4 → 2.0.8 (2).** These are on the plugin classpath through `cyclonedx-core-java` 13.4.0, CycloneDX 3.6.1's transitive.
  - **Kotlin `kotlin-stdlib`, `kotlin-stdlib-common` and `kotlin-reflect` 2.4.10 → 2.4.21 (5).** Gradle constrains plugin classpaths to its embedded Kotlin (`kotlin-stdlib:{strictly 2.4.21}`), which the 9.9 notes move to 2.4.21. Build-health 3.20.1 already asked for `kotlin-bom` 2.4.21 but resolved 2.4.10 on 9.8.2.
- **Calibration:** the classification reproduced the committed representation for all 40 removed artifacts. 11 are covered with no row, and 29 have exact rows: EX-479..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865.
- **Classification of the 40 added artifacts** (`gpgv` against a dearmored copy of the committed keyring only). Each has the same signer and representation as its predecessor:
  - **Covered, no row (11):** Tomcat 11.0.29 (`A9C5DF4D…2F6059E7`, signed 2026-11-09, no expiry or revocation), and Kotlin 2.4.21 (the trusted subkey `6F538074…6A0975F8B1127B83`, signed 2026-10-06, valid until 2027-04-11).
  - **Rows (29):**
    - 6 give `NO_PUBKEY` from globally ignored keys: build-health and `graph-support` (`95962C5E716C39AA`), `cyclonedx-core-java` (`6FD54E1F311B47E9`);
    - 19 give a valid signature from a globally ignored key: Jackson (`28118C07…3D12CA2AC19F3181`) and `json-schema-validator` (`AEB1E1AE…3EFC46EE83C40224`);
    - 4 Plugin Portal artifacts are unsigned.
- **Other stop conditions: none hit.**
  - No BADSIG and no signing-key change.
  - Portal and Central bytes are identical wherever both exist, and the CycloneDX plugin is Portal-only, as 3.5.1 was.
  - POM `url`, `scm` and `organization` are unchanged apart from each release's version tag.
- **Effect:** 29 rows (EX-894..EX-922) replace 29 retired rows, like for like. The ledger stays at 594 rows, and 77 IDs remain.
- **One `<configuration>` text reference:** the global `<ignored-key id="3EFC46EE83C40224">` has `reason="Exact checksum exceptions EX-510 and EX-511 required"`. Retiring the 2.0.4 rows makes that text name rows that no longer exist. The new 2.0.8 rows would be EX-916 and EX-917.

Options:
- **A (recommended):** land all four bumps with the 29 rows, and update only that reason to name EX-916 and EX-917. That is a single reason-attribute change, with no trust change. The `cmp` check becomes "identical except that attribute".
- **B:** the same 29 rows, leaving `<configuration>` byte-identical with the stale reason text.
- **C:** hold CycloneDX at 3.5.1 and land Tomcat, build-health and Gradle 9.9.1. That needs 5 rows (EX-894..EX-898), leaves `<configuration>` identical, and needs a Dependabot ignore for `org.cyclonedx.bom` 3.6.x. CycloneDX 3.6.x brings no fix Paygate needs (SPDX licence expressions and a task-input fix).

## Tasks
- [ ] **Baseline.** At base, run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`, and record task and test counts.
- [ ] **Versions.** Apply #113's four changes and the Tomcat pin comment.
  - Check the wrapper inputs: the jar SHA-256 and `distributionSha256Sum` match `services.gradle.org` `gradle-9.9.1-wrapper.jar.sha256` and `gradle-9.9.1-bin.zip.sha256`.
- [ ] **Compatibility review** of the four release notes, including Gradle 9.9. Check: each relevant item is recorded here with its disposition. `git diff --stat <base> -- 'paygate-*/src/**'` is empty.
- [ ] **Provenance.** Generate, calibrate on the removed set, classify, run the key-validity sweep, then add and remove rows from EX-894 at 2026-12-31 UTC. Checks:
  - `python3 scripts/audit-dependency-provenance-sources.py` matches every row;
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls` passes;
  - trust-state diffs: a `cmp` of `<configuration>` against base is identical, and `git diff <base> -- gradle/verification-keyring.keys` is empty;
  - the metadata diff has no `<trusted-key>`, `<trusting>`, `<pgp>` or `<ignored-key>` additions.
- [ ] **Documentation.** Write the review document, update SECURITY.md, CHANGELOG, CLAUDE.md and the README. Checks:
  - a relative-link and anchor check over the changed Markdown;
  - `./scripts/validate-agents-md.sh`;
  - no stale `9.8.2` references in CLAUDE.md or the READMEs.
- [ ] **Final checks.** Each Gradle run uses a new, empty `GRADLE_USER_HOME` with strict verification, copying only the NVD data directory:
  - `./gradlew --no-daemon spotlessCheck check --rerun-tasks`;
  - `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`, with counts compared against the baseline;
  - `./gradlew --no-daemon buildHealth cyclonedxBom dependencyCheckAggregate --rerun`: no unsuppressed findings, only CVE-2025-32013 suppressed, and the SBOM file count recorded;
  - `git diff --check <base>`.
- [ ] After merge, close #113 with a link to the merged PR. (optional)

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan. In particular, confirm that Tomcat keeps its explicit pin at 11.0.29.
