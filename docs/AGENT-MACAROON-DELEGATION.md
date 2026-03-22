# Agent Macaroon Delegation Guide

How AI agents (and any multi-tier client) obtain, use, and delegate L402 macaroons
to sub-agents with attenuated permissions.

## Overview

L402 combines HTTP 402 responses with macaroon-based credentials and Lightning
invoices. The critical property for agent workflows is that macaroons support
**attenuation** -- anyone holding a macaroon can add restrictions (caveats) to it
before passing it along, but **nobody can remove caveats**. This enables
least-privilege delegation without any server interaction.

## Step 1: Obtain a Macaroon

An agent calls a paygate-protected endpoint and receives a 402 challenge:

```
Agent-A  ->  GET /products
         <-  402 Payment Required
             WWW-Authenticate: L402 macaroon="abc123...", invoice="lnbc5000n1..."
```

The response contains:
- An **unsigned macaroon** with server-minted caveats (capabilities, expiry, etc.)
- A **Lightning invoice** the agent must pay

The macaroon is cryptographically useless until the invoice is paid. Agent-A pays
the 5000 sats via Lightning, obtaining a **preimage** as proof of payment.

## Step 2: Use the Macaroon

Agent-A replays the request with the macaroon and preimage:

```
Agent-A  ->  GET /products
             Authorization: L402 abc123...:preimage_hex
         <-  200 OK
```

The server verifies:
1. The macaroon signature chain is valid (originated from this server's root key)
2. The preimage matches the payment hash embedded in the macaroon identifier
3. All caveats are satisfied

## Step 3: Understand Caveats

The macaroon the server minted contains **first-party caveats** -- restrictions the
server checks on every request. These come from the endpoint configuration:

```yaml
paygate:
  endpoints:
    - path: /products/**
      price-sats: 5000
      timeout-seconds: 3600
      capabilities:
        - products:read
```

The resulting macaroon contains caveats like:

```
service = my-api
expires_at = 2026-03-21T19:00:00Z
capabilities = products:read
```

**The macaroon is NOT tied to a single request.** It is valid for any request that
satisfies all its caveats. In this example, Agent-A can call `GET /products`
repeatedly for the next hour with no additional payment.

## Step 4: Delegate to Sub-Agents (Attenuation)

This is where macaroons differentiate themselves from API keys. Agent-A can
**attenuate** the macaroon -- add stricter caveats -- and hand it to a sub-agent.
The cryptographic operation is:

```
new_signature = HMAC-SHA256(previous_signature, new_caveat)
```

Any L402 client library can do this locally. No server interaction required.

### Example: Orchestrator delegates to two sub-agents

```
Orchestrator Agent (pays 5000 sats)
  gets macaroon: [service=my-api, capabilities=products:read;orders:read, expires=1hr]

  |-- Research Agent (attenuated copy)
  |     added caveats: [capabilities=products:read, expires_at=+15min]
  |     -> can call GET /products, GET /products/{id}
  |     -> CANNOT call /orders (capability removed)
  |     -> expires after 15 minutes (tighter than the original 1hr)
  |
  |-- Order Agent (attenuated copy)
        added caveats: [capabilities=orders:read, expires_at=+30min]
        -> can call GET /orders
        -> CANNOT read /products
        -> expires after 30 minutes
```

One payment. Three different permission scopes. No trust required between agents --
the HMAC signature chain is cryptographically enforced. A sub-agent **cannot**
escalate its own permissions because it cannot forge the chain without the original
signing key.

### Attenuation rules

- **Anyone** can add caveats to a macaroon they hold
- **Nobody** can remove or weaken existing caveats
- The server checks **all** caveats; the tightest constraint wins
- Attenuated macaroons are fully self-contained (no server round-trip to delegate)

## Supported Caveat Types

Caveats the paygate server recognizes and enforces:

| Caveat | Format | Description | Stateless? |
|--------|--------|-------------|------------|
| `service` | `service = <name>` | Must match the configured service name | Yes |
| `expires_at` | `expires_at = <ISO-8601>` | Macaroon expires at this time | Yes |
| `capabilities` | `capabilities = <cap1>;...` | Required capabilities for the endpoint | Yes |
| `path` | `path = /products/**` | Restrict to a URL path pattern | Yes |
| `max_uses` | `max_uses = 10` | Limit total request count | No (server-side counter) |

**Stateless** caveats are verified purely from the macaroon + request data.
**Stateful** caveats (like `max_uses`) require the server to track usage against
the token ID.

## Access Control Models

These caveats can be combined to implement different access models:

### Time-bound access

The default model. Pay once, access until expiry.

```
expires_at = 2026-03-21T20:00:00Z
capabilities = products:read
```

### Metered access (call count)

Pay for a fixed number of requests. Requires server-side tracking.

```
max_uses = 10
capabilities = products:read
```

### Scoped delegation

Restrict a sub-agent to a subset of endpoints.

```
capabilities = products:read
path = /products/electronics/**
expires_at = 2026-03-21T16:30:00Z
```

### Budget delegation

Allow a sub-agent to encounter nested paywalls up to a spending limit.

```
max_amount_sats = 500
capabilities = products:read
```

## Security Properties

- **No shared secrets between agents** -- delegation uses public cryptographic
  attenuation, not secret sharing
- **Least privilege by default** -- each sub-agent gets only the permissions it needs
- **Revocation** -- the server can revoke any macaroon by token ID if needed
- **Fail closed** -- if the Lightning backend is unreachable, the server returns 503,
  never 200
- **Constant-time verification** -- all secret comparisons use XOR accumulation,
  never `Arrays.equals`

## Client-Side Example (Pseudocode)

```java
// Agent-A obtains macaroon via L402 flow
L402Credential credential = l402Client.payAndObtain("https://api.example.com/products");

// Agent-A attenuates for sub-agent with read-only, 15-min scope
Macaroon delegated = credential.macaroon()
    .addFirstPartyCaveat("capabilities = products:read")
    .addFirstPartyCaveat("expires_at = " + Instant.now().plus(Duration.ofMinutes(15)));

// Hand the attenuated macaroon + original preimage to the sub-agent
SubAgent researchAgent = new SubAgent(delegated, credential.preimage());

// Sub-agent uses it directly -- no additional payment needed
researchAgent.call("GET", "https://api.example.com/products");
```

## Further Reading

- [L402 Protocol Specification](https://lsat.tech)
- [Macaroons: Cookies with Contextual Caveats](https://research.google/pubs/pub41892/)
- [BOLT #11: Invoice Protocol](https://github.com/lightning/bolts/blob/master/11-payment-encoding.md)
