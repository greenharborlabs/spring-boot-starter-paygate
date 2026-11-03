# Dependency provenance exception review — 2026-11-03

This review renews `config/dependency-provenance-exceptions.tsv` through **2026-12-01 UTC**, as set out in [the validator tightening and renewal plan](../../keel/2026-11-01-tighten-dependency-provenance-validator-and-renew-ledger.md). The work was performed on 2026-11-03 UTC on plan base `651c43e`, which carries `main` at `c288ec6`. It also makes the validator require an exact row for every artifact Gradle can accept on its checksum alone, and adds those rows. No trust was added: `gradle/verification-keyring.keys` and `<trusted-keys>` are unchanged.

## Renewal of the existing rows

- **Starting state.** The starting ledger had 511 unique rows, all with the 2026-11-22 deadline. Its SHA-256 was `f1f52ab501433d9017529e001cd4700e02fcec6f23df41d6a5c4084b58776858`. The metadata (`4983724fd46885f612ec2594710f9047bba13f90447a3cfaa295108d57328eec`) and keyring (`11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460`) are byte-identical to the state reconciled by the [November 1 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md). No dependency has changed since.
- **Pre-edit validator.** `./gradlew --no-daemon validateDependencyProvenance` passed before any edit.
- **Source-byte audit.** At 2026-11-03T02:57Z, `python3 scripts/audit-dependency-provenance-sources.py` fetched all 511 ledger URLs over HTTPS and hashed them with SHA-256. It exited 0 with 511/511 matches, 72,420,133 bytes in total: 500 from Maven Central and 11 from the Gradle Plugin Portal.
- **Edit.** `review-date` changed from `2026-11-22` to `2026-12-01` on all 511 rows. A field-level comparison with the starting ledger found 511 `review-date` changes, no other field changes and no header changes. The renewed ledger's SHA-256 is `af2c48c7a76dd415be411315e9647963d9a6b5eb43838cbaa71ef097824c5f93`.
- **Signatures.** The detached-signature classification for the existing rows was not repeated for this renewal step. Its inputs (metadata and keyring) are byte-identical to those of the November 1 review.

## Validator rule and checksum-only artifacts

The [November 1 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#limits) recorded that `scripts/validate-dependency-provenance.sh` required a row only for checksums annotated `A key couldn't be downloaded`. This review closes that gap.

### Classification

- **Static scan.** All 965 metadata artifacts were classified against `<trusted-keys>`, `<ignored-keys>` and artifact-level `<pgp>` pins. 454 artifacts carry a checksum but have no row.
- **Detached signatures.** Each of the 454 artifacts and its `.asc` were fetched over HTTPS on 2026-11-03 UTC, from Maven Central and otherwise from the Gradle Plugin Portal. The `-jre` path was used for the Guava `-android` jar. Every artifact matched its metadata SHA-256. For `io.spring.dependency-management.gradle.plugin-1.1.7.pom`, Maven Central serves different bytes; the Plugin Portal bytes match.

  Signatures were verified in an isolated GPG home holding only the committed keyring, with `--no-auto-key-retrieve`. The import result matches earlier audits: 187 keys processed, 116 imported, 52 without user IDs. An artifact counts as covered only when its signer is a key that is trusted for its exact coordinates, or pinned on it, and not ignored.

| Result | Artifacts |
| --- | --- |
| Covered: valid signature from a non-ignored key trusted for, or pinned to, its coordinates | 391 |
| Checksum-only: valid signature from a key that is not trusted for these coordinates, or is ignored | 31 |
| Checksum-only: signing key absent from the committed keyring (`NO_PUBKEY`) | 19 |
| Checksum-only: no detached signature published | 13 |
| Checksum-only: Guava `guava-33.6.0-jre.jar` under component `33.6.0-android` | 1 |

The Guava jar's signature, fetched from the `33.6.0-jre` path, verifies with a key trusted for `com.google.guava`. Gradle cannot locate that signature through the variant redirect, so it records the jar as `Artifact is not signed` and checks the checksum only.

The same procedure on the 10 existing rows that sit under a non-ignored scope found each signed by a different key: EX-361, EX-366 through EX-368, EX-426, EX-427, EX-431, EX-432, EX-502 and EX-503. They are checksum-only too.

### Artifact pins

Only 2 of the 7 artifact-level `<pgp>` pins verify with the committed keyring: `jvnet-parent-1.pom` and `velocity-master-7.pom`. The keys pinned on these 4 artifacts are absent from the keyring:
- `gson-2.3.1.pom` (EX-810);
- `apache-16.pom` (EX-836);
- `commons-parent-39.pom` (EX-837);
- `plexus-8.pom` (EX-844).

The `plexus-8` pin also names `012579464D01C06A`, which the November 1 reconciliation added to the global `<ignored-keys>`. That review stated that no signature-covered artifact is signed by the newly ignored keys. That statement did not hold for `plexus-8`. Even before the ignore, its pin had no key in the committed keyring, so the pin never gave keyring-backed coverage. All four now have rows.

The optional Gradle `--write-verification-metadata` cross-check was not run. The GPG classification against the committed keyring is the stricter test, and every artifact it does not cover now has a row.

### Rule

The validator now parses each `<component>`/`<artifact>` as a unit. An artifact with a checksum must have an exact row (group, module, version, file and SHA-256) when either of these holds:

1. Its `<sha256>` carries any `reason` annotation.
2. No non-ignored key covers it. A key covers an artifact through a `<trusted-key>`/`<trusting>` scope that matches its exact coordinates, or through a `<pgp>` pin whose fingerprint is in the committed keyring.

Further details:
- **Regex scopes.** Only Gradle's generated group-prefix shape, `^<literal>($|([.].*))`, is evaluated as a regex scope. Any other regex counts as not covering, so the artifact needs a row.
- **Row linkage.** Each row must name one exact metadata artifact together with its checksum. This replaces three independent global searches, which accepted a checksum belonging to a different artifact.
- **Guava mapping.** The single accepted source/version difference is component version `<v>-android` to source version `<v>-jre`, when the filename is `<module>-<v>-jre.<ext>`. `scripts/audit-dependency-provenance-sources.py` applies the same rule.
- **Annotation of the residual class.** Coverage that only the detached signature can disprove is a covering scope whose signer is a different key. The 25 artifacts in that class are annotated in the metadata with `reason="Signature is not from a key trusted for these coordinates"`, so the first condition enforces their rows. The class is the 10 existing rows above plus 15 new ones. No other metadata change was made.

### Ledger additions

64 exact rows were added as EX-797 through EX-860, in coordinate order. They have the 2026-12-01 deadline, the owner `Green Harbor Labs maintainer` and the existing review trigger. Their sources are 58 from Maven Central and 6 from the Gradle Plugin Portal.

| Category | Rows | Rationale |
| --- | --- | --- |
| Valid signature, key not trusted for these coordinates or ignored | 31 | Existing valid-signature rationale |
| `NO_PUBKEY` | 15 | Existing `NO_PUBKEY` rationale |
| Pinned key absent from the keyring or ignored | 4 | New pin rationale |
| Unsigned, Maven Central | 7 | New Maven Central unsigned rationale |
| Unsigned, Gradle Plugin Portal | 6 | Existing Plugin Portal unsigned rationale |
| Guava `-android` variant redirect | 1 | New Guava rationale |

The additions cover the November 1 review's 46 gap artifacts, the 13 legacy unsigned artifacts, the Guava jar, and 4 pinned artifacts. The 4 pinned artifacts are `plexus-8.pom` and the 3 pins whose keys are missing. No metadata entry was removed: the November 1 enumeration of the resolved set still holds, and no dependency changed.

The ledger now has 575 rows, SHA-256 `5f67e564c54466b9db001f8dcd955d22e6054bafd9502de977bee747e6a94143`. The metadata is SHA-256 `769da5ba16a27227012850e17420be19f7dad99e31bcd0c6fbbc426c983677e3`. The keyring is unchanged.

### Source-byte audit and negative controls

- **Audit.** At 2026-11-03T03:06Z, `python3 scripts/audit-dependency-provenance-sources.py` exited 0 with 575/575 SHA-256 matches, 82,476,457 bytes in total: 558 from Maven Central and 17 from the Gradle Plugin Portal.
- **New fixtures.** `scripts/test-dependency-provenance.sh` gained 12 cases:
  - a dropped row for an unscoped artifact, for an unsigned artifact, for a residual-annotated artifact, for an ignored-key scope, and for the ignored `plexus-8` pin;
  - an artifact-level ignore of a covering pin key;
  - a pin to a fingerprint absent from the keyring;
  - an unsupported regex form;
  - a row carrying another artifact's checksum;
  - three source-mapping misuses.
- **Expected rejections.** Each case must be rejected with its expected message, and a mutation that leaves the inputs unchanged fails the harness.
- **The fixtures detect a regression.** Against the previous validator, extended only with the Guava mapping, all 9 rule cases were accepted.
- **Portability.** The validator and harness pass with macOS awk, and with mawk and gawk on Ubuntu 24.04.

## Limits and interval

- **Publisher identity.** This review re-establishes artifact bytes, sources and metadata linkage. It does not authenticate publisher identity: no key was recovered, imported or trusted, and no fingerprint was confirmed out of band. `<trusted-keys>`, `<ignored-keys>`, the `<pgp>` pins and the keyring are unchanged.
- **Residual detection.** The offline validator cannot detect an unannotated checksum-only artifact under a covering scope: one signed by a key other than the one trusted for its coordinates. A future dependency change could add such an artifact. The detached-signature classification that policy requires for every dependency change remains the control for that case.
- **Key servers.** The metadata does not disable Gradle's key-server lookup for keys absent from the keyring. A key obtained that way is not publisher approval. Every artifact whose key is absent from the keyring now has a row whatever Gradle fetches.
- **ID space.** IDs use three digits (`EX-NNN`). EX-860 is the highest, which leaves 139 IDs.

The owner approved the 2026-12-01 UTC deadline on 2026-11-01. It was proposed as 30 days from that date. Measured from this work date it is 28 days, which is shorter rather than longer than the 30-day intervals of the [September 23](DEPENDENCY-PROVENANCE-REVIEW-2026-09-23.md) and [October 15](DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md) renewals. The date falls the day after the CVE-2025-32013 suppression deadline (2026-11-30), so both monthly reviews happen in the same week. Publisher identity is still unauthenticated, so a longer interval would extend unauthenticated trust without new evidence.
