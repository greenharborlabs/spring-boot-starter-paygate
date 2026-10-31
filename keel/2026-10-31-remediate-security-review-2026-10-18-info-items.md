# Remediate 2026-10-18 security review Info items
Status: planned
Base revision: pending approval
Review scope: paygate-api/src/**, paygate-core/src/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnd/src/**, paygate-lightning-lnbits/src/**, paygate-integration-tests/src/**, paygate-example-app-spring-security/src/test/**, build.gradle.kts, META-INF/**, integration-tests/docker-compose*.yml, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, SECURITY.md, CHANGELOG.md, README.md, */README.md

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

## Tasks
- [ ] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the PoCs in and record which still reproduce.
- [ ] I-01, I-04, I-05, I-06: strict V2 and L402 decoding, cache ordering, preimage zeroization, closed-context throw. Check: inverted V2ParserEdgeCase and L402Price `c2`/`c3`; go-macaroon interop vectors still parse; module tests.
- [ ] I-13, I-14: MPP canonical base64url, `\u` parsing, final-second validity, secret check, JCS Javadoc. Check: inverted MppBinding; a credential is valid until its expiry instant and rejected after it.
- [ ] I-07, I-08, I-09, I-11: filter price fallback, result type, session docs, exception propagation. Check: inverted AuthResultTrust and LegacySessionPersistence, the latter documenting the remaining deprecated-API behaviour; a handler exception reaches `/error` in both modes.
- [ ] I-10, I-12, I-17, I-18: property guard, multi-method handlers, README example, redispatch price. Check: inverted ProtocolSwitchSpelling and HiddenMethodOverride, or a recorded "resolved earlier" result; the README example context starts; a FORWARD to another endpoint re-resolves the price.
- [ ] I-16: BOLT11 payment-hash extractor and backend checks. Check: specification vectors, a corrupted checksum is rejected, and a mismatching backend response gives 503 for LND and LNbits.
- [ ] I-19: remove `META-INF`, fix `installGitHook`, pin digests, fix SCM URLs. Check: a scratch-repository run of the hook shows unstaged files stay unstaged; `docker manifest inspect` (or equivalent) confirms each pinned digest, or the item is recorded `blocked`; generated POM inspection.
- [ ] Ledger, SECURITY.md, CHANGELOG and README updates. Check by inspection: every in-scope row is `implemented` or `accepted` with evidence, and the ledger links this record.
- [ ] Final: run `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan.
