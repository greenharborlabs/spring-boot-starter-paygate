# Dependency provenance exception review — 2026-11-15

This review reconciles `gradle/verification-metadata.xml` and `config/dependency-provenance-exceptions.tsv` with [the Gradle dependency update plan](../../keel/2026-11-15-land-gradle-dependency-updates-105-104.md).

**Updates landed** (from Dependabot PRs #105 and #104):
- Byte Buddy 1.18.17
- build-health 3.20.1
- Spotless 8.10.2

**Updates held:**
- **gRPC** stays at 1.84.1, Spring Boot 4.1.2's managed version.
- **`mockwebserver3`** stays at 5.3.2, because of a stewardship and signing-key change.
- **Protobuf Gradle plugin** stays at 0.10.0, because the 0.10.1 jar Gradle resolves fails its publisher's signature.
- **Spotless** is not taken past 8.10.2, because of a signing-key rotation.

The work was performed on 2026-11-15 UTC on base `230b3600ea724f9b4908830822ba82a7125dfc8e`. **No trust was added:** the `<configuration>` block (all `<trusted-keys>` and `<ignored-keys>`) and `gradle/verification-keyring.keys` are byte-identical to the base. Every new artifact is either signature-covered by a key already trusted for its coordinates, or carries an exact exception row with the existing **2026-12-01 UTC** deadline.

## Evidence

### Starting and final state

| | Base (`230b360`) | Final |
| --- | --- | --- |
| Metadata SHA-256 | `769da5ba16a27227012850e17420be19f7dad99e31bcd0c6fbbc426c983677e3` | `3fdbcdb6de48f32854c1bf8a4e3624fe0010c8b936a51ed287ddc6e472626468` |
| Components / artifacts | 546 / 965 | 547 / 964 |
| Ledger SHA-256 | `5f67e564c54466b9db001f8dcd955d22e6054bafd9502de977bee747e6a94143` | `4079c494f5b3f8df1ebc50030359ab4d16a68bc48b764ba3ebe189e2c5b815f2` |
| Ledger rows | 575 | 575 |
| Keyring SHA-256 | `11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460` | unchanged |

### Resolution sets

Two scratch copies of the tree, one at base and one at the final versions, kept `<configuration>` byte-identical and emptied `<components>`. Each ran `--write-verification-metadata sha256` from a new, empty Gradle user home, with `-Pintegration` and the task set of the [November 1 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#current-resolution-set): `build buildHealth cyclonedxBom aggregateJavadoc spotlessCheck pmdMain :paygate-core:jmhJar :paygate-integration-tests:test :paygate-integration-tests:securityTest dependencyCheckAggregate`. Only the existing Dependency-Check NVD database was copied into each home. It is scan data, not a Gradle-verified artifact.

- **Base:** exit 0, 595 tasks, 963 artifacts. All of them are in the committed metadata. The other 2 committed entries are the hand-added Linux protoc executables, which are unchanged.
- **Final:** exit 0, 594 tasks, 962 artifacts.
- **Delta:**
  - **28 added.** These are the new versions of build-health and its `graph-support`, Spotless and its libraries, JGit 7.7.1, Eclipse OSGi 3.24.200, solstice 1.8.2, google-java-format 1.30.0, Byte Buddy, and one new `commons-codec` 1.22.0 POM.
  - **29 removed.** These are the versions they supersede, plus the Guava 32.1.3 POM and the `checker-qual` 3.37.0 POM, which only Spotless 7 used.
  - **0 checksums changed.**
- The final metadata's artifact set equals the final pass plus the two Linux protoc entries. Kept entries were not re-rendered.

Spotless 8.10.2 uses google-java-format 1.30.0 as its default for `removeUnusedImports`, replacing 1.24.0. The formatter pin, `googleJavaFormat("1.35.0")`, is unchanged.

### Signature classification of the 28 new artifacts

Each artifact and its `.asc` were fetched over HTTPS, from Maven Central or from the Gradle Plugin Portal where Gradle resolved them, and matched to the generated SHA-256. Each signature was verified with `gpgv` against a dearmored copy of the committed keyring only. An artifact counts as covered only when its signer is a non-ignored key trusted for its exact coordinates.

To calibrate the method, I ran the same procedure on the 32 artifacts removed in the first candidate pass: the 29 removed here, plus Protobuf plugin 0.10.0's jar, POM and marker. It reproduced the committed representation for all 32: every covered artifact had no row, and every other artifact had an exact row.

| Outcome | Artifacts | Detail |
| --- | --- | --- |
| Covered by an existing trusted key (checksum entry only) | 14 | `spotless-lib`, `spotless-lib-extra` 4.10.2 and `spotless-plugin-gradle` 8.10.2 (`A31DDE88…2C7F998F4272C851`, `com.diffplug.spotless`); Byte Buddy 1.18.17 (`net.bytebuddy` key); JGit 7.7.1 (`7C669810…CE140C2876`, `org.eclipse.jgit`) |
| Exception: signing key not in the keyring (`NO_PUBKEY`), signer already globally ignored | 12 | `dependency-analysis-gradle-plugin` 3.20.1 and `graph-support` 0.13.0 (`95962C5E716C39AA`); google-java-format 1.30.0 and its parent (`D364ABAA39A47320`); `commons-codec` 1.22.0 POM (`530AA5F25C25011F`); solstice 1.8.2 (`436902AF59EDF60E`); Eclipse OSGi 3.24.200 (`700E4F39BC05364B`) |
| Exception: publisher does not sign the artifact | 2 | Plugin Portal markers for build-health 3.20.1 and Spotless 8.10.2 |

Each exception follows the representation of the artifact it replaces:
- `reason="A key couldn't be downloaded"` for build-health, OSGi and `commons-codec`;
- `reason="Signature is not from a key trusted for these coordinates"` for google-java-format, matching 1.35.0. It sits under the `^com.google` scope but is signed by another key;
- `reason="A publisher-controlled signing key source is unavailable"` for solstice;
- `reason="Artifact is not signed"` for the markers.

No `<ignored-key>` was added or changed, because every exception signer was already globally ignored.

### Ledger changes

- **Removed (14 rows):** EX-134, EX-135, EX-364, EX-365, EX-469 through EX-472, EX-477, EX-804, EX-814, EX-815, EX-818 and EX-840. Nothing resolves these artifacts any more.
- **Added (14 rows):** EX-861 through EX-874, appended in coordinate order.
  - Each has the deadline `2026-12-01`, the existing owner `Green Harbor Labs maintainer` and the existing review trigger.
  - **Rationale:** 13 rows replace a removed row for the same group and module, and reuse its rationale. EX-870 (`commons-codec` 1.22.0 POM) replaces no row. It reuses the rationale of the existing `commons-codec` 1.22.1 rows, EX-527 and EX-528.
  - 12 rows have Maven Central sources and 2 have Gradle Plugin Portal sources. Each URL is the source of the bytes Gradle resolved.
- **Unchanged:** 561 rows, byte-identical.
- **Result:** 575 rows. 125 three-digit IDs remain (EX-875..EX-999).

### Source-byte audit

`python3 scripts/audit-dependency-provenance-sources.py` fetched all 575 ledger URLs over HTTPS on 2026-11-15 UTC and hashed them with SHA-256. It exited 0 with 575/575 matches, 82,550,441 bytes in total: 558 from Maven Central and 17 from the Gradle Plugin Portal.

## Held updates

### `mockwebserver3` 5.5.1 (OkHttp)

- **Signing key changed:** 5.3.2 is signed by `793FD5751A0F0780`, the Square key. 5.5.1 is signed by a different key, `6751E1A6E2001B8D`.
- **Source moved:** in the same release, the POM's SCM and site moved from `github.com/square/okhttp` to `github.com/lysine-dev/okhttp` and `lysine.dev`.
- **Repo transferred:** GitHub redirects `square/okhttp` (created 2012) to `lysine-dev/okhttp`, owned by an unverified organization created 2026-03-02.

This may be a legitimate change of maintainers, but nothing reviewed authenticates the new key or the new steward. The dependency is test-only. `com.squareup.okhttp3:*` is ignored in `.github/dependabot.yml` until a key-recovery task authenticates it.

### Protobuf Gradle plugin 0.10.1

`pluginManagement` lists `gradlePluginPortal()` first, so Gradle resolves the Plugin Portal's copy of `protobuf-gradle-plugin-0.10.1.jar`:

| Source | Jar SHA-256 | `.asc` result |
| --- | --- | --- |
| Gradle Plugin Portal (`plugins-artifacts.gradle.org`), the copy Gradle resolves | `02aee6feb303c2ef359a217d7c87ddf65f44828cd2b9aa98550e634a7179cf9d` | **`BADSIG`** |
| Maven Central | `6cbcf1b3f2aa4d267995b4891dafa96d4e72c21893d938f3ac49127be35e814c` | verifies with `1DBB44E80F61493D6369B5FB95C15058A5EDA4F1`, trusted for `^com.google` |

Both repositories serve the same `.asc`. Under strict verification the build would fail, because the signing key is trusted and not ignored. For 0.10.0, the bytes Gradle resolves matched Central's signed jar.

The plugin stays at 0.10.0. Dependabot ignores only version 0.10.1, so a corrected release will still be proposed.

### Spotless 8.10.3 and later

| Releases | Signing key | In keyring |
| --- | --- | --- |
| Up to 8.10.2 (2026-09-04) | `2C7F998F4272C851`, a subkey of `A31DDE881C3E3C4C985BD0D02C7F998F4272C851` | yes, trusted for `com.diffplug.spotless` |
| 8.10.3 (2026-09-25) and 8.10.4 | `FA5C18CFB74EC7C6` | no |

The POM's source is still `github.com/diffplug/spotless`, and GitHub shows no transfer. 8.10.2 keeps signature coverage. `com.diffplug.spotless` at `>= 8.10.3` is ignored in `.github/dependabot.yml` until a key-recovery task authenticates the new key.

## Limits

- **Publisher identity:** this review authenticates bytes and links them to sources and metadata. It does not authenticate publisher identity. No key was recovered, imported or trusted, and no fingerprint was confirmed out of band.
- **Revoked and expired keys:** the open trust decision about 14 revoked or expired keyring keys, recorded in the [November 3 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md#limits-and-interval), is unchanged.
- **Ledger expiry:** this review does not renew the ledger. All 575 rows, including the 14 new ones, expire on 2026-12-01 UTC.
- **Not checked:** the classification relies on Gradle skipping ignored keys, as in the earlier reviews. Live LND and LNbits and the Docker stacks were not exercised.

## Checks after reconciliation

- **`./gradlew --no-daemon validateDependencyProvenance verifyDependencyProvenanceNegativeControls`:** exit 0, both passed.
- **`python3 scripts/audit-dependency-provenance-sources.py`:** exit 0, 575/575 matches.
- **`cmp` of the `<configuration>` block against base, and `git diff 230b360 -- gradle/verification-keyring.keys`:** identical and empty. The metadata diff has no `<trusted-key>`, `<trusting>`, `<pgp>` or `<ignored-key>` additions.
- Strict empty-home build results are recorded in the [plan record](../../keel/2026-11-15-land-gradle-dependency-updates-105-104.md#tasks).

The environment was Java 25.0.1, Gradle 9.8.2 and GnuPG `gpgv` 2.5.20 on macOS arm64. This evidence awaits fresh-context review before merge, which must happen before 2026-12-01 UTC.
