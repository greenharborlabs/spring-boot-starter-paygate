# Remediate 2026-10-18 security review Low findings
Status: closed
Base revision: 817e8b9c8a6612fcd3d8cacf270ee20aba0c54d5
Review scope: paygate-core/src/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnbits/src/**, paygate-integration-tests/src/**, paygate-example-app-spring-security/src/test/**, integration-tests/scripts/setup-go-interop.sh, integration-tests/scripts/run-go-interop-test.sh, paygate-api/src/main/java/com/greenharborlabs/paygate/api/PaymentCredential.java, integration-tests/PLAYBOOK.md, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, SECURITY.md, CHANGELOG.md, README.md, */README.md

## Goal
Close the deferred Low findings SR-12 to SR-16 and SR-18 to SR-24 from the [2026-10-18 security review](../reports/security-review-2026-10-18-claude-opus-5-5.md), together with the Info items that share their fixes: I-02 and I-03 (with SR-12), I-15 (with SR-21) and the slow-body part of SR-22. Also correct the [SECURITY.md](../SECURITY.md) "policy method" wording to "request method" ([review finding 1](2026-10-21-remediate-security-review-2026-10-18.md#attempt-1)).

Acceptance evidence:
- Each PoC used here comes from `/Users/mark/code/greenharborlabs/paygate-secreview`. Inverted, it passes as a regression test:
  - L402Price, HolderAppendedIssuerCaveat, ClientIpLegacySelfBind
  - RouteOverlapAnalyzer, VerifyRouteOverlapShapes
  - SpringSecurityRateLimit, ServletRouting, PreRateLimitWork
  - FileRootKeyStore, LnbitsBoundary
- SR-15, SR-18, SR-19, SR-21 and SR-24 have no PoC, so each gets a new test, or a script check for SR-24.
- In the [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md), each in-scope row is `implemented` and names its regression tests. The remaining Info rows name the follow-up record.
- Root `check` and the integration `test` and `securityTest` suites pass.

## Constraints
- Follow [AGENTS.md](../AGENTS.md) and [CLAUDE.md](../CLAUDE.md):
  - `paygate-api` and `paygate-core` stay JDK-only;
  - `paygate-protocol-mpp` does not depend on `paygate-core`;
  - secret comparisons are constant-time;
  - failures fail closed.
- The review report is gitignored. The ledger therefore stays the tracked source of each finding's substance, as in the [previous record](2026-10-21-remediate-security-review-2026-10-18.md).
- The review was made at `ef395bb`. Its line references predate SR-01 to SR-11 (merged in #94), so re-locate each mechanism on the current base before changing it. The PoCs assert *vulnerable* behaviour. Confirm that each still reproduces before inverting it.
- Built-in L402 issuance (`L402Protocol.formatChallenge`) emits `{svc}_valid_until` as its last caveat, after `{svc}_price_sats`. Holders can only append caveats.
- The LNbits Docker stacks already run with `SPRING_PROFILES_ACTIVE=dev` and `allow-plaintext-http=true`. A dev-profile gate must keep them starting.
- The SR-05 capture filter in Spring Security mode also runs before any rate limiting. The review reported SR-20 for servlet mode only.
- Do not touch the PoC worktrees except to copy tests out of them.

## Decisions
- **Scope.** This record covers the 12 Lows and the Info items that share their code (I-02, I-03, I-15). The other 16 Info items go to a separate follow-up record: I-01, I-04 to I-14, I-16 to I-19 (I-17 partially done), plus I-20's optional strict mode. Rejected alternative: all 31 items in one record. That is about 2.5× the size of the record just reviewed, mostly unrelated small changes.
- **SR-12, I-02, I-03 (issuer prefix).** The issuer prefix is the set of caveats before the first `{svc}_valid_until`. Holders can only append, so caveats in that prefix come from the issuer. Price evidence, mandatory boundaries and the `client_ip` binding check count only caveats in the issuer prefix.
  - A credential whose price or binding caveat appears only after that point is treated as lacking it (`MISSING_PRICE_EVIDENCE`, or binding absent).
  - The 4-argument `L402Validator` constructor fails closed on a price-less credential instead of accepting it at any price.
  - The [L402 README](../paygate-protocol-l402/README.md) custom-issuer section will require price caveats and require `{svc}_valid_until` to come after every issuer caveat.

  Rejected alternative: a separate issuer-caveat marker, which would change the wire format and break go-macaroon compatibility.
- **SR-13 (overlap analyzer).** `RouteOverlapAnalyzer` reports `POSSIBLE_OVERLAP` when one side has run out of segments and the other has exactly one remaining `**` or `{*x}` segment. The existing WARN/FAIL policy then applies. Rejected alternative: a request-time 500 when MVC selects an unprotected mapping. That changes the accepted L-14 shadowing behaviour and needs its own decision.
- **SR-14 (rate-limit resolution).** `PaygateAuthFailureRateLimitFilter` uses `endpointRegistry.resolve(request)`, the same resolution the authentication filter uses.
- **SR-15 (penalty classification).** `PaygateAuthenticationFilter` sets a request attribute when a presented credential fails for a client-fault reason: 400 for malformed, 402 for invalid or expired. The rate-limit filter penalizes on that attribute instead of on 401/503.
  - Backend-outage 503s and first challenges with no credential are not penalized.
  - The [Spring Security README](../paygate-spring-security/README.md) and SECURITY.md are aligned with this behaviour.
- **SR-16 (child dispatcher contexts).** A root-context listener receives `ContextRefreshedEvent`, which child contexts propagate to their parent. When the event comes from a context other than the root, the listener checks that context's own beans for `@PaymentRequired` on a class or method, and throws if it finds any. The child servlet then fails to initialise, which is fail closed.

  Bean scanning is chosen over scanning handler mappings because default-strategy dispatchers register no `HandlerMapping` beans. Multi-dispatcher paid routes are documented as unsupported. Rejected alternative: documentation alone, which leaves 200-unpaid as the failure mode.
- **SR-18 (MPP realm).**
  - `MppProtocol` gains a constructor taking `serviceName`, and auto-configuration passes `paygate.service-name`.
  - Validation rejects a challenge whose realm differs, using a constant-time comparison.
  - The [MPP README](../paygate-protocol-mpp/README.md) requires one binding secret per service.

  Existing constructors keep the current behaviour and gain a Javadoc note; they will not be deprecated now.
- **SR-19 (MPP `source`).** Remove `source` from the authentication attributes in `PaygateAuthenticationProvider` and `PaygateAuthenticationToken`. `PaymentCredential.source()` stays, documented as unverified client input. Rejected alternative: renaming it to an `unverified.source` attribute, which keeps an unauthenticated value in the authority path for no current consumer.
- **SR-20 (rate limit before work).** In servlet mode, `PaygateSecurityFilter` acquires the rate-limit token before body capture and pricing, and passes `rateLimitAlreadyConsumed` on so no request consumes two tokens. For Spring Security mode, the capture-before-rate-limit ordering (SR-05 filter) is recorded as residual risk bounded by `max-bytes` and is not changed here.
- **SR-21, I-15 (LNbits plaintext).**
  - LNbits plaintext opt-in goes through `DevelopmentSafetyPolicy.validateProfiles`, as LND does.
  - `*.localhost` names are accepted only when every resolved address is loopback.
  - The `lnbits` Compose hostname exception stays, now dev-gated.
  - `LnbitsConfig` `equals`/`hashCode` exclude the API key, and `toString` and error messages strip userinfo from `baseUrl`.
- **SR-22 (LNbits response bounds).** Use a bounded body handler capped at 1 MiB, plus an overall deadline equal to the request timeout covering headers and body. Exceeding either is a backend failure, which maps to 503.
- **SR-23 (root-key revocation).** `revokeRootKey` deletes a regular, non-link key file whatever its mode. It throws for symlinks and special files instead of returning silently, so revocation never quietly does nothing.
- **SR-24 (interop temp directory).** `setup-go-interop.sh` uses `mktemp -d` by default. An explicit `GO_INTEROP_DIR` must be owned by the current user and must not be group- or world-writable.
- **Documentation.** Update the ledger rows. Correct the SECURITY.md "policy method" wording. Add CHANGELOG entries for the visible behaviour changes:
  - L402: price and binding caveats after `{svc}_valid_until` are ignored, and the 4-argument validator fails closed on price-less credentials.
  - MPP: realm mismatch is rejected, and the `source` attribute is removed.
  - Spring Security: the new penalty classification.
  - LNbits: plaintext now requires a dev profile, and responses are capped at 1 MiB.
  - Startup or initialisation fails for `@PaymentRequired` in a child dispatcher context.

Approved: 2026-10-31 by Mark Hammond at plan revision 817e8b9c8a6612fcd3d8cacf270ee20aba0c54d5.

## Tasks
- [x] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the in-scope PoCs in, confirm each still reproduces, then invert them.
- [x] SR-12/I-02/I-03: issuer-prefix rule and 4-argument constructor fail-closed. Check: the inverted L402Price, HolderAppendedIssuerCaveat and ClientIpLegacySelfBind PoCs; built-in credentials still validate; a custom-issuer credential with price after `valid_until` gets `MISSING_PRICE_EVIDENCE`.
- [x] SR-13: catch-all overlap. Check: the three shapes from the review WARN by default and fail startup under `FAIL`.
- [x] SR-14/SR-15: limiter resolution and penalty attribute. Check: the inverted SpringSecurityRateLimit PoC; invalid credentials reach 429; backend 503s and first challenges are not penalized.
- [x] SR-16: child-context guard. Check: the inverted real-Tomcat ServletRouting PoC (no 200 unpaid), and a root-only application still starts.
- [x] SR-18/SR-19: realm check and `source` removal. Check: a cross-realm credential is INVALID under a shared secret; same-realm still validates; `source` is absent from the attributes.
- [x] SR-20: rate limit before capture and pricing. Check: the inverted PreRateLimitWork PoC (pricing calls equal admitted requests) and no double token consumption.
- [x] SR-21/I-15/SR-22: LNbits gate, resolution, config hygiene and response bounds. Check: the inverted LnbitsBoundary PoC; non-dev plaintext fails startup; a 2 MiB body and a slow body each fail within the deadline; `docker-compose-lnbits.yml` still starts (optional, needs Docker).
- [x] SR-23/SR-24: revocation and temp directory. Check: the inverted FileRootKeyStore PoC; `shellcheck integration-tests/scripts/setup-go-interop.sh` (optional); an inspection that a pre-planted directory owned by another user is refused.
- [x] Ledger, SECURITY.md, CHANGELOG and README updates. Check by inspection: every in-scope row is `implemented` with tests that exist, and the remaining Info rows reference the follow-up record.
- [x] Final: run `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Implementation complete at `f4de1ba`; Attempt 1 rework complete at `0e9822b`; awaiting fresh-context review.

Baseline at `817e8b9`: pass. `./gradlew check --continue --rerun-tasks` exited 0 with 3,141 default-module tests and no failures or skips. `./gradlew :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` ran 96 tests and `securityTest` ran 66, all passing, with fresh XML timestamps from 2026-10-31T13:22Z. A stale 2026-09-24 `wavelengthSpike` result in the build directory was not part of this run and was excluded.

WORK results on the merged branch:
- **Final required checks: pass.**
  - `./gradlew spotlessCheck check --rerun-tasks --continue` exited 0: 3,294 default-module tests, 0 failures or skips.
  - `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` 145, `securityTest` 66, 0 failures or skips. XML timestamps (2026-10-31T13:43Z) confirm a fresh run.
  - `git diff --check 817e8b9 HEAD` was clean.
- **PoCs.** Each of the 10 in-scope PoCs reproduced at base before its fix and was converted into an inverted, renamed regression test; no `SecReview*` files remain. The [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md) maps each finding to its tests. Two L402Price PoC cases cover out-of-scope Info items (I-04 caching, I-01 unpadded base64) and were left out of the regression test; they still reproduce in the PoC worktree.
- **SR-24.** `shellcheck` on both interop scripts was clean. A scratch run refused group- or world-writable, foreign-owned, symlinked, dangling and non-directory `GO_INTEROP_DIR` targets and accepted an owned 0700 directory; a real Go build succeeded in the default `mktemp` mode. macOS only.
- **Skipped (optional):** the Docker LNbits stack was not started because parallel work bound the same fixed host ports. A context-runner test starts the `lnbits` host under the `dev` profile instead.

Points for the reviewer:
1. **Scope additions within the decisions.** `PaymentCredential.source()` gained Javadoc only (SR-19), and `run-go-interop-test.sh` also passed the shared `/tmp` path, so its default moved to `mktemp` (SR-24). Both were added to `Review scope`.
2. **SR-12.** `IssuerCaveatPrefix` is a new public class in paygate-core. `MacaroonVerifier.verifyCaveats` drops `{svc}_price_sats` from verified attributes unless the issuer prefix contains a price caveat. With no backend, a price-less credential on a `ROUTE_STABLE` route now gets `MISSING_PRICE_EVIDENCE` (402) rather than `EVIDENCE_UNAVAILABLE`. Nineteen existing tests that minted price-less credentials or signed issuer caveats after `valid_until` were updated to sign a price first; no assertion was weakened.
3. **SR-15 and servlet mode.** Spring Security mode now penalizes 400, 402 and unclassified 401 only. Servlet mode still penalizes every presented-credential `PaymentValidationException`, including a validation-time `UNAVAILABLE` 503. That is pre-existing and outside the SR-15 decision, so SECURITY.md now states the behaviour per mode instead of claiming outages are never penalized.
4. **SR-16.** In Boot 4 a `load-on-startup` child dispatcher failure fails application startup; a lazy one returns 500. `PaygateChildContextGuard` mirrors `AbstractHandlerMethodMapping` candidate detection, skipping null or unresolvable types (catching `Throwable`, as MVC does) and scoped targets, so Spring Cloud child contexts are unaffected.
5. **SR-18.** Only the full 5-argument `MppProtocol` constructor binds the realm; a shorter `(secret, serviceName)` overload would make `new MppProtocol(secret, null)` ambiguous.
6. **SR-20.** Two SR-09 cardinality tests relied on pricing running before rate limiting and now admit their requests; their bounded-tag assertions are unchanged.
7. **SR-21/SR-22.** A package-private `LnbitsConfig` constructor and `HostResolver` seam make resolution testable. A malformed-URI error no longer carries `URISyntaxException` as its cause, because its message repeats userinfo. Slow-body tests assert elapsed-time bounds.
8. **Limits.**
   - Live LND and LNbits, the Docker LNbits stacks and GitHub runners were not exercised.
   - Residual risks are recorded per row in the ledger, including Spring Security mode capture before rate limiting (SR-20) and LNbits DNS re-resolution on connect (SR-21).

### Attempt 1
Reviewed revision: 3ce817c09745115abc64660f54a5b76816fced37
Verdict: rework

Findings and dispositions:
1. **SR-12/I-02 is still exploitable in Spring Security mode (security; acceptance failure).** `PaygateAuthenticationProvider.authenticateL402` ([`PaygateAuthenticationProvider.java:128`](../paygate-spring-security/src/main/java/com/greenharborlabs/paygate/spring/security/PaygateAuthenticationProvider.java#L128)) accepts when `clientAddressBindingEnabled && clientAddressCaveatCount(credential.macaroon()) != 1` passes. That counts every `client_ip` caveat, not only those in the issuer prefix. L402 credentials in Spring Security mode do not pass through `L402Protocol.validate`, so the new `hasIssuerClientAddressBinding` check ([`L402Protocol.java:214`](../paygate-protocol-l402/src/main/java/com/greenharborlabs/paygate/protocol/l402/L402Protocol.java#L214)) never runs on this path. A credential minted while binding was disabled plus a holder-appended `client_ip` equal to the requesting address therefore satisfies the count and binds successfully with `paygate.protocols.l402.client-address-binding-enabled=true`. The check predates this record (this diff only removes `source` from this file), but the record and its documentation now assert a guarantee this path does not provide: ledger `I-02` says "remediated", [`SECURITY.md:88`](../SECURITY.md#L88) says "the same key appended after it is treated as absent", [`paygate-protocol-l402/README.md`](../paygate-protocol-l402/README.md#L340) says "a legacy unbound credential stays rejected after its holder appends one", and [`CHANGELOG.md:51`](../CHANGELOG.md#L51) says a holder "cannot self-bind a legacy unbound credential with `client_ip`". No test covers the provider path: `IssuerPrefixRegressionTest` exercises `L402Protocol`, and `ClientAddressBindingIT` only presents credentials the issuer already bound. Rework: enforce the issuer prefix in the provider (reuse `IssuerCaveatPrefix` or `L402Protocol.hasIssuerClientAddressBinding` instead of a raw count), add a provider-level regression test for a legacy unbound credential with an appended `client_ip`, and keep the docs and ledger aligned with the resulting guarantee.
2. **The deferred Info rows do not name the follow-up record (acceptance gap).** Acceptance requires that "the remaining Info rows name the follow-up record", but the deferred rows in [`docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md`](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md#L56) only say `planned`; only the section introduction at line 12 mentions "a separate follow-up Keel record". Add a concrete reference (path or title) to each deferred row, or amend the acceptance claim. Low severity; no functional impact.

Reviewed clean: SR-13 zero-segment catch-all logic; SR-14/SR-15 endpoint resolution and client-fault penalty; SR-16 child-context guard (including the real-Tomcat test); SR-18 realm binding (constant-time, non-null realm); SR-19 `source` removal; SR-20 admission before capture and pricing, and the no-double-token paths; SR-21/I-15 dev gate and config hygiene; SR-22 body and deadline bounds; SR-23 revocation; SR-24 private temp directory. No other correctness, data-loss or unnecessary-complexity findings.

Verification:
- Preconditions (status `review`, full base revision, concrete `Review scope` excluding the record, clean tree, scoped paths clean): pass.
- Scoped diff `817e8b9..3ce817c`: pass. `HEAD` changes only this record (38 insertions, 15 deletions), so the reviewed implementation is the `ca1fdb4`..`f4de1ba` history.
- `./gradlew spotlessCheck check --rerun-tasks --continue`: pass, exit 0, `BUILD SUCCESSFUL in 1m 21s`; 3,294 module tests, 0 failures, errors or skips across 492 result XML files.
- `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration`: pass, exit 0; `test` 145 tests and `securityTest` 66 tests, 0 failures or skips.
- `git diff --check 817e8b9 HEAD`: pass (clean).
- `shellcheck` and `bash -n` on `integration-tests/scripts/setup-go-interop.sh` and `integration-tests/scripts/run-go-interop-test.sh`: pass.
- Acceptance criteria (inverted PoCs, ledger rows, documentation claims, final checks): fail because of findings 1 and 2; the remaining acceptance items pass.
- Docker LNbits stack: skipped (optional per the record). Live LND, LNbits, Docker stacks and GitHub runners were not exercised, matching the record's stated limits.

Rework after Attempt 1 (implementation at `0e9822b`):
1. **Finding 1, fixed in `c0c3929`.** The issuer-prefix binding rule moved into `IssuerCaveatPrefix.hasIssuerClientAddressBinding`: exactly one `client_ip`, and it must lie before the first `{svc}_valid_until`. Keys are compared exactly, as both previous counters did. `L402Protocol` and `PaygateAuthenticationProvider` both call it, so the Spring Security path no longer accepts a holder-appended `client_ip` on a legacy unbound credential. The provider fails binding closed when it has no service name. `ProviderIssuerClientAddressBindingRegressionTest` (3 tests) mints built-in-order credentials and covers three cases: an unbound credential with an appended matching `client_ip` is rejected INVALID, an issuer-bound credential authenticates but rejects an added second `client_ip`, and an unbound credential authenticates with binding disabled. Against the previous raw count, the self-binding test fails (1 of 3). The documented guarantees in SECURITY.md, the L402 README and the CHANGELOG now hold in both modes, and the SR-12 and I-02 ledger rows cite the new test.
2. **Finding 2, fixed in `0e9822b`.** Each of the 17 Info rows not remediated here (I-01, I-04 to I-14, I-16 to I-20) now says it is tracked in the follow-up record *Remediate 2026-10-18 security review Info items*. That record does not exist yet, so it is named by title rather than path.

Rework verification:
- `./gradlew spotlessCheck check --rerun-tasks --continue`: pass, exit 0. 3,297 default-module tests, 0 failures or skips.
- `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration`: pass, exit 0. `test` 145 and `securityTest` 66, 0 failures, with fresh XML timestamps (2026-10-31T14:09Z).
- `git diff --check 817e8b9 HEAD`: pass (clean).

### Attempt 2
Reviewed revision: ba86f7ed44bd4236dbea140e54bdd65033b1f79b
Verdict: pass
Findings and dispositions: No findings; no rework required.

Attempt 1 finding 1 (SR-12/I-02 provider bypass) is fixed. `IssuerCaveatPrefix.hasIssuerClientAddressBinding` requires exactly one `client_ip` and that it lies before the first `{svc}_valid_until`; both `L402Protocol` and `PaygateAuthenticationProvider` call it, and the provider fails binding closed without a service name. The exact-key comparison for `client_ip` matches the previous counting semantics and, because verification evaluates the issuer-signed caveat independently, the padded-key case does not create a bypass. `ProviderIssuerClientAddressBindingRegressionTest` covers the legacy-unbound plus holder-appended `client_ip` case, the issuer-bound plus second `client_ip` case, and binding disabled; all three pass. The SECURITY.md, L402 README, CHANGELOG and SR-12/I-02 ledger claims now match both modes.

Attempt 1 finding 2 is fixed. Each deferred, partial or accepted Info row now names the follow-up record *Remediate 2026-10-18 security review Info items* (title only, as the record does not exist yet), satisfying the acceptance clause.

The rework diff since Attempt 1 (`3ce817c..0e9822b`) was reviewed clean: no correctness, security, data-loss, unnecessary-complexity or documentation-drift issues. No changes were made to the reviewed implementation.

Verification:
- Preconditions (status `review`, full base revision, concrete `Review scope` excluding the record, clean tree, scoped paths clean): pass. `HEAD` changes only this record, so the reviewed implementation is the `817e8b9..0e9822b` history.
- Scoped diff `817e8b9..HEAD`: pass.
- `./gradlew spotlessCheck check --rerun-tasks --continue`: pass, exit 0, `BUILD SUCCESSFUL in 1m 24s`; 3,297 default-module tests across 493 result XMLs, 0 failures, errors or skips.
- `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration`: pass, exit 0; `test` 145 tests and `securityTest` 66 tests, 0 failures, errors or skips, with fresh result XMLs from 2026-10-31T17:20Z.
- `git diff --check 817e8b9 HEAD`: pass (clean).
- Docker LNbits stack, live LND and LNbits, and GitHub runners: not exercised, matching the record's stated limits.

## Outcome
The 12 deferred Low findings (SR-12 to SR-16, SR-18 to SR-24) and Info items I-02, I-03 and I-15 are remediated on `fix/security-review-2026-10-18-lows`. Review Attempt 2 passed at `ba86f7e`; the implementation is the `817e8b9..0e9822b` history.

**L402 issuer evidence (SR-12, I-02, I-03).** `IssuerCaveatPrefix` counts price evidence, required boundaries and `client_ip` binding only from caveats before the first `{svc}_valid_until`. A holder-appended price is never reported as verified. The 4-argument `L402Validator` rejects price-less credentials.
- Attempt 1 found that the Spring Security provider still counted every `client_ip`. Both paths now share `hasIssuerClientAddressBinding`, which the provider-level regression test covers.

**Routing and rate limiting (SR-13, SR-14, SR-15, SR-20).**
- Zero-segment catch-all overlaps are reported.
- The Spring Security rate limiter resolves endpoints like the authentication filter.
- The penalty applies only to client-fault failures.
- In servlet mode, the rate-limit token is acquired before body capture and pricing.

**Child dispatcher contexts (SR-16).** `@PaymentRequired` in a child context now fails closed. `PaygateChildContextGuard` mirrors MVC handler detection, so Spring Cloud child contexts are unaffected.

**MPP (SR-18, SR-19).** The realm is bound to `paygate.service-name` through a new constructor. The unverified `source` is no longer an authentication attribute.

**LNbits (SR-21, I-15, SR-22).**
- Plaintext requires a dev profile.
- `*.localhost` names must resolve to loopback.
- The API key is excluded from `equals` and `hashCode`, and userinfo is masked.
- Responses are capped at 1 MiB under a single deadline.

**Developer safety (SR-23, SR-24).**
- Revocation deletes key files whatever their mode and throws for symlinks and special files.
- The Go interop scripts use a private `mktemp` directory.

**Verification.** All 10 in-scope PoCs reproduced at base and are now inverted regression tests. At Attempt 2:
- `spotlessCheck check`: 3,297 tests passed.
- Integration `test` (145) and `securityTest` (66): passed.
- `git diff --check`: clean.
- Baseline at `817e8b9`: 3,141 / 96 / 66 tests passed.

**Documentation.**
- The [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md) marks every in-scope row `implemented` with its tests.
- [SECURITY.md](../SECURITY.md) has the "request method" correction, the issuer-prefix rule, and the penalty behaviour stated per mode.
- [CHANGELOG.md](../CHANGELOG.md) has Security and Changed entries.
- The module READMEs ([L402](../paygate-protocol-l402/README.md), [MPP](../paygate-protocol-mpp/README.md), [Spring Security](../paygate-spring-security/README.md), [autoconfigure](../paygate-spring-autoconfigure/README.md), [LNbits](../paygate-lightning-lnbits/README.md)) and the [playbook](../integration-tests/PLAYBOOK.md) are updated.

**Limits and remaining uncertainty.**
- Not exercised: the Docker LNbits stack (optional, skipped), live LND and LNbits, and GitHub runners.
- SR-24 was checked on macOS only.
- Accepted residual risks, recorded per row in the ledger:
  - Spring Security mode still captures bodies before rate limiting.
  - Servlet mode still penalizes validation-time `UNAVAILABLE` 503s.
  - The older `MppProtocol` constructors do not check the realm.
  - LNbits re-resolves DNS on connect.
  - The slow-body tests depend on timing.
- Deferred: the other Info items (I-01, I-04 to I-14, I-16 to I-19, and I-20's optional strict mode) go to the follow-up record *Remediate 2026-10-18 security review Info items*, which does not exist yet.

## Next action
Open a pull request from `fix/security-review-2026-10-18-lows` to `main` with the dispositions in [the ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md).
