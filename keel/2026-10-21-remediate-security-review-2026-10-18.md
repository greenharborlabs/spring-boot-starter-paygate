# Remediate 2026-10-18 security review High and Medium findings
Status: working
Base revision: 84239cabe96fa2df6b3d3d27c1604cbcabba32e2
Review scope: paygate-core/src/main/**, paygate-core/src/test/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnd/src/main/**, paygate-integration-tests/src/**, paygate-example-app/src/test/**, paygate-example-app-spring-security/src/test/**, .github/workflows/snapshot.yml, .github/workflows/release.yml, .github/workflows/security-suite.yml, .github/requirements/**, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, docs/AGENT-MACAROON-DELEGATION.md, SECURITY.md, CHANGELOG.md, README.md, PLAYBOOK.md, */README.md

## Goal
Close the three High and eight Medium findings (SR-01 to SR-11) plus SR-17, which shares SR-01's fix, from the [2026-10-18 security review](../reports/security-review-2026-10-18-claude-opus-5-5.md) on branch `fix/security-review-2026-10-18`. Acceptance evidence:
- Each finding's PoC from the review worktree, inverted, passes as a regression test (`400`/`402`/`INVALID`/startup failure, handler not invoked). The worktree is `/Users/mark/code/greenharborlabs/paygate-secreview`, which holds 34 uncommitted `SecReview2026_10_18_*` files.
- A tracked disposition ledger records every SR-01 to SR-24 finding and I-01 to I-20 item. Rows remediated here are `implemented` with evidence; the rest are `planned` or `deferred`.
- Root `check` and the integration `test` and `securityTest` suites pass.

## Constraints
- Follow [AGENTS.md](../AGENTS.md) and [CLAUDE.md](../CLAUDE.md): `paygate-api` and `paygate-core` stay JDK-only, `paygate-protocol-mpp` must not depend on `paygate-core`, secret comparisons are constant-time, and the system fails closed.
- `reports/` is gitignored, so the review is not tracked. The new ledger must carry each finding's substance, following the format of the [Kimi Medium ledger](../docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md).
- The review was made at `ef395bb`, which is this branch's starting `HEAD`, so its line references are current. Its PoCs assert *vulnerable* behaviour and use real Tomcat for path cases. Keep that: MockMvc skips container URI handling.
- Several statements in the docs are false today and must be corrected:
  - [SECURITY.md](../SECURITY.md) "exact request identity" (MPP).
  - The [Kimi M-2 ledger row](../docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md) "covered price".
  - [paygate-protocol-mpp/README.md](../paygate-protocol-mpp/README.md), lines 11–12, which says MPP binds route and capability.
  - The LND README's `invoice.macaroon` guidance, which lacks `info:read`.
- Hash-locking the CI pip installs needs network access to resolve hashes. Confirming that `SONATYPE_*`/`GPG_*` are environment-scoped secrets is a live GitHub check that Keel work cannot perform.
- There are no unrelated dirty paths. Do not touch the PoC worktrees except to copy tests out of them.

## Decisions
- **Scope.** This record covers SR-01 to SR-11 and SR-17. Defer the other Lows (SR-12 to SR-16, SR-18 to SR-24) and the Info items to a follow-up Keel record, and list them in the ledger as `deferred`. Rejected alternative: one record covering all 44 items, which is too large for a single review.
- **SR-01/03/04/17 (path identity).** `ApplicationRelativeRequestResolver` rejects with 400, through the existing malformed-URI path, any request whose once-decoded segment:
  - is `.`, `..` or empty (`//`); a trailing slash stays allowed;
  - still contains `%`;
  - contains `\`;
  - carries `;` path parameters.

  The resolver then joins `valueToMatch()` with no further decoding. Manual routes are matched on `ServletRequestPathUtils.parse(request).pathWithinApplication()`. `PaygateHandlerInterceptor` adds a backstop for non-`HandlerMethod` handlers: it returns 500 when a paid manual route has no recorded successful decision. `PathNormalizer` remains for caveat-pattern normalization only.

  Rejection applies to every request the filter sees, not only paid ones. A lookup-time check alone cannot work, because the bypass happens before any policy is found. This matches `StrictHttpFirewall` and is a visible behaviour change that goes in the CHANGELOG. Rejected alternative: keep the decoding and also match the raw path, which leaves two divergent identities.
- **SR-02 (MPP binding).** Bind the canonical route, policy method and capability (or `~`) into the challenge's already-HMAC-bound server `opaque` JCS map. `MppProtocol.validate` then:
  - compares those fields in constant time with `REQUEST_ROUTE`, `REQUEST_METHOD` and `REQUESTED_CAPABILITY`;
  - parses the bound `request.amount` and `currency` strictly and requires `amount ≥ CURRENT_PRICE_SATS`.

  A missing or mismatched field means INVALID. Remove the `DefaultCapabilityResolver` MPP request-metadata fallback.

  This keeps the 7-slot HMAC layout and RFC 9530 digest semantics unchanged. Credentials issued before the upgrade fail closed, bounded by expiry (at most 24 h, documented in the CHANGELOG). Rejected alternatives:
  - folding these fields into the digest with a version bump, which overloads the content-digest meaning;
  - a price check alone, which leaves equal-price handlers switchable.
- **SR-05 (form bodies).** In Spring Security mode, register a Paygate body-capture filter for paid routes at `HIGHEST_PRECEDENCE+10`, ahead of `FormContentFilter`. It enforces `max-bytes` and replays the captured body downstream. Return 400 when the declared `Content-Length` is greater than 0 and fewer bytes were observed. Document `spring.mvc.formcontent.filter.enabled=false` as interim guidance.
- **SR-06/07 (client address).**
  - `ClientIpResolver` joins all `X-Forwarded-For` header lines in order.
  - `resolve` stops at the first unparseable entry and returns the nearest trusted hop.
  - `resolveBindingAddress` returns empty when the lines are ambiguous.
  - Startup fails when address binding is enabled without a `client_ip` verifier.
- **SR-08 (chain guard).** Give the probe a non-null `HttpServletMapping` and a parsed `RequestPath`, and turn matcher exceptions into explicit startup errors. Check coverage per paid registration: derive a sample path for each, find the first matching chain, and require rate-limit, then Paygate, then authorization filters in that chain. Reuse the SR-01 interceptor backstop.
- **SR-09 (meter cardinality).** Map the `method` tag and log field to the standard methods, with `_other` for anything else.
- **SR-10 (Lightning credentials).** Docs and metadata only:
  - document a baked LND macaroon with `invoices:read invoices:write info:read` and the LNbits invoice key;
  - remove "admin" guidance.

  The optional startup warning is deferred.
- **SR-11 (CI).**
  - Remove `actions/cache` and setup-gradle caching from the snapshot and release publishing jobs.
  - Install semgrep and zizmor with `pip install --require-hashes -r .github/requirements/<tool>.txt`.
  - Record the secret-scope check as a manual ledger action.
- **Documentation.** Add `docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md` and link it from SECURITY.md. Correct the false statements listed under Constraints. Update CHANGELOG, the MPP/autoconfigure/security READMEs and [docs/AGENT-MACAROON-DELEGATION.md](../docs/AGENT-MACAROON-DELEGATION.md), which needs a path-normalization note.

Approved: 2026-10-21 by Mark Hammond at plan revision 84239cabe96fa2df6b3d3d27c1604cbcabba32e2.

## Tasks
- [ ] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the relevant PoCs into the branch, confirm they still reproduce, then invert them.
- [ ] SR-01/03/04/17: resolver rejection, manual-route matching on `RequestPath`, and the non-`HandlerMethod` interceptor backstop. Check: real-Tomcat regression for every vector in the review (servlet mode, plus Spring Security with a relaxed firewall), and module tests.
- [ ] SR-02: opaque route/method/capability binding, the amount check and removal of the capability fallback. Check: the four inverted MPP PoCs, plus equal-price/different-route and capability-cache-miss cases.
- [ ] SR-05: early body-capture filter and the length-mismatch 400. Check: Spring Security form PUT/POST replay gets 402, oversized forms get 400, pricing sees the real size, and the POST handler still receives its parameters.
- [ ] SR-06/07: XFF joining and stop-on-unparseable; binding startup guard. Check: inverted resolver and IT PoCs, plus an `ApplicationContextRunner` startup failure.
- [ ] SR-08/09: chain-guard probe and per-route coverage; bounded method tag. Check: negated lambda/regex chains fail startup, `/api/**` plus catch-all starts, and 1000 random methods give at most 9 tags.
- [ ] SR-10/11: least-privilege docs and metadata; CI cache removal and hash-locked pip. Check: inspect the workflow diff, and run `zizmor .github/workflows` (optional, needs a local install).
- [ ] Ledger, SECURITY.md, CHANGELOG and README corrections. Check: by inspection, every SR and I item has a row, and each `implemented` row names its regression test.
- [ ] Final: `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Working. Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Implement the approved tasks.
