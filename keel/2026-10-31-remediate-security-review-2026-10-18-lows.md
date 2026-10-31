# Remediate 2026-10-18 security review Low findings
Status: working
Base revision: 817e8b9c8a6612fcd3d8cacf270ee20aba0c54d5
Review scope: paygate-core/src/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnbits/src/**, paygate-integration-tests/src/**, paygate-example-app-spring-security/src/test/**, integration-tests/scripts/setup-go-interop.sh, integration-tests/PLAYBOOK.md, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, SECURITY.md, CHANGELOG.md, README.md, */README.md

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
- [ ] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the in-scope PoCs in, confirm each still reproduces, then invert them.
- [ ] SR-12/I-02/I-03: issuer-prefix rule and 4-argument constructor fail-closed. Check: the inverted L402Price, HolderAppendedIssuerCaveat and ClientIpLegacySelfBind PoCs; built-in credentials still validate; a custom-issuer credential with price after `valid_until` gets `MISSING_PRICE_EVIDENCE`.
- [ ] SR-13: catch-all overlap. Check: the three shapes from the review WARN by default and fail startup under `FAIL`.
- [ ] SR-14/SR-15: limiter resolution and penalty attribute. Check: the inverted SpringSecurityRateLimit PoC; invalid credentials reach 429; backend 503s and first challenges are not penalized.
- [ ] SR-16: child-context guard. Check: the inverted real-Tomcat ServletRouting PoC (no 200 unpaid), and a root-only application still starts.
- [ ] SR-18/SR-19: realm check and `source` removal. Check: a cross-realm credential is INVALID under a shared secret; same-realm still validates; `source` is absent from the attributes.
- [ ] SR-20: rate limit before capture and pricing. Check: the inverted PreRateLimitWork PoC (pricing calls equal admitted requests) and no double token consumption.
- [ ] SR-21/I-15/SR-22: LNbits gate, resolution, config hygiene and response bounds. Check: the inverted LnbitsBoundary PoC; non-dev plaintext fails startup; a 2 MiB body and a slow body each fail within the deadline; `docker-compose-lnbits.yml` still starts (optional, needs Docker).
- [ ] SR-23/SR-24: revocation and temp directory. Check: the inverted FileRootKeyStore PoC; `shellcheck integration-tests/scripts/setup-go-interop.sh` (optional); an inspection that a pre-planted directory owned by another user is refused.
- [ ] Ledger, SECURITY.md, CHANGELOG and README updates. Check by inspection: every in-scope row is `implemented` with tests that exist, and the remaining Info rows reference the follow-up record.
- [ ] Final: run `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved plan, record each check result, and set `Status: review`.
