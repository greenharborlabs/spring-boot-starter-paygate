# Full Project Review: 7 issues (7 critical, 0 informational) across 6 sections

**Mode:** `--full --critical-only`
**Date:** 2026-03-28
**Sections reviewed:** Protocol API, Macaroon Crypto, Caveat Verifiers & Key Stores, Protocol Implementations (L402+MPP), Spring Security Filter & Auth, Spring Auto-Configuration & Lightning Backends

---

## DESIGN — needs `/plan-work` before implementation

These items need architectural thinking, trade-off analysis, or multi-file design before code is written.

### CRITICAL

- **D1.** [PaygatePathUtils.java + PathGlobMatcher.java] Dual path normalizers with divergent behavior create authorization bypass vector
  Why design needed: Two independent path normalization implementations operate on the same request path — `PaygatePathUtils` (Spring layer, iterative percent-decode up to 3 passes) and `PathGlobMatcher` (core layer, single-pass decode). Divergent behavior on double-encoded sequences (`%252e`) and decode ordering means the endpoint-registry lookup path and the caveat-verification path can disagree, enabling path-based caveat bypass. Requires a single canonical `PathNormalizer` shared across modules.
  Files: `paygate-spring-autoconfigure/.../PaygatePathUtils.java`, `paygate-core/.../PathGlobMatcher.java`, `paygate-core/.../PathCaveatVerifier.java`, `paygate-spring-autoconfigure/.../PaygateSecurityFilter.java`, `paygate-spring-security/.../PaygateAuthenticationFilter.java`

- **D2.** [PaygateSecurityFilter.java + PaygateAuthenticationFilter.java] Rate limiter bypass on non-`PaymentValidationException` paths; no rate limiter in Spring Security mode
  Why design needed: In servlet mode, the rate limiter is only consumed on `PaymentValidationException` — generic `RuntimeException` paths (malformed base64, oversized payloads) bypass it entirely. In Spring Security mode, there is no rate limiter at all. Architectural decision needed: where does rate limiting live in the Spring Security filter chain, and should it fire before or after credential parsing?
  Files: `paygate-spring-autoconfigure/.../PaygateSecurityFilter.java`, `paygate-spring-security/.../PaygateAuthenticationFilter.java`, `paygate-spring-security/.../PaygateSecurityAutoConfiguration.java`

- **D3.** [PaygateAuthenticationProvider.java + PaygateAuthenticationToken.java] `PAYGATE_CAPABILITY_*` authorities silently lost on cache miss
  Why design needed: The `CapabilityCache` is the sole source of capability authorities. On cache eviction, node restart, or LRU pressure, `resolveCapabilities()` returns empty, and all `PAYGATE_CAPABILITY_*` authorities vanish — causing silent 403s for valid paid tokens. Affects both L402 and MPP. Requires a fallback strategy design: extract capabilities from macaroon caveats (L402) or request metadata (MPP) when cache misses, treating the cache as optimization not authority.
  Files: `paygate-spring-security/.../PaygateAuthenticationProvider.java`, `paygate-spring-security/.../PaygateAuthenticationToken.java`, `paygate-spring-autoconfigure/.../PaygateChallengeService.java`

- **D4.** [MppChallengeBinding.java + MppCredentialParser.java + MinimalJsonParser.java] MPP HMAC delimiter injection + unbounded credential parsing
  Why design needed: The HMAC input uses pipe (`|`) as delimiter but does not reject pipes in `realm`/`method`/`intent`, enabling field-boundary confusion. Separately, `MinimalJsonParser` has no depth limit or max string length, allowing stack exhaustion and heap abuse via crafted credentials. Multiple competing fixes (length-prefix vs delimiter rejection, depth counter placement) require design trade-off analysis.
  Files: `paygate-protocol-mpp/.../MppChallengeBinding.java`, `paygate-protocol-mpp/.../MppCredentialParser.java`, `paygate-protocol-mpp/.../MinimalJsonParser.java`

---

## IMPL — ready for `/orchestrate`

These items are surgical, well-defined fixes that can go straight to implementation.

### CRITICAL

- **I1.** [paygate-core/.../PathCaveatVerifier.java:50-53] Encoded-slash check bypassed via double-encoding (`%252F`) and backslash encoding (`%5C`/`%5c`)
  Fix: Extend `containsEncodedSlash` to also reject `%5C`/`%5c` (backslash encoding) and `%25` sequences (double-encoding indicator).
  Files: `paygate-core/src/main/java/com/greenharborlabs/paygate/core/macaroon/PathCaveatVerifier.java`

- **I2.** [paygate-core/.../PathCaveatVerifier.java] Path traversal via `..` segments not rejected — normalization mismatch risk with web server
  Fix: Reject request paths containing `..` segments outright in `PathCaveatVerifier.verify()` before normalization, similar to how encoded slashes are rejected: `if (requestPath.contains("/..") || requestPath.contains("../")) throw ...`
  Files: `paygate-core/src/main/java/com/greenharborlabs/paygate/core/macaroon/PathCaveatVerifier.java`

- **I3.** [paygate-spring-security/.../PaygateAuthenticationFilter.java:79-87] Authentication bypass on unregistered endpoints — credential for endpoint A grants `ROLE_PAYMENT` on non-gated endpoint B
  Fix: In `doFilterInternal`, after resolving `endpointConfig`, if null (unregistered endpoint), skip authentication and continue filter chain: `if (endpointConfig == null) { filterChain.doFilter(request, response); return; }`. The servlet-mode filter already has this guard.
  Files: `paygate-spring-security/src/main/java/com/greenharborlabs/paygate/spring/security/PaygateAuthenticationFilter.java`

---

## Summary

| Category | Critical | Informational | Total |
|----------|----------|---------------|-------|
| DESIGN   | 4        | —             | 4     |
| IMPL     | 3        | —             | 3     |
| **Total**| **7**    | **0**         | **7** |

### Action Playbook

Copy-paste commands grouped into **waves** of work that can be run in parallel. Tasks within a wave touch different files and are safe to run concurrently. Complete all tasks in a wave before starting the next.

#### Wave 1 — 3 parallel tasks
```bash
# D1. Path normalization consolidation  [Files: PaygatePathUtils.java, PathGlobMatcher.java, PathCaveatVerifier.java, PaygateSecurityFilter.java, PaygateAuthenticationFilter.java]
/plan-work "Consolidate dual path normalizers (PaygatePathUtils + PathGlobMatcher) into a single canonical PathNormalizer to eliminate authorization bypass via normalization mismatch" --review reports/review-full-2026-03-28.md

# D3. Capability authority cache-miss fallback  [Files: PaygateAuthenticationProvider.java, PaygateAuthenticationToken.java, PaygateChallengeService.java]
/plan-work "Design fallback for PAYGATE_CAPABILITY_* authorities on CapabilityCache miss — extract from macaroon caveats (L402) or request metadata (MPP) so cache is optimization not authority" --review reports/review-full-2026-03-28.md

# D4. MPP HMAC delimiter injection + unbounded parsing  [Files: MppChallengeBinding.java, MppCredentialParser.java, MinimalJsonParser.java]
/plan-work "Fix MPP HMAC pipe-delimiter injection and add credential size/depth limits to MinimalJsonParser and MppCredentialParser" --review reports/review-full-2026-03-28.md
```

#### Wave 2 — 2 parallel tasks
```bash
# D2. Rate limiter architecture for Spring Security mode  [Files: PaygateSecurityFilter.java, PaygateAuthenticationFilter.java, PaygateSecurityAutoConfiguration.java]
/plan-work "Add rate limiting to Spring Security mode auth failures and fix servlet-mode rate limiter bypass on generic exceptions" --review reports/review-full-2026-03-28.md

# I1+I2+I3. Path traversal + auth bypass surgical fixes  [Files: PathCaveatVerifier.java, PaygateAuthenticationFilter.java]
/orchestrate reports/review-impl-plan-2026-03-28.md --scope "Wave 1"
```
