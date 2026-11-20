# Land Gradle dependency and wrapper updates from Dependabot #113
Status: closed
Base revision: 66886db0a98b20f35efb90cc8899e0c2a8ef9a78
Review scope: build.gradle.kts, settings.gradle.kts, gradle/wrapper/gradle-wrapper.jar, gradle/wrapper/gradle-wrapper.properties, gradle/verification-metadata.xml, config/dependency-provenance-exceptions.tsv, docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md

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

**Amendment, 2026-11-18: the change needs 29 replacement rows, not about 10.**

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

Amendment approved: 2026-11-18 by Mark Hammond at plan revision 0338ee1531caf793d4571999fbf03dc15a957bf1 (option A: land all four bumps with the 29 rows, and update only the `3EFC46EE83C40224` ignore reason to name EX-916 and EX-917).

**Correction to the amendment (review Attempt 1):** the calibration bullet's list "EX-479..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865" expands to 32 IDs. It wrongly includes EX-480, EX-481 and EX-483, which remain in the ledger because their Jackson artifacts (`jackson-bom` 2.19.2 and 2.21.7, `jackson-parent` 2.19.3) are still resolved. The 29 retired rows are EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865, as in the Provenance task and the review document. The approved option and the row counts are unchanged.

## Tasks
- [x] **Baseline.** At base, run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration`, and record task and test counts.
  - **pass:** with `--rerun-tasks` (a first run without it was mostly up to date and was discarded), `check` exit 0, 121/121 tasks executed, 3,448 tests with 0 failures, errors or skips. Integration `test` 154 and `securityTest` 71, 36/36 tasks executed, 0 failures.
- [x] **Versions.** Apply #113's four changes and the Tomcat pin comment.
  - **pass:** `build.gradle.kts`, `settings.gradle.kts`, `gradle-wrapper.jar` and `gradle-wrapper.properties` were taken from #113 head `3702e70` (whose base blobs match ours). The one-line Tomcat comment was added at `val tomcatVersion`. The `gradlew` scripts are unchanged.
  - **pass:** the wrapper jar is `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8` and `distributionSha256Sum` is `d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228`. Both equal services.gradle.org's `gradle-9.9.1-wrapper.jar.sha256` and `gradle-9.9.1-bin.zip.sha256`.
- [x] **Compatibility review** of the four release notes, including Gradle 9.9. Check: each relevant item is recorded here with its disposition. `git diff --stat <base> -- 'paygate-*/src/**'` is empty.
  - **Usage checked first:**
    - The repo has no `HttpServletRequest.upgrade()` call, valve, `ProxyErrorReportValve`, `server.compression`, `server.http2`, Tomcat customizer, `org.apache.catalina` or `org.apache.coyote` import, WebSocket or EL use.
    - The example apps set only `server.port`.
    - The build has no test filter (`filter {}`, `--tests` in CI), `Sync` task, custom `Wrapper` task, Problems API use or version catalog. The only `filter` hit is a Kotlin collection call at `build.gradle.kts:371`.
  - **Tomcat 11.0.28 and 11.0.29** ([changelog](https://tomcat.apache.org/tomcat-11.0-doc/changelog.html)): 11.0.28 was never released, and its entries ship in 11.0.29 (changelog dated 2026-11-09). [security-11](https://tomcat.apache.org/security-11.html) lists no CVE for either version as of 2026-11-18. Relevant items:
    - HTTP/2 SETTINGS-ACK flood detection, flow-control fixes (backlog allocation capped at the connection window), and closed-stream and large-header handling: hardening, no configuration here. **No action.**
    - Upgrade must set an `Upgrade` response header, and a request can't start both async processing and an upgrade: no `upgrade()` call here. **Not applicable.**
    - `ProxyErrorReportValve` restrictions: not used. **Not applicable.**
    - `Accept-Encoding: *` enables gzip: compression is not enabled. **Not applicable.**
    - WebSocket client and EL `RecordELResolver` fixes: not used. **Not applicable.**
    - A 405 response now includes `Allow`; `maxLocaleCount` ignores extra locales; JSON nesting is limited to 1000; async-timeout and async-count leak fixes: these are behaviour changes in the embedded server under the example apps and tests. The strict final run, with the integration suites, covers them. **No action unless a test fails.**
  - **CycloneDX 3.6.0 and 3.6.1** ([releases](https://github.com/CycloneDX/cyclonedx-gradle-plugin/releases)):
    - New `failOnUnresolvedDependencies` property, default `false`: **not used**.
    - SPDX licence expressions from POMs are kept: this changes SBOM licence fields only. **Accepted.**
    - The resolved dependency graph is now a task input: up-to-date behaviour only. **Accepted.**
    - `cyclonedx-core-java` moves to 13.4.0, which pulls Jackson 2.22.3 and `json-schema-validator` 2.0.8 onto the plugin classpath. **Reconciled in Provenance.**
    - No task-name, output or schema change is documented. `cyclonedxBom` runs in the final checks.
  - **build-health 3.20.2 and 3.20.3** ([CHANGELOG](https://github.com/autonomousapps/dependency-analysis-gradle-plugin/blob/main/CHANGELOG.md)):
    - fixes for regressions from 3.20.0 and 3.20.1, and KMP and source-attribution fixes;
    - built against Gradle 9.9.1;
    - `graph-support` 0.15.0 comes from the POM only.

    No configuration change. **`buildHealth` runs in the final checks.**
  - **Gradle 9.9.0 and 9.9.1** ([release notes](https://docs.gradle.org/9.9.1/release-notes.html), [upgrading guide](https://docs.gradle.org/9.9.1/userguide/upgrading_version_9.html)):
    - **Security fixes:** GHSA-gqrr-4j89-5267 (High, 8.6) and GHSA-pcp8-98xm-c6pr (High, 7.0), both daemon-registry trust issues, are **adopted**. GHSA-3cfg-jpcr-gfp3 (Critical, 9.3) affects version-catalog accessors; this build has none. **Not applicable.**
    - **Potential breaking changes:**
      - `--tests` filtering now applies to non-method JUnit Platform tests: no filters. **Not applicable.**
      - The `wrapper` task keeps existing properties: the wrapper was not regenerated. **Not applicable.**
      - Problems API ID regrouping, and the CC and Problems report formats: not used. **Not applicable.**
      - Absent providers in required file inputs, eager legacy `ResolvedConfiguration` artifact methods, and directory and settings-loading rules: the generation pass (594 tasks) and the final runs exercise the build. **No failure.**
      - The embedded Kotlin moves to 2.4.21, with incremental Kotlin DSL compilation: this moves plugin-classpath `kotlin-stdlib`, `kotlin-stdlib-common` and `kotlin-reflect` to 2.4.21. **Reconciled in Provenance.**
    - **Deprecations** (Ant integration, `zipTo`/`unzipTo`, `ValueSource` injection, artifact-transform outputs and others): none is reported in the build output. The 13 Kotlin DSL `provideDelegate` and `getting` warnings, which cite the Gradle 9.6 guide, are identical on 9.8.2 and 9.9.1 (`diff` of the `w:` lines). **Pre-existing; no action.**
  - **pass:** `git diff --stat 66886db -- 'paygate-*/src/**'` is empty.
- [x] **Provenance.** Generate, calibrate on the removed set, classify, run the key-validity sweep, then add and remove rows from EX-894 at 2026-12-31 UTC. Checks:
  - `python3 scripts/audit-dependency-provenance-sources.py` matches every row;
  - `./gradlew validateDependencyProvenance verifyDependencyProvenanceNegativeControls` passes;
  - trust-state diffs: a `cmp` of `<configuration>` against base is identical except for the `3EFC46EE83C40224` `<ignored-key>` reason attribute, as approved in amendment option A, and `git diff <base> -- gradle/verification-keyring.keys` is empty;
  - the metadata diff has no `<trusted-key>`, `<trusting>`, `<pgp>` or `<ignored-key>` additions.

  Evidence is in [the second November 18 review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md). Results, as amended:
  - **Result:** 40 artifacts added and 40 removed, 0 checksums changed.
    - 11 new artifacts (Tomcat, Kotlin) are covered with no row.
    - 29 rows were removed (EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601, EX-861..EX-865), and 29 added like for like as EX-894..EX-922 at 2026-12-31.
    - The ledger stays at 594 rows, and 77 IDs remain.
  - **Calibration:** reproduced the committed representation for all 40 removed artifacts.
  - **Key-validity sweep:** the same 18 keys. 74 artifacts in scope: 17 other signer, 32 valid at signing, 25 already with rows. None of the added artifacts falls in any of those scopes.
  - **pass:** `python3 scripts/audit-dependency-provenance-sources.py` at 2026-11-18T23:01Z, exit 0, 594/594 SHA-256 matches, 83,924,010 bytes (577 Maven Central, 17 Gradle Plugin Portal).
  - **pass:** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, exit 0.
  - **pass, as amended:**
    - `git diff 66886db -- gradle/verification-keyring.keys` is empty.
    - The `<configuration>` block is 197 lines at base and after: lines 3–199 inclusive, including the `<configuration>` and `</configuration>` tags. They differ in exactly one line, the `3EFC46EE83C40224` reason (`EX-510 and EX-511` → `EX-916 and EX-917`), as approved in option A. (Corrected in the Attempt 1 rework. The original count of 200 came from a check script that split the file at `<components>`, so it also counted the XML prolog, the root element and the partial `<components>` line.)
    - The metadata diff's only trust-element lines are that `<ignored-key>` line, removed and re-added with the same ID. No `<trusted-key>`, `<trusting>` or `<pgp>` line changed.
  - **pass:** a check script confirmed that every kept artifact and ledger row is byte-identical, and every new entry copies its predecessor's `reason` and rationale. The final artifact set equals the after pass plus the two Linux protoc entries.
- [x] **Documentation.** Write the review document, update SECURITY.md, CHANGELOG, CLAUDE.md and the README. Checks:
  - a relative-link and anchor check over the changed Markdown;
  - `./scripts/validate-agents-md.sh`;
  - no stale `9.8.2` references in CLAUDE.md or the READMEs.

  Results:
  - **Documents changed:**
    - [DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md) is added, and [SECURITY.md](../SECURITY.md#dependency-provenance-and-local-credentials) links it as the current review.
    - The CHANGELOG `[Unreleased]` `Changed` section has a new bullet.
    - CLAUDE.md line 12 now reads Gradle 9.9.1, with a Tomcat pin note beside the gRPC note.
    - The README compatibility table reads Gradle wrapper 9.9.1.
  - **pass:** a relative-link and anchor check, with GitHub slug rules, over the review document, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md and this record: 53 links, 0 broken. As a control, the same checker flagged a deliberately broken `#limits` anchor to the November 18 review.
  - **pass:** `./scripts/validate-agents-md.sh`, exit 0.
  - **pass:** no `9.8.2` remains in CLAUDE.md, README.md or any module or integration README.
- [x] **Final checks.** Each Gradle run uses a new, empty `GRADLE_USER_HOME` with strict verification, copying only the NVD data directory:
  - `./gradlew --no-daemon spotlessCheck check --rerun-tasks`;
  - `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`, with counts compared against the baseline;
  - `./gradlew --no-daemon buildHealth cyclonedxBom dependencyCheckAggregate --rerun`: no unsuppressed findings, only CVE-2025-32013 suppressed, and the SBOM file count recorded;
  - `git diff --check <base>`.

  Results:
  - **Discarded first attempt.** While the first set of final runs was going, I ran `./scripts/validate-agents-md.sh` in the same worktree. Its `--test-dry-run` commands execute `:paygate-core:test` and `:paygate-spring-autoconfigure:test` and rewrite those modules' build outputs.
    - `check` failed with `NoSuchFileException` on `paygate-spring-autoconfigure/build/test-results/test/binary/in-progress-results-generic.bin`.
    - The integration run had 5 `TamperDetectionIT` failures, each a 404 where a 402 or 400 was expected.

    `TamperDetectionIT` had passed 8/8 in the after-state generation pass, which used the same dependencies. I reran all three runs with nothing else touching the worktree, and they passed, so the failures were caused by the concurrent run, not by the update. The rerun results below are the ones recorded.
  - **pass:** `./gradlew --no-daemon spotlessCheck check --rerun-tasks` on Gradle 9.9.1, exit 0. 121/121 tasks executed, and 3,448 tests with 0 failures, errors or skips. The baseline was 121 tasks and 3,448 tests.
  - **pass:** `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`, exit 0, 38/38 tasks executed. `test` 154 and `securityTest` 71, with 0 failures, matching the baseline.
  - **pass:** `./gradlew --no-daemon buildHealth cyclonedxBom dependencyCheckAggregate --rerun`, exit 0. Dependency-Check 13.0.0 reported "Found 0 vulnerabilities". The report (generated 2026-11-18T23:10Z) lists 126 dependencies, 0 active findings, and 1 suppressed finding: CVE-2025-32013 on `paygate-lightning-lnbits-0.1.8-SNAPSHOT.jar`.
  - **pass:** in that run the CycloneDX tasks were up to date from the discarded attempt. So `./gradlew --no-daemon buildHealth cyclonedxBom --rerun-tasks` was run from another new, empty home: exit 0, 435/435 tasks executed, including `buildHealth` and all 16 CycloneDX tasks. It produced 32 SBOM files: 16 `cyclonedxBom` files (8 modules, JSON and XML) and the 16 matching `cyclonedxDirectBom` files.
  - **pass:** `git diff --check 66886db`, exit 0.
  - **Limits:**
    - The review host is macOS arm64, so the Linux protoc executables are first verified by Gradle in CI. They are unchanged.
    - Live LND and LNbits and the Docker stacks were not exercised.
    - Publisher identity is not authenticated.
- [ ] After merge, close #113 with a link to the merged PR. (optional)

## Review
Implementation complete on `chore/land-dependabot-113-2026-11`: `b1776e9` (versions, metadata, ledger) and `4da6d57` (documentation) on base `66886db`. Awaiting fresh-context review.

### Attempt 1
Reviewed revision: a402bb16a79be8dc241d7aa70137b4d6d9a261d8
Verdict: rework
Findings and dispositions:
1. **Rework — the recorded `<configuration>` line count is wrong.** The Task says the block "has 200 lines at base and after". At both `66886db` and `a402bb1` it is 197 lines: lines 3–199 inclusive, including the `<configuration>` and `</configuration>` tags. The substantive result is correct: the blocks differ in exactly one line, the `3EFC46EE83C40224` reason. Correct the count and keep the one-line-diff result.
2. **Rework — the amendment's retired-row list is inconsistent.** The amendment says "29 have exact rows: EX-479..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865". That range expands to 32 IDs and includes EX-480, EX-481 and EX-483, which remain. The retired set is the 29 rows listed in the Provenance Task and the review document: EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601, EX-861..EX-865. Add a labelled correction; the approved amendment text can stay intact.
3. **No implementation findings.** The ledger, metadata, keyring, wrapper, build, tests and advisory scan all pass. Only the recorded evidence needs correction.

Verification:
- Preconditions: **pass** — `Status: review`, full base revision `66886db`, concrete scope, record outside scope; `HEAD` is `a402bb1`; scoped paths clean; only the scoped files plus this record changed since base; `a402bb1` touches only this record.
- Scoped diff (`66886db..a402bb1`): **pass** — exactly `build.gradle.kts`, `settings.gradle.kts`, the wrapper jar and properties, `gradle/verification-metadata.xml`, the ledger, the new review document, `SECURITY.md`, `CHANGELOG.md`, `CLAUDE.md` and `README.md`; `git diff --stat 66886db -- 'paygate-*/src/**'` is empty.
- Hashes and structure: **pass** — metadata `e3eff36c…`, ledger `9a6b2a80…`, keyring `11d778c6…`; base metadata `cd0b29ed…` and ledger `873d19bd…`; 547 components, 964 artifacts, 594 ledger rows, 77 IDs left.
- Metadata delta: **pass** — 40 artifacts added and 40 removed; 0 checksums changed among the 924 common artifacts (corrected in the Attempt 2 rework; it previously read "896 common entries"); 11 added artifacts have checksum-only coverage (6 Tomcat, 5 Kotlin) and 29 have exact rows.
- Ledger delta: **pass** — 29 rows removed and 29 added (EX-894..EX-922); 565 common rows byte-identical; each new row matches a predecessor by group, module and artifact type and preserves its rationale, owner, deadline, trigger and source host; 25 Central and 4 Plugin Portal sources; no duplicate IDs; IDs match `EX-[0-9]{3}`; all new rows use 2026-12-31.
- Trust state: **pass** — keyring diff empty; trust-element counts unchanged; the metadata diff's only `<ignored-key>` change is the `3EFC46EE83C40224` reason; `<configuration>` differs in exactly one line (but is 197 lines, not 200 — finding 1).
- Validator and negative controls (`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`): **pass** — BUILD SUCCESSFUL, both tasks executed.
- Source-byte audit (`python3 scripts/audit-dependency-provenance-sources.py`): **pass** — 594/594 SHA-256 matches, 83,924,010 bytes (577 Maven Central, 17 Plugin Portal), matching the record.
- `./gradlew --no-daemon spotlessCheck check --rerun-tasks`: **pass** — 121/121 tasks executed; 3,448 default-module tests across 508 result files with 0 failures, errors or skips. The raw 3,602 includes 154 integration results.
- `./gradlew --no-daemon -Pintegration :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest --rerun-tasks`: **pass** — 38/38 tasks; `test` 154 and `securityTest` 71, 0 failures, errors or skips.
- `./gradlew --no-daemon buildHealth cyclonedxBom --rerun-tasks`: **pass** — 435/435 tasks; 32 SBOM files (16 `cyclonedxBom` and 16 `cyclonedxDirectBom`).
- `./gradlew --no-daemon dependencyCheckAggregate --rerun`: **pass** — 126 dependencies, 0 active findings, 1 suppressed finding: CVE-2025-32013 on `paygate-lightning-lnbits-0.1.8-SNAPSHOT.jar`.
- Wrapper: **pass** — the committed jar is `621008af…3cdaf8` and `distributionSha256Sum` is `d8a18a7a…ba228`; both equal the files services.gradle.org publishes for 9.9.1.
- Documentation: **pass, apart from findings 1–2** — a link and anchor check over the review document, `SECURITY.md`, `CHANGELOG.md`, `CLAUDE.md`, `README.md` and this record found 54 local links and 0 broken; `./scripts/validate-agents-md.sh` exits 0; no `9.8.2` remains in `CLAUDE.md` or the READMEs.
- External claims: **pass** — Gradle 9.9.1's release notes list GHSA-gqrr-4j89-5267 and GHSA-pcp8-98xm-c6pr; Tomcat 11.0.29 is dated 2026-11-09, 11.0.28 is absent from Maven Central and marked not released, the 11.0.28/29 changelog contains the SETTINGS-acknowledgement, flow-control, upgrade and `ProxyErrorReportValve` changes, and the Tomcat 11 security page lists no CVE for either version.
- `git diff --check 66886db`: **pass** — exit 0.
- Optional post-merge task (close #113): **skipped** — optional and not applicable before merge.
- Material limits: publisher identity is not authenticated; key validity is derived from the committed keyring; live LND/LNbits and the Docker stacks were not exercised; the Linux protoc executables are unchanged and first verified by Gradle in CI. The full check ran from the default Gradle home, not a new empty one, but `--rerun-tasks` executed all 121 tasks under strict verification.

### Attempt 1 rework
Both findings were evidence errors in this record. No file in Review scope changed: `git diff --name-only a402bb1` over the scoped paths is empty, and the implementation stays `b1776e9` and `4da6d57`.

Dispositions:
1. **Fixed.** The Provenance task now says the `<configuration>` block is 197 lines (lines 3–199, including the tags) at base and after, and keeps the one-line-diff result. A recount on `66886db` and `a402bb1` (`sed -n '3,199p'` plus `diff`) confirms 197 lines, with line 10, the `3EFC46EE83C40224` reason, as the only difference. The 200 came from my check script splitting the file at `<components>`; the task now notes this.
2. **Fixed.** A labelled correction now follows the amendment approval, and the approved amendment text is unchanged. It gives the 29 retired rows as EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865, and says EX-480, EX-481 and EX-483 remain (`jackson-bom` 2.19.2 and 2.21.7, `jackson-parent` 2.19.3). A `comm` of base and current ledger IDs gives exactly those 29. The review document never had the wrong range.
3. No implementation change, as Attempt 1 found none.

Verification after rework:
- **pass:** a relative-link and anchor check with GitHub slug rules, over the review document, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md and this record: 54 links, 0 broken.
- **pass:** `./scripts/validate-agents-md.sh`, exit 0. It ran alone, with no other Gradle run in the worktree.
- **pass:** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, exit 0.
- **pass:** `git diff --check 66886db`, exit 0. No `paygate-*/src/**` changes.
- **Not rerun:** the strict builds, integration suites, `buildHealth`, SBOM, advisory scan, source-byte audit and wrapper check. The rework changes only this record, which none of them read, and Attempt 1 recorded all of them as pass at `a402bb1`.

### Attempt 2
Reviewed revision: bd6ef087f9a448f9126214a0106ded3afb9c0611
Verdict: rework
Findings and dispositions:
1. **Rework — the Attempt 1 metadata-delta count is wrong.** Attempt 1 records "0 checksums changed among the 896 common entries". Base and final each carry 964 artifacts; with 40 added and 40 removed, **924 artifacts** are common, and all 924 are representation-identical. The rest of the record already states 964 artifacts and the 40/40 delta correctly, so only "896" needs correcting (prefer "924 common artifacts" to remove the ambiguity in "entries").
2. **Rework — the Provenance check still says the `<configuration>` block is identical to base.** The approved amendment (option A) changes the `3EFC46EE83C40224` ignored-key reason and says "The `cmp` check becomes 'identical except that attribute'." The check bullet still reads "a `cmp` of `<configuration>` against base is identical". Update it to the approved one-line exception; the result bullet already reports the pass "as amended".
3. **No implementation findings.** The two Attempt 1 evidence errors are fixed and verified (197-line configuration block with a one-line diff; the 29-row retired list). Metadata, ledger, keyring, wrapper, documentation and links are otherwise consistent.

Verification:
- Preconditions: **pass** — `Status: review` before this attempt, full base revision `66886db`, concrete scope, record outside scope; reviewed revision `bd6ef08`; worktree clean; `git diff --name-only a402bb1..bd6ef08` is only this record; `git diff --name-only 66886db..bd6ef08` is exactly the scope plus this record; `git diff --stat 66886db -- 'paygate-*/src/**'` is empty.
- Attempt 1 rework findings: **pass** — `<configuration>` is 197 lines (lines 3–199) at base and head and differs only on line 10, the approved `3EFC46EE83C40224` reason; the retired IDs are exactly EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601, EX-861..EX-865 (29), with EX-480, EX-481 and EX-483 still present.
- Metadata delta: **pass** — 547 components and 964 artifacts at base and head; 40 added, 40 removed, 924 common, 0 changed; 11 added artifacts are checksum-only (6 Tomcat, 5 Kotlin) and 29 have exact rows whose sha256 and reasons match their predecessors.
- Ledger delta: **pass** — 29 rows removed and 29 added (EX-894..EX-922); 565 common rows byte-identical; each new row matches a predecessor by group/module/artifact type and keeps its rationale, owner and trigger; all use 2026-12-31; 25 Maven Central and 4 Plugin Portal sources; IDs match `EX-[0-9]{3}` with no duplicates; 594 rows and 77 free IDs remain.
- Trust state: **pass** — keyring SHA-256 `11d778c6…` unchanged; `<trusted-key>` / `<trusting>` / `<pgp>` counts unchanged at 119 / 14 / 7; the only `<ignored-key>` change is the approved reason; no trust was added.
- Wrapper: **pass** — the committed jar `621008af…3cdaf8` and `distributionSha256Sum` `d8a18a7a…ba228` equal the files services.gradle.org publishes for 9.9.1 (fetched this attempt).
- Documentation: **pass** — SECURITY.md links the `-2` review as current; the CHANGELOG, CLAUDE.md and README changes match the landed versions; a relative-link and anchor check over the review document, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md and this record found 0 broken local links (55 local links; the sole `mailto:` was excluded as a non-file link).
- Carried from Attempt 1 at `a402bb1`, with no scoped file changed since: validator and negative controls, source-byte audit, strict `spotlessCheck check` (121/121 tasks, 3,448 tests), integration `test`/`securityTest` (154 and 71), `buildHealth`/`cyclonedxBom` (435/435 tasks, 32 SBOM files), `dependencyCheckAggregate` (0 active, 1 suppressed CVE-2025-32013), and `git diff --check` — all **pass** in Attempt 1's verification.
- Optional post-merge task (close #113): **skipped** — optional and not applicable before merge.
- Material limits: publisher identity is not authenticated; key validity is derived from the committed keyring; live LND/LNbits and the Docker stacks were not exercised; the Linux protoc executables are unchanged and first verified by Gradle in CI. This attempt did not rerun the Gradle suites because the only change since Attempt 1 is this record.

### Attempt 2 rework
Both findings were evidence wording in this record. No file in Review scope changed: `git diff --name-only bd6ef08` over the scoped paths is empty, and the implementation stays `b1776e9` and `4da6d57`.

Dispositions:
1. **Fixed.** Attempt 1's metadata-delta line now reads "0 checksums changed among the 924 common artifacts", with a note that it previously said "896 common entries". A recount comparing base `66886db` and head `bd6ef08` metadata gives 964 artifacts on each side, 40 added, 40 removed and 0 checksums changed, so 964 − 40 = 924 common artifacts.
2. **Fixed.** The Provenance task's trust-state check now reads: identical "except for the `3EFC46EE83C40224` `<ignored-key>` reason attribute, as approved in amendment option A". That matches the amendment and the result bullet. A search of this record and the review document for other claims that `<configuration>` is identical found only:
   - the scratch-tree generation method, where the two trees' blocks are identical to each other;
   - the description of options B and C.

   Both are correct as written.
3. No implementation change, as Attempt 2 found none.

Verification after rework:
- **pass:** a relative-link and anchor check with GitHub slug rules, over the review document, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md and this record: 54 relative links, 0 broken. The `mailto:` link is excluded as a non-file link.
- **pass:** `./scripts/validate-agents-md.sh`, exit 0, run with no other Gradle run in the worktree.
- **pass:** `./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`, exit 0.
- **pass:** `git diff --check 66886db`, exit 0. `git diff --stat 66886db -- 'paygate-*/src/**'` is empty.
- **Not rerun:** the strict builds, integration suites, `buildHealth`, SBOM, advisory scan, source-byte audit and wrapper check. The rework changes only this record, which none of them read. Attempt 1 recorded them as pass at `a402bb1`, and Attempt 2 confirmed no scoped file changed since.

### Attempt 3
Reviewed revision: 24ba4e237939cb7638de8741ce41e5104181887e
Verdict: pass
Findings and dispositions: No findings; no rework required. Both Attempt 2 findings are fixed: the metadata-delta line now reads 924 common artifacts (964 − 40) and notes the prior wording, and the Provenance check now states the approved one-line `<configuration>` exception. Both match the metadata and the approved amendment.

Verification:
- Preconditions: **pass** — `Status: review`, full base revision `66886db`, concrete scope, record outside scope; reviewed revision `24ba4e2`; worktree clean; `git diff --name-only bd6ef08..24ba4e2` is only this record; `git diff --name-only 66886db..24ba4e2` is exactly the scope plus this record.
- Scoped implementation: **pass** — no scoped path changed since `bd6ef08`; `git diff --stat 66886db -- 'paygate-*/src/**'` is empty; metadata `e3eff36c…`, ledger `9a6b2a80…`, keyring `11d778c6…`, wrapper jar `621008af…` and distribution `d8a18a7a…` all match the record; no trust was added.
- Rework findings: **pass** — the metadata-delta line is 924 common artifacts (964 base and final, 40 added, 40 removed, 0 changed), which reproduces from the metadata; the trust-state check reads "identical except for the `3EFC46EE83C40224` `<ignored-key>` reason attribute, as approved in amendment option A", matching the one-line diff and the result.
- Validator and negative controls (`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`): **pass** — BUILD SUCCESSFUL at `24ba4e2`, both tasks executed.
- `git diff --check 66886db`: **pass** — clean.
- Documentation: **pass** — a relative-link and anchor check over the review document, SECURITY.md, CHANGELOG.md, CLAUDE.md, README.md and this record found 0 broken local links; SECURITY.md links the `-2` review as current.
- Carried from Attempt 1 and Attempt 2 at `a402bb1` and `bd6ef08`, with no scoped file changed since: source-byte audit (594/594), strict `spotlessCheck check` (121/121 tasks, 3,448 tests), integration `test`/`securityTest` (154 and 71), `buildHealth`/`cyclonedxBom` (435/435 tasks, 32 SBOM files), and `dependencyCheckAggregate` (0 active, 1 suppressed CVE-2025-32013) — all **pass**.
- Optional post-merge task (close #113): **skipped** — optional and not applicable before merge.
- Material limits: publisher identity is not authenticated; key validity is derived from the committed keyring; live LND/LNbits and the Docker stacks were not exercised; the Linux protoc executables are unchanged and first verified by Gradle in CI. This attempt reran the validator and negative controls but not the full suites, because no scoped file changed since Attempt 1.

## Outcome
Closed after review Attempt 3 passed at `24ba4e2`. The implementation is `b1776e9` (versions, metadata, ledger) and `4da6d57` (documentation) on base `66886db`, on branch `chore/land-dependabot-113-2026-11`. Later commits change only this record.

What changed:
- **Versions.** All four updates from Dependabot #113 landed: Tomcat 11.0.29 (the explicit pin is kept, with a comment), CycloneDX plugin 3.6.1, build-health 3.20.3 and Gradle wrapper 9.9.1. The wrapper jar and properties were taken from #113 after their checksums matched services.gradle.org. No source or build-script adaptation was needed.
- **Provenance.** No trust was added: the keyring is byte-identical.
  - The verification metadata has 40 artifacts added and 40 removed, with 0 checksums changed.
  - 11 new artifacts (Tomcat, Kotlin) are covered by keys already trusted for their coordinates and signed while valid.
  - The other 29 replace 29 retired rows like for like, as EX-894..EX-922 at the 2026-12-31 UTC deadline. The ledger stays at 594 rows, and 77 three-digit IDs remain.
  - The key-validity sweep found no new artifact in the scope of the 18 revoked or expired keys.
- **Approved amendment (option A).** The plan estimated about 10 rows. 29 were needed, because CycloneDX 3.6.1 pulls Jackson 2.22.3 and `json-schema-validator` 2.0.8 onto the plugin classpath, and Gradle 9.9.1 moves plugin-classpath Kotlin to 2.4.21. The owner approved all four updates. The only `<configuration>` change is the `3EFC46EE83C40224` ignore reason, which now names EX-916 and EX-917.

Verification at Attempt 3, all pass. Attempt 3 reran the validator, negative controls, documentation checks and `git diff --check`. The rest carried from Attempt 1, and no scoped file changed since.
- **Strict empty-home builds on Gradle 9.9.1:** `spotlessCheck check`, 121/121 tasks and 3,448 tests. Integration `test` 154 and `securityTest` 71, matching the baseline.
- **Provenance controls:** the validator, its negative controls, and the source-byte audit at 594/594.
- **Build tasks:** `buildHealth` and `cyclonedxBom` (435/435 tasks, 32 SBOM files). `dependencyCheckAggregate` has 0 active findings, and only the approved CVE-2025-32013 is suppressed.
- **Wrapper and docs:** wrapper checksums, the documentation link check, `validate-agents-md.sh`, and `git diff --check`.

Review corrections:
- **Attempt 1:** the `<configuration>` block is 197 lines, not 200. The amendment's retired-row range wrongly included EX-480, EX-481 and EX-483.
- **Attempt 2:** the metadata delta has 924 common artifacts, not 896, and the trust-state check now states the approved reason-only exception.

All of these were record-only fixes.

During implementation, a first set of final runs failed because `validate-agents-md.sh` ran concurrently in the same worktree. The isolated reruns are the recorded results.

Limits and remaining uncertainty:
- Publisher identity is not authenticated.
- Key validity comes from the committed keyring, which may carry stale expiry dates.
- The Linux protoc executables are unchanged and first verified by Gradle in CI.
- Live LND and LNbits and the Docker stacks were not exercised.

Documentation:
- [DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md);
- [SECURITY.md](../SECURITY.md#dependency-provenance-and-local-credentials), which links it as the current review;
- the [CHANGELOG](../CHANGELOG.md) `Changed` entry;
- the Gradle and Tomcat notes in [CLAUDE.md](../CLAUDE.md) and the [README](../README.md#compatibility).

## Next action
Open a pull request from `chore/land-dependabot-113-2026-11` to `main` that links [the second November 18 provenance review](../docs/security/DEPENDENCY-PROVENANCE-REVIEW-2026-11-18-2.md), merge it before 2026-12-31 UTC, and close #113 as superseded.
