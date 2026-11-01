# Remediate 2026-10-18 security review Info items
Status: closed
Base revision: b1d9c9ed8b04a1afae3141e693c04ea347e1c3c3
Review scope: paygate-api/src/**, paygate-core/src/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnd/src/**, paygate-lightning-lnbits/src/**, paygate-integration-tests/src/**, paygate-example-app-spring-security/src/test/**, build.gradle.kts, META-INF/**, integration-tests/docker-compose*.yml, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, docs/macaroons-deep-dive.md, SECURITY.md, CHANGELOG.md, README.md, */README.md

## Goal
Resolve the Info items from the [2026-10-18 security review](../reports/security-review-2026-10-18-claude-opus-5-5.md) that the [Low remediation record](2026-10-31-remediate-security-review-2026-10-18-lows.md) deferred: I-01, I-04 to I-14, I-16 to I-19, and the optional I-20 strict mode. Each item is either fixed or explicitly closed with a reason.

Acceptance evidence:
- Each available PoC is copied from `/Users/mark/code/greenharborlabs/paygate-secreview` and inverted, then passes as a regression test:
  - V2ParserEdgeCase
  - L402Price cases `c2` (I-04) and `c3` (I-01)
  - MppBinding
  - AuthResultTrust
  - LegacySessionPersistence
  - ProtocolSwitchSpelling
  - HiddenMethodOverride

  Items with no PoC each get a new test or a recorded inspection.
- In the [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md):
  - every in-scope Info row is `implemented` with its evidence, or `accepted` with a reason;
  - the follow-up reference by title is replaced with a link to this record.
- Root `check` and the integration `test` and `securityTest` suites pass.

## Constraints
- Follow [AGENTS.md](../AGENTS.md) and [CLAUDE.md](../CLAUDE.md):
  - `paygate-api` and `paygate-core` stay JDK-only;
  - `paygate-protocol-mpp` does not depend on `paygate-core`;
  - comparisons are constant-time, and failures fail closed;
  - the V2 format stays byte-compatible with go-macaroon;
  - L402 uses padded standard base64 and MPP uses unpadded base64url.
- [SECURITY.md](../SECURITY.md) already says the release rejects "noncanonical credential encodings". I-01 and I-13 are therefore code that falls short of a documented guarantee, not a new policy.
- The review was made at `ef395bb` and its PoCs predate SR-01 to SR-24. Re-run each PoC on the base first. A PoC that no longer reproduces is recorded as resolved by earlier work, with evidence, and not reworked. I-12 is the likely candidate: `PaygateEndpointRegistry` now registers each mapped method separately.
- Pinning container images by digest (I-19) needs registry access. Record it as `blocked` if the registry is unreachable; never guess a digest. Draft PR #85 separately adds container scanning.
- Do not touch the PoC worktrees except to copy tests out of them.

## Decisions
- **I-01 (strict V2 and L402 decoding).**
  - The V2 parser rejects duplicate identifier fields, unknown field types and out-of-order fields, as go-macaroon does.
  - L402 base64 decoding requires padding and canonical trailing bits: re-encoding must reproduce the input exactly.
  - Rejected alternative: changing SECURITY.md to match the lenient parser. go-macaroon and Paygate's own issuer only produce canonical encodings, so leniency serves no client.
- **I-04 (cache after checks).** `L402Validator` inserts a credential into the cache only after every validator-side check, including paid price. The protocol-level `client_ip` binding check runs on every request, including cache hits. A failing variant can therefore only churn the cache, which is recorded as residual.
- **I-05 (preimage zeroization).** Zeroize the preimage in a `finally` that also covers a throwing price check.
- **I-06 (closed context).** `ChallengeContext.rootKeyBytes()` throws `IllegalStateException` after `close()`.
- **I-07 (filter without entry point).** `PaygateAuthenticationFilter` built without an entry point keeps the static price for static-priced endpoints. For an endpoint with a named pricing strategy it fails closed with 503, instead of charging the static price.
- **I-08 (authentication result type).** If the `AuthenticationManager` result is not an authenticated `PaygateAuthenticationToken`, the filter does not set the security context. It responds through the existing unexpected-failure 503 path.
- **I-09 (session persistence).** Documentation only. The Spring Security README states that payment authorities are per-request, and that the deprecated `requireExplicitSave(false)` makes them session-wide on unpaid routes. Rejected alternative: runtime detection, which Spring Security does not expose reliably.
- **I-10 (property spelling).** An unconditional guard fails startup when `paygate.enabled` or `paygate.protocols.l402.enabled` is set to anything other than `true` or `false` (case-insensitive). Spring binds `on`/`yes` as `true` but `@ConditionalOnProperty(havingValue="true")` treats them as off, so today a typo silently removes the payment wall. Rejected alternative: accepting `on`/`yes`, which hides the typo.
- **I-11 (handler exceptions).** Exceptions thrown downstream of a successful payment decision propagate unchanged in both modes. `chain.doFilter` moves out of the validation `try`, so an application exception reaches `/error` instead of becoming a 503 "Lightning unavailable".
- **I-12 (multi-method paid handlers).** Re-run the PoC on base. If 500 still reproduces, root-cause it and make each mapped method enforce independently. Otherwise record it as resolved by earlier work.
- **I-13 (MPP strictness).**
  - Challenge `id` and other base64url fields decode only canonically: no padding, zero trailing bits.
  - `MinimalJsonParser` accepts only `\u` followed by exactly four hex digits.
  - Validation rejects only a credential that is past expiry or beyond the maximum lifetime. Today's issuance-time minimum-lifetime check also runs at validation, which rejects a valid credential in its final second; that check is removed from validation.
  - The binding secret is rejected if it is a single repeated byte, and the README documents generating it with `openssl rand`. Rejected alternative: estimating entropy, which gives false confidence.
- **I-14.** Correct the `JcsSerializer` Javadoc to say UTF-16 code-unit order.
- **I-16 (invoice consistency).** Add a JDK-only BOLT11 payment-hash extractor to `paygate-core`. It does a bech32 decode with checksum verification and reads the `p` tagged field. Both backends reject a created invoice whose bolt11 payment hash differs from the returned hash, which fails closed with 503. Tests use BOLT11 specification vectors. Rejected alternative: accepting the limitation because the backend is trusted. A mismatch strands the client's payment and costs only a bounded parser.
- **I-17 (README examples).** Fix the [autoconfigure README](../paygate-spring-autoconfigure/README.md) examples that fail startup, and the "caller-selected" wording. An `ApplicationContextRunner` test mirrors the corrected example.
- **I-18 (redispatch price).** Store the trusted price together with its resolved endpoint. A redispatch reuses it only for an equal endpoint and otherwise re-resolves.
- **I-19 (prior Info items).**
  - Delete the stray root `META-INF/` actuator imports file; it is not on any module's resource path.
  - `installGitHook` re-stages only files that were already staged.
  - Pin `lightninglabs/lnd:v0.18.4-beta` and `lncm/bitcoind:v27.0` by multi-architecture index digest.
  - Correct the POM SCM and project URLs to the https `spring-boot-starter-paygate` repository.
- **I-20 (strict caveat mode): not planned.** Unknown caveats pass through by design to support delegation (prior I-4/L-6), and no deployment has asked for strict mode. The row stays `accepted` with this reason; a strict mode would need its own record if requested.
- **Documentation.** Update the ledger, SECURITY.md where a guarantee changes (I-01, I-11, I-13, I-16), and the CHANGELOG for the visible changes:
  - strict encodings;
  - the property-spelling startup failure;
  - application exceptions no longer mapped to 503;
  - MPP final-second acceptance;
  - invoice-hash mismatch returning 503;
  - `rootKeyBytes()` after `close()` throwing.

Approved: 2026-10-31 by Mark Hammond at plan revision b1d9c9ed8b04a1afae3141e693c04ea347e1c3c3.

## Tasks
- [x] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the PoCs in and record which still reproduce.
- [x] I-01, I-04, I-05, I-06: strict V2 and L402 decoding, cache ordering, preimage zeroization, closed-context throw. Check: inverted V2ParserEdgeCase and L402Price `c2`/`c3`; go-macaroon interop vectors still parse; module tests.
- [x] I-13, I-14: MPP canonical base64url, `\u` parsing, final-second validity, secret check, JCS Javadoc. Check: inverted MppBinding; a credential is valid until its expiry instant and rejected after it.
- [x] I-07, I-08, I-09, I-11: filter price fallback, result type, session docs, exception propagation. Check: inverted AuthResultTrust and LegacySessionPersistence, the latter documenting the remaining deprecated-API behaviour; a handler exception reaches `/error` in both modes.
- [x] I-10, I-12, I-17, I-18: property guard, multi-method handlers, README example, redispatch price. Check: inverted ProtocolSwitchSpelling and HiddenMethodOverride, or a recorded "resolved earlier" result; the README example context starts; a FORWARD to another endpoint re-resolves the price.
- [x] I-16: BOLT11 payment-hash extractor and backend checks. Check: specification vectors, a corrupted checksum is rejected, and a mismatching backend response gives 503 for LND and LNbits.
- [x] I-19: remove `META-INF`, fix `installGitHook`, pin digests, fix SCM URLs. Check: a scratch-repository run of the hook shows unstaged files stay unstaged; `docker manifest inspect` (or equivalent) confirms each pinned digest, or the item is recorded `blocked`; generated POM inspection.
- [x] Ledger, SECURITY.md, CHANGELOG and README updates. Check by inspection: every in-scope row is `implemented` or `accepted` with evidence, and the ledger links this record.
- [x] Final: run `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Implementation complete at `3bc5804`; awaiting fresh-context review.

Baseline at `4740301`: pass.
- `./gradlew check --continue --rerun-tasks` exited 0: 3,297 default-module tests, 0 failures or skips.
- `./gradlew :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` 145 and `securityTest` 66, 0 failures. XML timestamps (2026-10-31T21:00Z) confirm a fresh run.

WORK results on the merged branch:
- **Final required checks: pass.**
  - `./gradlew spotlessCheck check --rerun-tasks --continue` exited 0: 3,447 default-module tests, 0 failures or skips.
  - `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` 154 and `securityTest` 71, 0 failures or skips, with fresh XML timestamps (2026-10-31T21:21Z).
  - `git diff --check 4740301 HEAD` was clean.
- **PoCs, all reproduced at base before their fixes and now inverted regression tests:**
  - V2ParserEdgeCase: duplicate-identifier and unknown or out-of-order field cases. Its base64 case calls the JDK decoder directly; L402Price `c3` covers the Paygate path.
  - L402Price `c2` and `c3`.
  - MppBinding I-13 cases. The malleable-`id` case needed route and method in its setup after SR-02.
  - AuthResultTrust, LegacySessionPersistence, ProtocolSwitchSpelling and HiddenMethodOverride.
  - I-12 was **not** resolved by earlier work. Both per-method registrations matched, and the ambiguity guard threw.
  - No `SecReview*` files remain.
- **No PoC.** New tests cover I-05 (indirectly, through the cache tests), I-06, I-07, I-11, I-16, I-17 and I-18, and the I-13 expiry and secret cases. Agents confirmed that the I-07, I-08, servlet I-11, I-12 and I-13 tests fail against the pre-fix code.
- **I-16.** `Bolt11PaymentHashExtractorTest` uses 15 valid and 8 invalid vectors copied from BOLT #11 (`lightning/bolts` commit `191462825ae341f0a27098765c075f01ca9bc6c2`), plus constructed edge cases. `InvoiceHashMismatchIT` shows a 503 through the filter for both backends.
- **I-19.**
  - Index digests were resolved with `docker buildx imagetools inspect`: LND `sha256:e44e6296…` (OCI index: amd64, arm64) and bitcoind `sha256:324fec72…` (manifest list: amd64, arm/v7, arm64/v8). Both pinned references re-resolve, and `docker compose config -q` passes for all four files.
  - The hook script was rendered from `build.gradle.kts` and run in a scratch repository with a reformatting `gradlew`. Staged files, including a name with a space, were re-staged; an unrelated unstaged file stayed unstaged.
  - The generated `paygate-core` POM shows https `spring-boot-starter-paygate` URLs.

Points for the reviewer:
1. **I-06 implementation.** `ChallengeContext` is a record and cannot hold a closed flag without changing its public components. The owned root-key array therefore carries one private trailing state byte (open, closed or absent), read and written under the context monitor. The accessor and constructors are unchanged, and `equals`/`hashCode` already ignore the field. Reflective readers would see one extra byte.
2. **I-10.** The guard is an `EnvironmentPostProcessor` registered in a new `paygate-spring-autoconfigure` `META-INF/spring.factories`, so it also runs when Paygate is disabled. It requires exactly `true` or `false` (any case) **without trimming**, because `@ConditionalOnProperty` compares the raw value. It runs only under `SpringApplication`, not plain `ApplicationContextRunner`.
3. **I-12 goes slightly beyond the decision.** Fixing only the registry would let a credential paid for POST run the same handler as DELETE via `_method`. `PaygateHandlerInterceptor` now also requires the authorized policy to equal the dispatched method's policy, and needs both success attributes. The built-in filters set both.
4. **I-11.** Spring Security mode already propagated handler exceptions, so only servlet mode changed. The caveat verify-duration metric now excludes handler time. The reference `SecurityConfig` authenticates `/error`, so after a handler exception it answers 402 (no invoice minted). The example app's `src/main` is outside this scope, so the README documents permitting `/error` and the ERROR dispatch instead.
5. **I-18 contract change.** `REQUEST_PRICE_ATTRIBUTE` now holds the price with its endpoint config. New public `PaygateRequestPricingService.memoizedPrice` and `memoize` are used by every reader and writer, including the I-07 branch in `PaygateAuthenticationFilter`, which was integrated across the two parallel changes.
6. **I-13.**
   - A noncanonical `id` still parses and is rejected at binding verification (`INVALID`).
   - The README uses `openssl rand -hex 32`, the project's existing guidance.
   - The plan assumed an issuance-time lifetime check that does not exist, so zero or over-24-hour timeouts still issue unusable challenges.
7. **I-16 deliberately rejects uppercase invoices.** BOLT #11 allows them, but LND and LNbits return lowercase. The node signature is not verified.
8. **Limits.**
   - I-05 has no direct test.
   - The I-17 LNbits example is not compiled by a test.
   - The Docker stacks, live LND and LNbits, and GitHub runners were not exercised.
   - The example app's `eclipse-temurin:25` images remain unpinned, outside the I-19 decision.
   - `docs/macaroons-deep-dive.md` was added to `Review scope` because the I-17 wording lived there.

### Attempt 1
Reviewed revision: 503af2d9e983cfb7798c56646176a141826ee50a
Verdict: pass
Findings and dispositions: No findings; no rework required. The scoped diff implements each I-01 to I-20 decision, and the documentation claims match the implementation.
Verification:
- Scoped diff inspection (base `b1d9c9e` through `503af2d`, 70 files): pass. Only the task record is outside the declared scope, and the working tree is clean.
- `./gradlew spotlessCheck check --continue --rerun-tasks`: pass (BUILD SUCCESSFUL; 3,447 default-module tests, 0 failures, errors, or skips).
- `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration`: pass (`test` 154, `securityTest` 71; 0 failures, errors, or skips).
- `git diff --check 4740301 HEAD`: pass (clean).
- I-19 pinned digests: pass. `docker buildx imagetools inspect` reports LND index `sha256:e44e6296…` and bitcoind `sha256:324fec72…`, matching the compose files, and `docker compose config -q` passes for all four files.
- Generated POM: pass. `paygate-core` uses the https `spring-boot-starter-paygate` project and SCM URLs.
- Material limits: live LND/LNbits and the Docker stacks were not exercised; the example app's `eclipse-temurin:25` images remain unpinned; I-05 has no direct zeroization test; the I-17 LNbits example is not compiled by a test. These are recorded in the record and ledger and do not block the scoped claims.

## Outcome
Every remaining Info item from the 2026-10-18 security review is resolved on `fix/security-review-2026-10-18-info`. Review Attempt 1 passed at `503af2d`; the implementation is the `b1d9c9e..3bc5804` history. I-01 to I-19 are remediated, and I-20 (strict caveat mode) is closed as an accepted limitation, not planned.

**Credential encodings and validation (I-01, I-04, I-05, I-06, I-13, I-14).**
- V2 headers and L402 base64 must be canonical, as go-macaroon requires.
- MPP base64url and `\u` escapes are strict.
- MPP credentials are valid through their expiry instant.
- A repeated-byte MPP binding secret fails startup.
- A credential is cached only after the price check, and the preimage is zeroized when that check throws.
- `ChallengeContext.rootKeyBytes()` throws after `close()`, using a private trailing state byte.
- The `JcsSerializer` Javadoc is corrected.

**Spring integration (I-07, I-08, I-09, I-10, I-11, I-12, I-17, I-18).**
- The no-entry-point named-pricing case and untrusted `AuthenticationManager` results fail closed with 503.
- The session-persistence hazard is documented.
- Non-boolean `paygate.enabled` or `paygate.protocols.l402.enabled` values fail startup.
- Handler exceptions after payment reach error handling in servlet mode.
- Multi-method paid handlers enforce each method separately. The interceptor also blocks `_method` method switching.
- The README override examples start as written.
- Redispatch pricing is bound to its endpoint.

**Backends and build (I-16, I-19).**
- A JDK-only BOLT11 payment-hash check makes LND and LNbits reject an inconsistent created invoice with 503.
- LND and bitcoind images are digest-pinned.
- `installGitHook` re-stages only previously staged files.
- The POM URLs are corrected, and the stray root `META-INF` is removed.

**Verification.**
- All 8 PoCs reproduced at base and are inverted regression tests. I-12 needed a real fix rather than being resolved by earlier work.
- At Attempt 1:
  - `spotlessCheck check`: 3,447 tests (baseline 3,297).
  - Integration `test`: 154 (baseline 145).
  - Integration `securityTest`: 71 (baseline 66).
  - All passed, and `git diff --check` was clean.
- The BOLT #11 specification vectors pass.
- The pinned digests and the generated POM were confirmed independently by the reviewer.

**Documentation.**
- The [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md) now resolves every 2026-10-18 finding and Info item, and links this record.
- [SECURITY.md](../SECURITY.md) documents the strict encodings, the BOLT11 check, handler-exception propagation, redispatch pricing and the switch guard.
- [CHANGELOG.md](../CHANGELOG.md) is updated.
- The [Spring Security README](../paygate-spring-security/README.md), the [autoconfigure README](../paygate-spring-autoconfigure/README.md), the [MPP README](../paygate-protocol-mpp/README.md), the backend READMEs and [docs/macaroons-deep-dive.md](../docs/macaroons-deep-dive.md) are updated.

**Limits and remaining uncertainty.**
- Not exercised: live LND and LNbits, the Docker stacks, and GitHub runners.
- I-05 has no direct zeroization test.
- The I-17 LNbits example is not compiled by a test.
- The example app's `eclipse-temurin:25` images remain unpinned.
- The reference Spring Security app answers 402 on `/error` after a handler exception, because its chain authenticates `/error`.
- L402 clients that strip base64 padding are now rejected.
- BOLT11 node signatures are not verified, and uppercase invoices are rejected.

## Next action
Open a pull request from `fix/security-review-2026-10-18-info` to `main`, with the dispositions in [the ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md).
