# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

Remediates SR-2026-10-18-01 through -24 and Info items I-01 through I-19 from the 2026-10-18
security review; I-20 remains an accepted limitation. Dispositions for every finding are tracked in `docs/security/OPUS-2026-10-18-FINDING-DISPOSITIONS.md`.

- **Request path identity (SR-01, SR-03, SR-04, SR-17).** Paygate now authorizes the once-decoded
  path that Spring dispatches on. Every request whose path has a `.`, `..` or empty segment (a
  single trailing slash is allowed), residual `%`, `\` or `;` path parameters is rejected with 400
  `MALFORMED_URI` before policy lookup, whether or not it is paid. Manual routes match Spring's
  parsed `RequestPath`. Paid manual routes served by non-`@RequestMapping` handlers fail closed with
  500 when no Paygate decision was recorded. Previously, manual paid `RouterFunction` routes could be
  reached unpaid through dot segments in servlet mode, and delegated `path` caveats and MPP digests
  could be escaped the same way.
- **MPP binding (SR-02).** MPP challenges now bind the route pattern, request method and capability
  in the HMAC-bound `opaque`. Validation requires the bound amount to cover the current price.
  MPP credentials issued before this release are rejected until they expire (at most 24 hours). In
  Spring Security mode, MPP `PAYGATE_CAPABILITY_*` authorities come only from the verified binding.
  `DefaultCapabilityResolver` no longer grants the requested endpoint's capability.
- **Form bodies in Spring Security mode (SR-05).** A new `paygateRequestBodyCaptureFilterRegistration`
  captures paid request bodies at `HIGHEST_PRECEDENCE + 10`, before `FormContentFilter`,
  `HiddenHttpMethodFilter` and `CsrfFilter`, and replays them downstream. A body shorter than its
  declared `Content-Length` is rejected with 400 `REQUEST_BODY_INCOMPLETE` in both modes.
- **Forwarded addresses (SR-06).** All `X-Forwarded-For` lines are joined in order. The right-to-left
  walk stops at the first entry that is not an IP literal and uses the nearest trusted hop, and
  client-address binding fails closed when the lines are ambiguous.
- **Client-address binding guard (SR-07).** Startup fails when
  `paygate.protocols.l402.client-address-binding-enabled=true` and no `client_ip` caveat verifier is
  registered.
- **Spring Security chain guard (SR-08).** Startup now checks that every paid route is first matched,
  for REQUEST, ASYNC, FORWARD and ERROR dispatch, by a chain that applies Paygate enforcement.
  Chains using `PathPatternRequestMatcher` no longer crash startup with an NPE.
- **Meter cardinality (SR-09).** The `method` tag on `paygate.security.decisions` is limited to the
  standard HTTP methods, `_unknown` and `_other`.
- **Lightning credentials (SR-10).** The docs now recommend a baked LND macaroon with
  `invoices:read invoices:write info:read` and the LNbits Invoice/read key instead of admin
  credentials. LND's `invoice.macaroon` alone lacks `info:read`, which fails health checks.
- **CI supply chain (SR-11).** Snapshot and release publishing jobs no longer restore the Gradle
  Actions cache. Semgrep and zizmor install from hash-locked requirement files.
- **L402 issuer evidence (SR-12, I-02, I-03).** Holders can only append caveats, so price evidence,
  the required boundaries and the `client_ip` binding now count only from caveats before the first
  `{service}_valid_until`. A holder-appended `{service}_price_sats` no longer satisfies price
  evidence or appears as a verified attribute, and a holder cannot self-bind a legacy unbound
  credential with `client_ip`. The 4-argument `L402Validator` (no Lightning backend) rejects
  credentials without a signed price with `MISSING_PRICE_EVIDENCE`.
- **Route overlap analysis (SR-13).** Zero-segment catch-all overlaps (paid `/api/**` vs free
  `/api`, paid `/reports` vs free `/reports/**`, paid `/a/b/**` vs free `/a/{x}`) now produce the
  `paygate.routing.overlap-policy` warning and fail startup under `FAIL`.
- **Auth-failure rate limiting (SR-14, SR-15).** In Spring Security mode, the limiter resolves the
  protected endpoint the same way as the authentication filter, so a paid route behind a narrower
  unpaid mapping is still limited. The penalty token is charged for malformed (400), invalid or
  expired (402) and unclassified (401) credential failures, and no longer for backend 503s, first
  challenges or re-price replacement challenges.
- **Child dispatcher contexts (SR-16).** `@PaymentRequired` in a `DispatcherServlet` context other
  than the root context is unsupported and now fails closed: startup fails for `load-on-startup`
  servlets, and the servlet otherwise fails to initialise (500) instead of serving the paid route
  unpaid.
- **MPP realm (SR-18).** MPP validation rejects a credential whose challenge `realm` differs from
  `paygate.service-name` (or `default`), so services that share a binding secret no longer accept
  each other's credentials. Use one binding secret per service.
- **MPP `source` (SR-19).** MPP authentication tokens no longer carry the unauthenticated `source`
  attribute. `PaymentCredential.source()` remains and is documented as unverified client input.
- **Rate limit before work (SR-20).** In servlet mode, a paid request acquires its rate-limit token
  before body capture and named pricing strategies run, so a rate-limited request does no such work.
- **LNbits transport (SR-21, I-15).** `paygate.lnbits.allow-plaintext-http=true` now fails startup
  unless every active profile is `dev`, `local`, `development` or `test`, matching plaintext LND.
  A `*.localhost` plaintext host is accepted only when every resolved address is loopback.
  `LnbitsConfig.equals`/`hashCode` ignore the API key, and `toString()` and validation errors mask
  base-URL userinfo.
- **LNbits response bounds (SR-22).** LNbits response bodies are capped at 1 MiB, and one deadline
  equal to the request timeout covers headers and body. Exceeding either fails the call with 503.
- **Root-key revocation (SR-23).** `FileBasedRootKeyStore.revokeRootKey` deletes a regular key file
  whatever its permissions, and throws for a symlink or special file instead of returning silently.
- **Go interop helper (SR-24).** `setup-go-interop.sh` and `run-go-interop-test.sh` build in a fresh
  `mktemp -d` directory. An explicit `GO_INTEROP_DIR` must be owned by the current user and must not
  be group- or world-writable.
- **Canonical credential encodings (I-01, I-13).** V2 macaroons with duplicate identifiers,
  out-of-order header fields or unknown header field types are rejected, matching go-macaroon. An
  L402 macaroon must be canonical padded standard base64; missing padding or nonzero trailing bits
  is a malformed header. MPP base64url fields must be unpadded with zero trailing bits, so a padded
  or re-spelled challenge `id` is rejected as `INVALID`, and MPP JSON `\u` escapes need exactly four
  ASCII hex digits.
- **L402 validation hygiene (I-04, I-05).** A credential that fails the paid-price check is no
  longer cached, and the preimage is zeroized when the price check throws.
- **Spring Security filter (I-07, I-08).** A `PaygateAuthenticationFilter` built without an entry
  point returns 503 for endpoints with a named pricing strategy instead of charging the static
  price. An `AuthenticationManager` result that is not an authenticated `PaygateAuthenticationToken`
  returns 503 and leaves the security context unset.
- **Switch spelling (I-10).** Startup fails when `paygate.enabled` or
  `paygate.protocols.l402.enabled` is anything other than `true` or `false` (any case), even when
  Paygate is disabled. Previously `on` or `yes` silently disabled Paygate or started it without a
  payment protocol.
- **Multi-method paid handlers (I-12).** Paid handlers mapping several HTTP methods now enforce each
  method separately instead of returning 500. A credential paid for one method cannot run another,
  including through `HiddenHttpMethodFilter` `_method` overrides.
- **Invoice consistency (I-16).** The LND and LNbits backends reject a created invoice whose BOLT11
  `payment_request` is malformed or commits to a different payment hash than the backend reported,
  failing closed with 503 instead of issuing an unusable challenge.
- **Redispatch pricing (I-18).** A FORWARD, ASYNC or ERROR redispatch to a different paid endpoint
  re-resolves that endpoint's price instead of reusing the original request's price.
- **Build and fixtures (I-19).** LND and bitcoind integration images are pinned by digest,
  `installGitHook` re-stages only previously staged files, the POM points at the https
  `spring-boot-starter-paygate` repository, and a stray root `META-INF` file is removed.

### Changed

- Dependencies: Spring Boot 4.1.2 (Spring Security 7.1.2, Spring Framework 7.0.10, Jackson
  3.1.7, Micrometer 1.17.2), Tomcat 11.0.27, Log4j 2.26.1, Caffeine 3.3.1, gRPC 1.84.1, Protobuf
  4.36.2, and Gradle 9.8.2 with updated CycloneDX, JMH, Protobuf and build-health plugins. gRPC
  follows Spring Boot's managed version (1.84.1), so every `io.grpc` artifact resolves to one
  version. Tests use OkHttp `mockwebserver3` 5.3.2.
- Servlet-mode applications that used matrix variables or dot segments in request paths now receive
  400.
- These setups now fail closed with 400 instead of passing an unbound body:
  - a paid POST form route whose mapping uses `params=` conditions;
  - a filter ordered before Paygate that reads the parameters of a paid form request;
  - a hidden-method override onto a paid route with a different method.
- Paid POST form handlers on MPP or named-pricing routes now receive their form parameters.
- With trusted `ip:port` proxies, rate-limit identity now groups clients under the proxy address.
- New public API:
  - `PaymentProtocol.validateAndResolveCapabilities` (default method);
  - `L402Validator.hasCaveatVerifier`;
  - `PaygateResponseWriter.writeRequestBodyIncomplete`;
  - `RequestBodyIncompleteException`.
- `new L402Protocol(..., true)` and the binding-enabled `PaygateAuthenticationProvider` constructor
  throw when the validator has no `client_ip` verifier.
- Custom L402 issuers must sign `{service}_price_sats` and must sign `{service}_valid_until` after
  every other issuer caveat; caveats signed after it are not issuer evidence.
- New public API: `IssuerCaveatPrefix` (paygate-core) and the realm-binding
  `MppProtocol(secret, parserLimits, previousSecret, clock, serviceName)` constructor. The existing
  `MppProtocol` constructors do not check the realm.
- Plaintext LNbits setups outside a development profile, and applications with `@PaymentRequired`
  in a child dispatcher context, now fail instead of starting.
- In servlet mode, an exception thrown by the application after a successful payment reaches the
  container's error handling (normally 500) instead of being reported as 503 "Lightning
  unavailable". Spring Security chains that authenticate `/error` must permit it or the ERROR
  dispatch. The caveat verify-duration metric now measures validation only.
- MPP credentials are accepted through their expiry instant, including the final second.
- An MPP challenge-binding secret, current or previous, consisting of one repeated byte fails
  startup.
- `ChallengeContext.rootKeyBytes()` throws `IllegalStateException` after `close()`.
- The `PaygateRequestPricingService.REQUEST_PRICE_ATTRIBUTE` value is no longer a bare
  `TrustedRequestPrice`; use the new `PaygateRequestPricingService.memoizedPrice` and `memoize`.
- The autoconfigure README's validator and caveat-verifier override examples now start as written.

## [0.1.7] - 2026-09-23

### Security

- Upgrade Spring Boot from 4.0.7 to 4.0.8 and Tomcat from 11.0.24 to 11.0.25 to incorporate dependency advisory fixes.
- Replace both disposable LNbits integration fixtures' affected 0.12.11 image with the supported, digest-pinned v1.6.2 image; keep payment preimages out of LND INFO logs and make first-install failure handling fail closed.
- Strengthen Dependency-Check risk-disposition validation and negative controls so a false-positive suppression must have an exact package URL, current evidence, bounded expiry, and explicit approval.
- Recheck all 464 exact dependency-provenance artifact hashes and renew their bounded review date without changing the pinned checksums.

### Fixed

- Build project JARs before the aggregate dependency scan and fail duplicate-class warnings, making clean-checkout security checks more reliable.

## [0.1.6] - 2026-08-23

### Security

- **Kimi L-1–L-28 — accountable Low-finding hardening**: Added a canonical implemented-control
  ledger, append-only evidence schema, mutation-tested validator, and CI/release gates. The ledger
  records successful current-revision evidence and approval by the named OpenAI Codex AI security
  reviewer. The final review also closed nested Spring Security chain coverage, local LNbits
  setup-state symlink handling, and test-mode preimage cleanup findings before the release gate
  passed at reviewed revision `c45e2a9b4d6c9694dcb021d869242270d65356e3`.

- L402 challenges now bind the settled invoice amount in a signed service-scoped paid-price caveat;
  protected requests re-evaluate their trusted current price on every validation, including cache hits.
- Dynamic price evaluation is bounded and request-memoized; unavailable pricing and ambiguous legacy
  evidence fail closed, while authenticated underpayment receives a fresh full-price challenge.
- **M-3 — trustworthy request-size pricing**: Named pricing strategies now receive a bounded,
  replayable server-observed body. The two `/api/v1/analyze` examples no longer use `Content-Length`
  as price evidence; non-identity content encodings are rejected before challenge or handler work and
  emit one sanitized `COMPRESSED_BODY_REJECTED` decision.
- **M-4 — authenticated caveat-key compatibility**: Macaroon signatures continue to cover exact
  raw caveat bytes. After a successful signature check, ASCII space/tab edge padding on a known
  caveat key is canonicalized for verifier lookup and L402 boundary enforcement; padded
  registrations are rejected and padded unknown keys retain delegation semantics.

- **M-1 — trusted Spring Security attributes**: Deprecated credential-only L402 authentication
  factories now expose protected system metadata and explicit capabilities only; they no longer
  copy raw caveats. Use the new `L402Validator.ValidationResult` factory to carry
  verifier-approved attributes into authentication state.
- **M-1–M-4 — auditable remediation evidence**: Added the tracked Kimi Medium finding ledger,
  strict disposition validator, isolated negative controls, and build/release gates. Rows remain
  implemented with approval pending until successful current-implementation evidence and named
  security or release review are recorded.

## [0.1.5] - 2026-08-16

### Security

- **DeepSeek L1 — credential lifecycle**: `PaymentCredential` now owns defensive hash/preimage copies, is explicitly closeable and destroyable, and rejects sensitive access after idempotent cleanup. MPP parsing, servlet enforcement, Spring Security enforcement, and both credential stores now release their owned material at their lifecycle boundaries.
- **DeepSeek L2 — guarded test completion**: Test completion material is emitted only after the complete local-development profile, ephemeral-memory root-key-store, and exact synthetic-backend checks pass. Unsafe or ambiguous test-mode configuration fails before traffic is accepted.
- **DeepSeek L3 — cache shutdown finality**: In-memory and Caffeine credential stores destroy only their private copies on every removal path and make shutdown final: racing or later stores cannot retain a credential after close.
- **DeepSeek I1 — accepted MPP replay model**: MPP remains an exact-request reusable bearer until expiry, not single-use replay prevention. Operators of state-changing endpoints should use short expiries, idempotency or transaction uniqueness, and optional consumed state where business semantics require it.
- **DeepSeek I2 — plaintext LND boundary**: Plaintext LND now requires an explicit opt-in, a complete allowed development-profile set, and a loopback literal or exact all-loopback `localhost` target before any channel is created. Remote TLS behavior is unchanged.
- **DeepSeek I3 — LND macaroon lifetime**: LND interceptors precompute one canonical lower-case macaroon representation per wallet-client lifecycle, release it during shutdown, and reject later use. Java `String` and transport copies remain a documented residual risk.
- **DeepSeek I4 — example bootstrap consent**: Example LNbits auto-provisioning is disabled by default. An explicit local opt-in performs bounded direct local requests and publishes a validated key only in process memory; container setup injects disposable keys instead of provisioning through a service hostname.
- **DeepSeek I5 — monitored dependency baseline**: `releaseReadiness -Pintegration` now includes OWASP Dependency-Check, fail-closed approved-risk/suppression validation, and current scan reports.
- **DeepSeek I6 — response storage protection**: Paygate-generated 401, 402, malformed-credential, validation, and authentication failures consistently include `Cache-Control: no-store` and `X-Content-Type-Options: nosniff` without changing required protocol challenge headers.
- **DeepSeek I7 — verified proxy boundary**: Documentation makes explicit that forwarded addresses require a trusted proxy boundary and IP/prefix buckets are spoofable abuse controls, not authenticated identity or authorization.
- **DeepSeek I8 — verified example defaults**: Release gates inspect source defaults and built example artifacts for embedded secrets and unintended management exposure.
- **C-1 — paygate-spring-autoconfigure** and **paygate-spring-security**: Bind L402 credentials to the canonical application-relative route and HTTP method, preventing replay across protected request boundaries.
- **C-2 — paygate-spring-autoconfigure** and **paygate-spring-security**: Resolve policy against the application-relative current dispatch so deployment prefixes cannot bypass payment enforcement.
- **H-1 — paygate-core**: Enforce immutable issuance ceilings during capability attenuation so delegated credentials cannot self-escalate beyond their originally issued capabilities.
- **H-2 — paygate-spring-autoconfigure** and **paygate-spring-security**: Apply protected `GET` payment policy to `HEAD` requests while keeping issued credentials bound to the actual `HEAD` method.
- **H-3 — paygate-spring-autoconfigure** and **paygate-spring-security**: Redact bearer credentials from diagnostic output.
- **Credential cache revocation — paygate-core**: Require authoritative root-key existence after proof-of-payment and before every credential-cache read. Exact cache hits still skip signature recomputation; missing or failed root-key lookup best-effort evicts the stale slot and returns a sanitized `REVOKED_CREDENTIAL`.
- **MPP request binding — paygate-api and paygate-protocol-mpp**: Authenticate challenge expiry and bind credentials to the exact raw-query presence/value, including absent versus empty, in addition to method, application-relative path, and bounded body digest. MPP remains a reusable, transferable bearer for that exact request until expiry; it is not single-use replay prevention.
- **Invalid-request containment — paygate-spring-autoconfigure and paygate-spring-security**: Reject presented malformed credentials with stable safe 400 responses and invalid, expired, or insufficient credentials with 402 responses without minting replacement invoices or root keys. Abuse throttling remains 429; Lightning/backend failures return 503 and fail closed without client-fault penalties.
- **Enforcement parity — paygate-spring-autoconfigure and paygate-spring-security**: Resolve overlapping Spring MVC policies deterministically, reject unresolved mapping conflicts at startup, enforce changed protected targets across REQUEST/ASYNC/FORWARD/ERROR dispatches without duplicate charging, and require Paygate enforcement in effective Spring Security chains serving paid routes.
- **Trusted authentication state — paygate-spring-security**: Expose only uniquely owned, successfully verified caveat values as attributes/authorities and retain no raw bearer header, parsed credential, or payment preimage after authentication.
- **Strict boundaries and operations — paygate-core, provider, and Spring modules**: Reject noncanonical bounded credential input, unsupported third-party caveats, and additional/discharge macaroons; enforce literal exact-string `client_ip` semantics without DNS/CIDR interpretation; verify provider hash/preimage lengths and paid-preimage correspondence; harden root-key filesystem permissions/symlink behavior and secret cleanup; and keep logs, metrics, health, and client details bounded and redacted.
- **Release gates — build**: Add `verifySupplyChainNegativeControls`, `verifyModuleCoverage`, and `validateFindingDispositions`; compose them into `releaseReadiness -Pintegration` with the integration/security suites. Disposition validation fails until every supported finding has executed, verified evidence.

### Changed

- **Credential schema compatibility**: Newly issued L402 credentials use signed identifier version 1. Identifier v0 credentials are rejected even after valid holder attenuation; clients must obtain and pay a new challenge, and custom issuers must mint v1. The 66-byte identifier layout, HTTP challenge `version="0"`, Macaroon V2 encoding, and Go parser compatibility are unchanged.
- **Unsigned location semantics**: Macaroon location remains an authorization-neutral V2 serialization hint and participates in object equality, hashing, and cache variant replacement, while `Macaroon.toString()` now omits null, ordinary, and attacker-controlled location values from diagnostics.
- **Compatibility**: Intentionally reject legacy L402 credentials that lack the required reserved `route`, `method`, or `{serviceName}_capabilities` caveats; affected clients must obtain newly issued credentials. The context-less `L402Validator.validate(String)` descriptor remains callable but fails closed for boundary-bound credentials, so integrations must use a context-bearing overload with trusted `REQUEST_ROUTE` and `REQUEST_METHOD` metadata. The public `ErrorCode` enum now includes `MISSING_REQUEST_CONTEXT` (HTTP 401); downstream exhaustive source switches must be updated and recompiled when upgrading.
- **Delegation compatibility**: Pre-existing cross-application credentials that used generic `route`, `method`, or the active service capability key are now evaluated as this application's reserved first-party boundaries once those verifiers are registered; only truly unregistered caveat keys retain pass-through behavior.
- **Route compatibility**: The retained config-based `PaygateChallengeService` challenge overloads sign the exact parsed spelling of `PaygateEndpointConfig.pathPattern()`. Manually constructing `/api/orders/` when the registered route is `/api/orders` intentionally fails closed; registry-based callers should pass `ResolvedEndpoint`. Signed route comparison remains exact, and no new case, percent-encoding, whitespace, or trailing-slash normalization is promised.
- **Request-body configuration**: Add `paygate.request-body.max-bytes`, default `8192`, with a valid range of 1–16,777,216 bytes (16 MiB). Oversized protected requests are rejected before handler work; operators serving larger paid bodies must opt into an appropriate bound.
- **IPv6 rate identity configuration**: Add `paygate.rate-limit.ipv6-prefix-length`, default `64`, with a valid range of 0–128 bits. Trusted-proxy resolution precedes prefix grouping; changing the prefix changes abuse buckets, not authorization identity.
- **MPP migration**: Legacy/noncanonical Payment credentials, credentials without authenticated expiry, and requests whose exact raw-query identity differs now fail closed and must obtain a new challenge. Exact-request credentials can still be reused until expiry and must be protected as bearer secrets.
- **L402 and delegation migration**: Identifier-v0, boundary-incomplete, or otherwise noncanonical L402 credentials, third-party caveats, and additional macaroons are intentionally rejected. Clients must obtain and pay a newly issued credential; custom issuers must provide the required service, route, method, `valid_until`, and capability-ceiling boundaries.
- **Deployment migration**: Test mode now requires every active profile to be in the safe `test`/`dev`/`local`/`development` allowlist. Operators must validate their production routing/filter-chain topology, trusted proxies, protected-body sizes, and root-key/LND mount permissions before rollout; rollback can re-enable deliberately rejected legacy behavior.
- **Generic verifier contract**: `MacaroonVerifier` authenticates an HMAC chain and evaluates configured caveats, but does not verify payment preimages, require identifier v1 or the complete first-party boundary set, or apply cache policy. Direct first-party L402 integrations must use `L402Validator`.
- **Spring Security resolver migration**: Replace `new DefaultCapabilityResolver(cache, serviceName)` with `new DefaultCapabilityResolver(cache)`. This intentional constructor break removes a dead argument; service identity remains in `CapabilityResolutionContext`, and `paygate.service-name` remains supported by authentication and validation components.

## [0.1.4] - 2026-07-01

### Security

- **paygate-lightning-lnbits**: Require HTTPS LNbits URLs by default; plaintext HTTP now needs an explicit local/test opt-in and is limited to loopback or the known Docker Compose LNbits service host.
- **paygate-spring-security**: Fail startup in Spring Security mode when no `PaygateAuthenticationFilter` is present in the effective filter chain, unless advanced custom filter-chain wiring is explicitly acknowledged.
- **paygate-spring-autoconfigure**: Reject the committed MPP sample challenge-binding secret outside test mode.
- **integration-tests**: Bind local Lightning and Bitcoin service ports to loopback and narrow regtest Bitcoin RPC access to the Docker subnet.

### Changed

- **paygate-spring-autoconfigure**: Disable the Paygate actuator endpoint by default; enable it explicitly with `paygate.actuator.enabled=true`.
- **paygate-example-app** and **paygate-example-app-spring-security**: Stop activating the dev profile from default `application.yml`; local test mode now stays in the explicit dev profile.

### Fixed

- **paygate-protocol-mpp**: Convert malformed echoed challenge-binding fields into `INVALID_CHALLENGE_BINDING` validation failures instead of leaking delimiter parsing exceptions.

## [0.1.3] - 2026-06-06

### Security

- **paygate-protocol-mpp**: Removed `MppMetadata.rawCredentialJson()` and the three-argument canonical constructor so parsed MPP metadata no longer retains decoded credential JSON containing `payload.preimage`; downstream callers should use `echoedChallenge()` and `source()`.
- **paygate-protocol-mpp**: Require request digest binding for all MPP challenges and credential validation, so Payment credentials are bound to the exact method, path, and request body.
- **paygate-protocol-mpp**: Zeroize local preimage, payment-hash, and computed-hash validation copies after MPP credential validation.
- **paygate-protocol-l402**: Zeroize the root-key defensive copy after macaroon challenge minting.
- **paygate-lightning-lnd**: Store LND macaroon credentials as defensive byte-array copies and zeroize interceptor-owned macaroon bytes when factory-created channels shut down.
- **paygate-lightning-lnbits**: Stop including LNbits non-2xx response bodies in warning logs and exception messages.
- **paygate-spring-autoconfigure**: Rate-limit unauthenticated MPP challenge requests before request-body digest capture.
- **paygate-spring-autoconfigure**: Reject malformed forwarded client IP identities instead of treating them as trusted proxy entries.
- **paygate-spring-autoconfigure**: Create Lightning invoices before root-key generation so invoice failures do not allocate root-key material.

### Fixed

- **paygate-lightning-lnd**: Validate invoice amounts and lookup payment-hash lengths before making LND backend calls.
- **paygate-lightning-lnbits**: Validate invoice amounts before making LNbits backend calls.
- **paygate-protocol-mpp**: Avoid previous-secret HMAC verification when the current challenge-binding secret already validates.
- **paygate-spring-autoconfigure**: Require the Paygate Spring Security integration module before `auto` mode switches from servlet filtering to Spring Security.
- **paygate-spring-autoconfigure**: Fail explicit `paygate.security-mode=spring-security` startup when either Spring Security or the Paygate Spring Security integration module is missing.
- **paygate-spring-autoconfigure**: Avoid double-charging challenge creation after the rate-limit token has already been consumed.

### Changed

- **paygate-lightning-lnd**: Added a preferred byte-array constructor path for `MacaroonClientInterceptor` while preserving the existing `String` constructor for source compatibility.
- **paygate-protocol-mpp**: Documented the required digest validation flow for Payment challenge handling.
- **release**: Updated the release checklist around the current Maven Central release flow.

## [0.1.2] - 2026-06-05

### Changed

- **paygate-core**: Clarified credential cache ownership boundaries so stores retain and return caller-owned copies.
- **paygate-core**: Reused precomputed caveat verifier maps for fresh and cached L402 credential validation paths.

### Security

- **paygate-core**: Reject non-canonical V2 macaroons with trailing bytes after the signature.
- **paygate-core**: Zeroize temporary payment preimage bytes decoded from hex.
- **paygate-core**: Destroy retained cache-owned preimages on revoke, expiry, replacement, capacity eviction, and close.
- **paygate-protocol-l402**: Clean up parsed credential material when validation fails.
- **paygate-spring-autoconfigure**: Apply credential ownership cleanup to the Caffeine-backed credential store.

### Fixed

- **paygate-core**: Preserve usable validation results after credential cache revocation.
- **paygate-core**: Keep cached credential validation fail-closed while avoiding repeated verifier-map construction.

## [0.1.1] - 2026-05-27

### Security

- Removed checked-in local L402 integration credential material from `integration-tests/.l402-credential.env`.
- Added `*.env` ignore coverage while still allowing `*.env.example` templates.
- Hardened release publishing so `v*` release tags must point to commits already on `main`.

## [0.1.0] - 2026-05-27

### Added

- **paygate-core**: Macaroon V2 binary serialization/deserialization, byte-level compatible with Go `go-macaroon`
- **paygate-core**: HMAC-SHA256 crypto chain with key derivation (`HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`)
- **paygate-core**: Macaroon identifier layout: `[version:2B BE][payment_hash:32B][token_id:32B]` (66 bytes)
- **paygate-core**: First-party caveat support with built-in verifiers (service, capabilities, expiry)
- **paygate-core**: Capabilities caveat verifier for fine-grained access control
- **paygate-core**: Monotonic restriction validation for caveats
- **paygate-core**: Constant-time equality checks for all secret comparisons (XOR accumulation)
- **paygate-core**: `SensitiveBytes` wrapper for root key zeroization on close
- **paygate-core**: `RootKeyStore` implementations: file-based (with caching/eviction) and in-memory
- **paygate-core**: `InMemoryCredentialStore` with configurable max size and eviction policies
- **paygate-core**: L402 protocol flow: 402 challenge issuance and credential validation with preimage verification
- **paygate-core**: Multi-token `Authorization` header parsing
- **paygate-core**: LSAT backward compatibility in header parsing
- **paygate-core**: Delegation caveat verifiers for path, method, and client_ip restrictions
- **paygate-api**: Protocol abstraction API for multi-protocol support (JDK only, zero dependencies)
- **paygate-protocol-l402**: L402 protocol implementation module
- **paygate-protocol-mpp**: MPP (Modern Payment Protocol) dual-protocol support with HMAC-SHA256 challenge binding, base64url encoding, and RFC 8785 JCS deterministic serialization
- **paygate-lightning-lnd**: LND gRPC Lightning backend with invoice creation and settlement verification
- **paygate-lightning-lnbits**: LNbits REST Lightning backend with invoice creation and settlement verification
- **paygate-spring-autoconfigure**: Spring Boot auto-configuration for all paygate components
- **paygate-spring-autoconfigure**: `@PaymentRequired` annotation for declarative endpoint protection
- **paygate-spring-autoconfigure**: Servlet filter for L402 challenge/validation lifecycle
- **paygate-spring-autoconfigure**: Pluggable `PaygatePricingStrategy` for dynamic per-request pricing
- **paygate-spring-autoconfigure**: Credential caching with Caffeine-backed store and dynamic TTL from `valid_until` caveats
- **paygate-spring-autoconfigure**: `CachingLightningBackendWrapper` for health check result caching
- **paygate-spring-autoconfigure**: `TokenBucketRateLimiter` for rate-limiting challenge issuance
- **paygate-spring-autoconfigure**: Micrometer metrics integration (challenge count, validation count, latency)
- **paygate-spring-autoconfigure**: Spring Boot Actuator health indicator (`PaygateLightningHealthIndicator`)
- **paygate-spring-autoconfigure**: Test mode with auto-settle invoices (blocked in `prod` profiles)
- **paygate-spring-autoconfigure**: IDE autocomplete via `additional-spring-configuration-metadata.json`
- **paygate-spring-security**: `PaygateAuthenticationProvider` for Spring Security integration
- **paygate-spring-security**: `PaygateAuthenticationFilter` for servlet-based authentication
- **paygate-spring-security**: `PaygateAuthenticationToken` for the Spring Security authentication model
- **paygate-spring-boot-starter**: Dependency aggregator module for single-dependency adoption
- **paygate-example-app**: Reference application demonstrating dynamic pricing and protected endpoints
- **paygate-example-app-spring-security**: Reference application demonstrating dual-protocol support with Spring Security
- Docker support: `Dockerfile` and `docker-compose.yml` for containerized deployment
- CI/CD: GitHub Actions workflows for CI, release (Sonatype staging), and snapshot publishing
- CI/CD: Dependabot configuration for automated dependency updates
- CI/CD: CodeQL analysis workflow for security scanning
- CI/CD: Gradle wrapper validation in CI
- CI/CD: Integration test CI stage
- CI/CD: Aggregate Javadoc generation and upload as a GitHub Actions artifact
- Testcontainers-based integration test module
- Go interop test automation for cross-platform macaroon compatibility
- `.editorconfig` for consistent formatting across editors
- Smoke test script for manual Lightning validation
- Configuration properties under `paygate.*` prefix with sensible defaults

### Security

- Fail-closed design: Lightning backend unreachable returns 503, never 200
- All secret comparisons use constant-time XOR accumulation (never `Arrays.equals`)
- Root keys wrapped in `SensitiveBytes` with explicit zeroization
- Macaroon values never logged in full; only token IDs appear in logs
- `FileBasedRootKeyStore` returns defensive copies of root keys
- Sig byte array zeroization in `MacaroonMinter` prevents key material leakage
- LND macaroon file size guard rejects unexpectedly large credential files

### Fixed

- Synchronized `InMemoryRootKeyStore` to prevent race conditions under concurrent access
- Health gauge uses cached value instead of blocking `isHealthy()` call
- `TokenBucketRateLimiter` bucket count race condition resolved
- `LndBackend.close()` properly awaits channel termination instead of fire-and-forget
- LNbits response timestamps parsed from actual API response instead of fabricated
- Standardized logging to `System.Logger` across all modules, replacing mixed SLF4J usage
- Shared L402 header parsing contract via `L402HeaderComponents` eliminates divergent regex implementations
- Bounded metrics cardinality prevents unbounded tag explosion in Micrometer metrics
- Unknown caveat handling: skip unknown caveats per specification instead of rejecting
- Unknown caveat handling documentation corrected to match implementation behavior
- `WWW-Authenticate` header format corrected to `L402 version="0", token=`
- `MacaroonSerializer` validation for field types and lengths

[0.1.7]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/greenharborlabs/spring-boot-starter-paygate/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/greenharborlabs/spring-boot-starter-l402/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/greenharborlabs/spring-boot-starter-l402/releases/tag/v0.1.0
