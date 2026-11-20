# Dependency provenance exception review — 2026-11-18 (2)

This review reconciles `gradle/verification-metadata.xml` and `config/dependency-provenance-exceptions.tsv` with [the Dependabot #113 landing plan](../../keel/2026-11-18-land-gradle-dependency-updates-113.md) and its approved amendment.

**Updates landed** (from Dependabot PR #113, head `3702e70d77f7383307b217c412212e36a61b0b0e`):
- Tomcat embed `core`, `el` and `websocket` 11.0.29, keeping the explicit pin
- CycloneDX Gradle plugin 3.6.1
- build-health 3.20.3
- Gradle wrapper 9.9.1 (jar and distribution)

The work was performed on 2026-11-18 UTC on base `66886db0a98b20f35efb90cc8899e0c2a8ef9a78`, which carries `main` at `5d9eebe`.

**No trust was added.** `gradle/verification-keyring.keys` is byte-identical to the base. The `<configuration>` block differs in one attribute: the reason text of the existing global `<ignored-key id="3EFC46EE83C40224">` now names the rows that replace the retired ones (see [Configuration](#configuration)). No `<trusted-key>`, `<trusting>`, `<pgp>` or `<ignored-key>` was added, removed or re-scoped. Every new artifact is either signature-covered by a key already trusted for its coordinates, or carries an exact exception row with the existing **2026-12-31 UTC** deadline.

## Evidence

### Starting and final state

| | Base (`66886db`) | Final |
| --- | --- | --- |
| Metadata SHA-256 | `cd0b29ed6a720e6028c5d42799d4870ed596c63ad75b59fe946cd84945d30b9a` | `e3eff36c8b11522eab22d02ef4854decee050ebbf5c580ae7dfdcd8db783eced` |
| Components / artifacts | 547 / 964 | 547 / 964 |
| Ledger SHA-256 | `873d19bd266c6914361c2ffb1fac15ccac1440fcf6e2414f1d39d15cbe951286` | `9a6b2a802d123ccadc853a38d3cb7e88d2bb461496a23ed9384673595c8f0887` |
| Ledger rows | 594 | 594 |
| Keyring SHA-256 | `11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460` | unchanged |

### Wrapper

Both #113 values match the files services.gradle.org publishes:

| Input | SHA-256 | Published file |
| --- | --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8` | `gradle-9.9.1-wrapper.jar.sha256` |
| `distributionSha256Sum` | `d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228` | `gradle-9.9.1-bin.zip.sha256` |

The wrapper jar and properties were taken from #113 as they are; the wrapper was not regenerated. The `gradlew` scripts are unchanged.

### Resolution sets

Two scratch copies of the tree, one at base and one with #113's four files, kept `<configuration>` byte-identical and emptied `<components>`. Each ran `--write-verification-metadata sha256` from a new, empty Gradle user home, with `-Pintegration` and the task set of the [November 15 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md#resolution-sets). The base ran on its own 9.8.2 wrapper, and the after tree on 9.9.1. Only the existing Dependency-Check NVD database was copied into each home. It is scan data, not a Gradle-verified artifact.

- **Base:** exit 0, 594 tasks executed, 962 artifacts. All of them are in the committed metadata. The other 2 committed entries are the hand-added Linux protoc executables, which are unchanged.
- **After:** exit 0, 594 tasks executed, 962 artifacts.
- **Delta:** 40 added, 40 removed, **0 checksums changed**. Each added module version replaces one removed version of the same module.
- The final metadata's artifact set equals the after pass plus the two Linux protoc entries. Kept entries were not re-rendered.

**Attribution.** The planning pre-check predicted 16 of the added artifacts. The other 24 are transitive and were traced with `buildEnvironment` on both trees and the cached module metadata:

| Added | Artifacts | Source |
| --- | --- | --- |
| Tomcat 11.0.29 | 6 | the explicit pin |
| `dependency-analysis-gradle-plugin` 3.20.3, `graph-support` 0.15.0, build-health marker | 5 | build-health 3.20.3 |
| `cyclonedx-gradle-plugin` 3.6.1, marker, `cyclonedx-core-java` 13.4.0 | 5 | CycloneDX 3.6.1 |
| Jackson 2.22.2 → 2.22.3 (`jackson-base`, `jackson-bom`, `core`, `databind`, `dataformat-xml`, `dataformat-yaml`, `dataformats-text`, `datatype-jsr310`, `module-blackbird`, `modules-base`, `modules-java8`) | 17 | `cyclonedx-core-java` 13.4.0 on the plugin classpath |
| `json-schema-validator` 2.0.4 → 2.0.8 | 2 | `cyclonedx-core-java` 13.4.0 on the plugin classpath |
| `kotlin-stdlib`, `kotlin-stdlib-common`, `kotlin-reflect` 2.4.10 → 2.4.21 | 5 | Gradle 9.9.1, which constrains plugin classpaths to its embedded Kotlin (`kotlin-stdlib:{strictly 2.4.21}`, 2.4.10 on 9.8.2) |

Build-health 3.20.1 already declared `kotlin-bom` 2.4.21, but resolved 2.4.10 on Gradle 9.8.2, so the Kotlin move comes from the wrapper.

### Signature classification

Each artifact and its `.asc` were fetched over HTTPS, from Maven Central or from the Gradle Plugin Portal where Gradle resolved them, and matched to the generated SHA-256. Each signature was verified with `gpgv --keyring` against a dearmored copy of the committed keyring only. An artifact counts as covered only when its signer is a non-ignored key trusted for its exact coordinates, every scope attribute applies, and the signature was made before the end of the signing key's validity: the earliest of primary expiry, signing-subkey expiry and revocation, from the committed keyring.

**Calibration.** I ran the same procedure on the 40 removed artifacts first. It reproduced the committed representation for all 40: 11 covered artifacts with no row, and 29 artifacts with exact rows (EX-479, EX-482, EX-484..EX-498, EX-510, EX-511, EX-597..EX-601 and EX-861..EX-865).

**The 40 added artifacts.** Each has the same signer and the same representation as its predecessor.

| Outcome | Artifacts | Detail |
| --- | --- | --- |
| Covered by an existing trusted key (checksum entry only) | 11 | Tomcat 11.0.29 jars and POMs (`A9C5DF4D22E99998D9875A5110C01C5A2F6059E7`, trusted for `org.apache.tomcat.embed`; signed 2026-11-09; no expiry or revocation recorded). Kotlin 2.4.21 (`6F538074CCEBF35F28AF9B066A0975F8B1127B83`, a subkey of `2FBA29D0…0729A0AFF8999A87`, trusted for `org.jetbrains.kotlin`; signed 2026-10-06; valid until 2027-04-11) |
| Exception: valid signature from a globally ignored key | 19 | Jackson 2.22.3 (`28118C07…3D12CA2AC19F3181`, signed 2026-09-21) and `json-schema-validator` 2.0.8 (`AEB1E1AE…3EFC46EE83C40224`, signed 2026-10-02; its trusted scope is version 1.5.9 only) |
| Exception: signing key not in the keyring (`NO_PUBKEY`), signer globally ignored | 6 | `dependency-analysis-gradle-plugin` 3.20.3 and `graph-support` 0.15.0 (`95962C5E716C39AA`); `cyclonedx-core-java` 13.4.0 (`6FD54E1F311B47E9`) |
| Exception: publisher does not sign the artifact | 4 | Plugin Portal: build-health 3.20.3 marker; CycloneDX plugin 3.6.1 jar, module and marker |

Each new metadata entry copies the `reason` of the entry it replaces, or its absence:
- `reason="A key couldn't be downloaded"` for build-health and `cyclonedx-core-java`;
- `reason="Artifact is not signed"` for the four Plugin Portal artifacts;
- no reason for Jackson and `json-schema-validator`, as for 2.22.2 and 2.0.4, because their signers are globally ignored.

**Other stop conditions from the plan: none found.**
- No `BADSIG`, and no signing-key change from any predecessor.
- **Repository bytes:** Plugin Portal and Maven Central serve identical bytes for `dependency-analysis-gradle-plugin` 3.20.3 (jar and module), `graph-support` 0.15.0 and `cyclonedx-core-java` 13.4.0. The CycloneDX plugin 3.6.1 is published only on the Portal, as 3.5.1 was.
- **Stewardship:** POM `url`, `scm`, `organization` and `parent` are unchanged from each predecessor apart from the release's own version tag and parent version. This covers Tomcat, build-health, `graph-support`, CycloneDX, Jackson, `json-schema-validator` and Kotlin.

### Key-validity sweep

The [November 18 rule](DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity) was re-run on the final metadata and ledger.
- **Inventory:** every `<trusted-key>` and `<pgp>` reference was matched against primary and subkey fingerprints. The result is the same 18 revoked or expired trusted, non-globally-ignored keys, 4 of them referenced by a subkey.
- **Scopes:** every scope attribute (`group`, `name`, `version`, `file`, exact or regex) was applied.
- **Result:** 74 committed artifacts are in scope, as on November 18:
  - 17 are signed by a different trusted key;
  - 32 were signed while their key was valid;
  - 25 already carry exact rows: the 6 set aside on November 18, including EX-543 and EX-544, and the 19 key-validity rows EX-875..EX-893.
- **None of the 40 added artifacts falls in any of those 18 keys' scopes.**

### Ledger changes

- **Removed (29 rows):** EX-479, EX-482, EX-484 through EX-498, EX-510, EX-511, EX-597 through EX-601, and EX-861 through EX-865. Nothing resolves these artifacts any more. EX-480, EX-481 and EX-483 stay, because their Jackson artifacts are still resolved.
- **Added (29 rows):** EX-894 through EX-922, appended in coordinate order.
  - Each replaces a removed row for the same group and module, and keeps its rationale, owner `Green Harbor Labs maintainer` and review trigger.
  - Each has the deadline `2026-12-31`.
  - 25 rows have Maven Central sources and 4 have Gradle Plugin Portal sources. Each URL is the source of the bytes Gradle resolved.
  - A field comparison with the retired rows found no other difference.
- **Unchanged:** 565 rows, byte-identical.
- **Result:** 594 rows. 77 three-digit IDs remain (EX-923..EX-999).

### Configuration

The global ignore for the `json-schema-validator` signing key named the rows it requires:

```diff
-<ignored-key id="3EFC46EE83C40224" reason="Exact checksum exceptions EX-510 and EX-511 required"/>
+<ignored-key id="3EFC46EE83C40224" reason="Exact checksum exceptions EX-916 and EX-917 required"/>
```

Retiring EX-510 and EX-511 would otherwise leave that reason naming rows that no longer exist. The owner approved this reason-only change on 2026-11-18 (amendment option A). The key ID, its global scope and every other `<configuration>` line are unchanged. No other configuration reason names a retired row.

### Source-byte audit

At 2026-11-18T23:01Z, `python3 scripts/audit-dependency-provenance-sources.py` fetched all 594 ledger URLs over HTTPS and hashed them with SHA-256. It exited 0 with 594/594 matches, 83,924,010 bytes in total: 577 from Maven Central and 17 from the Gradle Plugin Portal.

## Limits

- **Publisher identity:** this review authenticates bytes and links them to sources and metadata. It does not authenticate publisher identity. No key was recovered, imported or trusted, and no fingerprint was confirmed out of band.
- **Key validity is not checked offline.** As in the [November 18 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#limits-and-interval), the rule above holds because this classification ran. The validator does not read key validity.
- **Ledger expiry:** this review does not renew the ledger. All 594 rows, including the 29 new ones, expire on 2026-12-31 UTC.
- **Not checked:** the classification relies on Gradle skipping ignored keys, as in the earlier reviews. Live LND and LNbits and the Docker stacks were not exercised. The Linux protoc executables are unchanged and first verified by Gradle in CI.

## Checks after reconciliation

- **`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`:** exit 0, both passed.
- **`python3 scripts/audit-dependency-provenance-sources.py`:** exit 0, 594/594 matches.
- **Trust-state diffs:**
  - `git diff 66886db -- gradle/verification-keyring.keys` is empty.
  - The `<configuration>` block differs from base only in the reason attribute above.
  - The metadata diff adds no `<trusted-key>`, `<trusting>` or `<pgp>` element, and its only `<ignored-key>` change is that reason.
- Strict empty-home build results are recorded in the [plan record](../../keel/2026-11-18-land-gradle-dependency-updates-113.md#tasks).

The environment was Java 25.0.1, Gradle 9.8.2 (base) and 9.9.1 (after), and GnuPG `gpgv` 2.5.20 on macOS arm64. This evidence awaits fresh-context review before merge, which must happen before 2026-12-31 UTC.
