# Remediate 2026-10-18 security review High and Medium findings
Status: closed
Base revision: 84239cabe96fa2df6b3d3d27c1604cbcabba32e2
Review scope: paygate-api/src/**, paygate-core/src/**, paygate-protocol-l402/src/**, paygate-protocol-mpp/src/**, paygate-spring-autoconfigure/src/**, paygate-spring-security/src/**, paygate-lightning-lnd/src/main/**, paygate-integration-tests/src/**, paygate-integration-tests/build.gradle.kts, paygate-example-app/src/**, paygate-example-app-spring-security/src/test/**, .github/workflows/snapshot.yml, .github/workflows/release.yml, .github/workflows/security-suite.yml, .github/requirements/**, docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md, docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md, docs/AGENT-MACAROON-DELEGATION.md, integration-tests/PLAYBOOK.md, SECURITY.md, CHANGELOG.md, README.md, */README.md

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
- [x] Baseline: run `./gradlew check` and `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` at base and record the results. Copy the relevant PoCs into the branch, confirm they still reproduce, then invert them.
- [x] SR-01/03/04/17: resolver rejection, manual-route matching on `RequestPath`, and the non-`HandlerMethod` interceptor backstop. Check: real-Tomcat regression for every vector in the review (servlet mode, plus Spring Security with a relaxed firewall), and module tests.
- [x] SR-02: opaque route/method/capability binding, the amount check and removal of the capability fallback. Check: the four inverted MPP PoCs, plus equal-price/different-route and capability-cache-miss cases.
- [x] SR-05: early body-capture filter and the length-mismatch 400. Check: Spring Security form PUT/POST replay gets 402, oversized forms get 400, pricing sees the real size, and the POST handler still receives its parameters.
- [x] SR-06/07: XFF joining and stop-on-unparseable; binding startup guard. Check: inverted resolver and IT PoCs, plus an `ApplicationContextRunner` startup failure.
- [x] SR-08/09: chain-guard probe and per-route coverage; bounded method tag. Check: negated lambda/regex chains fail startup, `/api/**` plus catch-all starts, and 1000 random methods give at most 9 tags.
- [x] SR-10/11: least-privilege docs and metadata; CI cache removal and hash-locked pip. Check: inspect the workflow diff, and run `zizmor .github/workflows` (optional, needs a local install).
- [x] Ledger, SECURITY.md, CHANGELOG and README corrections. Check: by inspection, every SR and I item has a row, and each `implemented` row names its regression test.
- [x] Final: `./gradlew spotlessCheck check`, `./gradlew :paygate-integration-tests:test :paygate-integration-tests:securityTest -Pintegration` and `git diff --check`, then record the results.

## Review
Implementation complete at `add37b7`; awaiting fresh-context review.

Baseline at `84239ca`: pass. `./gradlew check --continue --rerun-tasks` exited 0 with 2,921 default-module tests and no failures or skips. `./gradlew :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` ran 44 tests and `securityTest` ran 31, all passing. An earlier `--offline` run of `check` failed at `:paygate-api:pmdMain` because PMD dependencies were missing from the offline cache, so this was an environment problem rather than a code failure.

WORK results on the merged branch:
- **Final required checks: pass.**
  - `./gradlew spotlessCheck check --rerun-tasks --continue` exited 0: 3,141 default-module tests, 0 failures or skips.
  - `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration` exited 0: `test` 96, `securityTest` 66, 0 failures or skips. The XML timestamps confirm a fresh run.
  - `git diff --check 84239ca HEAD` was clean.
- **PoCs.** Each package copied its review PoCs from `/Users/mark/code/greenharborlabs/paygate-secreview` and confirmed they reproduced on its base, except the SR-01 and SR-02 packages, which did not report this. All PoCs were converted into inverted, renamed regression tests. The SR-05 and SR-06 packages also showed their new tests failing against the old code. The PoC files themselves are not committed. The [ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md) maps each finding to its tests.
- **Interim failure.** Two merged-branch `check -Pintegration` runs failed only at `:paygate-integration-tests:spotlessJavaCheck`. The packages ran `check` without `-Pintegration`, which skips Spotless on new integration tests, so the violations went unnoticed. Fixed in `3f11285` and `299234e`; every test passed in both runs.
- **SR-11.** zizmor (high) and actionlint passed, and a hash-verified `pip download` of both requirement files succeeded. Workflow behavior on GitHub runners was not exercised.

Points for the reviewer:
1. **API addition.** `paygate-api` gained the default method `PaymentProtocol.validateAndResolveCapabilities`, so SR-02 can grant MPP capabilities only from the verified binding. This is outside the originally listed scope but within the approved architecture.
2. **SR-08 filter order.** `PaygateAuthenticationFilter` is required in the first matching chain. The order of `AuthorizationFilter` is checked only when it is present, as before; existing chains without it still start.
3. **SR-05 side effects.** Paid POST form routes whose mapping uses `params=` conditions, and filters that read form parameters before Paygate, now fail closed with 400 `REQUEST_BODY_INCOMPLETE`. These are listed in the CHANGELOG.
4. **SR-06 rate limiting.** Behind trusted proxies that append `ip:port`, rate-limit identity now collapses to the proxy address.
5. **Limits.**
   - No validator script covers the new ledger.
   - The publishing-secret scope check (SR-11) remains a manual action.
   - SR-06(a) was verified only in tests, not through a real HAProxy.
   - Live LND and LNbits were not exercised.

### Attempt 1
Reviewed revision: faeae7f1468fc3169b61f01c1e76f093fabe5ae9
Verdict: pass
Findings and dispositions:
1. Non-blocking (documentation wording). The SR-02 decision and [SECURITY.md](../SECURITY.md) line 87 say the opaque binds the "policy method". The code binds the actual HTTP request method instead: both `PaygateChallengeService` and the two validation paths pass `request.getMethod()`. The MPP, root and autoconfigure READMEs, the CHANGELOG and `MppRequestBinding` Javadoc all correctly say "request method". This is stricter than planned, not weaker: a `*` policy cannot be reused across methods, and HEAD/GET already differ through the digest. So no assurance is overstated. The deviation was not noted in the record. Correct the SECURITY.md wording in the follow-up Low/Info record; no rework required here.
2. Non-blocking (accepted limitations, already in the ledger):
   - The SR-08 guard probes one sample path per paid route, so a chain whose matcher selects only some concrete paths can still pass startup. For non-`HandlerMethod` manual routes, the interceptor backstop fails these requests closed.
   - Under SR-05, the capture filter's `registry.resolve` evaluates MVC `params=` conditions. On paid form POSTs this drains the body first, so those requests get 400 `REQUEST_BODY_INCOMPLETE`. The CHANGELOG already lists this.

   Both are recorded as residual risk, so no rework is required.

No correctness, security, data-loss or acceptance failures were found. Every scoped claim was checked against the code:
- **SR-01/03/04/17.** The resolver rejects the listed segment shapes. Manual routes match the parsed `RequestPath`, and the interceptor backstop compares against the recorded `ResolvedEndpoint` (record equality).
- **SR-02.** The route/method/capability comparison runs only after the HMAC check and is constant-time. It parses the amount strictly, fails closed when `CURRENT_PRICE_SATS` is missing, and the metadata fallback is removed.
- **SR-05 to SR-11.** These match the decisions.
- **Documentation.** The four statements listed under Constraints are corrected. Every High, Medium and Low finding, and every Info item, has a ledger row. Each `implemented` row names regression tests that exist in the tree.
Verification:
- `./gradlew spotlessCheck check --rerun-tasks --continue`: pass. Exit 0, with 3,141 default-module tests and 0 failures, errors or skips, summed from the test XML.
- `./gradlew :paygate-integration-tests:spotlessCheck :paygate-integration-tests:test --rerun :paygate-integration-tests:securityTest --rerun -Pintegration`: pass. Exit 0: `test` 96 and `securityTest` 66 tests, 0 failures. The XML timestamps (2026-10-22T02:06Z UTC) confirm a fresh run. Integration Spotless was up to date, carrying its earlier successful result.
- `git diff --check 84239ca HEAD`: pass (clean).
- Workflow diff inspection: pass. No `actions/cache` or setup-gradle caching remains in `snapshot.yml` or `release.yml`, and both pip installs use `--require-hashes --only-binary :all:`. `actionlint` exited 0.
- `zizmor .github/workflows` (optional): skipped, because zizmor is not installed locally. The implementer's zizmor (high) run is the only evidence. Workflow behaviour on GitHub runners was not exercised.
- Limits: the PoCs were not rerun against the base. Reproduction there rests on the review at `ef395bb` and the implementer's report. Live LND, LNbits and a real HAProxy were not exercised, and the publishing-secret scope check remains a manual action.

## Outcome
SR-01 to SR-11 and SR-17 are remediated on `fix/security-review-2026-10-18`; review Attempt 1 passed at `faeae7f`.
- **Path identity (SR-01/03/04/17).** `ApplicationRelativeRequestResolver` returns 400 for dot, empty, `%`-residual, backslash and `;`-parameter segments on every request. Manual routes match the parsed `RequestPath`, and a `PaygateHandlerInterceptor` backstop fails non-`HandlerMethod` paid routes closed.
- **MPP binding (SR-02).** The HMAC-bound opaque now carries route, actual request method (stricter than the planned policy method) and capability, checked in constant time with a strict amount ≥ current price check. The capability metadata fallback is removed, and `PaymentProtocol.validateAndResolveCapabilities` was added to `paygate-api`. Credentials issued before the upgrade fail closed until expiry.
- **SR-05 to SR-11.** These were implemented as decided: early form-body capture, X-Forwarded-For joining with a binding startup guard, per-route chain coverage, a bounded method tag, least-privilege Lightning credential docs, and CI cache removal with hash-locked pip installs.
- **Documentation.** The new [disposition ledger](../docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md) covers every SR and I item. [SECURITY.md](../SECURITY.md), [CHANGELOG.md](../CHANGELOG.md), the READMEs and [docs/AGENT-MACAROON-DELEGATION.md](../docs/AGENT-MACAROON-DELEGATION.md) were corrected.

Verification passed at `faeae7f`:
- `./gradlew spotlessCheck check` ran 3,141 tests.
- The integration suites ran `test` 96 and `securityTest` 66.
- `git diff --check` and actionlint were clean.
- The implementer ran zizmor; the reviewer did not rerun it.

Remaining uncertainty:
- Live LND, LNbits and a real HAProxy were not exercised, and workflow behaviour on GitHub runners is unverified.
- The publishing-secret scope check remains a manual action.
- No validator covers the new ledger.
- Accepted residual risk: the SR-08 guard probes one sample path per route, and paid form POST routes using `params=` conditions get 400 `REQUEST_BODY_INCOMPLETE`.

Deferred to a follow-up record:
- the SR-12 to SR-16 and SR-18 to SR-24 Lows and the Info items listed as `deferred` in the ledger;
- the SECURITY.md "policy method" wording from review finding 1.

After review, the CVE-2025-32013 suppression in [config/dependency-check-suppressions.xml](../config/dependency-check-suppressions.xml) expired on 2026-10-31, so root `check` now fails on every branch until it is renewed. This is outside this record's scope.

## Next action
Open a PR from `fix/security-review-2026-10-18` to `main` once the CVE-2025-32013 renewal in [config/dependency-check-risk-dispositions.md](../config/dependency-check-risk-dispositions.md) restores root `check`.
