# Integration Testing Playbook

Step-by-step manual test scenarios for the L402 Spring Boot Starter. Each scenario includes exact commands, expected outputs, and troubleshooting guidance.

**Prerequisites:** Docker Engine 24+, Docker Compose v2, `curl`, `jq`, `python3`, and a POSIX shell (bash/zsh). For the Go interop test, a Go 1.21+ toolchain is also required.

All commands assume you are in the `integration-tests/` directory unless otherwise noted.

---

## Table of Contents

- [Quick Smoke Test](#quick-smoke-test) -- 5-command zero-to-verified flow
- [Manual Local Walkthrough](#manual-local-walkthrough) -- run infra locally and step through L402 and MPP by hand

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
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
```

**2. Start LNbits, bootstrap a wallet, and write the API key to `.env`:**

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
```

This waits for LNbits to become healthy, creates a test wallet, and stores `LNBITS_API_KEY` in `.env`. After it finishes, restart the example app so it picks up the key:

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
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

## Manual Local Walkthrough

Use this when you want to run the local infrastructure, keep the example app up, and manually step through the payment-gated request flow. This walkthrough uses LNbits backed by the local payee LND node and pays invoices through `lnd-payer`, so the payment returns a real preimage and the proof can be checked end to end.

Each step below calls out:
- **Where:** the directory or shell context to run the command in.
- **What:** the concrete action being performed.
- **Why:** the role that action plays in the payment proof flow.
- **How to verify:** the signal that tells the tester the step worked.

### What You Will Run

- `bitcoind`: local Bitcoin regtest chain used only inside Docker.
- `lnd`: local payee Lightning node connected to that regtest chain.
- `lnd-payer`: local payer Lightning node with a channel to `lnd`.
- `lnbits`: REST wallet API backed by the local LND node.
- `paygate-example-app`: Spring Boot example app protected by Paygate.

Default host URLs:

```bash
APP_URL="http://localhost:${APP_PORT:-18080}"
LNBITS_URL="http://localhost:${LNBITS_PORT:-15000}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
```

### 1. Start Bitcoin and Both LND Nodes

**Where:** run from `integration-tests/`.

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
```

**What happens:**
- Docker starts a private regtest Bitcoin node, a payee LND node, and a payer LND node.
- `setup-lnd-channel.sh` waits for all services, mines spendable regtest coins to `lnd-payer`, connects it to `lnd`, opens a channel, and waits for the channel to become active.
- No real Bitcoin or Lightning funds are used.

**Why this is required:** LNbits creates invoices on the payee LND node. A separate payer node must settle those invoices over a real Lightning channel so the payer receives the actual preimage for `sha256(preimage) == payment_hash`. Paying from a wallet in the same local LNbits instance can be treated as an internal payment and may not produce a usable proof preimage.

**How to verify:** the setup script should end with `Setup complete`, print payer/payee channel balances, and show active channels on both nodes.

### 2. Start LNbits and Create a Wallet Key

**Where:** run from `integration-tests/`.

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
```

**What happens:**
- LNbits starts with `LndRestWallet`, using LND's REST endpoint, TLS cert, and admin macaroon from the shared Docker volume.
- `setup-lnbits.sh` initializes LNbits first-install state if needed, logs in, creates a wallet, and writes `LNBITS_API_KEY` to `.env`.
- That API key is the wallet admin key used by the example app configuration. Local proof smoke tests pay via `lnd-payer`, not via this LNbits wallet.

**Why this is required:** the example app talks to LNbits to create invoices. It needs a wallet admin key so invoice creation succeeds.

**How to verify:** `.env` contains `LNBITS_API_KEY=...`, and `curl http://localhost:15000/api/v1/health` returns successfully.

### 3. Start the Example App

**Where:** run from `integration-tests/`.

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
```

**What happens:**
- The app starts with `PAYGATE_BACKEND=lnbits`.
- Inside Docker, the app reaches LNbits at `http://lnbits:5000`.
- On your host, you reach the app at `http://localhost:18080` unless you changed `APP_PORT`.

Wait for the app:

```bash
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

**Why this is required:** the app is the protected resource server. It issues payment challenges and validates the macaroon plus preimage credential after payment.

**How to verify:** the wait loop prints `App is ready`, or a direct request to `http://localhost:18080/api/v1/health` returns `200`.

### 4. Request the Protected Endpoint

**Where:** run from the same `integration-tests/` shell where the `APP_URL`, `PROTECTED_ENDPOINT`, and `HEALTH_ENDPOINT` variables are set.

```bash
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')

echo "HTTP status: $HTTP_STATUS"
echo "$BODY_402" | jq .
tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:"
```

**What happens:**
- You call a protected endpoint without credentials.
- The app returns `402 Payment Required`.
- The response includes payment challenges. The L402 challenge contains a macaroon and Lightning invoice.

**Why this is required:** this is the challenge phase of the flow. The app mints a macaroon whose identifier commits to the invoice payment hash. The client must pay the invoice and later present the macaroon plus preimage.

**How to verify:** `HTTP status: 402` is printed, the body is valid JSON, and at least one `WWW-Authenticate` header starts with `L402`.

Extract the L402 values:

```bash
WWW_AUTH=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:" | sed 's/^[^:]*: //' | grep "^L402 " | head -1)
rm -f "$HEADER_FILE"

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "Macaroon chars: ${#MACAROON}"
echo "Invoice chars: ${#INVOICE}"
```

**How to verify:** both lengths are non-zero. The invoice should look like a regtest BOLT11 invoice beginning with `lnbcrt`.

### 5. Pay the Invoice Through lnd-payer

**Where:** run from `integration-tests/`.

```bash
PAY_RESULT=$(docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest payinvoice --force "$INVOICE")

printf '%s\n' "$PAY_RESULT"
PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
PREIMAGE=$(printf '%s' "$PAY_RESULT" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
```

**What happens:**
- `lnd-payer` pays the invoice created by LNbits on the payee LND node.
- LND settles the invoice and reveals the Lightning payment preimage.
- The payment hash is `sha256(preimage)` and is also embedded in the Paygate macaroon identifier.

**Why this is required:** the preimage is the payment proof. Paygate does not trust LNbits payment status alone; it verifies that the preimage hashes to the payment hash committed into the macaroon.

**How to verify:** the `lncli` output includes `Payment status: SUCCEEDED`, `PAYMENT_HASH` is 64 lowercase hex characters, and `PREIMAGE` is 64 lowercase hex characters.

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
PAY_RESULT=$(docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest payinvoice --force "$INVOICE")

PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
PREIMAGE=$(printf '%s' "$PAY_RESULT" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
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
docker compose -f docker-compose-lnbits-lnd.yml logs -f paygate-example-app
```

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

Use this helper in any scenario that needs a fresh valid L402 credential from the two-node LNbits-over-LND stack. It requests a protected resource, extracts the L402 macaroon and invoice, pays the invoice through `lnd-payer`, extracts the payment hash and preimage from `lncli`, and verifies `sha256(preimage) == payment_hash`.

**Where:** define this function in the same `integration-tests/` shell where `PROTECTED_ENDPOINT` is set.

```bash
get_lnbits_lnd_l402_credential() {
  HEADER_FILE=$(mktemp)
  BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
  HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
  WWW_AUTH=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:" | sed 's/^[^:]*: //' | grep "^L402 " | head -1)
  rm -f "$HEADER_FILE"

  if [ "$HTTP_STATUS" != "402" ] || [ -z "$WWW_AUTH" ]; then
    echo "Failed to obtain L402 challenge"
    echo "$BODY_402"
    return 1
  fi

  MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
  INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

  PAY_RESULT=$(docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
    lncli --network=regtest payinvoice --force "$INVOICE")

  PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
  PREIMAGE=$(printf '%s' "$PAY_RESULT" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')

  PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)

  if [ "$PREIMAGE_HASH" != "$PAYMENT_HASH" ]; then
    echo "Payment proof mismatch: sha256(preimage)=$PREIMAGE_HASH payment_hash=$PAYMENT_HASH"
    return 1
  fi

  echo "Credential ready: macaroon=${#MACAROON} chars payment_hash=${PAYMENT_HASH:0:16}..."
}
```

**What to remember:** the `lncli payinvoice` output format varies by LND version and image. The local image used here prints table/text output with `Payment hash:` and `preimage:` lines, so the helper parses those lines instead of assuming JSON.

---

## 1. Happy Path (LND)

Full payment flow: request protected resource, receive 402 challenge, pay the invoice via LND, then access the resource with the L402 credential.

### 1.1 Start the environment

```bash
docker compose -f docker-compose-lnd.yml up -d
bash scripts/setup-lnd.sh
```

Wait for the example app to become healthy:

```bash
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

### 1.2 Request the protected endpoint (expect 402)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
HEADERS=$(curl -sI "$PROTECTED_ENDPOINT")

echo "HTTP Status: $HTTP_STATUS"
echo "$HEADERS" | grep -i "www-authenticate"
```

**Expected:**
- HTTP status: `402`
- `WWW-Authenticate` header present with format:
  ```
  WWW-Authenticate: L402 version="0", token="<base64>", macaroon="<base64>", invoice="<bolt11>"
  ```

### 1.3 Extract the macaroon and invoice

```bash
WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')

MACAROON=$(echo "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(echo "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "Macaroon: ${MACAROON:0:40}..."
echo "Invoice:  ${INVOICE:0:40}..."
```

Verify both values are non-empty:

```bash
[ -n "$MACAROON" ] && echo "OK: macaroon captured" || echo "FAIL: macaroon is empty"
[ -n "$INVOICE" ] && echo "OK: invoice captured" || echo "FAIL: invoice is empty"
```

### 1.4 Pay the invoice via lncli

```bash
PAY_RESULT=$(docker compose -f docker-compose-lnd.yml exec -T lnd \
  lncli --network=regtest payinvoice --force "$INVOICE" 2>&1)

echo "$PAY_RESULT"
```

**Expected:** Output indicates the payment succeeded. Depending on the LND image, this may be JSON with a `payment_preimage` field or table/text output with a `Payment status: SUCCEEDED, preimage: ...` line.

### 1.5 Extract the preimage

```bash
PREIMAGE=$(printf '%s' "$PAY_RESULT" | jq -r '.payment_preimage // .preimage // empty' 2>/dev/null || true)
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(printf '%s' "$PAY_RESULT" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
fi

echo "Preimage: $PREIMAGE"
[ -n "$PREIMAGE" ] && echo "OK: preimage captured" || echo "FAIL: preimage is empty"
```

### 1.6 Access the protected endpoint with L402 credential (expect 200)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

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

HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
echo "HTTP Status (cache hit): $HTTP_STATUS"
```

**Expected:** HTTP status `200`. The second request should be notably faster (credential cached).

### 1.8 Tear down

```bash
docker compose -f docker-compose-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 402 but empty `WWW-Authenticate` | App misconfigured | Check `PAYGATE_ENABLED=true` in container env |
| `payinvoice` hangs | LND has no funds | Re-run `scripts/setup-lnd.sh` to mine blocks |
| `payinvoice` returns `FAILED` | Invoice expired or already paid | Get a fresh 402 challenge and retry |
| `payinvoice` output is not JSON | LND image prints table/text output | Parse `Payment hash:` and `preimage:` lines, as shown above |
| 401 with valid credential | Preimage/macaroon mismatch | Ensure you extracted both from the same 402 response |

---

## 2. Happy Path (LNbits)

Same flow as scenario 1, but the app creates invoices through LNbits. Use `docker-compose-lnbits-lnd.yml` for full proof verification with `lnd-payer` paying the invoice over a real channel. Use `docker-compose-lnbits.yml` only for faster setup and invoice checks.

### 2.1 Start the environment

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app

until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

### 2.2 Request the protected endpoint (expect 402)

```bash
WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")

echo "HTTP Status: $HTTP_STATUS"

MACAROON=$(echo "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(echo "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

echo "Macaroon: ${MACAROON:0:40}..."
echo "Invoice:  ${INVOICE:0:40}..."
```

**Expected:** HTTP status `402` with a valid `WWW-Authenticate` header.

### 2.3 Pay the invoice via lnd-payer

```bash
PAY_RESULT=$(docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest payinvoice --force "$INVOICE")

printf '%s\n' "$PAY_RESULT"
PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
PREIMAGE=$(printf '%s' "$PAY_RESULT" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]')
```

**Expected:** `lncli` prints `Payment status: SUCCEEDED` plus `Payment hash:` and `preimage:` lines.

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

HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

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
# Start bitcoind + both LND nodes, then open a payer channel
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh

# Start LNbits and create an API key
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh

# Start the app with a 30-second credential timeout
PAYGATE_DEFAULT_TIMEOUT_SECONDS=30 \
  docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app

until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

**Alternative:** If the timeout is not configurable via environment variable at the container level, modify the `@L402Protected` annotation or set it in `application.yml` and rebuild:

```bash
# Override via Docker Compose environment (add to the paygate-example-app service)
# PAYGATE_DEFAULT_TIMEOUT_SECONDS: "30"
```

### 3.2 Obtain and pay for a credential

```bash
# Define get_lnbits_lnd_l402_credential from "Reusable LNbits Proof Helper"
# first if this is a new shell.
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

**Expected:** HTTP status `401`. The response body should indicate the credential has expired.

### 3.5 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Still 200 after timeout | Timeout override not applied | Verify with `docker compose exec paygate-example-app env \| grep TIMEOUT` |
| 401 immediately | Credential validation failed | Check preimage extraction in step 3.2 |

---

## 4. Tamper Detection

Verify that the server rejects tampered macaroons and mismatched preimages.

**Prerequisite:** Complete scenario 1 or 2 first to obtain a valid `$MACAROON` and `$PREIMAGE`. Or run the following to get a valid credential using LNbits backed by a payee LND node and paid by `lnd-payer`:

```bash
# Quick setup (if not already running)
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done

# Get a valid credential. Define get_lnbits_lnd_l402_credential from
# "Reusable LNbits Proof Helper" first if this is a new shell.
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

**Expected:** HTTP status `401`.

### 4.2 Wrong preimage

```bash
# Use a completely wrong preimage (64 hex characters of zeros)
WRONG_PREIMAGE="0000000000000000000000000000000000000000000000000000000000000000"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${WRONG_PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "Wrong preimage: $HTTP_STATUS"
```

**Expected:** HTTP status `401`.

### 4.3 Macaroon from one token with preimage from another

Get a second credential and cross them:

```bash
MACAROON_1="$MACAROON"
PREIMAGE_1="$PREIMAGE"

# Get a second credential and preserve it separately.
get_lnbits_lnd_l402_credential
MACAROON_2="$MACAROON"
PREIMAGE_2="$PREIMAGE"

# Cross them: first macaroon with second preimage
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON_1}:${PREIMAGE_2}" \
  "$PROTECTED_ENDPOINT")

echo "Cross-token (mac1 + preimage2): $HTTP_STATUS"

# And the reverse: second macaroon with first preimage
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON_2}:${PREIMAGE_1}" \
  "$PROTECTED_ENDPOINT")

echo "Cross-token (mac2 + preimage1): $HTTP_STATUS"
```

**Expected:** Both should return HTTP status `401`. The payment hash embedded in the macaroon identifier must match `SHA256(preimage)`.

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

**Expected:** All should return `401` or `402` (not `500`). The server must never crash on malformed input.

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Tampered macaroon returns 200 | Signature not being verified | Check `L402Validator` logic |
| Wrong preimage returns 200 | Payment hash not checked against preimage | Check `SHA256(preimage) == payment_hash` in validator |
| 500 on malformed input | Missing error handling | This is a bug -- the server should never return 500 for bad auth |

---

## 5. Fail-Closed Test

Verify that the server returns `503 Service Unavailable` when the Lightning backend is unreachable, rather than silently allowing access.

### 5.1 Start the LND environment

```bash
docker compose -f docker-compose-lnd.yml up -d
bash scripts/setup-lnd.sh
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

### 5.2 Confirm normal operation

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "Before stopping LND: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (normal challenge response).

### 5.3 Stop the Lightning container

```bash
docker compose -f docker-compose-lnd.yml stop lnd
echo "LND container stopped."

# Wait a moment for the app's health cache to expire
sleep 10
```

### 5.4 Request the protected endpoint (expect 503)

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `503`
- The server must NOT return `200` (that would mean fail-open, a security vulnerability)
- The server should NOT return `500` (unhandled exception)

### 5.5 Verify the health endpoint also reflects the issue

```bash
HEALTH_RESPONSE=$(curl -s "$APP_URL/actuator/health" 2>/dev/null || curl -s "$HEALTH_ENDPOINT")
echo "$HEALTH_RESPONSE" | jq . 2>/dev/null || echo "$HEALTH_RESPONSE"
```

**Expected:** Health status should indicate the Lightning backend is down.

### 5.6 Restart LND and confirm recovery

```bash
docker compose -f docker-compose-lnd.yml start lnd

# Wait for LND to become healthy again
echo "Waiting for LND recovery..."
sleep 15

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "After LND recovery: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (normal operation resumed).

### 5.7 Tear down

```bash
docker compose -f docker-compose-lnd.yml down -v
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
docker compose -f docker-compose-lnbits.yml up -d
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d paygate-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
```

### 6.2 Send a burst of unauthenticated requests

```bash
echo "Sending 50 rapid requests..."
for i in $(seq 1 50); do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
  echo "Request $i: $HTTP_STATUS"
  # No sleep -- fire as fast as possible
done
```

**Expected:** The first several requests return `402`. After the burst limit is exceeded, subsequent requests return `429 Too Many Requests`.

### 6.3 Verify 429 response includes Retry-After header

```bash
HEADERS=$(curl -sI "$PROTECTED_ENDPOINT")
echo "$HEADERS" | grep -i "retry-after"
```

**Expected:** If rate-limited, the response should include a `Retry-After` header indicating when the client can retry.

### 6.4 Wait and verify recovery

```bash
echo "Waiting 10 seconds for rate limit to reset..."
sleep 10

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
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

Verify the L402 flow works with the `paygate-spring-security` module, confirming that the `SecurityContext` is populated with an `L402AuthenticationToken`.

### 7.1 Prerequisites

The example app must include the `paygate-spring-security` dependency and have Spring Security enabled. Verify the app configuration includes:

```yaml
# In the example app's application.yml or via environment variables
spring.security.enabled: true
```

### 7.2 Start the environment

Use either LND or two-node LNbits-over-LND. This example uses LNbits backed by the payee LND node and pays through `lnd-payer` so the credential flow has a real proof preimage:

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
```

### 7.3 Verify unauthenticated request returns 402

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")
echo "Unauthenticated: $HTTP_STATUS"
```

**Expected:** HTTP status `402` (Spring Security delegates to the L402 filter).

### 7.4 Obtain and use a valid L402 credential

Follow the same steps as scenario 2 (LNbits happy path) to obtain `$MACAROON` and `$PREIMAGE`, then:

```bash
# (Assuming MACAROON and PREIMAGE are set from the LNbits payment flow)
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Status: $HTTP_STATUS"
echo "Body: $BODY"
```

**Expected:**
- HTTP status: `200`
- The response confirms access was granted through Spring Security's authentication chain

### 7.5 Verify SecurityContext population (via debug endpoint)

If the example app exposes a debug/whoami endpoint that shows the current authentication principal:

```bash
RESPONSE=$(curl -s \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$APP_URL/api/v1/whoami" 2>/dev/null)

echo "$RESPONSE" | jq . 2>/dev/null || echo "$RESPONSE"
```

**Expected:** If available, the response should show an `L402AuthenticationToken` with the token ID as the principal. If no such endpoint exists, verify via application logs:

```bash
docker compose -f docker-compose-lnbits-lnd.yml logs paygate-example-app | grep -i "L402Auth"
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

**Expected:** Both should return `402` (challenge) or `401` (unauthorized), not `200`.

### 7.7 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 403 instead of 402 | Spring Security rejecting before L402 filter | Check filter order -- L402 filter must run before default auth |
| 401 with valid L402 | `L402AuthenticationProvider` not registered | Verify `paygate-spring-security` is on the classpath |
| 200 without auth | Security not enabled | Check `spring.security.enabled` and `@EnableWebSecurity` |

---

## 8. LSAT Backward Compatibility

Verify that the server accepts the legacy `LSAT` scheme in the `Authorization` header, maintaining backward compatibility with older clients.

### 8.1 Start any environment and obtain a valid credential

Use two-node LNbits-over-LND so the LSAT credential carries a real proof preimage:

```bash
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done

# Obtain a credential. Define get_lnbits_lnd_l402_credential from
# "Reusable LNbits Proof Helper" first if this is a new shell.
get_lnbits_lnd_l402_credential
```

### 8.2 Use the `L402` scheme (baseline)

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "L402 scheme: $HTTP_STATUS"
```

**Expected:** HTTP status `200`.

### 8.3 Use the legacy `LSAT` scheme

```bash
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: LSAT ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

echo "LSAT scheme: $HTTP_STATUS"
```

**Expected:** HTTP status `200`. The server must accept both `L402` and `LSAT` prefixes.

### 8.4 Verify case insensitivity (optional)

```bash
# Lowercase
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: l402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")
echo "Lowercase l402: $HTTP_STATUS"

# Mixed case
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Lsat ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")
echo "Mixed case Lsat: $HTTP_STATUS"
```

**Expected:** Both should return `200` if the server does case-insensitive scheme matching. If the server is case-sensitive, these may return `402` -- document the actual behavior.

### 8.5 Tear down

```bash
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 401/402 with LSAT scheme | Backward compatibility not implemented | Check header parsing logic for `LSAT` prefix support |
| 200 with L402 but 401 with LSAT | Scheme comparison is case-sensitive and only matches `L402` | Add `LSAT` to accepted schemes |

---

## 9. Go Interop Test

Verify byte-level compatibility between the Java macaroon implementation and the Go `go-macaroon` library. This confirms that macaroons minted by one implementation can be deserialized and verified by the other.

**Requires:** Go 1.21+ toolchain installed on the host.

### 9.1 Setup

Clone or use the Go interop test utility. A minimal Go program is needed:

```bash
mkdir -p /tmp/paygate-go-interop
cat > /tmp/paygate-go-interop/main.go << 'GOEOF'
package main

import (
	"encoding/base64"
	"fmt"
	"os"

	"gopkg.in/macaroon.v2"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, "Usage: %s <command> [args...]\n", os.Args[0])
		fmt.Fprintf(os.Stderr, "Commands:\n")
		fmt.Fprintf(os.Stderr, "  verify <base64-macaroon>   Deserialize and print macaroon fields\n")
		fmt.Fprintf(os.Stderr, "  mint <hex-root-key> <id>   Mint a macaroon and print base64\n")
		os.Exit(1)
	}

	switch os.Args[1] {
	case "verify":
		if len(os.Args) < 3 {
			fmt.Fprintln(os.Stderr, "Missing macaroon argument")
			os.Exit(1)
		}
		raw, err := base64.StdEncoding.DecodeString(os.Args[2])
		if err != nil {
			fmt.Fprintf(os.Stderr, "Base64 decode error: %v\n", err)
			os.Exit(1)
		}
		var m macaroon.Macaroon
		if err := m.UnmarshalBinary(raw); err != nil {
			fmt.Fprintf(os.Stderr, "Macaroon unmarshal error: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Location: %s\n", m.Location())
		fmt.Printf("ID (hex): %x\n", m.Id())
		fmt.Printf("ID (len): %d\n", len(m.Id()))
		fmt.Printf("Signature (hex): %x\n", m.Signature())
		fmt.Printf("Caveats: %d\n", len(m.Caveats()))
		for i, c := range m.Caveats() {
			fmt.Printf("  Caveat[%d]: %s\n", i, string(c.Id))
		}
		fmt.Println("OK: Go successfully deserialized Java macaroon")

	case "mint":
		if len(os.Args) < 4 {
			fmt.Fprintln(os.Stderr, "Usage: mint <hex-root-key> <identifier>")
			os.Exit(1)
		}
		// Simplified: use raw bytes for root key
		rootKey := []byte(os.Args[2])
		id := []byte(os.Args[3])
		m, err := macaroon.New(rootKey, id, "l402", macaroon.V2)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Mint error: %v\n", err)
			os.Exit(1)
		}
		raw, err := m.MarshalBinary()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Marshal error: %v\n", err)
			os.Exit(1)
		}
		fmt.Print(base64.StdEncoding.EncodeToString(raw))

	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", os.Args[1])
		os.Exit(1)
	}
}
GOEOF

cat > /tmp/paygate-go-interop/go.mod << 'MODEOF'
module paygate-go-interop

go 1.21

require gopkg.in/macaroon.v2 v2.1.0
MODEOF

cd /tmp/paygate-go-interop && go mod tidy && go build -o paygate-go-interop .
```

### 9.2 Java-to-Go: Mint in Java, verify in Go

Start the environment and obtain a macaroon from a 402 challenge:

```bash
# Ensure the app is running (LNbits or LND)
MACAROON=$(curl -sI "$PROTECTED_ENDPOINT" | \
  grep -i "www-authenticate" | \
  sed 's/^[^:]*: //' | \
  sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')

echo "Java macaroon: ${MACAROON:0:40}..."

# Verify with Go
/tmp/paygate-go-interop/paygate-go-interop verify "$MACAROON"
```

**Expected:**
- Go successfully deserializes the macaroon
- ID length is 66 bytes (2 version + 32 payment_hash + 32 token_id)
- Output ends with `OK: Go successfully deserialized Java macaroon`

### 9.3 Go-to-Java: Mint in Go, verify in Java

This test requires access to the app's root key, which is only practical in test mode. The unit-level cross-language tests in `paygate-core` (see `src/test/resources/go-macaroon-fixtures/`) provide more rigorous coverage of this direction.

For a manual smoke test:

```bash
# Mint a simple macaroon with Go
GO_MACAROON=$(/tmp/paygate-go-interop/paygate-go-interop mint "test-root-key" "test-identifier")
echo "Go macaroon: ${GO_MACAROON:0:40}..."

# The Java app cannot verify this directly (different root key),
# but we can test deserialization via a dedicated test endpoint if available,
# or via the unit test suite:
cd /Users/mark/code/greenharborlabs/spring-boot-starter-l402
./gradlew :paygate-core:test --tests "*MacaroonInterop*" --tests "*GoMacaroon*"
```

**Expected:** The unit tests that cover Go fixture files pass, confirming byte-level compatibility.

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

### Credential extraction one-liner

```bash
# Get macaroon and invoice from a 402 response
eval $(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | \
  sed 's/.*macaroon="\([^"]*\)".*invoice="\([^"]*\)".*/MACAROON="\1"\nINVOICE="\2"/')
```

### Pay via lnd-payer and extract preimage

```bash
PAY=$(docker compose -f docker-compose-lnbits-lnd.yml exec -T lnd-payer \
  lncli --network=regtest payinvoice --force "$INVOICE") && \
PAYMENT_HASH=$(printf '%s' "$PAY" | grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]') && \
PREIMAGE=$(printf '%s' "$PAY" | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//' | tr '[:upper:]' '[:lower:]') && \
echo "PAYMENT_HASH=$PAYMENT_HASH" && \
echo "PREIMAGE=$PREIMAGE"
```

### Full L402 request

```bash
curl -v -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" "$PROTECTED_ENDPOINT"
```

### Docker log inspection

```bash
# LND stack
docker compose -f docker-compose-lnd.yml logs -f paygate-example-app

# LNbits FakeWallet stack
docker compose -f docker-compose-lnbits.yml logs -f paygate-example-app

# LNbits-over-LND stack
docker compose -f docker-compose-lnbits-lnd.yml logs -f paygate-example-app
```

### Reset everything

```bash
docker compose -f docker-compose-lnd.yml down -v
docker compose -f docker-compose-lnbits.yml down -v
docker compose -f docker-compose-lnbits-lnd.yml down -v
docker volume prune -f
```
