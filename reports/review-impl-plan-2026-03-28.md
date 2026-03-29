# IMPL Fixes from Review

**Source:** `reports/review-full-2026-03-28.md`
**Generated:** 2026-03-28

## Summary
Surgical fixes identified by /review. Each work unit is a self-contained fix
that can be implemented without architectural decisions.

## Wave 1: Path traversal and authentication bypass fixes

### W1-01: Harden PathCaveatVerifier encoded-slash check against double-encoding and backslash bypass
The `containsEncodedSlash` method only checks for `%2F`/`%2f`. Double-encoding (`%252F`) bypasses the check because `%25` is not detected. Backslash encoding (`%5C`/`%5c`) is also not checked, but some web servers treat backslash as a path separator.

**Files:** `paygate-core/src/main/java/com/greenharborlabs/paygate/core/macaroon/PathCaveatVerifier.java`
**Acceptance criteria:**
- `containsEncodedSlash` rejects `%5C` and `%5c` (backslash encoding)
- `containsEncodedSlash` rejects `%25` sequences (double-encoding indicator)
- Existing tests continue to pass
- New test cases cover: `%5C`, `%5c`, `%252F`, `%252f`, `%255C` inputs
**Error handling:** N/A (surgical fix)
**Tests:** Add test cases to `PathCaveatVerifierTest` for double-encoded slashes and backslash encodings; verify they are rejected

### W1-02: Reject dot-dot path segments in PathCaveatVerifier before normalization
`resolveDotSegments` normalizes `..` segments rather than rejecting them. If the web server's normalization differs from `PathGlobMatcher`'s, an attacker can craft paths that pass the caveat check but resolve differently on the server.

**Files:** `paygate-core/src/main/java/com/greenharborlabs/paygate/core/macaroon/PathCaveatVerifier.java`
**Acceptance criteria:**
- `PathCaveatVerifier.verify()` throws `MacaroonVerificationException` with `CAVEAT_NOT_MET` when request path contains `/..` or `../`
- Check runs before any normalization or glob matching
- Existing tests continue to pass
- New test cases cover: `/api/../secret`, `/../../../api/data`, `/api/v1/..`
**Error handling:** N/A (surgical fix)
**Tests:** Add test cases to `PathCaveatVerifierTest` for paths containing `..` segments; verify they are rejected with appropriate exception

### W1-03: Skip authentication for unregistered endpoints in PaygateAuthenticationFilter
`shouldNotFilter` checks only whether the Authorization header matches a known protocol scheme, not whether the endpoint is registered in the `PaygateEndpointRegistry`. A valid credential for endpoint A authenticates to any unregistered endpoint B, granting `ROLE_PAYMENT` on paths that were never payment-gated. The servlet-mode filter (`PaygateSecurityFilter`) already has this guard.

**Files:** `paygate-spring-security/src/main/java/com/greenharborlabs/paygate/spring/security/PaygateAuthenticationFilter.java`
**Acceptance criteria:**
- In `doFilterInternal`, after resolving `endpointConfig`, if null: skip authentication and call `filterChain.doFilter(request, response); return;`
- OR: add endpoint registry check to `shouldNotFilter` so unregistered endpoints are never processed
- Valid credentials presented to unregistered endpoints are ignored (no `ROLE_PAYMENT` granted)
- Valid credentials presented to registered endpoints continue to work
- Existing tests continue to pass
- New test case: valid L402/MPP credential sent to unregistered path results in no authentication being set
**Error handling:** N/A (surgical fix)
**Tests:** Add integration test in `DualProtocolSpringSecurityIT` verifying that a valid credential on an unregistered path does not receive `ROLE_PAYMENT`
