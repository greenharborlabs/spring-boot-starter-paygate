# Dependency provenance review — 2026-11-21

This review records a signing-key refresh and the decisions on the two held keys, under [the key-refresh plan](../../keel/2026-11-21-refresh-dependency-signing-keys.md). The work was performed on 2026-11-21 UTC on `main` at `61a64b8`.

**Summary.**
- **Key refresh.** Six of the 18 revoked or expired trusted keys have newer expiry self-signatures from their own primary key. Their committed copies now carry those signatures.
- **Rows retired.** Twelve artifacts that were signed after their key's recorded expiry were in fact signed while the key was valid. Their exact rows (EX-879..EX-881 and EX-885..EX-893) and artifact-scoped ignores are retired. The other 7 rows from the [November 18 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity) stay.
- **Spotless.** Spotless 8.10.3 and later are signed by a new subkey of the DiffPlug primary key that already certifies the trusted Spotless subkey. That subkey is now trusted for `com.diffplug.spotless`, and the Dependabot hold is lifted.
- **OkHttp.** The owner chose to lift the hold without trusting the new lysine.dev key. OkHttp 5.5.1 will land on exact checksum rows, as 5.3.2 does today.
- **No row renewed.** Every remaining row keeps its 2026-12-31 UTC deadline.

## Starting and final state

| | Base (`61a64b8`) | Final |
| --- | --- | --- |
| Keyring SHA-256 | `11d778c62cd27b106102b5c9485b4dc8d895cf2933eacfc03f7cbeb7baf77460` | `224431dd9663094b4b7a5caddce14c207115ee6590fd3f58a049e51a9c88a1bf` |
| Metadata SHA-256 | `e3eff36c8b11522eab22d02ef4854decee050ebbf5c580ae7dfdcd8db783eced` | `e9245bee44af6a5b7eaf4c0ecf8aa0d2e62a184fcf08cb2730e542cbe4d96b65` |
| Components / artifacts | 547 / 964 | 547 / 964 |
| Trusted keys | 118 | 119 |
| Ledger SHA-256 | `9a6b2a802d123ccadc853a38d3cb7e88d2bb461496a23ed9384673595c8f0887` | `2c5b3bd4f78120b6bf3571f151f9aa56e1a58e9b16855d1d80990a5cdfabf9d3` |
| Ledger rows | 594 | 582 |

## Why a keyserver copy can refresh a trusted key

An expiry extension is a new self-signature made with the key's own private primary key. A keyserver can withhold such a signature, but it cannot forge one. So a newer self-signature that verifies against a primary fingerprint already in the committed keyring is authenticated by that key. This adds no trust in a new identity. The remaining risk is a withheld revocation. Every key was therefore fetched from both keyserver.ubuntu.com and keys.openpgp.org, and every revocation present in either copy would have been applied. None was found.

The refresh adds **no** UID, user attribute, subkey or third-party certification. A new subkey would widen what a primary-fingerprint trust accepts, so the only subkey added is the separately approved Spotless subkey.

## Keyring refresh

### Keyring structure

The keyring has 95 armored blocks and 187 key copies, covering 116 distinct keys:
- **Gradle-generated blocks:** 71 blocks with `pub`/`uid`/`sub` text headers. Their UIDs carry no self-signature, so these copies hold no primary expiry data.
- **Full copies:** 24 blocks without headers, including one block of 93 keys at the end of the file. These hold the self-signatures that determine validity.

All 18 keys have a full copy, and 14 also have a Gradle-generated copy. Only full copies were changed. Gradle-generated blocks are byte-identical. Because the 93-key block is re-armored, its text diff spans the whole block. Review it at packet level, not as text.

### Per-key result

Each fetched copy was fetched over HTTPS on 2026-11-21 UTC.

| Key (trust reference) | Holder | Validity end, committed | Validity end, refreshed | keyserver.ubuntu.com copy SHA-256 | keys.openpgp.org copy SHA-256 |
| --- | --- | --- | --- | --- | --- |
| `0F07D1201BDDAB67CFB84EB479752DB6C966F0B8` | Rob Manning | expired 2011-08-28 | unchanged | `58472e4f01c894b414550ee8bbd5bdcb29066ef30bcf41aca585118202efa923` | not served (404) |
| `7A1D848E7C2AF85EEBA69C99E7BF252CF360097E` | Gil Tene | expired 2017-11-23 | unchanged | `09b00d5a7112eb771655f81b64cbbf3bced6e7d89aac52887c64f5195c6c9f79` | `67b70135d6181321e53ffd572a579b67faad8c6dab62427d7ccaee856f2218c6` |
| `B01128C8FD304FE48E6EED2C347CD0EAED975C5F` | Eric Vergnaud | expired 2018-12-31 | **2023-02-08** (from openpgp.org) | `5e18aae29bb41a2dc1ab4325b3d96f3a3c24ce9fbf966fefff4946eac94b9159` | `f0db5089607f5d3f847e4b10b29f86dfe5c88fa842d4de5f71cadafc87e79449` |
| `E3A9F95079E84CE201F7CF60BEDE11EAF1164480` | Joe Schmetzer | expired 2020-11-20 | unchanged | `d4c6c61e570ac52dc59acf368fd6241834f115021c9bb8283b0f2549cec6ce55` | `f8d91f9615503c4c7abeb3514ee7215756c010d061f3fbff44a72b164857c59e` |
| `57312C37B064EE0FDAB0130490D5CE79E1DE6A2C` | John Tims | expired 2021-11-19 | unchanged | `2ace040a705d67297a91829537ea2b5e6b3fa087828aaf21ee8ef9c5a95e0861` | `de797fb463a064d012b7c53476a5facc0fd00e55b899cbea88ce8ce3b6880680` |
| `147B691A19097624902F4EA9689CBE64F4BC997F` | Szczepan Faber | expired 2023-02-14 | **2030-10-11** (from Ubuntu) | `456dba8803d7ca365be873062eaff35fb8a57abca4d254f2d7bb71e25d53da06` | `70597ca1fd8a81caae5d71f9d516357467ee62209a5b4e80f412a346f2452b24` |
| `70CD19BFD9F6C330027D6F260315BFB7970A144F` | EE4J Automated Build | expired 2023-03-21 | unchanged | `3ba2976ecc16ab2b81f029536a03375c4acf1251d1971db3f37e9736e8302802` | `f1284d1a008a7b16bc8e8d4e5e13165d4288936199c0f80a8dec8cba84701910` |
| `9B32CBC0F3F6BA4C13D611FC21871D2A9AB66A31` | RSocket Admin | expired 2023-06-09 | unchanged | `01f7cfdafcb1de5e00ffd67c5bfa44f5cd5bb711d83423e984d022e3eec3af12` | not served (404) |
| `2E3A1AFFE42B5F53AF19F780BCF4173966770193` | IntelliJ IDEA Sign Key | expired 2023-12-08 | unchanged | `18056df6e52699242d1db0e3693b3451822e6fb5ec5afe632fa982abb0c170b1` | not served (404) |
| `2BCBDD0F23EA1CAFCC11D4860374CF2E8DD1BDFD` | Sonatype, Inc. | revoked 2024-01-04 | unchanged | `b622f378b8d006c9363fc46857c978ccdaf21b1c1b4dfee95d3c664568b4b2e7` | `cee196e7ea45e23415a573899dc2b0a46ef5772bfb5be277d922d861c6c1e3c5` |
| `2B34821418CF19CF1F2A8352953E02E4F573B46F` (subkey) | Eclipse Jakarta EE Platform | expired 2024-02-13 | unchanged | `e020eeb2a38fabc7565d1e3422aa255fa765be415c9f43b7534190854de4d462` | `b3c81ef2c9c79b2c36e96404d342811fbb1a854f6e318f3874e915550934a6ee` |
| `2518174F4111F02779592A6F9757D7E7E06DD2AC` (subkey) | Fabian Stäber | expired 2024-12-31 | unchanged | `228f5c3b599eef9ce49580c82bd34cef40aa66e0d5930b1ec6078ee5c854b149` | not served (404) |
| `4797B4F5DCC46CEA61059071A1AE06236CA2BA62` | DiffPlug LLC | expired 2025-01-11 | unchanged | `bf28c89c65f22074b0803c539d041673b007fb2b656b693f20980a50ecc7efb6` | `9db6938df46e236283e0e8013f7a446be2decee9c03568eac275cfdd79af6616` |
| `FB35C8D02B4724DADA23DE0AFD116C1969FCCFF3` | Sean Leary | expired 2025-06-17 | **2035-12-22** (from Ubuntu) | `18805809df7343c5f8ccbad3f2f97553442663216e2424eaf9459e46cbff43bf` | `5f550797ecf6b736586cda160ed3741c9e2751c6d74a4bf5a27fdd7ded3273ae` |
| `6F9D10E747DC79485ABB4F72B709E61252F136DD` | Claude Brisson | expired 2025-09-10 | unchanged | `ada74f555a6c8be010c8a71bd98bdc1cc24975bd064bcf2e99bd5b62ee7d86bc` | `d6e7e6e04c681648093dda923b7386d93b64e9dca7152b338df144ff54bba6d2` |
| `07A4FA67A8222F1B39F9EDAEF8052E4E1BD0DB31` (subkey) | Neo4j | expired 2026-02-12 | **2027-09-20** (from Ubuntu) | `24e58364b4c142ab97042c87c9ba1d985dd6d97f9edf3f112e52d0d7b12999a7` | `606a34fc1b1c039ae848cba3163405d1c07fb78b44cd97c9ab5b4e76a5918b6c` |
| `6A814B1F869C2BBEAB7CB7271A2A1C94BDE89688` | Michael Osipov | expired 2026-06-05 | **2028-10-14** (from openpgp.org) | `8adc455dde639896eb96e73b74ad029b0182768baec52e66817e689378ffdb7c` | `3388d38bac2786e5798562d970a6ce5b90bb1337cd05a8e0172f67c53ca9b9be` |
| `E7DC75FC24FB3C8DFE8086AD3D5839A2262CBBFB` (subkey) | Kotlin Libraries Release | expired 2026-09-29 | **2028-09-26** (from Ubuntu) | `75c51fbd84153f19d8b0997301481e60b8401e14562fe1ec4fcf5f8a843832f1` | `3688ed45fe6838fcfae660b55bc65071981e20988cc09e25cfba7a5c0d361638` |

The six refreshed keys received only signature packets, all issued by the key's own primary:

| Key | Added signatures |
| --- | --- |
| ANTLR `B01128C8…347CD0EAED975C5F` | 1 UID certification (2021-02-08) |
| Mockito `147B691A…689CBE64F4BC997F` | 1 UID certification, 1 subkey binding (2026-10-12) |
| org.json `FB35C8D0…FD116C1969FCCFF3` | 1 UID certification (2025-12-24) |
| Neo4j `76DBA0D1…89AF105CEEC705A4` (primary of the trusted subkey `07A4FA67…F8052E4E1BD0DB31`) | 1 UID certification, 2 subkey bindings (2026-08-26) |
| Michael Osipov `6A814B1F…1A2A1C94BDE89688` | 2 UID certifications, 1 subkey binding (2026-10-15) |
| Kotlin Libraries `BC900CD2…E3822B59020A349D` (primary of the trusted subkey `E7DC75FC…3D5839A2262CBBFB`) | 1 UID certification, 3 subkey bindings (2026-09-27) |

Only the newest self-signature per UID or subkey was added, and only when it was newer than the committed one. Older historical self-signatures that keyservers also serve were not added, because they change nothing.

**Structural checks** against the base keyring, by packet comparison:
- **Unchanged blocks:** every Gradle-generated block and every unchanged full block is byte-identical.
- **Changed keys:** every key in a changed block keeps its order and fingerprint. Every base packet is present, in order.
- **Added packets:** every added packet is a signature issued by that key's own primary. The one exception is the approved Spotless subkey packet.
- **gpg:** in an isolated GnuPG home, `gpg --show-keys --with-sig-check` reports every self-signature on the 18 keys as good (`!`).
- **Fingerprints:** the set of primary and subkey fingerprints in the self-certified copies is the base set plus `2820DE9F95EC4043E616B735FA5C18CFB74EC7C6`, with none removed.

## Key-validity reclassification

The key-validity rule from [SECURITY.md](../../SECURITY.md#dependency-provenance-and-local-credentials) was reapplied to every committed artifact in the 18 keys' scopes. Selection matched every key reference against both primary and subkey fingerprints, and applied every scope attribute. That gives 74 artifacts, 25 of which had rows. Each artifact and its `.asc` were fetched over HTTPS from Maven Central, and all 74 matched their committed SHA-256. Each signature was verified with `gpgv` against a dearmored copy of the keyring, base and refreshed in turn. Signature creation time was then compared with the end of the signing key's validity, which is the earliest of primary expiry, signing-subkey expiry and revocation.

| Outcome | Base keyring | Refreshed keyring |
| --- | --- | --- |
| Signed by a different trusted key (out of scope) | 17 | 17 |
| Signed while the key was valid | 32 | 44 |
| Signed after the key's validity ended (row required) | 19 | 7 |
| Already had a row for another reason (unchanged; 4 not verifiable with the keyring, 1 not fetched from Central or the Plugin Portal, 1 signed by another key) | 6 | 6 |

The base column reproduces the [November 18 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md#key-validity). The 12 artifacts that moved:

| Rows retired | Artifacts | Signed | Validity end, committed → refreshed |
| --- | --- | --- | --- |
| EX-879..EX-881 | `antlr4-master` and `antlr4-runtime` 4.9.3 | 2021-11-06 | 2018-12-31 → 2023-02-08 |
| EX-885..EX-886 | `json` 20260522 | 2026-05-22 | 2025-06-17 → 2035-12-22 |
| EX-887..EX-891 | `mockito-bom`, `mockito-core` and `mockito-junit-jupiter` 5.23.0 | 2026-03-11 | 2023-02-14 → 2030-10-11 |
| EX-892..EX-893 | `neo4j-bolt-connection-bom` 11.0.2 and `neo4j-java-driver-bom` 6.1.0 | 2026-04-30 and 2026-05-01 | 2026-02-12 → 2027-09-20 |

**Still signed after validity (7 rows stay):**
- Android `annotations` 4.1.1.4 (EX-875, EX-876);
- `querydsl-bom` 5.1.0 (EX-877);
- `rsocket-bom` 1.1.5 (EX-878);
- Hamcrest 3.0 (EX-882..EX-884).

Their publishers' keys show no extension on either keyserver.

The two refreshed Kotlin and Maven keys (`E7DC75FC…`, `6A814B1F…`) change no row: every committed artifact they sign was already signed while valid. Their refresh keeps future releases covered.

**Metadata and ledger changes.**
- **Metadata:** the 12 artifact-scoped `<ignored-keys>` blocks naming EX-879..EX-881 and EX-885..EX-893 are removed, and the artifacts' checksums are unchanged. The artifacts are now covered by their trusted key's signature, as they were before the [November 18 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-18.md).
- **Ledger:** the field-level comparison shows those 12 rows removed and no other field changed. The 582 remaining rows all keep the 2026-12-31 deadline. Retired IDs are not reused; 77 IDs remain (EX-923..EX-999).

## Spotless 8.10.3 and later

The [November 15 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md#spotless-8103-and-later) held Spotless at 8.10.2 because 8.10.3 and later are signed by `FA5C18CFB74EC7C6`, which was not in the keyring.

**Evidence:**
- **Same primary.** Both keyservers serve the DiffPlug primary key `27294482F2D2F9A318CA041B1AAAA7AB37293D3E` with two signing subkeys.
  - Fetched copies: Ubuntu copy SHA-256 `ed244d0763f8674dc375d15528b357d5872935709f66bb2ae0c04ffe34b9fb49`; openpgp.org copy SHA-256 `e35125d54693f783af30fd4c38dc57eaffaf4ade853582f28b22d4bd3e0b66d5`.
  - The subkeys are the trusted `A31DDE881C3E3C4C985BD0D02C7F998F4272C851` (`2C7F998F4272C851`) and the new `2820DE9F95EC4043E616B735FA5C18CFB74EC7C6` (`FA5C18CFB74EC7C6`, created 2026-09-17, expiring 2030-05-27).
  - That primary, with the trusted subkey, is already in the committed keyring.
- **Valid binding.** The new subkey's binding signature is issued by the committed primary `1AAAA7AB37293D3E`, and `gpg --with-sig-check` reports it as good. Neither copy carries a revocation.
- **Releases verify.** With the new copy, `gpgv` reports `VALIDSIG 2820DE9F…FA5C18CFB74EC7C6` for the jar, POM and Gradle module of:
  - `spotless-plugin-gradle` 8.10.4 and 8.10.3 (signed 2026-10-08 and 2026-09-25);
  - `spotless-lib` and `spotless-lib-extra` 4.10.4.

  With the base keyring, each gives `NO_PUBKEY`. All were fetched from Maven Central.
- **Source unchanged.** The POM source is still `github.com/diffplug/spotless`, as the November 15 review recorded.

**Change.**
- **Keyring:** the full DiffPlug copy gains the subkey packet and its binding signature. The Gradle-generated copy is unchanged.
- **Metadata:** `<trusted-key id="2820DE9F95EC4043E616B735FA5C18CFB74EC7C6" group="com.diffplug.spotless"/>`, scoped like the existing subkey trust.
- **Dependabot:** the `com.diffplug.spotless` `>= 8.10.3` ignore is removed.

**Rejected:** trusting the primary `27294482…`. That would accept any future DiffPlug subkey without review.

No Spotless version changes here. Landing 8.10.4 is a separate Dependabot task and needs its own classification of new transitive artifacts.

## OkHttp 5.5.1

The [November 15 review](DEPENDENCY-PROVENANCE-REVIEW-2026-11-15.md) held `mockwebserver3` at 5.3.2. 5.5.1 is signed by `5DD80B0DDCCBE005F0A47FD66751E1A6E2001B8D` ("lysine.dev <project-lysine@commonhaus.org>", created 2026-07-21, self-signed only), and the project moved to the `lysine-dev` GitHub organization.

**Evidence gathered on 2026-11-21:**
- **Repository transfer.** `square/okhttp`, `square/okio` and `square/retrofit` resolve to `lysine-dev/okhttp`, `lysine-dev/okio` and `lysine-dev/retrofit`, with repository IDs 5152285, 17812502 and 892275 and creation dates 2012, 2014 and 2010 unchanged. These are transfers of the original repositories, which only an owner of the `square` organization can perform.
- **Commonhaus Foundation.** The foundation's announcement of 2026-06-17 ([discussion 315](https://github.com/commonhaus/foundation/discussions/315), posted by `aalmiray` in the foundation's own repository) states that OkHttp, Okio, Retrofit and SQLDelight joined Commonhaus under the lysine.dev banner. It names Jesse Wilson and Jake Wharton, the projects' long-time maintainers, as its leaders. The foundation's `PROJECTS.yaml` lists `lysine-dev/okhttp` and `lysine-dev/okio`.
- **Fingerprint publication.** The fingerprint appears only on lysine-dev channels:
  - OkHttp's `docs/security/security.md`, added on 2026-07-22 in a GitHub-verified signed commit by `swankjesse` (`afad3bea3e`, #9575);
  - the 5.5.0 CHANGELOG ("OkHttp artifacts are now signed with our new signing key").

  Neither Commonhaus nor Square/Block publishes it, and no Block announcement was found. Copy hashes: Ubuntu `9f9ebda27e83a209335cdecd86a40ef71a4b0640fd68dd2f6b93953f70aa7217`; openpgp.org `198e67760785e08afd534e57fa54b25a4c8446dc4a187ddc1880c030d370a327`, which has its UID stripped.
- **Prior trust.** The prior Square key `793FD5751A0F0780` is already globally ignored. OkHttp 5.3.2 is accepted today by exact checksum rows, not by signature.

**Decision (owner, 2026-11-21): option B.**
- **Hold lifted:** the `com.squareup.okhttp3:*` Dependabot ignore is removed.
- **No trust added:** the lysine.dev key is not trusted. When 5.5.1 lands, its artifacts take exact checksum rows, as 5.3.2's do.
- **Rejected:** trusting the key (option A), because no Square- or Commonhaus-controlled channel publishes the fingerprint. Keeping the hold (option C) was also rejected.

Okio (`com.squareup.okio`, trusted under the Square key) moved too, so its next release is expected to hit the same key change.

## Limits

- **Publisher identity.** This review authenticates key *continuity*: new self-signatures and a new subkey, each certified by a key already trusted. It does not confirm any fingerprint out of band.
- **Withheld revocations.** A revocation withheld from both keyservers would not be seen.
- **Full revocation sweep.** Only the 18 revoked or expired keys were refreshed. A revocation sweep of all 116 keys is a separate task.
- **Gradle-generated copies.** The 14 Gradle-generated copies of refreshed keys still carry their older subkey bindings. They have no self-certified UID, so gpg ignores them, and Gradle does not check expiry.
- **Offline enforcement.** Key validity is still not checked offline. Each renewal and each dependency change must reclassify, as before.

## Checks

Recorded in [the plan record](../../keel/2026-11-21-refresh-dependency-signing-keys.md#tasks).
