# Integration Testing Playbook

Step-by-step manual test scenarios for the L402 Spring Boot Starter. Each scenario includes exact commands, expected outputs, and troubleshooting guidance.

**Prerequisites:** Docker Engine 24+, Docker Compose v2, `curl`, `jq`, `python3`, and a POSIX shell (bash/zsh). For the Go interop test, a Go 1.21+ toolchain is also required.

On macOS, Docker Desktop must be running before any setup script is started. If Docker is stopped, commands fail with an error like `failed to connect to the docker API at unix:///Users/.../.docker/run/docker.sock`; start Docker Desktop, wait for it to report that the engine is running, then rerun the setup command.

All commands assume you are in the `integration-tests/` directory unless otherwise noted.

If you paste snippets into an interactive `zsh` session, enable comment handling first with `setopt interactivecomments`, or omit lines that start with `#`.

---

## Table of Contents

- [Quick Smoke Test](#quick-smoke-test) -- 5-command zero-to-verified flow
- [Local Stack Diagrams](#local-stack-diagrams) -- service topology and protocol flow
- [Manual Local Walkthrough](#manual-local-walkthrough) -- run infra locally and step through L402 and MPP by hand
- [What setup-lnd-channel.sh Does](#what-setup-lnd-channelsh-does) -- line-by-line role of the two-node channel bootstrap
- [What setup-lnbits.sh Does](#what-setup-lnbitssh-does) -- line-by-line role of the LNbits wallet bootstrap

1. [Happy Path (LND)](#1-happy-path-lnd)
2. [Happy Path (LNbits)](#2-happy-path-lnbits)
3. [Expiration Test](#3-expiration-test)
4. [Tamper Detection](#4-tamper-detection)
5. [Fail-Closed Test](#5-fail-closed-test)
6. [Rate Limiting Test](#6-rate-limiting-test)
7. [Spring Security Integration Test](#7-spring-security-integration-test)
8. [LSAT Backward Compatibility](#8-lsat-backward-compatibility)
9. [Go Interop Test](#9-go-interop-test)

---

## Quick Smoke Test

A short guide to go from zero to a verified **402 -> pay -> 200** flow using LNbits backed by a local payee LND node and a distinct local payer LND node. This path verifies `sha256(preimage) == payment_hash` before presenting the credential to the example app.

### Prerequisites

Docker Engine 24+, Docker Compose v2, `curl`, and `jq` must be installed.

### Steps

**1. Start bitcoind + both LND nodes, then open a payer channel:**

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
```

**2. Start LNbits, bootstrap a wallet, and write the API key to `.env`:**

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
```

This waits for LNbits to become healthy, creates a test wallet, and stores `LNBITS_API_KEY` in `.env`. After it finishes, restart the example app so it picks up the key:

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/start-example-app.sh
```

**3. Run the automated smoke test:**

```bash
PAYER_BACKEND=lnd-cli bash scripts/run-smoke-test.sh
PAYER_BACKEND=lnd-cli bash scripts/run-mpp-smoke-test.sh
```

The scripts wait for the app to become healthy, then exercise the full proof flow: unauthenticated request (expect 402), invoice payment through `lnd-payer`, and authenticated request with the credential (expect 200).

**4. Check the output.** Each step prints PASS (green) or FAIL (red). The script exits `0` on success, non-zero on failure.

**5. Tear down:**

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Notes

- The smoke test script does **not** start or stop Docker containers. You manage the stack lifecycle yourself (steps 1 and 5).
- The script sources `.env` automatically if present, picking up `LNBITS_API_KEY`, `LNBITS_PORT`, and `APP_PORT`.
- `docker-compose-lnbits.yml` still runs the fast LNbits FakeWallet environment for setup and invoice checks. FakeWallet and local LNbits self-payments do not provide usable full-proof preimages, so the smoke scripts fail early with a preimage/hash mismatch unless you use `PAYER_BACKEND=lnd-cli` with the distinct payer node.
- For the full manual walkthrough of each scenario, see the numbered sections below.

---

## Local Stack Diagrams

These diagrams describe the two-node LNbits-over-LND stack used by the quick smoke test and the manual walkthrough. The key detail is that LNbits creates invoices through the payee LND node, while `lnd-payer` pays those invoices over a real local Lightning channel so the client receives a usable payment preimage.

### Service Topology

```text
Tester shell
curl, jq, scripts
      |
      | HTTP request
      v
paygate-example-app ---------------> LNbits
protected Spring Boot resource       wallet REST API
      |                                  |
      | create invoice                   | LND REST
      |                                  v
      |                              lnd-payee
      |                              node
      |                                  ^
      |                                  | Lightning channel
      v                                  v
lnd-payer ------------------------- bitcoind
payer node       chain sync/funding  regtest chain

The tester pays the invoice by running lncli against lnd-payer.
```

### L402 Challenge And Payment Proof

```text
Client / smoke script        paygate-example-app        LNbits        lnd-payee        lnd-payer
        |                            |                    |              |                |
        | GET /api/v1/data           |                    |              |                |
        |--------------------------->|                    |              |                |
        |                            | Create invoice     |              |                |
        |                            |------------------->|              |                |
        |                            |                    | Add invoice  |                |
        |                            |                    |------------->|                |
        |                            |                    | invoice +    |                |
        |                            |                    | payment_hash |                |
        |                            |<-------------------|<-------------|                |
        | 402 Payment Required       |                    |              |                |
        | L402 macaroon + invoice    |                    |              |                |
        |<---------------------------|                    |              |                |
        | payinvoice invoice         |                    |              |                |
        |--------------------------------------------------------------------------->|
        |                            |                    |              | settle invoice|
        |                            |                    |              |<---------------|
        | payment_hash + preimage    |                    |              |                |
        |<---------------------------------------------------------------------------|
        | verify sha256(preimage) == payment_hash                                    |
        |                            |                    |              |                |
        | Authorization: L402 macaroon:preimage                                      |
        |--------------------------->|                    |              |                |
        |                            | verify macaroon, caveats, expiry, preimage hash|
        | 200 protected data         |                    |              |                |
        |<---------------------------|                    |              |                |
```

### Why The Separate Payer Node Matters

```text
Good local proof path:

Invoice contains payment_hash
        |
        v
Pay through lnd-payer over a real local Lightning channel
        |
        v
Payment returns real preimage
        |
        v
Client presents macaroon:preimage
        |
        v
App checks sha256(preimage) == payment_hash

Path to avoid for proof verification:

Invoice contains payment_hash
        |
        v
Same-wallet LNbits self-payment or FakeWallet
        |
        v
No usable full-proof preimage, or hash mismatch
```

---

## Manual Local Walkthrough

Use this when you want to run the local infrastructure, keep the example app up, and manually step through the payment-gated request flow. This walkthrough uses LNbits backed by the local payee LND node and pays invoices through `lnd-payer`, so the payment returns a real preimage and the proof can be checked end to end.

Each step below calls out:
- **Where:** the directory or shell context to run the command in.
- **What:** the concrete action being performed.
- **Why:** the role that action plays in the payment proof flow.
- **How to verify:** the signal that tells the tester the step worked.

### What You Will Run

- `bitcoind`: local Bitcoin regtest chain used only inside Docker.
- `lnd-payee`: local payee Lightning node connected to that regtest chain.
- `lnd-payer`: local payer Lightning node with a channel to `lnd-payee`.
- `lnbits`: REST wallet API backed by the local LND node.
- `paygate-example-app`: Spring Boot example app protected by Paygate.

Default host URLs:

```bash
APP_URL="http://localhost:${APP_PORT:-18080}"
LNBITS_URL="http://localhost:${LNBITS_PORT:-15000}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
```

The manual flow follows this shape:

```text
Start regtest Bitcoin and both LND nodes
        |
        v
Open payer channel to payee LND
        |
        v
Start LNbits and create wallet API key
        |
        v
Start paygate-example-app
        |
        v
Unauthenticated request returns 402 challenge
        |
        v
Pay invoice through lnd-payer
        |
        v
Check sha256(preimage) equals payment_hash
        |
        v
Retry with L402 macaroon:preimage
        |
        v
Protected endpoint returns 200
```

### 1. Start Bitcoin and Both LND Nodes

**Where:** run from `integration-tests/`.

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
```

**What happens:**
- `setup-lnd-channel.sh` starts `bitcoind`, `lnd-payee`, and `lnd-payer`, waits for Docker health checks when supported by Docker Compose, then performs command-level readiness checks.
- The script mines spendable regtest coins to `lnd-payer`, connects it to `lnd-payee`, opens a channel, and waits for the channel to become active.
- No real Bitcoin or Lightning funds are used.

**Why this is required:** LNbits creates invoices on the payee LND node. A separate payer node must settle those invoices over a real Lightning channel so the payer receives the actual preimage for `sha256(preimage) == payment_hash`. Paying from a wallet in the same local LNbits instance can be treated as an internal payment and may not produce a usable proof preimage.

**How to verify:** the setup script should end with `Setup complete`, print payer/payee channel balances, and show active channels on both nodes.

### What `setup-lnd-channel.sh` Does

`integration-tests/scripts/setup-lnd-channel.sh` bootstraps the regtest Lightning channel used by the LNbits-over-LND smoke tests. It starts the required Docker Compose services if needed, waits for them to become healthy when Docker Compose supports `--wait`, then prepares a real local payment path from `lnd-payer` to `lnd-payee`.

| Step | What the script does | Why it matters |
| --- | --- | --- |
| 1 | Uses `COMPOSE_FILE`, defaulting to `docker-compose-lnbits-lnd.yml`. | Targets the LNbits-over-LND stack rather than the FakeWallet or single-LND stacks. |
| 2 | Checks that `jq` is installed. | The script parses `lncli` JSON output for block heights and channel state. |
| 3 | Starts `bitcoind`, `lnd-payee`, and `lnd-payer` with Docker Compose, using `up -d --wait` when available. | Prevents the setup from racing ahead before containers are healthy. |
| 4 | Waits for `bitcoind`, `lnd-payee`, and `lnd-payer` to answer commands. | Adds command-level readiness checks after Docker health checks. |
| 5 | Waits for both LND nodes to catch up to the current regtest block height. | LND cannot reliably fund wallets or open channels until it has synced to `bitcoind`. |
| 6 | Creates a new address on `lnd-payer` and mines 101 blocks to it. | Gives the payer spendable regtest coins; 101 blocks mature coinbase outputs. |
| 7 | Reads the identity public keys for `lnd-payee` and `lnd-payer`. | These pubkeys identify the Lightning peers and are needed for `connect`, `openchannel`, and channel checks. |
| 8 | Connects `lnd-payer` to `lnd-payee` at `${PAYEE_PUBKEY}@lnd-payee:9735`. | Establishes the peer connection before opening a channel. |
| 9 | Checks whether an active payer-to-payee channel already exists. | Makes the script safe to rerun without opening duplicate channels. |
| 10 | Opens a channel from `lnd-payer` to `lnd-payee` when needed. | Creates the Lightning route used to pay invoices created by LNbits on the payee node. |
| 11 | Mines confirmation blocks and waits until the channel is active on both nodes. | Confirms the funding transaction and ensures both nodes can use the channel. |
| 12 | Prints wallet and channel balances. | Gives a quick sanity check that the payer has funds and both nodes see the channel. |

The default channel capacity is `1000000` sats, controlled by `CHANNEL_CAPACITY_SATS`. The default number of confirmation blocks is `6`, controlled by `CHANNEL_CONFIRMATION_BLOCKS`. The Docker health wait timeout defaults to `300` seconds via `COMPOSE_WAIT_TIMEOUT_SECONDS`, and command-level waits default to `180` attempts via `MAX_ATTEMPTS`. The default service names are `lnd-payee` and `lnd-payer`; they can also be overridden with `PAYEE_LND_SERVICE` and `PAYER_LND_SERVICE`.

If the script fails, read the last `ERROR:` message first. The most common causes are missing `jq`, Docker services not running, LND not catching up to `bitcoind`, or a channel that never becomes active. You can inspect the same state manually with:

```bash
docker compose -f docker-compose-lnbits-lnd.yml exec -T bitcoind \
  bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass getblockcount

docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest getinfo

docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest listchannels
```

### 2. Start LNbits and Create a Wallet Key

**Where:** run from `integration-tests/`.

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
```

**What happens:**
- `setup-lnbits.sh` starts LNbits with `LndRestWallet`, using LND's REST endpoint, TLS cert, and admin macaroon from the shared Docker volume.
- The script waits for Docker health checks when supported by Docker Compose, then waits for the LNbits HTTP health endpoint.
- It initializes LNbits first-install state if needed, logs in, creates a wallet, and writes `LNBITS_API_KEY` to `.env`.
- That API key is the wallet admin key used by the example app configuration. Local proof smoke tests pay via `lnd-payer`, not via this LNbits wallet.

**Why this is required:** the example app talks to LNbits to create invoices. It needs a wallet admin key so invoice creation succeeds.

**How to verify:** `.env` contains `LNBITS_API_KEY=...`, and `curl http://localhost:15000/api/v1/health` returns successfully.

### What `setup-lnbits.sh` Does

`integration-tests/scripts/setup-lnbits.sh` bootstraps LNbits for the local integration stack. It starts the LNbits Docker Compose service if needed, waits for LNbits, performs first-install setup when the instance is fresh, creates a wallet, extracts that wallet's Admin API key, and writes it to `integration-tests/.env` as `LNBITS_API_KEY`.

| Step | What the script does | Why it matters |
| --- | --- | --- |
| 1 | Locates `integration-tests/.env` and sources it if it exists. | Reuses local overrides such as `LNBITS_PORT`, `LNBITS_URL`, or an existing API key. |
| 2 | Defaults `COMPOSE_FILE` to `docker-compose-lnbits.yml`, `LNBITS_PORT` to `15000`, and `LNBITS_URL` to `http://localhost:15000`. | Lets the same script work for the fast FakeWallet stack and the LNbits-over-LND stack when `COMPOSE_FILE=docker-compose-lnbits-lnd.yml` is passed. |
| 3 | Starts the `lnbits` service with Docker Compose, using `up -d --wait` when available. | Prevents the script from racing ahead before the container is healthy. |
| 4 | Waits for `${LNBITS_URL}/api/v1/health` to return successfully. | Adds an HTTP-level readiness check before initialization. |
| 5 | Calls `PUT /api/v1/auth/first_install` with a local setup username and password. | Initializes LNbits on a fresh volume so API login and wallet creation can work. |
| 6 | Treats `200` as initialized, `401` as already initialized, and `404`/`405` as first-install endpoint unavailable. | Handles multiple LNbits versions and reruns without failing unnecessarily. |
| 7 | Attempts to log in with `POST /api/v1/auth`. | Newer LNbits flows require a bearer token before creating an account wallet. |
| 8 | Creates a wallet using `POST /api/v1/account` when login succeeded, otherwise falls back to `POST /api/v1/wallet`. | Supports both authenticated and older unauthenticated wallet creation APIs. |
| 9 | Parses `adminkey` from the wallet response. | This key is what the example app uses to ask LNbits to create invoices. |
| 10 | Updates or appends `LNBITS_API_KEY=<adminkey>` in `integration-tests/.env`. | Makes the key available to Docker Compose and the example app after restart. |
| 11 | Prints a reminder to restart `paygate-example-app`. | The app reads `LNBITS_API_KEY` at startup, so it will not see the new key until restarted. |

The default setup credentials are local-only test values: `LNBITS_SETUP_USERNAME=paygate-admin` and `LNBITS_SETUP_PASSWORD=paygate-test-password`. Override them only if your LNbits volume was initialized with different credentials. The Docker health wait timeout defaults to `300` seconds via `COMPOSE_WAIT_TIMEOUT_SECONDS`, and the HTTP health wait defaults to `120` attempts via `MAX_ATTEMPTS`.

The script starts LNbits if needed and creates the wallet key, but it does **not** restart the example app after changing `.env`. Run:

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/start-example-app.sh
```

If the script fails, check the printed HTTP status and LNbits logs:

```bash
docker compose -f docker-compose-lnbits-lnd.yml logs --no-log-prefix lnbits
curl -s http://localhost:15000/api/v1/health
grep '^LNBITS_API_KEY=' .env
```

### 3. Start the Example App

**Where:** run from `integration-tests/`.

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/start-example-app.sh
```

**What happens:**
- The app starts with `PAYGATE_BACKEND=lnbits`.
- `scripts/start-example-app.sh` removes any stale `paygate-example-app` container before starting it. The playbook uses several Compose files with the same project and service names; removing the old container prevents a previous LND-only run from keeping `PAYGATE_BACKEND=lnd`.
- Inside Docker, the app reaches LNbits at `http://lnbits:5000`.
- On your host, you reach the app at `http://localhost:18080` unless you changed `APP_PORT`.

Wait for the app:

```bash
# Load APP_PORT from .env if present, define URLs for this shell, then wait.
set -a
[ -f .env ] && . ./.env
set +a

APP_URL="http://localhost:${APP_PORT:-18080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"

COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/wait-for-app.sh
```

**Why this is required:** the app is the protected resource server. It issues payment challenges and validates the macaroon plus preimage credential after payment.

**How to verify:** the wait loop prints `App is ready`, or a direct request to `http://localhost:18080/api/v1/health` returns `200`.

Use `scripts/wait-for-app.sh` instead of an unbounded `until curl ...` loop. It defines the endpoint from `.env`, times out, and prints container status plus app logs if the app never becomes healthy.

### 4. Request the Protected Endpoint

**Where:** run from the same `integration-tests/` shell where the `APP_URL`, `PROTECTED_ENDPOINT`, and `HEALTH_ENDPOINT` variables are set.

```bash
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
HEADERS=$(tr -d '\r' < "$HEADER_FILE")
rm -f "$HEADER_FILE"

echo "HTTP status: $HTTP_STATUS"
echo "$BODY_402" | jq .
printf '%s\n' "$HEADERS" | grep -i "^www-authenticate:"
```

**What happens:**
- You call a protected endpoint without credentials.
- The app returns `402 Payment Required`.
- The response includes payment challenges. The L402 challenge contains a macaroon and Lightning invoice.

**Why this is required:** this is the challenge phase of the flow. The app mints a macaroon whose identifier commits to the invoice payment hash. The client must pay the invoice and later present the macaroon plus preimage.

**How to verify:** `HTTP status: 402` is printed, the body is valid JSON, and at least one `WWW-Authenticate` header starts with `L402`.

Extract the L402 values:

```bash
WWW_AUTH=$(printf '%s\n' "$HEADERS" \
  | grep -i "^www-authenticate:[[:space:]]*L402 " \
  | sed 's/^[^:]*:[[:space:]]*//' \
  | head -1)

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "Macaroon chars: ${#MACAROON}"
echo "Invoice chars: ${#INVOICE}"
```

**How to verify:** both lengths are non-zero. The invoice should look like a regtest BOLT11 invoice beginning with `lnbcrt`.

Do not run `rm -f "$HEADER_FILE"` before extracting the headers unless you have already saved them into `HEADERS`. If you see `no such file or directory: /var/folders/.../tmp...`, the temporary header file was already deleted or belongs to an earlier shell command. Re-run the full request block above to recreate `HEADERS`, then run the extraction block.

### 5. Pay the Invoice Through lnd-payer

**Where:** run from `integration-tests/`, in the same shell where `INVOICE` and `MACAROON` were set by step 4.

Pay the invoice with the helper script and import the resulting `PAYMENT_HASH` and `PREIMAGE` variables into your current shell:

```bash
eval "$(COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"

echo "PAYMENT_HASH=$PAYMENT_HASH"
echo "PREIMAGE=$PREIMAGE"
```

**What happens:**
- `scripts/pay-lnd-payer-invoice.sh` checks that `INVOICE` is non-empty.
- It runs `lncli decodepayreq` inside `lnd-payer` to extract the payment hash before payment.
- It pays the invoice through `lnd-payer`.
- It runs `trackpayment` for the final payment status and preimage, even if `payinvoice` itself printed no parseable output.
- It prints `PAYMENT_HASH=...` and `PREIMAGE=...` on stdout, and the `eval` imports them into your current shell.

**Why this is required:** the preimage is the payment proof. Paygate does not trust LNbits payment status alone; it verifies that the preimage hashes to the payment hash committed into the macaroon.

**How to verify:** the output includes `Payment status: SUCCEEDED`, `PAYMENT_HASH` is 64 lowercase hex characters, and `PREIMAGE` is 64 lowercase hex characters.

If you see `rpc error: code = AlreadyExists desc = invoice is already paid`, that invoice was already settled. Lightning invoices are single-use for this walkthrough. Go back to [Request the Protected Endpoint](#4-request-the-protected-endpoint), get a fresh 402 challenge, extract the new `INVOICE`, and pay that new invoice once.

Verify the proof before using it:

```bash
PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)

echo "payment_hash:     $PAYMENT_HASH"
echo "sha256(preimage): $PREIMAGE_HASH"
test "$PAYMENT_HASH" = "$PREIMAGE_HASH" && echo "Proof matches."
```

**How to verify:** `Proof matches.` is printed. If it is not, do not continue; the credential cannot satisfy the app's proof check.

### 6. Retry With the L402 Credential

**Where:** run from the same shell where `MACAROON`, `PREIMAGE`, and `PROTECTED_ENDPOINT` are set.

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

echo "HTTP status: $HTTP_STATUS"
echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
```

**What happens:**
- The app parses the macaroon and extracts the embedded payment hash.
- The app computes `sha256(preimage)` and compares it with that payment hash.
- If the macaroon signature, caveats, expiry, and payment proof all pass, the endpoint returns `200`.

**Why this is required:** this is the actual authorization step. It proves the client paid the invoice tied to this macaroon and can now access the protected resource.

**How to verify:** `HTTP status: 200` is printed and the response body contains the protected data.

### 7. Manually Test the MPP Flow

MPP uses the same invoice payment proof, but the challenge and credential are encoded differently. The app returns a `Payment` challenge in the JSON body, and the client returns a base64url-encoded credential in `Authorization: Payment ...`.

```text
Client                    paygate-example-app          LNbits / LND          lnd-payer
  |                              |                          |                    |
  | GET protected endpoint       |                          |                    |
  |----------------------------->|                          |                    |
  |                              | Create invoice           |                    |
  |                              |------------------------->|                    |
  |                              | Invoice + payment_hash   |                    |
  |                              |<-------------------------|                    |
  | 402 with protocols.Payment   |                          |                    |
  |<-----------------------------|                          |                    |
  | Decode request and extract invoice                      |                    |
  | payinvoice invoice           |                          |                    |
  |--------------------------------------------------------------------------->|
  | payment_hash + preimage      |                          |                    |
  |<---------------------------------------------------------------------------|
  | Build base64url Payment credential                                         |
  | Authorization: Payment credential |                          |             |
  |----------------------------->|                          |                    |
  |                              | Verify challenge binding and preimage proof  |
  | 200 + Payment-Receipt        |                          |                    |
  |<-----------------------------|                          |                    |
```

**Where:** run from `integration-tests/` in a shell with `APP_URL`, `PROTECTED_ENDPOINT`, and `HEALTH_ENDPOINT` set.

Request a fresh challenge and extract the MPP object:

```bash
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
PAYMENT_WWW_AUTH=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:" | sed 's/^[^:]*: //' | grep "^Payment " | head -1)
rm -f "$HEADER_FILE"

CHALLENGE_JSON=$(printf '%s' "$BODY_402" | jq -c '.protocols.Payment')
DIGEST=$(printf '%s' "$CHALLENGE_JSON" | jq -r '.digest')
REQUEST_B64URL=$(printf '%s' "$CHALLENGE_JSON" | jq -r '.request')

echo "HTTP status: $HTTP_STATUS"
echo "Payment header chars: ${#PAYMENT_WWW_AUTH}"
echo "Digest: $DIGEST"
```

**What happens:** the app returns the MPP challenge in `protocols.Payment`. The `digest` identifies what is being paid for, and `request` contains the payment request details.

**Why this is required:** MPP clients build credentials from the structured challenge, not from the L402 `macaroon:preimage` format.

**How to verify:** `HTTP status: 402`, a non-empty Payment header, and a non-empty `Digest`.

Decode the MPP request and extract the invoice:

```bash
REQUEST_JSON=$(python3 - "$REQUEST_B64URL" <<'PY'
import base64
import sys

value = sys.argv[1]
padding = "=" * ((4 - len(value) % 4) % 4)
print(base64.urlsafe_b64decode(value + padding).decode())
PY
)

INVOICE=$(printf '%s' "$REQUEST_JSON" | jq -r '.methodDetails.invoice')
echo "$REQUEST_JSON" | jq .
echo "Invoice chars: ${#INVOICE}"
```

**What happens:** the base64url request payload is decoded to JSON and the BOLT11 invoice is extracted.

**How to verify:** the decoded JSON is valid and `INVOICE` is non-empty.

Pay the invoice through `lnd-payer` and verify the proof:

```bash
eval "$(COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"

PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)

echo "payment_hash:     $PAYMENT_HASH"
echo "sha256(preimage): $PREIMAGE_HASH"
test "$PAYMENT_HASH" = "$PREIMAGE_HASH" && echo "Proof matches."
```

The helper writes progress to stderr and prints only `PAYMENT_HASH=...` and `PREIMAGE=...` to stdout, so `eval` imports those variables into your current shell.

Build the MPP credential and retry the protected endpoint:

```bash
CREDENTIAL_B64URL=$(python3 - "$CHALLENGE_JSON" "$PREIMAGE" <<'PY'
import base64
import json
import sys

challenge = json.loads(sys.argv[1])
preimage = sys.argv[2]
credential = {"challenge": challenge, "payload": {"preimage": preimage}}
raw = json.dumps(credential, separators=(",", ":")).encode()
print(base64.urlsafe_b64encode(raw).decode().rstrip("="))
PY
)

RESPONSE_HEADER_FILE=$(mktemp)
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -D "$RESPONSE_HEADER_FILE" \
  -H "Authorization: Payment ${CREDENTIAL_B64URL}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')
RECEIPT_HEADER=$(tr -d '\r' < "$RESPONSE_HEADER_FILE" | grep -i "^payment-receipt:" | sed 's/^[^:]*: //' | head -1)
rm -f "$RESPONSE_HEADER_FILE"

echo "HTTP status: $HTTP_STATUS"
echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
echo "Payment-Receipt chars: ${#RECEIPT_HEADER}"
```

**What happens:** the credential includes the original MPP challenge and the payment preimage. The app validates the credential and returns a `Payment-Receipt` header.

**Why this is required:** this proves the modern `Payment` protocol path works independently of the legacy L402 authorization header.

**How to verify:** `HTTP status: 200` and `Payment-Receipt chars` is greater than zero.

### 8. Keep Testing or Tear Down

The app and infrastructure are still running, so you can keep calling endpoints or inspect logs:

```bash
docker compose -f docker-compose-lnbits-lnd.yml logs --no-log-prefix -f paygate-example-app
```

Use `--no-log-prefix` for easier reading. Without it, Docker Compose prefixes every line with the service name, for example `paygate-example-app-1  |`, which pushes Spring's logger context to the right.

When finished:

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

The `-v` removes Docker volumes, including regtest chain state, LND wallet state, and LNbits data. Omit `-v` if you want to keep the local environment for another manual session.

---

## Common Variables

Set these once per session. Adjust if you changed ports in `.env`.

```bash
APP_URL="http://localhost:${APP_PORT:-18080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
```

### Reusable LNbits Proof Helper

Use this helper in any scenario that needs a fresh valid L402 credential from the two-node LNbits-over-LND stack. It requests a protected resource, extracts the L402 macaroon and invoice, pays the invoice through `scripts/pay-lnd-payer-invoice.sh`, imports the payment hash and preimage, and verifies `sha256(preimage) == payment_hash`.

**Where:** source this function in the same `integration-tests/` shell where you run the scenario.

```bash
. scripts/proof-helper.sh
get_lnbits_lnd_l402_credential
```

**What to remember:** the `lncli payinvoice` output format varies by LND version and image. Use `scripts/pay-lnd-payer-invoice.sh` instead of parsing `payinvoice` directly; it falls back to `trackpayment` and prints shell-safe assignments for `eval`.

---

## 1. Happy Path (LND)

Full payment flow: request protected resource, receive 402 challenge, pay the invoice via LND, then access the resource with the L402 credential.

### 1.1 Start the environment

```bash
COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/setup-lnd-channel.sh
COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/start-example-app.sh
```

`scripts/setup-lnd-channel.sh` starts `bitcoind`, `lnd-payee`, and `lnd-payer`, opens a real regtest channel, and makes the payee LND TLS cert plus admin macaroon readable through the app mount. The separate `lnd-payer` node is required because LND rejects self-payments; the app-created invoice cannot be paid by the same payee node that created it. Start the app after that with `scripts/start-example-app.sh`; it removes any stale app container before starting the LND-backed app.

### 1.2 Request the protected endpoint (expect 402)

```bash
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
HEADERS=$(tr -d '\r' < "$HEADER_FILE")
rm -f "$HEADER_FILE"

echo "HTTP Status: $HTTP_STATUS"
printf '%s\n' "$HEADERS" | grep -i "^www-authenticate:"
```

Use `curl -D` on the `GET` request instead of `curl -I`. In this app, `HEAD` can return a `200` response without payment challenge headers, while the protected `GET` response contains the `WWW-Authenticate` challenges.

**Expected:**
- HTTP status: `402`
- `WWW-Authenticate` header present with format:
  ```
  WWW-Authenticate: L402 version="0", token="<base64>", macaroon="<base64>", invoice="<bolt11>"
  ```

### 1.3 Extract the macaroon and invoice

```bash
WWW_AUTH=$(printf '%s\n' "$HEADERS" \
  | grep -i "^www-authenticate:[[:space:]]*L402 " \
  | sed 's/^[^:]*:[[:space:]]*//' \
  | head -1)

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "Macaroon: ${MACAROON:0:40}..."
echo "Invoice:  ${INVOICE:0:40}..."
```

Verify both values are non-empty:

```bash
[ -n "$MACAROON" ] && echo "OK: macaroon captured" || echo "FAIL: macaroon is empty"
[ -n "$INVOICE" ] && echo "OK: invoice captured" || echo "FAIL: invoice is empty"
```

### 1.4 Pay the invoice via lnd-payer

```bash
eval "$(COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"

echo "PAYMENT_HASH=$PAYMENT_HASH"
echo "PREIMAGE=$PREIMAGE"
```

**Expected:** the helper prints `Payment status: SUCCEEDED` in its progress output, then imports `PAYMENT_HASH` and `PREIMAGE` into your shell.

Do not pay this invoice with `docker compose -f docker-compose-lnd-two-node.yml exec lnd-payee lncli ...`. The payee node created the invoice, and LND returns `self-payments not allowed` when the same node tries to pay it.

### 1.5 Verify the preimage

```bash
PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)

[ "$PREIMAGE_HASH" = "$PAYMENT_HASH" ] && echo "OK: preimage matches payment hash" || echo "FAIL: preimage mismatch"
```

### 1.6 Access the protected endpoint with L402 credential (expect 200)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -n 1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `200`
- JSON body containing `"data": "premium content"`

### 1.7 Repeat with same credential (cache hit, expect 200)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -n 1)
echo "HTTP Status (cache hit): $HTTP_STATUS"
```

**Expected:** HTTP status `200`. The second request should be notably faster (credential cached).

### 1.8 Tear down

```bash
docker compose -f docker-compose-lnd-two-node.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 402 but empty `WWW-Authenticate` | App misconfigured | Check `PAYGATE_ENABLED=true` in container env |
| `payinvoice` hangs | Payer LND has no funds or no route | Re-run `COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/setup-lnd-channel.sh` |
| `payinvoice` returns `FAILED` or `self-payments not allowed` | Wrong payer, expired invoice, or already-paid invoice | Pay through `scripts/pay-lnd-payer-invoice.sh`; get a fresh 402 challenge if needed |
| `payinvoice` output is not JSON | LND image prints table/text output | Use `scripts/pay-lnd-payer-invoice.sh`; it falls back to `trackpayment` |
| 401 with valid credential | Preimage/macaroon mismatch | Ensure you extracted both from the same 402 response |

---

## 2. Happy Path (LNbits)

Same flow as scenario 1, but the app creates invoices through LNbits. Use `docker-compose-lnbits-lnd.yml` for full proof verification with `lnd-payer` paying the invoice over a real channel. Use `docker-compose-lnbits.yml` only for faster setup and invoice checks.

### 2.1 Start the environment

```bash
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/start-example-app.sh

APP_URL="http://localhost:${APP_PORT:-18080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
```

`scripts/start-example-app.sh` already waits for the health endpoint. Keep `PROTECTED_ENDPOINT` defined in the same shell for the remaining steps.

### 2.2 Request the protected endpoint (expect 402)

```bash
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
HEADERS=$(tr -d '\r' < "$HEADER_FILE")
rm -f "$HEADER_FILE"

WWW_AUTH=$(printf '%s\n' "$HEADERS" \
  | grep -i "^www-authenticate:[[:space:]]*L402 " \
  | sed 's/^[^:]*:[[:space:]]*//' \
  | head -1)

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "HTTP Status: $HTTP_STATUS"
echo "Macaroon: ${MACAROON:0:40}..."
echo "Invoice:  ${INVOICE:0:40}..."
```

**Expected:** HTTP status `402` with a valid `WWW-Authenticate` header.

### 2.3 Pay the invoice via lnd-payer

```bash
eval "$(COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"

echo "PAYMENT_HASH=$PAYMENT_HASH"
echo "PREIMAGE=$PREIMAGE"
```

**Expected:** the helper prints `Payment status: SUCCEEDED` in its progress output, then imports `PAYMENT_HASH` and `PREIMAGE` into your shell.

### 2.4 Verify the preimage

```bash
PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)

echo "payment_hash:     $PAYMENT_HASH"
echo "sha256(preimage): $PREIMAGE_HASH"
test "$PAYMENT_HASH" = "$PREIMAGE_HASH" && echo "Proof matches."
```

**Note:** Paying through a wallet in the same LNbits instance can record a zero or otherwise unusable preimage for local self-payments. Use `lnd-payer` for local proof verification.

### 2.5 Access the protected endpoint with L402 credential (expect 200)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -n 1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `200`
- JSON body containing `"data": "premium content"`

### 2.6 Repeat with same credential (cache hit, expect 200)

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "HTTP Status (cache hit): $HTTP_STATUS"
```

**Expected:** HTTP status `200`.

### 2.7 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| LNbits returns 401 on pay | Wrong API key | Re-run `scripts/setup-lnbits.sh` and restart the app |
| Preimage is empty or mismatched | Invoice was paid through LNbits self-payment or FakeWallet | Use `PAYER_BACKEND=lnd-cli` / `lnd-payer` for local proof verification |
| App returns 503 | Cannot reach LNbits | Verify `PAYGATE_LNBITS_URL` points to `http://lnbits:5000` inside Docker network |

---

## 3. Expiration Test

Verify that L402 credentials expire after the configured timeout.

### 3.1 Start LNbits-over-LND environment with short timeout

This test uses LNbits backed by a payee LND node and pays through `lnd-payer` so the credential has a real proof preimage. Override the timeout to 30 seconds:

```bash
PAYGATE_DEFAULT_TIMEOUT_SECONDS=30 \
  COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits-lnd-stack.sh
```

This starts bitcoind, both LND nodes, the payer channel, LNbits, and the example app. It also rebuilds the example app image by default so local source changes are included. Set `BUILD_APP=false` to skip the Docker image rebuild when you only need to restart existing containers.

**Alternative:** If the timeout is not configurable via environment variable at the container level, modify the `@L402Protected` annotation or set it in `application.yml` and rebuild:

```bash
# Override via Docker Compose environment (add to the paygate-example-app service)
# PAYGATE_DEFAULT_TIMEOUT_SECONDS: "30"
```

### 3.2 Obtain and pay for a credential

```bash
. scripts/proof-helper.sh
get_lnbits_lnd_l402_credential
```

### 3.3 Verify the credential works immediately

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "Immediate access: $HTTP_STATUS"
```

**Expected:** HTTP status `200`.

### 3.4 Wait for expiry and retry

```bash
echo "Waiting 35 seconds for credential to expire..."
sleep 35

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "After expiry: $HTTP_STATUS"
```

**Expected:** HTTP status `402`. The expired credential is rejected and the app returns a fresh payment challenge.

### 3.5 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `failed to connect to the docker API` | Docker Desktop / Docker Engine is not running, or `DOCKER_HOST` points at a stale socket | Start Docker, wait for `docker info` to succeed, then rerun the setup command |
| Still 200 after timeout | Timeout override not applied, or app image was not rebuilt after changing endpoint timeout behavior | Verify with `docker compose exec paygate-example-app env \| grep TIMEOUT`, rebuild the image, then restart the app |
| 401 immediately | Credential validation failed before the servlet challenge handler could issue a new payment challenge | Check preimage extraction in step 3.2 |

---

## 4. Tamper Detection

Verify that the server rejects tampered macaroons and mismatched preimages.

**Prerequisite:** Complete scenario 1 or 2 first to obtain a valid `$MACAROON` and `$PREIMAGE`. Or run the following to get a valid credential using LNbits backed by a payee LND node and paid by `lnd-payer`:

```bash
# Quick full-stack setup (if not already running)
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits-lnd-stack.sh

. scripts/proof-helper.sh
get_lnbits_lnd_l402_credential

# Confirm valid credential works
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")
echo "Valid credential: $HTTP_STATUS"  # Should be 200
```

### 4.1 Tampered macaroon (one byte modified)

```bash
# Flip one character in the middle of the base64-encoded macaroon
TAMPERED_MAC=$(echo "$MACAROON" | python3 -c "
import sys
mac = sys.stdin.read().strip()
mid = len(mac) // 2
# Flip the character at the midpoint
c = mac[mid]
replacement = 'A' if c != 'A' else 'B'
print(mac[:mid] + replacement + mac[mid+1:])
")

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${TAMPERED_MAC}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "Tampered macaroon: $HTTP_STATUS"
```

**Expected:** HTTP status `401` or `402`, but never `200` or `500`. The tampered credential must not grant access; depending on the servlet error path, the app may either reject it as unauthorized or return a fresh payment challenge.

### 4.2 Wrong preimage

```bash
# Use a completely wrong preimage (64 hex characters of zeros)
WRONG_PREIMAGE="0000000000000000000000000000000000000000000000000000000000000000"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${WRONG_PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "Wrong preimage: $HTTP_STATUS"
```

**Expected:** HTTP status `401` or `402`, but never `200` or `500`. The preimage must hash to the payment hash embedded in the macaroon identifier.

### 4.3 Macaroon from one token with preimage from another

Get a second credential and cross them:

```bash
MACAROON_1="$MACAROON"
PREIMAGE_1="$PREIMAGE"

get_lnbits_lnd_l402_credential
MACAROON_2="$MACAROON"
PREIMAGE_2="$PREIMAGE"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON_1}:${PREIMAGE_2}" \
  "$PROTECTED_ENDPOINT")

echo "Cross-token (mac1 + preimage2): $HTTP_STATUS"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON_2}:${PREIMAGE_1}" \
  "$PROTECTED_ENDPOINT")

echo "Cross-token (mac2 + preimage1): $HTTP_STATUS"
```

**Expected:** Both should return HTTP status `401` or `402`, but never `200` or `500`. The payment hash embedded in the macaroon identifier must match `SHA256(preimage)`.

### 4.4 Malformed Authorization header

```bash
# Missing preimage
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}" \
  "$PROTECTED_ENDPOINT")
echo "Missing preimage: $HTTP_STATUS"

# Empty macaroon
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 :${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")
echo "Empty macaroon: $HTTP_STATUS"

# Garbage value
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 not-a-real-credential" \
  "$PROTECTED_ENDPOINT")
echo "Garbage value: $HTTP_STATUS"
```

**Expected:** All should return `400` with `MALFORMED_HEADER`. The server must never return `200` or `500` for malformed input.

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Tampered macaroon returns 200 | Signature not being verified | Check `L402Validator` logic |
| Wrong preimage returns 200 | Payment hash not checked against preimage | Check `SHA256(preimage) == payment_hash` in validator |
| 500 on malformed input | Missing error handling | This is a bug -- the server should never return 500 for bad auth |

---

## 5. Fail-Closed Test

Verify that the server returns `503 Service Unavailable` when the Lightning backend is unreachable, rather than silently allowing access.

### 5.1 Start the LND environment and confirm normal operation

```bash
COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/setup-lnd-channel.sh
COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/start-example-app.sh

APP_URL="http://localhost:${APP_PORT:-18080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"

COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/wait-for-app.sh

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${PROTECTED_ENDPOINT:-http://localhost:18080/api/v1/data}")
echo "Before stopping LND: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (normal challenge response).

### 5.2 Pause the Lightning container

```bash
docker compose -f docker-compose-lnd-two-node.yml unpause lnd-payee 2>/dev/null || true
COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/start-example-app.sh

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "Before pausing LND: $HTTP_STATUS"

docker compose -f docker-compose-lnd-two-node.yml pause lnd-payee
sleep 10

docker compose -f docker-compose-lnd-two-node.yml ps paygate-example-app lnd-payee
```

Pause `lnd-payee` instead of stopping it. Stopping the dependency can also stop `paygate-example-app`; pausing only the payee LND container keeps the app running while making the Lightning backend unavailable.

### 5.3 Request the protected endpoint (expect 503)

```bash
RESPONSE=$(curl -s --max-time 30 -w "\n%{http_code}" "${PROTECTED_ENDPOINT:-http://localhost:18080/api/v1/data}")
HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -n 1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `503`
- If the status is `000`, confirm `paygate-example-app` is still `Up` in the `docker compose ps` output before debugging the application response.
- The server must NOT return `200` (that would mean fail-open, a security vulnerability)
- The server should NOT return `500` (unhandled exception)

### 5.4 Optional liveness check

```bash
HEALTH_RESPONSE=$(curl -sf "$APP_URL/actuator/health" 2>/dev/null || curl -s "$HEALTH_ENDPOINT")
echo "$HEALTH_RESPONSE" | jq . 2>/dev/null || echo "$HEALTH_RESPONSE"
```

**Expected:** `{"status":"ok"}` is normal here. This endpoint only confirms the example app is still reachable; it does not check the Lightning backend. The fail-closed assertion for this scenario is step 5.3 returning `503`.

### 5.5 Restart LND and confirm recovery

```bash
docker compose -f docker-compose-lnd-two-node.yml unpause lnd-payee

echo "Waiting for LND recovery..."
sleep 15

COMPOSE_FILE=docker-compose-lnd-two-node.yml bash scripts/start-example-app.sh

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "After LND recovery: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (normal operation resumed).

### 5.6 Tear down

```bash
docker compose -f docker-compose-lnd-two-node.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 200 instead of 503 | Fail-open bug | Critical security issue -- the app is granting access without Lightning |
| 500 instead of 503 | Unhandled exception | Check that `CachingLightningBackendWrapper` returns 503 on backend failure |
| Still 503 after restart | Health cache stale | Wait longer (default TTL is 5s) or check `l402.health-cache.ttl-seconds` |

---

## 6. Rate Limiting Test

Verify that rapid unauthenticated requests trigger rate limiting (HTTP 429).

### 6.1 Start any environment

Use the fast LNbits FakeWallet stack because this scenario only exercises unauthenticated challenge rate limits and does not need to pay an invoice:

```bash
docker compose -f docker-compose-lnbits.yml down -v
COMPOSE_FILE=docker-compose-lnbits.yml bash scripts/setup-lnbits.sh

APP_URL="http://localhost:${APP_PORT:-18080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"

PAYGATE_RATE_LIMIT_BURST_SIZE=3 \
PAYGATE_RATE_LIMIT_REQUESTS_PER_SECOND=0.1 \
COMPOSE_FILE=docker-compose-lnbits.yml bash scripts/start-example-app.sh

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "Before burst: $HTTP_STATUS"

if [ "$HTTP_STATUS" != "402" ]; then
  echo "Expected 402 before rate-limit burst; check LNbits/app logs before continuing."
  return 1 2>/dev/null || exit 1
fi
```

### 6.2 Send a burst of unauthenticated requests

```bash
echo "Sending 10 rapid requests..."
for i in $(seq 1 10); do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${PROTECTED_ENDPOINT:-http://localhost:18080/api/v1/data}")
  echo "Request $i: $HTTP_STATUS"
done
```

**Expected:** The first few requests return `402`. After the burst limit is exceeded, subsequent requests return `429 Too Many Requests`.

### 6.3 Verify 429 response includes Retry-After header

```bash
for i in $(seq 1 10); do
  HEADER_FILE=$(mktemp)
  HTTP_STATUS=$(curl -s -D "$HEADER_FILE" -o /dev/null -w "%{http_code}" "${PROTECTED_ENDPOINT:-http://localhost:18080/api/v1/data}")
  if [ "$HTTP_STATUS" = "429" ]; then
    tr -d '\r' < "$HEADER_FILE" | grep -i "^retry-after:"
    rm -f "$HEADER_FILE"
    break
  fi
  rm -f "$HEADER_FILE"
done
```

**Expected:** A rate-limited response includes `Retry-After: 1`.

### 6.4 Wait and verify recovery

```bash
echo "Waiting 12 seconds for rate limit to reset..."
sleep 12

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${PROTECTED_ENDPOINT:-http://localhost:18080/api/v1/data}")
echo "After cooldown: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (normal challenge, rate limit reset).

### 6.5 Tear down

```bash
docker compose -f docker-compose-lnbits.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Never see 429 | Rate limiter not enabled | Check `TokenBucketRateLimiter` config; may need explicit enablement |
| 429 on first request | Rate limit too aggressive | Check burst size and refill rate configuration |
| 429 never resets | Token bucket not refilling | Check timer/scheduler configuration |

---

## 7. Spring Security Integration Test

Verify the L402 flow works with the `paygate-spring-security` module, confirming that the `SecurityContext` is populated with a `PaygateAuthenticationToken`.

### 7.1 Prerequisites

The example app must include the `paygate-spring-security` dependency and explicitly select the Spring Security integration mode. Verify the Spring Security example app's main `application.yml` includes:

```yaml
paygate:
  enabled: true
  security-mode: spring-security
```

`paygate.security-mode` belongs in the main `application.yml` because it is part of the app's security architecture. Keep dev-only settings such as `paygate.test-mode: true` and local MPP secrets in `application-dev.yml`.

### 7.2 Start the environment

Use the Spring Security stack helper to start everything needed for this scenario in one go: clean LNbits-over-LND regtest state, payer/payee LND channel, LNbits wallet/API key, and the local Spring Security example app. Do not use `scripts/start-example-app.sh` for this scenario; that script starts the servlet example Docker service (`paygate-example-app`), not `paygate-example-app-spring-security`.

In the first shell, run this from `integration-tests/`:

```bash
bash scripts/start-spring-security-stack.sh
```

Leave that first shell running; `bootRun` owns the terminal while the app is up. The helper resets Docker volumes by default so stale regtest chain state cannot break channel setup. To reuse an existing clean stack, run `RESET_STACK=false bash scripts/start-spring-security-stack.sh`.

In a second shell, define the Spring Security app endpoints and wait for the app. Run this from `integration-tests/`; if your prompt already ends in `integration-tests`, do not run `cd integration-tests` again.

```bash
APP_URL="http://localhost:${SPRING_SECURITY_APP_PORT:-8081}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
export APP_URL PROTECTED_ENDPOINT HEALTH_ENDPOINT

for _ in $(seq 1 60); do
  curl -sf "$HEALTH_ENDPOINT" > /dev/null && break
  sleep 2
done

curl -sf "$HEALTH_ENDPOINT" > /dev/null || {
  echo "Spring Security app is not reachable at $HEALTH_ENDPOINT"
  echo "Check the first shell for bootRun errors."
  exit 1
}
```

### 7.3 Verify unauthenticated request returns 402

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "Unauthenticated: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (Spring Security delegates to the L402 filter).

### 7.4 Obtain and use a valid L402 credential

Use the proof helper to obtain `$MACAROON` and `$PREIMAGE` from the Spring Security app's challenge, then present the credential:

```bash
. scripts/proof-helper.sh
get_lnbits_lnd_l402_credential

RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -n 1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `200`
- The response confirms access was granted through Spring Security's authentication chain

### 7.5 Verify SecurityContext population

Call the Spring Security example app's protocol info endpoint. It reads the current `PaygateAuthenticationToken` from `SecurityContextHolder`:

```bash
RESPONSE=$(curl -s \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$APP_URL/api/v1/protocol-info")

echo "$RESPONSE" | jq . 2>/dev/null || echo "$RESPONSE"
```

**Expected:** HTTP status `200`, `protocol` is `L402`, and `tokenId` is present:

```json
{
  "tokenId": "...",
  "protocol": "L402",
  "attributes": {},
  "timestamp": "..."
}
```

### 7.6 Verify that non-L402 auth headers are handled correctly

```bash
# Bearer token should not be accepted on L402-protected endpoints
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer some-random-token" \
  "$PROTECTED_ENDPOINT")
echo "Bearer token on L402 endpoint: $HTTP_STATUS"

# No auth header at all
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  "$PROTECTED_ENDPOINT")
echo "No auth header: $HTTP_STATUS"
```

**Expected:** Both should return `402` (challenge) or `401` (unauthorized), not `200`. If you test `/api/v1/l402-only` with an authenticated non-L402 `Payment` credential, expect `403`.

### 7.7 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml stop lnbits lnd-payee lnd-payer bitcoind
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 403 instead of 402 | Authorization rule was reached with an authenticated credential that lacks the required role | Confirm which endpoint you called; `/api/v1/l402-only` requires `ROLE_L402` |
| 401 with valid L402 | `PaygateAuthenticationProvider` not registered | Verify `paygate-spring-security` is on the classpath and `paygate.security-mode=spring-security` is active |
| 200 without auth | Security not enabled or the endpoint is public | Check `paygate.security-mode=spring-security`, `@EnableWebSecurity`, and that you are not calling `/api/v1/health` |
| App starts on port 18080 | You are running the servlet Docker example | Stop `paygate-example-app` or use port 8081 for `paygate-example-app-spring-security` |
| `peer ... disconnected` while opening the channel | Stale LND/bitcoind volumes from an earlier regtest chain | Run `docker compose -f docker-compose-lnbits-lnd.yml down -v --remove-orphans`, then rerun section 7.2 |

---

## 8. LSAT Backward Compatibility

Verify that the server accepts the legacy `LSAT` scheme in the `Authorization` header, maintaining backward compatibility with older clients.

### 8.1 Start the environment and obtain a valid credential

Use the one-command LNbits-over-LND helper so the LSAT credential carries a real proof preimage. Run this from `integration-tests/`:

```bash
GENERATE_CREDENTIAL=true COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits-lnd-stack.sh
```

With `GENERATE_CREDENTIAL=true`, the helper pays a fresh invoice and writes `MACAROON`, `PREIMAGE`, and endpoint variables to `.l402-credential.env`. The `scripts/check-l402-scheme.sh` commands below load that file automatically.

The helper resets Docker volumes by default so stale regtest chain state cannot break LND channel setup. To reuse an existing clean stack, run `RESET_STACK=false GENERATE_CREDENTIAL=true COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits-lnd-stack.sh`.

### 8.2 Use the `L402` scheme (baseline)

```bash
scripts/check-l402-scheme.sh L402 "L402 baseline"
```

**Expected:** `PASS L402 baseline: HTTP 200`.

### 8.3 Use the legacy `LSAT` scheme

```bash
scripts/check-l402-scheme.sh LSAT "LSAT legacy"
```

**Expected:** `PASS LSAT legacy: HTTP 200`. The server must accept both `L402` and `LSAT` prefixes.

### 8.4 Verify case insensitivity

```bash
scripts/check-l402-scheme.sh l402 "lowercase l402"
scripts/check-l402-scheme.sh Lsat "mixed-case Lsat"
```

**Expected:** both commands print `PASS ... HTTP 200`. Header parsing uses case-insensitive matching for both `L402` and `LSAT`.

### 8.5 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v --remove-orphans
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 401/402 with LSAT scheme | Backward compatibility not implemented | Check header parsing logic for `LSAT` prefix support |
| 200 with L402 but 401 with LSAT | Scheme comparison is case-sensitive and only matches `L402` | Add `LSAT` to accepted schemes |
| `peer ... disconnected` while opening the channel | Stale LND/bitcoind volumes from an earlier regtest chain | Run `docker compose -f docker-compose-lnbits-lnd.yml down -v --remove-orphans`, then rerun 8.1 |

---

## 9. Go Interop Test

Verify byte-level compatibility between the Java macaroon implementation and the Go `go-macaroon` library. This confirms that macaroons minted by one implementation can be deserialized and verified by the other.

**Requires:** Go 1.21+ toolchain installed on the host.

### 9.1 Setup

Start the full local LNbits-over-LND stack, build the Go helper, and verify a Java-minted macaroon with Go:

```bash
bash scripts/run-go-interop-test.sh
```

This starts `bitcoind`, `lnd-payee`, `lnd-payer`, LNbits, and `paygate-example-app`, writes the helper source to `/tmp/paygate-go-interop`, builds `/tmp/paygate-go-interop/paygate-go-interop`, requests a fresh 402 challenge, and verifies the Java macaroon with Go.
By default it resets Docker volumes so stale regtest chain state cannot break setup. To reuse an existing clean stack, run `RESET_STACK=false bash scripts/run-go-interop-test.sh`.
If an earlier failed paste left your terminal at a `heredoc>` prompt, press `Ctrl-C` to return to the normal shell prompt before running the script.

### 9.2 Java-to-Go: Mint in Java, verify in Go

If the stack is already running and you only want to rerun the Java-to-Go check manually, obtain a macaroon from a 402 challenge:

```bash
APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"

MACAROON=$(curl -sI "$PROTECTED_ENDPOINT" | \
  grep -i "www-authenticate" | \
  sed 's/^[^:]*: //' | \
  sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')

if [ -z "$MACAROON" ]; then
  echo "No macaroon found. Make sure the example app is running and $PROTECTED_ENDPOINT returns a 402 challenge."
else
  echo "Java macaroon: ${MACAROON:0:40}..."
  /tmp/paygate-go-interop/paygate-go-interop verify "$MACAROON"
fi
```

**Expected:**
- Go successfully deserializes the macaroon
- ID length is 66 bytes (2 version + 32 payment_hash + 32 token_id)
- Output ends with `OK: Go successfully deserialized Java macaroon`

### 9.3 Go-to-Java: Mint in Go, verify in Java

This test requires access to the app's root key, which is only practical in test mode. The unit-level cross-language tests in `paygate-core` (see `src/test/resources/test-vectors/go-macaroon-vectors.json`) provide more rigorous coverage of this direction.

For a manual smoke test:

```bash
GO_MACAROON=$(/tmp/paygate-go-interop/paygate-go-interop mint "test-root-key" "test-identifier")
echo "Go macaroon: ${GO_MACAROON:0:40}..."

cd /Users/mark/code/greenharborlabs/spring-boot-starter-l402
./gradlew :paygate-core:test --tests "*GoVectorVerificationTest"
```

The Java app cannot verify the ad hoc Go-minted macaroon directly because it uses a different root key. `GoVectorVerificationTest` verifies the checked-in Go-generated fixture file under `paygate-core/src/test/resources/test-vectors/`, including Java deserialization of Go V2 bytes, signature chain compatibility, base64 compatibility, and 66-byte L402 identifiers.

### 9.4 Cleanup

```bash
rm -rf /tmp/paygate-go-interop
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `Macaroon unmarshal error` | Version mismatch (V1 vs V2) | Ensure Java mints V2 macaroons (`macaroon.V2`) |
| ID length is not 66 | Identifier layout differs | Check `[version:2][payment_hash:32][token_id:32]` encoding |
| Go toolchain not found | Go not installed | Install Go 1.21+ or skip this test |
| `go mod tidy` fails | No internet access | Pre-download `gopkg.in/macaroon.v2` or use a Go module proxy |

---

## Appendix: Quick Reference

### Credential extraction snippet

```bash
APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"

WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | tr -d '\r' | \
  grep -i "^www-authenticate:[[:space:]]*L402 " | \
  sed 's/^[^:]*:[[:space:]]*//' | \
  head -1)
MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

if [ -z "$MACAROON" ] || [ -z "$INVOICE" ]; then
  echo "Failed to extract macaroon or invoice from $PROTECTED_ENDPOINT"
else
  echo "MACAROON=${MACAROON:0:40}..."
  echo "INVOICE=${INVOICE:0:40}..."
fi
```

### Pay via lnd-payer and extract preimage

```bash
if [ -z "${INVOICE:-}" ]; then
  echo "INVOICE is empty. Run the credential extraction snippet first."
else
  eval "$(COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"
  echo "PAYMENT_HASH=$PAYMENT_HASH"
  echo "PREIMAGE=$PREIMAGE"
fi
```

### Full L402 request

```bash
APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"

if [ -z "${MACAROON:-}" ] || [ -z "${PREIMAGE:-}" ]; then
  echo "MACAROON or PREIMAGE is empty. Extract the challenge and pay the invoice first."
else
  curl -v -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" "$PROTECTED_ENDPOINT"
fi
```

### Docker log inspection

LND stack:

```bash
docker compose -f docker-compose-lnd.yml logs --no-log-prefix -f paygate-example-app
```

LNbits FakeWallet stack:

```bash
docker compose -f docker-compose-lnbits.yml logs --no-log-prefix -f paygate-example-app
```

LNbits-over-LND stack:

```bash
docker compose -f docker-compose-lnbits-lnd.yml logs --no-log-prefix -f paygate-example-app
```

If your Docker Compose version does not support `--no-log-prefix`, omit that flag. The logs still work, but each line will include the service prefix.

### Reset everything

```bash
docker compose -f docker-compose-lnd-two-node.yml down -v --remove-orphans
docker compose -f docker-compose-lnbits.yml down -v --remove-orphans
docker compose -f docker-compose-lnbits-lnd.yml down -v --remove-orphans
docker volume prune -f
```
