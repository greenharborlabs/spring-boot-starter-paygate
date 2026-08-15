# Agent Macaroon Delegation Guide

This guide describes the delegation behavior implemented by Paygate's first-party L402 flow. It is aimed at clients that already possess a paid L402 credential and want to attenuate it before handing it to another process or agent.

## Obtain and use an L402 credential

A protected request returns `402 Payment Required` with a signed macaroon and a Lightning invoice in the `WWW-Authenticate` challenge. The client pays the invoice to obtain the 32-byte preimage, then retries with:

```http
Authorization: L402 <base64-macaroon>:<64-character-hex-preimage>
```

The macaroon is issued before payment; the payment preimage is the proof that unlocks it. Both values are bearer credentials and must be protected from logs, URLs, analytics, and untrusted storage.

## Boundaries on newly issued credentials

Paygate issues identifier-v1 macaroons with these first-party caveats, in order:

```text
services=<service-name>:0
route=<canonical-registered-route>
method=<actual-http-method>
<service-name>_capabilities=<comma-separated-ceiling-or-~>
<service-name>_valid_until=<epoch-second>
```

- `route` is the exact canonical route selected by `PaygateEndpointRegistry`, not necessarily the literal request path.
- `method` is the actual request method. A HEAD request that inherits GET policy is still bound to HEAD.
- The capability value is an authorization ceiling. `~` means an authenticated empty capability set.
- `valid_until` is a Unix epoch second, not an ISO-8601 string.

The server validates in this security-sensitive order: parse, decode the identifier, verify the payment preimage, require the root key, inspect the credential cache, then either re-check cached caveats or perform full signature verification. A wrong preimage therefore cannot use root-key or cache behavior as a signature oracle.

## Holder attenuation

A holder can append a first-party caveat without knowing the root key. The new signature is:

```text
new_signature = HMAC-SHA256(key=current_signature, data="key=value")
```

The appended caveat and new signature form a different macaroon variant. Keep the identifier, location, and existing caveats unchanged; append the new caveat at the end. Paygate does not currently expose a high-level client attenuation helper, so client libraries must perform this operation with a Macaroon V2-compatible implementation and serialize the result with standard Base64 for the L402 header.

Attenuation can only preserve or narrow authority:

- `services`: the new service set must be a subset of the previous set.
- `method`: the new method set must be a subset of the previous set.
- `<service>_capabilities`: the new set must be a subset; named capabilities may narrow to `~`, but `~` cannot expand to a named grant.
- `<service>_valid_until`: the new epoch second must be no later than the previous value.
- `route`: an appended value must equal the already-issued canonical route.
- `path`: an optional holder constraint may narrow request paths with supported glob syntax.
- `client_ip`: an optional holder constraint may narrow use to one literal client IP string. Matching uses exact-string equality, with no DNS, CIDR, or network-range interpretation.

Malformed registered caveats and authority-expanding repetitions fail closed. Delegation-oriented verification skips unregistered caveat keys, so a custom caveat has no enforcement effect until the application registers a matching `CaveatVerifier`. First-party HTTP request validation must use `L402Validator`, which additionally requires Paygate's route, method, capability, identifier-v1, preimage, root-key, and cache policy boundaries.

## Practical delegation examples

An orchestrator holding a credential issued with `orders,products` can delegate a products-only variant by appending:

```text
<service-name>_capabilities=products
```

It can further restrict that variant to reads and a short lifetime:

```text
method=GET
<service-name>_valid_until=1786221000
```

These examples are illustrative. The expiry must be an epoch second no later than the issued expiry, and the delegated request must still match the original route and all earlier caveats. Paygate does not implement stateful caveats such as `max_uses` or `max_amount_sats`; applications that need them must design, register, and operate their own fail-closed verifier and state store.

## Cache and revocation behavior

Exact cached variants skip HMAC recomputation but still require proof-of-payment, an authoritative root-key lookup, and request-specific caveat verification. Removing a token's root key revokes both fresh and cached variants; the cache entry is also evicted best-effort. A request that obtained a defensive root-key copy immediately before concurrent revocation may complete, while any validation whose root-key lookup occurs after revocation fails.

Attenuated variants undergo full verification and may replace the single cached variant for that token ID. A failing different variant does not evict a separately cached valid variant merely because the signatures differ.

## Security checklist

- Never give a sub-agent broader capabilities or a later expiry than it needs.
- Never send a macaroon or preimage in a query string.
- Use TLS and avoid logging `Authorization` or `WWW-Authenticate` credential material.
- Preserve the original caveat order and use the exact `key=value` spelling when updating the signature chain.
- Treat a delegated macaroon and its payment preimage as a bearer credential pair.
- Obtain a fresh challenge after revocation or after compatibility changes invalidate an older credential.

## Further reading

- [Macaroons deep dive](macaroons-deep-dive.md)
- [Core module caveat and validator reference](../paygate-core/README.md)
- [Spring auto-configuration and route identity](../paygate-spring-autoconfigure/README.md)
- [L402 protocol adapter](../paygate-protocol-l402/README.md)
