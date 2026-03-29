# Macaroons: A Deep Dive for Spring Boot L402 Developers

A comprehensive technical guide to the macaroon authorization credential format,
written for Java/Spring Boot developers building the `spring-boot-starter-l402` library.

---

## Table of Contents

1. [What Are Macaroons?](#1-what-are-macaroons)
2. [Why Macaroons Were Invented](#2-why-macaroons-were-invented)
3. [Macaroon Anatomy](#3-macaroon-anatomy)
4. [The Cryptographic Construction](#4-the-cryptographic-construction-hmac-chaining)
5. [Attenuation and Delegation](#5-attenuation-and-delegation)
6. [Caveats: First-Party vs Third-Party](#6-caveats-first-party-vs-third-party)
7. [The Verification Algorithm](#7-the-verification-algorithm)
8. [How L402 Binds Macaroons to Lightning Payments](#8-how-l402-binds-macaroons-to-lightning-payments)
9. [The L402 Macaroon Identifier Format](#9-the-l402-macaroon-identifier-format)
10. [L402 Caveat Types](#10-l402-caveat-types)
11. [Macaroons vs JWTs](#11-macaroons-vs-jwts)
12. [Security Properties and Threat Model](#12-security-properties-and-threat-model)
13. [Implementation Guide for Java/Spring Boot](#13-implementation-guide-for-javaspring-boot)

---

## 1. What Are Macaroons?

A macaroon is a bearer authorization credential -- a small token that a server
issues and a client presents on subsequent requests to prove it is authorized.
If you have used cookies, OAuth access tokens, or JWTs, macaroons solve the
same fundamental problem: "should this request be allowed?"

What makes macaroons different is a single property: **anyone holding a valid
macaroon can add restrictions to it, but nobody can remove restrictions.** The
server does not need to be involved. The token holder just extends a
cryptographic chain, and the resulting token is strictly weaker than the
original.

This property is called **attenuation**, and it is the reason macaroons exist.

Macaroons were introduced in a 2014 paper by Arnar Birgisson, Joe Gibbs Politz,
Ulfar Erlingsson, Ankur Taly, Michael Vrable, and Mark Lentczner, published at
the Network and Distributed System Security Symposium (NDSS). The paper's full
title is "Macaroons: Cookies with Contextual Caveats for Decentralized
Authorization in the Cloud." The construction emerged from Google's need to
handle authorization across distributed cloud services without centralized
token-validation databases.

The name "macaroon" is a play on "cookie" -- another baked good that serves
as a bearer credential in HTTP.

---

## 2. Why Macaroons Were Invented

To understand why macaroons matter, consider what came before them and what
each approach gets wrong for distributed, delegated authorization.

### Cookies

HTTP cookies are opaque blobs. The server sets a cookie; the client sends it
back. The server must look up the cookie in a database to know what it means.

Problems:

- No delegation. You cannot give someone a "weaker" version of your cookie.
- Server-side state required. Every cookie lookup hits a database.
- No composability across services.

### OAuth 2.0 Tokens

OAuth tokens carry scopes (e.g., `read:email`, `write:repo`). They support
delegation through the authorization code flow.

Problems:

- Delegation requires server round-trips. To get a more restricted token,
you must ask the authorization server to issue one.
- Scope is fixed at issuance. The token holder cannot further restrict it.
- Token introspection requires network calls to the authorization server.

### JWTs (JSON Web Tokens)

JWTs encode claims as a signed JSON payload. They are self-contained -- the
verifier does not need a database lookup, just the signing key (or public key
for asymmetric JWTs).

Problems:

- Claims are fixed at issuance. To change permissions, you need a new JWT.
- No attenuation. You cannot add restrictions without the signing key.
- Overprivileged by default. If you receive a JWT with broad permissions,
you cannot narrow it before passing it to a sub-service.

### What Macaroons Fix

Macaroons combine the self-contained verification of JWTs with a cryptographic
construction that allows **anyone** to add restrictions (caveats) without
possessing the secret key. The key insight from the Google paper:

> "Macaroons are based on a construction that uses nested, chained MACs in a
> manner that is highly efficient, easy to deploy, and widely applicable."

This makes macaroons ideal for:

- **Microservice architectures** where service A needs to delegate a subset
of its authority to service B.
- **Agent-to-agent delegation** where an AI agent receives a token and must
hand a restricted version to a sub-agent.
- **Pay-per-request APIs (L402)** where the token must be bound to a specific
payment without the server needing to track session state.

---

## 3. Macaroon Anatomy

A macaroon has four components:

```
+----------------------------------------------------------+
|  MACAROON                                                |
|                                                          |
|  Location:    "https://api.example.com"    (optional)    |
|  Identifier:  "key-id-001"                 (public)      |
|  Caveats:     ["account = 12345",          (restrictions)|
|                "action = read",                          |
|                "expires = 1735689600"]                    |
|  Signature:   a1b2c3d4e5f6...              (HMAC chain)  |
|                                                          |
+----------------------------------------------------------+
```

### Location (optional)

A hint indicating which service the macaroon targets. This is NOT verified
cryptographically -- it exists purely for the client's convenience in knowing
where to send the token. In many implementations (including L402), the location
is empty or omitted.

### Identifier

A public value that the verifying server uses to look up the **root key** --
the secret used to mint the macaroon. The identifier is the starting input to
the HMAC chain.

In L402, the identifier has a specific binary structure containing the protocol
version, a token ID, and the Lightning payment hash (covered in section 9).

### Caveats

An ordered list of restrictions. Each caveat is a predicate -- a condition that
must be true for the macaroon to be valid. Caveats are strings, typically
encoded as `key = value` pairs.

Examples:

- `account = 12345` -- this token only works for account 12345
- `action = read` -- only read operations are allowed
- `time < 2025-12-31T23:59:59Z` -- expires at this timestamp
- `ip = 192.168.1.0/24` -- only valid from this IP range

Each caveat is cryptographically bound into the signature chain. Adding a
caveat changes the signature. Removing a caveat invalidates it.

### Signature

The final output of the HMAC chain. This is what the server verifies. It is
a 32-byte value (for HMAC-SHA256) that proves:

1. The macaroon was originally minted by someone who knew the root key.
2. The exact sequence of caveats is intact and unmodified.

---

## 4. The Cryptographic Construction (HMAC Chaining)

This is the core of how macaroons work. The construction is elegant and
surprisingly simple.

### Background: What is HMAC?

HMAC (Hash-based Message Authentication Code) takes two inputs -- a **key**
and a **message** -- and produces a fixed-size output (32 bytes for SHA-256).

Properties that matter here:

- Given the output, you cannot recover the key.
- Given the output, you cannot find a different message that produces the
same output (without the key).
- Even a one-bit change in the message produces a completely different output.

In Java, this is `javax.crypto.Mac` with the `HmacSHA256` algorithm.

### Step-by-Step: Minting a Macaroon

Let us walk through creating a macaroon with two caveats using concrete
(simplified) hex values.

**Setup:**

```
Root Key (K):   4f2379139f...  (32 bytes, server secret)
Identifier (I): "key-001"
```

**Step 1: Compute the initial signature**

```
sig_0 = HMAC(key=K, message=I)
      = HMAC("4f2379139f...", "key-001")
      = "e3d936ee94..."                     (32 bytes)
```

**Step 2: Add first caveat "account = 12345"**

The output of the previous HMAC becomes the KEY for the next one:

```
sig_1 = HMAC(key=sig_0, message="account = 12345")
      = HMAC("e3d936ee94...", "account = 12345")
      = "7a82c4f109..."                     (32 bytes)
```

**Step 3: Add second caveat "action = read"**

```
sig_2 = HMAC(key=sig_1, message="action = read")
      = HMAC("7a82c4f109...", "action = read")
      = "b5e8a31d02..."                     (32 bytes)
```

**The final macaroon:**

```
Identifier: "key-001"
Caveats:    ["account = 12345", "action = read"]
Signature:  "b5e8a31d02..."   (this is sig_2)
```

Here is the full flow as a diagram:

```
  Root Key (K)
       |
       v
  HMAC(K, "key-001")  ──────────────────>  sig_0
                                              |
                                              v
                         HMAC(sig_0, "account = 12345")  ──>  sig_1
                                                                |
                                                                v
                                        HMAC(sig_1, "action = read")  ──>  sig_2
                                                                              |
                                                                              v
                                                                     FINAL SIGNATURE
```

The critical insight: **each HMAC output becomes the key for the next HMAC**.
This is the "chain" in HMAC chaining.

### Why This Enables Attenuation

To add a new caveat, you need the current signature (which is public -- it is
part of the token). You do NOT need the root key. You just compute:

```
sig_3 = HMAC(key=sig_2, message="expires = 1735689600")
```

And the new macaroon is:

```
Identifier: "key-001"
Caveats:    ["account = 12345", "action = read", "expires = 1735689600"]
Signature:  sig_3
```

But to REMOVE a caveat -- say, to go from sig_2 back to sig_1 -- you would
need to reverse an HMAC, which is computationally infeasible. The one-way
nature of HMAC is what makes caveat removal impossible.

### Why This Does Not Leak the Root Key

Notice that the root key K is used exactly once: to compute sig_0. After that,
only HMAC outputs are used as keys. An attacker who intercepts a macaroon sees
sig_2 (the final signature) and the caveats, but cannot work backward through
the HMAC chain to recover K or any intermediate signature. Without K, they
cannot mint new macaroons from scratch -- they can only attenuate existing ones.

---

## 5. Attenuation and Delegation

Attenuation is the practical consequence of HMAC chaining. It enables a
pattern that no other common token format supports: **the token holder can
weaken the token without server involvement**.

### Concrete Delegation Example

Consider an AI orchestrator agent that receives a macaroon granting full API
access:

```
Original Macaroon (held by orchestrator):
  Identifier: "token-abc"
  Caveats:    ["services = my_api:0"]
  Signature:  "a1a1a1..."
```

The orchestrator needs to delegate a task to a research sub-agent, but the
sub-agent should only be able to read data, not write it, and only for the
next 5 minutes:

```
Attenuated Macaroon (given to sub-agent):
  Identifier: "token-abc"
  Caveats:    ["services = my_api:0",
               "action = read",
               "expires = 1709825100"]
  Signature:  "c3c3c3..."    (different from original -- new chain tail)
```

The orchestrator computed this by:

1. Taking its own macaroon's signature ("a1a1a1...")
2. HMAC-ing "action = read" with it
3. HMAC-ing "expires = 1709825100" with the result

No server call. No token exchange. No OAuth dance. The sub-agent gets a valid
but strictly weaker token.

### The Delegation Chain

This can go multiple levels deep:

```
Server (root key holder)
  |
  |  issues macaroon: "services = my_api:0"
  v
Orchestrator Agent
  |
  |  attenuates: + "action = read"
  |              + "expires = 1709825100"
  v
Research Sub-Agent
  |
  |  attenuates: + "endpoint = /v1/search"
  v
Search Worker
```

Each entity in the chain can only make the token weaker. The Search Worker
cannot remove the `action = read` caveat to gain write access. It cannot
remove the `expires` caveat to get a longer-lived token. It can only add
more restrictions.

### Why This Matters for L402

In the L402 world, a client pays once for a macaroon. That macaroon might
grant access to multiple endpoints at various capability levels. The client
can then:

- Share a restricted version with a collaborator (read-only access).
- Give a time-limited version to a temporary worker.
- Create an endpoint-specific version for a single-purpose automation.

All without paying again or asking the server to mint new tokens.

---

## 6. Caveats: First-Party vs Third-Party

Macaroons support two types of caveats. L402 primarily uses first-party
caveats, but understanding both is important for the full picture.

### First-Party Caveats

A first-party caveat is a condition that the **verifying server checks
directly**. The server has all the information it needs to evaluate the
predicate.

```
First-Party Caveat Examples:
  "account = 12345"           Server checks: does the request target account 12345?
  "action = read"             Server checks: is this a read operation?
  "time < 2025-12-31"         Server checks: is the current time before the deadline?
  "ip = 10.0.0.0/8"           Server checks: is the client IP in this range?
  "services = my_api:0"       Server checks: is the requested service in the list?
```

First-party caveats are simple strings. The server must implement a
**verifier function** for each caveat type it supports. During verification,
the server evaluates every caveat against the current request context. If any
caveat fails, the entire macaroon is rejected.

### Third-Party Caveats

A third-party caveat is a condition that requires **another service** to
attest to its truth. The verifying server cannot check it alone.

```
Third-Party Caveat Flow:

  +---------+        +----------+        +---------------+
  | Client  |        |  Server  |        | Third Party   |
  |         |        | (target) |        | (e.g., IdP)   |
  +---------+        +----------+        +---------------+
       |                   |                      |
       |  present macaroon |                      |
       |  with 3rd-party   |                      |
       |  caveat           |                      |
       |------------------>|                      |
       |                   |                      |
       |  "need discharge  |                      |
       |   from IdP"       |                      |
       |<------------------|                      |
       |                   |                      |
       |  "prove I am admin"                      |
       |----------------------------------------->|
       |                                          |
       |           discharge macaroon             |
       |<-----------------------------------------|
       |                   |                      |
       |  present original |                      |
       |  macaroon + bound |                      |
       |  discharge        |                      |
       |------------------>|                      |
       |                   |                      |
       |  OK (verified)    |                      |
       |<------------------|                      |
```

A third-party caveat is constructed differently from a first-party caveat:

1. The minter generates a random **caveat key**.
2. The minter encrypts the caveat key and a predicate for the third party,
  producing a **verification ID** (only the third party can decrypt it).
3. The caveat key is HMAC-ed into the macaroon's signature chain (just like
  a first-party caveat uses its string).
4. The third party, when asked, decrypts the verification ID, checks the
  predicate, and issues a **discharge macaroon** -- itself a macaroon
   whose root key is the caveat key.
5. The client **binds** the discharge macaroon to the original macaroon
  (using `HMAC(original_sig, discharge_sig)`) to prevent the discharge
   from being used with a different macaroon.

### Which Type Does L402 Use?

**L402 uses first-party caveats exclusively.** All L402 caveats (services,
capabilities, constraints, expiration) are conditions that the verifying
server checks directly.

However, the L402 protocol itself acts somewhat like a third-party caveat
system at a higher level: the Lightning payment functions as external
attestation. The payment hash in the macaroon identifier is essentially
saying "a third party (the Lightning Network) must attest that this invoice
was paid." But this is not implemented using the macaroon third-party caveat
mechanism -- it is handled at the protocol level by requiring the preimage
alongside the macaroon.

---

## 7. The Verification Algorithm

This is the algorithm your Spring Boot starter must implement. It has two
phases: **signature verification** (cryptographic) and **caveat satisfaction**
(logical).

### Pseudocode

```
function verifyMacaroon(macaroon, rootKey, requestContext):

    // === Phase 1: Signature Verification ===
    // Reconstruct the HMAC chain from the root key

    computedSig = HMAC(rootKey, macaroon.identifier)

    for each caveat in macaroon.caveats:
        computedSig = HMAC(computedSig, caveat)

    if NOT constantTimeEquals(computedSig, macaroon.signature):
        return REJECT("signature mismatch -- macaroon was tampered with
                        or not issued by this server")

    // === Phase 2: Caveat Satisfaction ===
    // Every caveat must evaluate to TRUE agaveat(key, value, requestContext)

        if NOT satisfied:
            return REJECT("caveat not satisfied: " + caveat)

    return ACCEPTainst the request

    for each caveat in macaroon.caveats:
        key, value = parseCaveat(caveat)   // split on "="

        satisfied = evaluateC
```

### Walkthrough with Concrete Values

Given:

```
Root Key:     4f9979139f39a0b8de0c11111c5f2e99    (16 bytes, hex)
Macaroon:
  Identifier: "token-001"
  Caveats:    ["services = my_api:0", "expires = 1735689600"]
  Signature:  b5e8a31d0247...                      (32 bytes, hex)
```

**Phase 1 execution:**

```
Step 1:  sig = HMAC("4f2379139f...", "token-001")
         sig = "e3d936ee94..."

Step 2:  sig = HMAC("e3d936ee94...", "services = my_api:0")
         sig = "7a82c4f109..."

Step 3:  sig = HMAC("7a82c4f109...", "expires = 1735689600")
         sig = "b5e8a31d0247..."

Compare:  computed "b5e8a31d0247..." == provided "b5e8a31d0247..."  --> MATCH
```

**Phase 2 execution:**

```
Caveat "services = my_api:0":
  Parse:  key = "services", value = "my_api:0"
  Check:  Is the requested service "my_api" at tier 0?
  Result: YES --> satisfied

Caveat "expires = 1735689600":
  Parse:  key = "expires", value = "1735689600" (Unix timestamp)
  Check:  Is current time < 1735689600?
  Result: Depends on current time
```

If both caveats are satisfied AND the signature matched, the macaroon is valid.

### Important: Constant-Time Comparison

The signature comparison MUST use constant-time equality to prevent timing
attacks. In Java:

```java
import java.security.MessageDigest;

// CORRECT -- constant time
boolean valid = MessageDigest.isEqual(computedSig, providedSig);

// WRONG -- variable time, leaks information
boolean valid = Arrays.equals(computedSig, providedSig);
```

`MessageDigest.isEqual()` always compares all bytes regardless of where a
mismatch occurs, preventing an attacker from guessing the signature one byte
at a time by measuring response times.

### The Full Verification Flow in L402

In L402, verification has an additional step: preimage verification.

```
L402 Verification Flow:

  Client sends:  Authorization: L402 <base64_macaroon>:<hex_preimage>

  +---------------------------------------------+
  |  1. Decode the macaroon from base64          |
  |  2. Extract identifier from macaroon         |
  |  3. Parse identifier to get:                 |
  |     - version                                |
  |     - payment_hash                           |
  |     - token_id                               |
  |  4. Look up root key using token_id          |
  |  5. Verify HMAC chain (Phase 1 above)        |
  |  6. Verify all caveats (Phase 2 above)       |
  |  7. Decode preimage from hex                 |
  |  8. Compute SHA256(preimage)                 |
  |  9. Compare with payment_hash from step 3    |
  |     If match: payment is confirmed           |
  |     If no match: REJECT                      |
  +---------------------------------------------+
```

Step 9 is the cryptographic binding between the macaroon and the Lightning
payment. Without a valid preimage, the macaroon is useless -- even if the
signature and caveats are valid.

---

## 8. How L402 Binds Macaroons to Lightning Payments

The binding between a macaroon and a Lightning Network payment is the
defining innovation of the L402 protocol. Here is how it works end-to-end.

### The Lightning Payment Primitive

In the Lightning Network, every payment involves:

- A **preimage**: a random 32-byte secret, known initially only to the payee.
- A **payment hash**: `SHA256(preimage)`, included in the invoice.

When a payer pays a Lightning invoice, the preimage is atomically revealed to
them as part of the payment settlement. This is enforced by the Hash
Time-Locked Contract (HTLC) mechanism -- the payer cannot receive the preimage
without the payment going through.

### The Binding Mechanism

```
L402 Payment Binding:

  SERVER                                          CLIENT
    |                                               |
    |  1. Generate preimage (random 32 bytes)        |
    |     preimage = 0x7f3a...                       |
    |                                               |
    |  2. Compute payment_hash = SHA256(preimage)    |
    |     payment_hash = 0x163102a9...               |
    |                                               |
    |  3. Create Lightning invoice with payment_hash |
    |     lnbc10n1p...                               |
    |                                               |
    |  4. Mint macaroon with payment_hash            |
    |     embedded in the identifier                 |
    |     identifier = [version | payment_hash |     |
    |                    token_id]                   |
    |                                               |
    |  5. Respond with HTTP 402:                     |
    |     WWW-Authenticate: L402                     |
    |       macaroon="<base64>",                     |
    |       invoice="lnbc10n1p..."                   |
    |<----------------------------------------------|
    |                                               |
    |                              6. Client pays   |
    |                                 Lightning     |
    |                                 invoice       |
    |                                               |
    |                              7. Client        |
    |                                 receives      |
    |                                 preimage      |
    |                                 as proof      |
    |                                 of payment    |
    |                                               |
    |  8. Client retries with:                       |
    |     Authorization: L402 <macaroon>:<preimage>  |
    |---------------------------------------------->|
    |                                               |
    |  9. Server verifies:                           |
    |     a. Macaroon signature (HMAC chain)         |
    |     b. All caveats satisfied                   |
    |     c. SHA256(preimage) == payment_hash         |
    |        from macaroon identifier                |
    |                                               |
    |  10. Serve the protected resource              |
    |<----------------------------------------------|
```

### Why This Binding Is Secure

The binding relies on two cryptographic properties:

1. **Preimage resistance of SHA-256**: Given `payment_hash`, an attacker
  cannot compute `preimage`. The only way to obtain the preimage is to
   pay the Lightning invoice.
2. **HMAC integrity of the macaroon**: The `payment_hash` is embedded in the
  macaroon identifier, which is the first input to the HMAC chain. Any
   modification to the payment hash would produce a different signature,
   which the server would reject.

Together, these properties mean:

- You cannot use a macaroon without paying (you need the preimage).
- You cannot swap a payment hash into someone else's macaroon (the
signature would not match).
- You cannot forge a preimage (SHA-256 preimage resistance).
- You cannot replay someone else's preimage with your own macaroon (the
payment hash would not match).

---

## 9. The L402 Macaroon Identifier Format

In standard macaroons, the identifier is an opaque string. In L402, the
identifier has a specific binary structure.

### Identifier Layout

```
+----------+-------------------+-------------------+
|  Version |   Payment Hash    |     Token ID      |
|  (2 B)   |     (32 B)        |     (32 B)        |
+----------+-------------------+-------------------+
   uint16       [32]byte            [32]byte

Total: 66 bytes
```

### Field Descriptions

**Version (2 bytes, uint16, big-endian)**

The protocol version. Currently `0`. This field allows the identifier format
to evolve in future versions of the L402 specification.

**Payment Hash (32 bytes)**

The SHA-256 hash of the Lightning invoice preimage. This is the cryptographic
link between the macaroon and the payment. The server generates a random
preimage, computes `SHA256(preimage)` to get this value, and embeds it in the
identifier.

Example (hex):

```
163102a9c88fa4ec9ac9937b6f070bc3e27249a81ad7a05f398ac5d7d16f7bea
```

**Token ID (32 bytes)**

A unique random identifier for this specific L402 token. This is distinct from
the macaroon's own identifier -- it is used for:

- Looking up the root key (the server maps token ID to the root key it used
to mint this macaroon).
- Tracking usage, rate limiting, and metering across multiple macaroons that
may share the same payment.
- Enabling revocation (revoking a token ID without revoking the underlying
macaroon structure).

Example (hex):

```
fed74b3ef24820f440601eff5bfb42bef4d615c4948cec8aca3cb15bd23f1013
```

### Why Not Use the Macaroon Identifier Directly?

The macaroon identifier (the full 66-byte structure) could theoretically serve
as the lookup key. But the L402 specification separates the token ID because:

- A token ID can be revoked (e.g., on a service tier upgrade) without
invalidating the cryptographic structure of the macaroon.
- Multiple macaroons can share a token ID (e.g., when a single payment
grants access to multiple services, each with its own macaroon).
- The token ID provides a stable reference for usage metering even if
caveats are attenuated.

---

## 10. L402 Caveat Types

L402 caveats follow a hierarchical structure: services, then capabilities
within each service, then constraints on each capability. All caveats are
first-party (verified by the server directly).

### Caveat Encoding

Each caveat is a UTF-8 string in the format:

```
key = value
```

The key and value are separated by `=` (space, equals sign, space). The
key uniquely identifies the caveat type. The value's format depends on the
key.

### Services Caveat

Specifies which services the macaroon grants access to and at what tier.

```
services = lightning_loop:0
```

Format: `service_name:tier` where tier is an integer (0 = basic). Multiple
services are comma-separated:

```
services = lightning_loop:0,lightning_pool:1
```

A macaroon without a services caveat is unrestricted in which services it
can access (within the scope of the server's domain). Adding a services
caveat restricts access to only the listed services.

### Capabilities Caveat

Restricts what operations are available within a service. The key is
`{service_name}_capabilities`:

```
lightning_loop_capabilities = loop_out,loop_in
```

If this caveat is absent for a given service, all capabilities of that
service are available. When present, only the listed capabilities are
allowed.

Multiple capabilities caveats for the same service must be progressively
more restrictive (each subsequent caveat must be a subset of the previous):

```
lightning_loop_capabilities = loop_out,loop_in    (first: allows both)
lightning_loop_capabilities = loop_out             (second: removes loop_in)
```

### Constraints Caveats

Further limit specific capabilities with measurable constraints. The key is
`{capability_name}_{constraint_type}`:

```
loop_out_monthly_volume_sats = 200000000
```

Like capabilities caveats, constraints must be progressively more restrictive.
A later constraint caveat cannot relax an earlier one:

```
loop_out_monthly_volume_sats = 200000000    (first: up to 200M sats/month)
loop_out_monthly_volume_sats = 100000000    (second: reduces to 100M sats/month)
```

### Expiration / Timeout Caveat

A timeout can be specified as a constraint that limits the validity window:

```
expires = 1735689600
```

Or as a relative timeout (seconds from issuance):

```
timeout = 86400
```

The server converts relative timeouts to absolute timestamps when verifying.

### Custom Caveats

The L402 specification allows for application-defined caveats beyond the
standard types. Your Spring Boot starter can define caveats specific to your
service:

```
endpoint = /v1/analyze
request_limit = 100
tier = premium
```

The only requirement: the server must implement a verifier function for each
custom caveat key it recognizes.

### Caveat Hierarchy Diagram

```
  services = my_api:0
       |
       +--- my_api_capabilities = search,analyze
       |         |
       |         +--- search_max_results = 50
       |         |
       |         +--- analyze_timeout_seconds = 30
       |
       +--- expires = 1735689600
```

Each level further restricts the token. A macaroon with all of these caveats
can only:

- Access the `my_api` service at tier 0.
- Use the `search` and `analyze` capabilities.
- Return at most 50 search results per request.
- Run analysis for at most 30 seconds.
- Be used before the expiration timestamp.

---

## 11. Macaroons vs JWTs

This comparison is relevant because many Java/Spring Boot developers have
extensive JWT experience. Understanding the differences helps frame what
macaroons do differently and why L402 uses them.


| Property                         | JWT (HS256/RS256)                                   | Macaroon (HMAC-SHA256)                                   |
| -------------------------------- | --------------------------------------------------- | -------------------------------------------------------- |
| **Token type**                   | Authentication + Authorization                      | Authorization only                                       |
| **Self-contained**               | Yes (claims in payload)                             | Partially (caveats in token, but root key lookup needed) |
| **Signing**                      | HMAC (symmetric) or RSA/ECDSA (asymmetric)          | HMAC only (symmetric)                                    |
| **Claims/Caveats**               | Fixed at issuance                                   | Can be extended by anyone holding the token              |
| **Attenuation**                  | Not possible without re-signing                     | Built-in (HMAC chaining)                                 |
| **Delegation**                   | Requires new token from issuer                      | Token holder can delegate directly                       |
| **Verification without network** | Yes (if public key available)                       | Yes (if root key available)                              |
| **Revocation**                   | Difficult (must check blocklist or wait for expiry) | Same challenge -- bearer tokens in general               |
| **Payload visibility**           | Claims are base64-encoded (readable)                | Caveats are readable strings                             |
| **Payload confidentiality**      | None (unless JWE)                                   | None (caveats are plaintext)                             |
| **Standard**                     | RFC 7519 (well-established)                         | Academic paper + implementations (no RFC)                |
| **Library ecosystem**            | Massive (every language, multiple per language)     | Small (libmacaroons, jmacaroons, go-macaroon)            |
| **Typical size**                 | 200-800 bytes                                       | 100-400 bytes                                            |
| **Primary use case**             | User identity across services                       | Capability delegation in distributed systems             |


### When to Use Which

**Use JWTs when:**

- You need to convey identity ("this is user 42").
- You need asymmetric verification (public key distribution).
- You are integrating with existing OAuth2/OIDC infrastructure.
- You do not need runtime attenuation.

**Use Macaroons when:**

- You need delegable, attenuable authorization tokens.
- Capability-based access control fits your model (L402, microservices).
- You want to minimize server-side state.
- Tokens must flow through a chain of services, each adding restrictions.

**L402 uses macaroons because:**

- The token must be bound to a payment (payment hash in identifier).
- Agents and users must be able to attenuate tokens before delegation.
- No identity is needed -- the payment IS the authorization.
- The HMAC construction is minimal and fast (a few microseconds per operation).

---

## 12. Security Properties and Threat Model

### Properties Provided by Macaroons

**1. Unforgeability**

Without the root key, an attacker cannot create a valid macaroon from scratch.
The HMAC chain starts from the root key, and HMAC is a pseudorandom function --
its output is indistinguishable from random without the key.

**2. Integrity (tamper-proofing)**

Modifying any caveat, removing a caveat, reordering caveats, or changing the
identifier invalidates the signature. The HMAC chain binds all components in
sequence.

**3. Attenuation-only modification**

Any holder can add caveats (weakening the token) but cannot remove them
(strengthening it). This is enforced by the one-way nature of HMAC.

**4. Non-escalation**

A delegated (attenuated) macaroon can never grant more authority than its
parent. This is the formal property that makes delegation safe.

### Threats and Mitigations

```
+-------------------+-------------------------------------------------+----------------------------------+
| Threat            | Description                                     | Mitigation                       |
+-------------------+-------------------------------------------------+----------------------------------+
| Token theft       | Attacker intercepts the macaroon + preimage     | Use TLS for all transport.       |
| (bearer token     | and replays it.                                 | Add IP caveats. Add short        |
|  problem)         |                                                 | expiration caveats.              |
+-------------------+-------------------------------------------------+----------------------------------+
| Caveat removal    | Attacker tries to remove a restrictive caveat   | Impossible. HMAC chain makes     |
|                   | to escalate privileges.                         | removal computationally          |
|                   |                                                 | infeasible.                      |
+-------------------+-------------------------------------------------+----------------------------------+
| Signature forgery | Attacker tries to create a valid signature      | Requires root key. HMAC is       |
|                   | for an arbitrary set of caveats.                | a PRF; forgery probability is    |
|                   |                                                 | negligible.                      |
+-------------------+-------------------------------------------------+----------------------------------+
| Replay attack     | Attacker reuses a valid macaroon after it       | Add "expires" or "timeout"       |
|                   | should no longer be valid.                      | caveats. Server-side nonce       |
|                   |                                                 | tracking for one-time-use.       |
+-------------------+-------------------------------------------------+----------------------------------+
| Root key          | Attacker obtains the server's root key.         | Rotate root keys. Store them     |
| compromise        |                                                 | in a secrets manager (Vault,     |
|                   |                                                 | AWS KMS). The root key is the    |
|                   |                                                 | crown jewel -- protect it like   |
|                   |                                                 | a TLS private key.               |
+-------------------+-------------------------------------------------+----------------------------------+
| Payment hash      | Attacker tries to use a macaroon without        | Preimage resistance of SHA-256.  |
| bypass            | paying by guessing or brute-forcing the         | 2^256 search space makes this    |
|                   | preimage.                                       | infeasible.                      |
+-------------------+-------------------------------------------------+----------------------------------+
| Caveat confusion  | Poorly designed caveat verifiers that accept     | Implement strict caveat parsing. |
|                   | unexpected values or fail open.                 | Unknown caveats MUST cause       |
|                   |                                                 | rejection, not be ignored.       |
+-------------------+-------------------------------------------------+----------------------------------+
```

### Critical Rule: Unknown Caveats MUST Fail Closed

If the server encounters a caveat key it does not recognize, it MUST reject
the macaroon. Never ignore unknown caveats. The reason: an attenuator may have
added a caveat expecting it to be enforced. If the server silently ignores it,
the attenuation is ineffective and the token grants more access than intended.

This is a common implementation mistake. Your caveat verifier should have a
default case that returns `false` for any unrecognized key.

### Bearer Token Limitations

Like all bearer tokens (cookies, JWTs, OAuth tokens), macaroons are vulnerable
to theft. If an attacker obtains a valid macaroon + preimage, they can use it.
Macaroons do not provide:

- **Proof of identity** -- they say "the holder is authorized," not "the
holder is Alice."
- **Proof of possession** -- unlike mTLS client certificates, presenting a
macaroon does not prove you are the intended recipient.

For L402, this is acceptable because **the payment IS the authorization**. The
system does not care who pays or who uses the token -- it cares that the token
is valid and the payment was made.

---

## 13. Implementation Guide for Java/Spring Boot

This section maps the concepts above to the concrete Java code you need to
write for `spring-boot-starter-l402`.

### What We Need to Build

Based on the L402 specification, the macaroon implementation requires:

1. **Macaroon minting** -- create a macaroon with the L402 identifier format.
2. **Macaroon serialization** -- encode/decode to binary and base64.
3. **HMAC chain computation** -- the core cryptographic operation.
4. **Caveat addition** -- append caveats and extend the HMAC chain.
5. **Signature verification** -- reconstruct the HMAC chain and compare.
6. **Caveat verification** -- parse and evaluate caveats against request context.
7. **Preimage verification** -- SHA-256 hash and compare with payment hash.
8. **L402 identifier parsing** -- extract version, payment hash, and token ID.

### Core Classes

```
com.greenharborlabs.l402.macaroon
  |
  +-- Macaroon.java              Value object: identifier, caveats, signature
  +-- MacaroonIdentifier.java    L402 identifier: version, paymentHash, tokenId
  +-- MacaroonMinter.java        Creates new macaroons from root key
  +-- MacaroonVerifier.java      HMAC verification + caveat checking
  +-- CaveatVerifier.java        Interface for pluggable caveat evaluation
  +-- HmacEngine.java            Thin wrapper around javax.crypto.Mac
```

### HmacEngine: The Foundation

Everything rests on a single cryptographic operation:

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HmacEngine {
    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Compute HMAC-SHA256(key, message).
     * This is the ONLY cryptographic operation needed for macaroons.
     */
    public static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }
}
```

This single method is used for:

- Minting: chaining from root key through identifier and caveats.
- Verification: reconstructing the chain and comparing signatures.
- Attenuation: extending the chain with new caveats.

No other cryptographic primitives are needed for first-party caveat macaroons.

### MacaroonMinter: Creating Macaroons

```java
public class MacaroonMinter {

    public Macaroon mint(byte[] rootKey, MacaroonIdentifier identifier,
                         List<String> caveats) {
        byte[] idBytes = identifier.toBytes();  // 66-byte L402 identifier

        // Start the HMAC chain
        byte[] sig = HmacEngine.hmac(rootKey, idBytes);

        // Extend chain through each caveat
        for (String caveat : caveats) {
            sig = HmacEngine.hmac(sig, caveat.getBytes(UTF_8));
        }

        return new Macaroon(idBytes, caveats, sig);
    }
}
```

### MacaroonVerifier: The Verification Algorithm

```java
public class MacaroonVerifier {

    private final Map<String, CaveatVerifier> verifiers;

    public boolean verify(Macaroon macaroon, byte[] rootKey,
                          RequestContext context) {
        // Phase 1: Signature verification
        byte[] computedSig = HmacEngine.hmac(rootKey, macaroon.identifier());

        for (String caveat : macaroon.caveats()) {
            computedSig = HmacEngine.hmac(computedSig,
                                          caveat.getBytes(UTF_8));
        }

        if (!MessageDigest.isEqual(computedSig, macaroon.signature())) {
            return false;  // tampered or not issued by this server
        }

        // Phase 2: Caveat satisfaction
        for (String caveat : macaroon.caveats()) {
            String[] parts = caveat.split(" = ", 2);
            if (parts.length != 2) return false;

            String key = parts[0].trim();
            String value = parts[1].trim();

            CaveatVerifier verifier = verifiers.get(key);
            if (verifier == null) return false;  // UNKNOWN CAVEATS FAIL CLOSED

            if (!verifier.verify(value, context)) return false;
        }

        return true;
    }
}
```

### L402 Identifier: Parsing the Binary Structure

```java
public class MacaroonIdentifier {
    private final int version;          // 2 bytes
    private final byte[] paymentHash;   // 32 bytes
    private final byte[] tokenId;       // 32 bytes

    public static MacaroonIdentifier fromBytes(byte[] data) {
        if (data.length != 66) {
            throw new IllegalArgumentException(
                "L402 identifier must be 66 bytes, got " + data.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        int version = buf.getShort() & 0xFFFF;    // unsigned
        byte[] paymentHash = new byte[32];
        buf.get(paymentHash);
        byte[] tokenId = new byte[32];
        buf.get(tokenId);

        return new MacaroonIdentifier(version, paymentHash, tokenId);
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(66);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) version);
        buf.put(paymentHash);
        buf.put(tokenId);
        return buf.array();
    }
}
```

### Preimage Verification

```java
import java.security.MessageDigest;

public class PreimageVerifier {

    public static boolean verify(byte[] preimage, byte[] paymentHash) {
        try {
            byte[] computed = MessageDigest.getInstance("SHA-256")
                                          .digest(preimage);
            return MessageDigest.isEqual(computed, paymentHash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

### Estimated Code Size


| Component                                 | Estimated Lines |
| ----------------------------------------- | --------------- |
| HmacEngine                                | ~20             |
| Macaroon (value object)                   | ~50             |
| MacaroonIdentifier                        | ~60             |
| MacaroonMinter                            | ~30             |
| MacaroonVerifier                          | ~80             |
| PreimageVerifier                          | ~15             |
| CaveatVerifier interface + built-in impls | ~100            |
| Serialization (binary + base64)           | ~50             |
| **Total**                                 | **~400 lines**  |


This is the entire macaroon stack needed for L402, with zero external crypto
dependencies beyond `javax.crypto.Mac` and `java.security.MessageDigest` --
both part of the standard JDK.

### Spring Boot Integration Points

The macaroon code above is a pure library. The Spring Boot integration layer
wires it into the HTTP lifecycle:

```
HTTP Request
     |
     v
L402Filter (servlet filter or Spring Security filter)
     |
     +-- Extract Authorization header
     |     Format: "L402 <base64_macaroon>:<hex_preimage>"
     |
     +-- Decode macaroon from base64
     +-- Decode preimage from hex
     |
     +-- Parse L402 identifier from macaroon
     +-- Look up root key by token_id
     |
     +-- MacaroonVerifier.verify(macaroon, rootKey, requestContext)
     +-- PreimageVerifier.verify(preimage, paymentHash)
     |
     +-- If valid: continue filter chain (serve the resource)
     +-- If invalid: return 402 with new macaroon + invoice
```

The `@L402Protected` annotation on controller methods triggers this filter,
with configuration specifying the price and which caveat verifiers apply.

---

## Further Reading

- **Original paper**: Birgisson et al., "Macaroons: Cookies with Contextual
Caveats for Decentralized Authorization in the Cloud," NDSS 2014.
- **L402 specification**: [https://docs.lightning.engineering/the-lightning-network/l402](https://docs.lightning.engineering/the-lightning-network/l402)
- **L402 protocol spec**: [https://github.com/lightninglabs/L402/blob/master/protocol-specification.md](https://github.com/lightninglabs/L402/blob/master/protocol-specification.md)
- **Fly.io deep dive**: [https://fly.io/blog/macaroons-escalated-quickly/](https://fly.io/blog/macaroons-escalated-quickly/)
- **libmacaroons (C reference implementation)**: [https://github.com/rescrv/libmacaroons](https://github.com/rescrv/libmacaroons)
- **jmacaroons (Java, stale but useful for reference)**: [https://github.com/nitram509/jmacaroons](https://github.com/nitram509/jmacaroons)
- **LND macaroons guide**: [https://docs.lightning.engineering/the-lightning-network/l402/macaroons](https://docs.lightning.engineering/the-lightning-network/l402/macaroons)

