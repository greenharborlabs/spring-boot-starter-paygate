# Dependency provenance exception review — 2026-11-01

This review reconciles `gradle/verification-metadata.xml` and `config/dependency-provenance-exceptions.tsv` with the dependency upgrade in [the Gradle minor and patch upgrade plan](../../keel/2026-11-01-upgrade-gradle-minor-and-patch-dependencies.md): Spring Boot 4.1.2, gRPC 1.84.1, Protobuf 4.36.2, Gradle 9.8.2, the build plugins, and MockWebServer 5.3.2 (`mockwebserver3`). The work was performed on 2026-11-01 UTC on top of upgrade commit `9ae29155afb32f47ba133865fba56b22645c1f3f`, which carries Dependabot PR #92 (rebased head `bdcf093`, cherry-picked as `aa7d850`) and the MockWebServer migration. No trust was added: `<trusted-keys>` and `gradle/verification-keyring.keys` are byte-identical to that commit. Every new artifact is either signature-covered by a key already trusted for its coordinates or carries an exact exception row with the shared **2026-11-22 UTC** deadline.

## Evidence

### Starting state

- The starting ledger at `9ae2915` had 464 unique rows, all with the 2026-11-22 deadline. Its SHA-256 was `450ba0228865d2145d306b36b2fdb579850e2a358b09119a65d7d62e71612bd8`, identical to the ledger renewed by the [October 15 review](DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md).
- The starting metadata (SHA-256 `50b1b924698c1e0c041c3d82bb0aff6169de2351843e345ee1707463f2c134fa`) had 751 components and 1,500 artifact checksums, 118 `<trusted-key>` entries, 40 global `<ignored-key>` entries, and 7 artifact-level `<pgp>` pins. The keyring SHA-256 is `11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460` before and after.

### Current resolution set

- The scratch copy of the tree kept `<configuration>` byte-identical and emptied `<components>`. Two generation passes ran from new, empty Gradle user homes with `-Pintegration` and the task set CI resolves: `build buildHealth cyclonedxBom aggregateJavadoc spotlessCheck pmdMain :paygate-core:jmhJar :paygate-integration-tests:test :paygate-integration-tests:securityTest dependencyCheckAggregate`. The CI workflows' other Gradle invocations are covered as follows:
  - CodeQL's `classes` is a subset of `build`.
  - The control tasks run shell scripts only.
  - `releaseReadiness` depends on these tasks plus script controls.
  - The credentialed publishing tasks (`publishAllPublicationsToReleaseStagingRepository`, `publishToSonatype`) were not run because they require signing material.
  - `--write-verification-metadata sha256,pgp`: exit 0, 595 tasks. In this mode Gradle omits checksums for artifacts it can cover with a key, so it was used only for Gradle's own signature observations. It proposed 11 new or widened `<trusted-key>` scopes and 7 new artifact-level `<pgp>` pins; all were discarded.
  - `--write-verification-metadata sha256`: exit 0, 595 tasks. This enumerated the complete current set: 546 components and 963 artifacts. No artifact appeared only in the pgp pass, and no checksum differed between passes.
- Protoc and `protoc-gen-grpc-java` are platform classifiers. CI runs on Linux, so `protoc-4.36.2-linux-x86_64.exe` (SHA-256 `f6ee22d6…98f864`) and `protoc-gen-grpc-java-1.84.1-linux-x86_64.exe` (`1aae9127…a9a3fe`) were added as `Verified against Maven Central`, as for the earlier versions. Each was fetched over HTTPS and matched Central's published SHA-1. The gRPC binary also matched Central's published SHA-256; Central publishes none for protoc. Both detached signatures verify with keys already trusted for `com.google.protobuf` and `io.grpc`. No other platform-specific artifact is resolved.
- Final metadata: 546 components and 965 artifacts (SHA-256 `4983724fd46885f612ec2594710f9047bba13f90447a3cfaa295108d57328eec`). 495 artifacts kept with their committed lines unchanged, 470 added, 1,005 removed, 0 checksums changed. Kept components keep their order, and no kept entry was re-rendered.

### Signature classification of the 470 new artifacts

Each artifact and its `.asc` were fetched over HTTPS from Maven Central, or from the Gradle Plugin Portal where Gradle resolved them, and matched to the generated SHA-256. Each signature was verified in an isolated GPG home holding only the committed keyring, with `--no-auto-key-retrieve`. The keyring imported partially: 187 processed, 116 imported, 52 without user IDs, matching the September 30 audit. An artifact counts as covered only when its signer is trusted for its exact coordinates and is not in `<ignored-keys>`. Gradle skips ignored keys and falls back to the checksum.

To calibrate the method, the same procedure on the 464 starting rows reproduced 318 `NO_PUBKEY` and 142 `VALIDSIG`. The remaining 4 were transient `.asc` fetch errors (EX-459 and EX-461 through EX-463, all removed below); with them, the total matches the earlier 146 valid / 318 `NO_PUBKEY`. Every signer of the 460 classified rows was a globally ignored key.

| Outcome | Artifacts | Groups |
| --- | --- | --- |
| Covered by an existing trusted key (checksum entry only) | 138 | `com.google.code.gson`, `com.google.guava`, `com.google.protobuf`, `io.grpc`, `io.netty`, `net.bytebuddy`, `org.apache.httpcomponents` (and `.client5`, `.core5`), `org.apache.logging.log4j`, `org.apache.tomcat.embed`, `org.codehaus.mojo`, `org.codehaus.plexus`, `org.eclipse.ee4j`, `org.infinispan`, `org.jacoco`, `org.jetbrains.kotlin`, `org.jetbrains.kotlinx`, `org.mockito`, `org.openjdk.jmh`, `org.ow2.asm`, `org.xmlunit` |
| Exception: signing key not in the keyring (`NO_PUBKEY`) | 221 | Spring (`org.springframework`, `.boot` 103, `.security` 17, `.amqp`, `.batch`, `.data`, `.integration`, `.pulsar`, `.restdocs`, `.session`, `.ws`), `io.micrometer`, `io.projectreactor`, `io.opentelemetry`, `io.prometheus`, `org.slf4j`, `com.autonomousapps`, `com.github.ben-manes.caffeine`, `com.google.errorprone`, `commons-codec`, `org.apache`, `org.apache.cassandra`, `org.apache.commons`, `org.apache.maven`, `org.codehaus.mojo`, `org.cyclonedx`, `org.eclipse.jetty` (and `.ee11`), `org.seleniumhq.selenium` |
| Exception: valid signature, key not trusted for these coordinates | 59 | `com.squareup.okhttp3` (MockWebServer 5.3.2 and OkHttp 5.3.2), `org.apache.maven`, `org.apache.maven.resolver`, `com.google.api.grpc`, `com.networknt`, `org.apache.activemq`, `org.apache.artemis`, `org.apache.pulsar`, `org.glassfish.jersey`, `org.jooq`, `org.mongodb`, `org.yaml` |
| Exception: valid signature, key trusted for the group but globally ignored | 43 | `com.fasterxml.*`, `tools.jackson.*`, `com.squareup.okio`, `org.eclipse.sisu`, `org.junit` |
| Exception: publisher does not sign the artifact | 9 | Plugin Portal plugin markers and jars: `com.autonomousapps.build-health`, `com.google.protobuf` gradle plugin, `me.champeau.jmh`, `org.cyclonedx`, `org.cyclonedx.bom`, `org.springframework.boot` gradle plugin |

Of the 138 covered artifacts, 137 are signature-verified by Gradle. The exception is `guava-33.6.0-jre.jar`, whose component is `guava:33.6.0-android`. Gradle follows Guava's module-metadata variant redirect and cannot locate the detached signature, so it records the jar as `Artifact is not signed` and verifies it by checksum only. This is the same representation as the committed `guava-33.5.0-jre.jar`. The signature, fetched from the `33.6.0-jre` path, verifies with a key trusted for `com.google.guava`. The ledger cannot represent this artifact: the validator and the audit require the source URL to use the component version, and that URL returns 404. So it has a checksum entry and no row, as before.

### Metadata representation of exceptions

Exception artifacts follow the existing pattern:
- **`NO_PUBKEY` artifacts:** checksum with `reason="A key couldn't be downloaded"`.
- **Valid-signature exceptions:** an unannotated checksum, the representation of the 100 unannotated valid-signature exceptions already in the file.
- **Unsigned artifacts:** `reason="Artifact is not signed"`.

Every exception signer is ignored, so Gradle falls back to the exact checksum.

`<ignored-keys>` changes (no key was trusted or un-ignored):
- **Added (12):** `012579464D01C06A`, `18D239B1CBCD2236`, `3EFC46EE83C40224`, `502001558D63CB9A`, `54A43F3254868410`, `66F8E4860BF74983`, `76E0008D166740A8`, `B6E83A2D2E447E56`, `BFF2EE42C8282E76`, `D3FA67D522C55256`, `E16AB52D79FD224F` and `F461A32102C936BB`. Each reason names its exact exception IDs. No still-resolved, signature-covered artifact is signed by any of these keys.
- **Scoped to two artifacts:** `F5C81DE10A0B8ECC` signs `snakeyaml` 2.6 (EX-787, EX-788). It is ignored only inside those two `<artifact>` elements, because the same key covers the still-resolved `snakeyaml` 2.5 through its existing version-scoped trusted key.
- **Updated:** the reason on `793FD5751A0F0780` now names EX-382, EX-385, EX-386 and EX-512 through EX-526. EX-383 and EX-384 were removed, and the new OkHttp and Okio rows were added.
- **Removed (1):** `2C7B12F2A511E325`. No remaining row or resolved artifact is signed by it.
- **Total:** 51 global entries and 2 artifact-scoped entries.

### Ledger changes

- **Removed (285 rows):** the coordinates are no longer resolved by any CI task. By prior signature result: 248 `NO_PUBKEY`, 33 `VALIDSIG`, and 4 with transient fetch errors in calibration (the `org.eclipse.sisu` 0.9.0.M4 rows). They span 39 groups, chiefly superseded Spring Boot 4.0.x, Spring Framework, Spring Security, Micrometer, Maven/Resolver, Jackson, JUnit and build-health plugin versions. EX-001 (`logback-classic` 1.5.34) is among them, and so is EX-464 (`jul-to-slf4j` 1.7.36 pom).
- **Unchanged (179 rows):** byte-identical, including the 2026-11-22 deadline. By signature result: 109 `VALIDSIG` and 70 `NO_PUBKEY`. Their artifacts are still resolved with unchanged checksums.
- **Added (332 rows):** EX-465 through EX-796, appended in coordinate order after the existing rows. IDs are unique, not reused, and need not be contiguous under the validator. Every new row has the deadline `2026-11-22`, the existing owner value `Green Harbor Labs maintainer`, the existing review trigger, and one of three rationales:
  - 221 `NO_PUBKEY` rows use the existing EX-464 rationale.
  - 102 valid-signature rows use the existing rationale "Signature verifies with a committed key, but publisher identity is unconfirmed…".
  - 9 unsigned rows use "Publisher does not sign this artifact; exact Gradle Plugin Portal SHA-256 required because no publisher signature can authenticate it."
- **Sources:** 323 new rows from Maven Central and 9 from the Gradle Plugin Portal. For two plugin markers (`build-health` 3.20.0 and Spring Boot 4.1.2), Maven Central serves different bytes under the same coordinate; their rows name the Plugin Portal bytes Gradle resolved.
- **Result:** 511 rows. The header lines are unchanged. SHA-256 `f1f52ab501433d9017529e001cd4700e02fcec6f23df41d6a5c4084b58776858`.

### Source-byte audit

`python3 scripts/audit-dependency-provenance-sources.py` fetched all 511 ledger URLs over HTTPS on 2026-11-01 UTC and hashed them with SHA-256. It exited 0 with 511/511 matches, 72,420,133 bytes in total: 500 from Maven Central and 11 from the Gradle Plugin Portal.

### Other committed changes

`scripts/test-dependency-provenance.sh`: the `source-coordinate-change` negative control edited ledger line 3 using EX-001's URL. With EX-001 removed, line 3 is EX-020 (`caffeine-3.2.4.module`). The control now redirects that row to the sibling `caffeine-3.2.4.pom` URL, which is the same mutation on the current row. A dry run confirmed that the mutation changes line 3, and the control still rejects it.

### Hand-verified entries removed

Of the 77 `Verified against Maven Central` entries, 76 are no longer resolved by any CI task, and neither is the single `Verified against Maven Central SHA-1` entry. These 77 removed entries comprise:
- 69 dependency `-sources.jar` files;
- the Linux protoc 4.29.3 and `protoc-gen-grpc-java` 1.80.0 executables;
- the `hamcrest` 1.3 and `kotlin-stdlib` 1.8.21/1.9.10 POMs;
- the `jul-to-slf4j` 1.7.36 POM (EX-464).

Their history (commits `57ec248`, `9a7cadc`) shows local IDE or audit use. One such entry, `apiguardian-api-1.1.2.pom` (CycloneDX), is still resolved and is kept. IDE source downloads for current versions now need their own reviewed entries.

## Limits

- **Publisher identity:** this review authenticates bytes and links them to sources and metadata. It does not authenticate publisher identity: no key was recovered, imported or trusted, and no fingerprint was confirmed out of band. A valid signature from a keyring key is not publisher approval, and neither is Gradle's generated trust.
- **Linux protoc executables:** Gradle has not verified the two Linux protoc executables locally. The review host is macOS arm64, so CI on Linux is their first Gradle verification. Their checksums and signatures were verified as described above.
- **Gradle's ignore semantics:** the classification of 43 artifacts as "trusted but ignored" relies on Gradle skipping ignored keys. Either way the build passes on their exact checksums.
- **Validator coverage:** `scripts/validate-dependency-provenance.sh` enforces a ledger row only for checksums annotated `A key couldn't be downloaded`. The 102 new valid-signature exceptions and 9 unsigned exceptions have rows, but the validator does not require them.
- **Pre-existing gap, unchanged by this review:** 46 committed, still-resolved artifacts verify by checksum alone but have no ledger row. They are 15 `NO_PUBKEY`, 27 signed by globally ignored keys and 4 signed by untrusted keys:
  - `logback` 1.5.38;
  - `google-java-format` 1.24.0 and 1.35.0;
  - `error_prone` 2.21.1;
  - `opentelemetry-bom` 1.49.0;
  - `jackson-annotations` 2.21 and its parents;
  - `okhttp` 4.12.0 and `okio` 3.6.0;
  - `guava-parent` 26.0-android and `failureaccess` 1.0.1;
  - `checker-qual` 3.37.0 and 3.54.0;
  - `apiguardian-api` 1.1.2 POM;
  - 13 `junit-bom` 5.x files.

  So do 13 legacy unsigned artifacts. Six more verify only through artifact-level `<pgp>` pins. Rows for these were not added, because this task's scope is new artifacts. They need a separate decision.

## Checks after reconciliation

Results:
- **`python3 scripts/audit-dependency-provenance-sources.py`:** exit 0, 511/511 matches.
- **`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls verifyRepositorySecretIgnoreControls verifyDependencyAdvisoryWorkflowControls`:** exit 0, all 4 tasks executed and passed.
- **`GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon check --rerun-tasks`:** exit 0 in 2m 18s with strict verification, 122/122 tasks executed. Default-module test XML has 3,447 tests, with 0 failures, errors or skips.
- **`GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon -Pintegration :paygate-integration-tests:test :paygate-integration-tests:securityTest`:** exit 0 with strict verification, both test tasks executed. `test` ran 154 tests and `securityTest` 71, with 0 failures, errors or skips.
- **`GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon -Pintegration buildHealth cyclonedxBom aggregateJavadoc spotlessCheck pmdMain :paygate-core:jmhJar dependencyCheckAggregate`:** exit 0 with strict verification, 527 tasks (449 executed). Dependency-Check reported 0 vulnerabilities, without OSS Index credentials.
- **`git diff 9ae2915 -- gradle/verification-keyring.keys`:** empty.
- **`git diff 9ae2915 -- gradle/verification-metadata.xml | grep '^+.*<trusted-key'`:** empty. There are also no `<trusting>` or `<pgp>` additions.
- **`git diff --check`:** exit 0.

The environment was Java 25.0.1 (Temurin), Gradle 9.8.2 and GnuPG 2.5.20 on macOS arm64. This evidence awaits fresh-context review before merge, which must happen before 2026-11-22 UTC.
