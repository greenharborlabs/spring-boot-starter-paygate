# Dependency provenance exception review — 2026-11-18

This review renews `config/dependency-provenance-exceptions.tsv` through **2026-12-31 UTC**, as set out in [the ledger renewal plan](../../keel/2026-11-18-renew-dependency-provenance-ledger-2026-12.md). It also settles the trust decision left open by the [November 3 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-03.md#limits-and-interval): revoked and expired keyring keys now give coverage only for signatures made while the key was valid.

The work was performed on 2026-11-18 UTC on plan base `325814e`, which carries `main` at `327b3a7`, after #111. **No trust was added:** `<configuration>`, including every `<trusted-key>`, global `<ignored-key>` and `<pgp>` pin, and `gradle/verification-keyring.keys` are byte-identical to the base.

## Renewal of the existing rows

- **Starting state.** All 575 rows had the 2026-12-01 deadline.
  - Ledger SHA-256: `4079c494f5b3f8df1ebc50030359ab4d16a68bc48b764ba3ebe189e2c5b815f2`.
  - Metadata SHA-256: `3fdbcdb6de48f32854c1bf8a4e3624fe0010c8b936a51ed287ddc6e472626468`.
  - Keyring SHA-256: `11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460`.

  These match the state reconciled by the [November 15 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md). No dependency has changed since.
- **Pre-edit validator.** `./gradlew --no-daemon validateDependencyProvenance` passed before any edit.
- **Source-byte audit.** At 2026-11-18T14:31Z, `python3 scripts/audit-dependency-provenance-sources.py` fetched all 575 ledger URLs over HTTPS and hashed them with SHA-256. It exited 0 with 575/575 matches, 82,550,441 bytes in total: 558 from Maven Central and 17 from the Gradle Plugin Portal.
- **Edit.** `review-date` changed from `2026-12-01` to `2026-12-31` on all 575 rows. A field-level comparison with the starting ledger found:
  - 575 `review-date` changes;
  - no other change to an existing field;
  - an unchanged header;
  - the 17 key-validity rows below as the only additions.

## Key validity

Gradle accepts a signature from a trusted key whether or not the key has since expired or been revoked. So does `validateDependencyProvenance`. At base, the committed keyring had 14 trusted, non-ignored keys that are revoked or expired.

**The rule.** These keys keep coverage only for signatures they made while valid. A key's validity ends at the earliest of:
- the primary key's expiry;
- the signing subkey's expiry;
- the revocation time.

All dates come from the committed keyring.

**Method.**
1. Every committed artifact in the 14 keys' trust scopes, or pinned to one of them, was selected. 4 already have rows and were set aside.
2. Each of the remaining 66 artifacts and its `.asc` were fetched over HTTPS from Maven Central and matched to the committed SHA-256.
3. Each signature was verified with `gpgv` against a dearmored copy of the committed keyring only.
4. The signature creation time was compared with the end of the signing key's validity.

**Result:**

| Outcome | Artifacts |
| --- | --- |
| Signed by a different trusted key (out of scope; coverage unchanged) | 23 |
| Signed while the key was valid (coverage stands) | 26 |
| Signed after the key's validity ended (coverage removed; exact row added) | 17 |

**Signed while valid (26):**
- **Sonatype release key** `2BCBDD0F…0374CF2E8DD1BDFD`, revoked 2024-01-04T20:49Z with reason `0x01`, superseded (12 artifacts):
  - `jvnet-parent-1.pom` (pinned), signed 2011;
  - `oss-parent-5.pom`;
  - `buildsupport` and `public-parent` 13 and 28;
  - six `ossindex-service` 1.8.2 artifacts, the latest signed 2022-04-27.
- **`org.codehaus.plexus` key** `6A814B1F…1A2A1C94BDE89688`, expired 2026-06-05: `plexus-cipher` 2.0 (2021) and the pinned `velocity-master-7.pom` (2024-07-30).
- **Other expired keys:**
  - `jaxb-api` 2.3.1 and its parent, signed 2018, expired 2023-03-21;
  - `velocity-engine` 2.4.1 and its parent, signed 2024-10-14, expired 2025-09-10;
  - `durian-swt.os` 4.3.0, signed 2023-12-08, expired 2025-01-11;
  - JetBrains `annotations` 13.0, signed 2013, expired 2023-12-08;
  - `mockito-bom` 4.11.0, signed 2022-12-28, expired 2023-02-14.

**Signed after validity (17):**

| Ignored key (scope; expiry in committed keyring) | Artifacts | Signed | Rows |
| --- | --- | --- | --- |
| `689CBE64F4BC997F` (`org.mockito`; 2023-02-14) | `mockito-bom`, `mockito-core` and `mockito-junit-jupiter` 5.23.0 (5) | 2026-03-11 | EX-887..EX-891 |
| `347CD0EAED975C5F` (`org.antlr`; 2018-12-31) | `antlr4-master` and `antlr4-runtime` 4.9.3 (3) | 2021-11-06 | EX-879..EX-881 |
| `BEDE11EAF1164480` (`org.hamcrest`; 2020-11-20) | `hamcrest` 3.0 (3) | 2024-08-01 | EX-882..EX-884 |
| `FD116C1969FCCFF3` (`org.json`; 2025-06-17) | `json` 20260522 (2) | 2026-05-22 | EX-885..EX-886 |
| `79752DB6C966F0B8` (`com.google.android`; 2011-08-28) | `annotations` 4.1.1.4 (2) | 2012-08-31 | EX-875..EX-876 |
| `90D5CE79E1DE6A2C` (`com.querydsl`; 2021-11-19) | `querydsl-bom` 5.1.0 (1) | 2024-01-29 | EX-877 |
| `21871D2A9AB66A31` (`io.rsocket`; 2023-06-09) | `rsocket-bom` 1.1.5 (1) | 2025-02-03 | EX-878 |

The `org.latencyutils` key (expired 2017) covers no committed artifact.

**Representation.**
- Each of the 17 artifacts gets an artifact-scoped `<ignored-keys>` entry for its signing primary key, with `reason="Exact checksum exception EX-NNN required"`. This is the form used for `snakeyaml` 2.6 in the [November 1 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-01.md#metadata-representation-of-exceptions). Its existing checksum is unchanged.
- Each gets an exact row (EX-875..EX-891) with:
  - the Maven Central source;
  - the existing owner and trigger;
  - the 2026-12-31 deadline;
  - a new rationale: "Signature was made after the signing key's expiry recorded in the committed keyring; exact Maven Central SHA-256 required pending key refresh and independent fingerprint confirmation."
- No `<trusted-key>`, `<trusting>`, `<pgp>` or global `<ignored-key>` changed.

**Likely cause.** Several of these publishers kept signing after the recorded expiry. Mockito's key, for example, is recorded as expired in 2023 but signed a 2026 release. This points to stale key copies in the committed keyring, where the publishers later extended the expiry with new self-signatures. Refreshing those copies would change the keyring. That is a separate, reviewed key-recovery task; this review does not accept a key-server copy as approval.

**Controls.**
- **Validator enforces the rows:** with EX-882 (`hamcrest-3.0.jar`) removed from a scratch ledger, `scripts/validate-dependency-provenance.sh` fails with `checksum-only artifact lacks an exact exception for org.hamcrest:hamcrest:3.0:hamcrest-3.0.jar`.
- **Gradle honors the ignore:** in two scratch trees, each resolved from a new, empty Gradle home, `hamcrest-3.0.module`'s checksum was deleted.
  - **With the new ignore:** `:paygate-spring-autoconfigure:dependencyInsight` on `testRuntimeClasspath` **fails** dependency verification for that artifact.
  - **Without the ignore:** it **passes** on the expired key's signature alone.

  So the 17 artifacts are now accepted by exact checksum, not by signature.

## Result

- **Ledger:** 592 rows, all with the 2026-12-31 deadline. SHA-256 `7c133d8f675669bc37442da05e94e5fe4877d88813b5ab4abef4b2b8f5dd4018`.
- **Metadata:** 17 artifact-scoped ignore blocks added, with no other change. SHA-256 `8423ed452e23d4c965f89c91e3136065a8a818f8e62300c7b40ae0200cba41df`.
- **Keyring:** unchanged.
- **ID space:** 108 three-digit IDs remain (EX-892..EX-999).

## Limits and interval

- **Publisher identity.** This review re-establishes artifact bytes, sources and metadata linkage. It does not authenticate publisher identity: no key was recovered, imported or trusted, and no fingerprint was confirmed out of band.
- **Key validity is not checked offline.** The validator does not read key validity, so the rule above is enforced by this classification at each renewal or dependency change. A future dependency signed after its key's recorded expiry would still pass Gradle and the validator until that classification runs.
- **Stale expiry data.** Validity comes from the committed keyring. A key whose publisher extended the expiry is treated as expired until a reviewed key refresh.
- **Residual detection.** As in earlier reviews, an unannotated checksum-only artifact under a covering scope cannot be detected offline.
- **Pending dependency update.** Dependabot #113 (Tomcat 11.0.29, CycloneDX plugin 3.6.1, build-health 3.20.3, Gradle wrapper 9.9.1) is not reconciled here. It needs its own review before it merges.

**Interval.** The owner approved **2026-12-31 UTC** on 2026-11-18, which is 43 days. That is longer than the 30-day intervals of the [September 23](DEPENDENCY-PROVENANCE-REVIEW-2026-09-23.md) and [October 15](DEPENDENCY-PROVENANCE-REVIEW-2026-10-15.md) renewals. It was chosen to line the ledger up again with the CVE-2025-32013 suppression deadline (2026-12-31), so both monthly reviews happen in the same week. Publisher identity is still unauthenticated. The next renewal is a fresh review, not a date bump.

## Checks

Recorded in [the plan record](../../keel/2026-11-18-renew-dependency-provenance-ledger-2026-12.md#tasks).

The environment was Java 25.0.1, Gradle 9.8.2 and GnuPG `gpgv` 2.5.20 on macOS arm64.
