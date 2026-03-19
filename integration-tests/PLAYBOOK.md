# Integration Testing Playbook

Step-by-step manual test scenarios for the L402 Spring Boot Starter. Each scenario includes exact commands, expected outputs, and troubleshooting guidance.

**Prerequisites:** Docker Engine 24+, Docker Compose v2, `curl`, `jq`, `python3`, and a POSIX shell (bash/zsh). For the Go interop test, a Go 1.21+ toolchain is also required.

All commands assume you are in the `integration-tests/` directory unless otherwise noted.

---

## Table of Contents

- [Quick Smoke Test](#quick-smoke-test) -- 5-command zero-to-verified flow

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

A 5-command guide to go from zero to a verified **402 -> pay -> 200** flow using the LNbits FakeWallet backend. No real Lightning node required.

### Prerequisites

Docker Engine 24+, Docker Compose v2, `curl`, and `jq` must be installed.

### Steps

**1. Start the LNbits stack:**

```bash
docker compose -f docker-compose-lnbits.yml up -d
```

**2. Bootstrap a wallet and write the API key to `.env`:**

```bash
bash scripts/setup-lnbits.sh
```

This waits for LNbits to become healthy, creates a test wallet, and stores `LNBITS_API_KEY` in `.env`. After it finishes, restart the example app so it picks up the key:

```bash
docker compose -f docker-compose-lnbits.yml up -d l402-example-app
```

**3. Run the automated smoke test:**

```bash
bash scripts/run-smoke-test.sh
```

The script waits for the app to become healthy, then exercises the full L402 flow: unauthenticated request (expect 402), invoice payment via LNbits, and authenticated request with the L402 credential (expect 200).

**4. Check the output.** Each step prints PASS (green) or FAIL (red). The script exits `0` on success, non-zero on failure.

**5. Tear down:**

```bash
docker compose -f docker-compose-lnbits.yml down -v
```

### Notes

- The smoke test script does **not** start or stop Docker containers. You manage the stack lifecycle yourself (steps 1 and 5).
- The script sources `.env` automatically if present, picking up `LNBITS_API_KEY`, `LNBITS_PORT`, and `APP_PORT`.
- For the full manual walkthrough of each scenario, see the numbered sections below.

---

## Common Variables

Set these once per session. Adjust if you changed ports in `.env`.

```bash
APP_URL="http://localhost:${APP_PORT:-8080}"
PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
HEALTH_ENDPOINT="$APP_URL/api/v1/health"
```

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

**Expected:** Output includes `"status": "SUCCEEDED"` and a `"payment_preimage"` field.

### 1.5 Extract the preimage

```bash
PREIMAGE=$(echo "$PAY_RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('payment_preimage', ''))
")

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
| 402 but empty `WWW-Authenticate` | App misconfigured | Check `L402_ENABLED=true` in container env |
| `payinvoice` hangs | LND has no funds | Re-run `scripts/setup-lnd.sh` to mine blocks |
| `payinvoice` returns `FAILED` | Invoice expired or already paid | Get a fresh 402 challenge and retry |
| 401 with valid credential | Preimage/macaroon mismatch | Ensure you extracted both from the same 402 response |

---

## 2. Happy Path (LNbits)

Same flow as scenario 1, but using the LNbits FakeWallet backend. The FakeWallet simulates payments without a real Lightning node, making this test faster and more deterministic.

### 2.1 Start the environment

```bash
docker compose -f docker-compose-lnbits.yml up -d lnbits
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d l402-example-app

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

### 2.3 Pay the invoice via LNbits API

Read the LNbits API key from `.env`:

```bash
source .env
LNBITS_URL="http://localhost:${LNBITS_PORT:-5000}"
LNBITS_KEY="$LNBITS_API_KEY"

PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")

echo "$PAY_RESULT" | jq .
```

**Expected:** JSON response with `"checking_id"` and `"payment_hash"` fields.

### 2.4 Extract the preimage from LNbits

```bash
PAYMENT_HASH=$(echo "$PAY_RESULT" | jq -r '.payment_hash')

# Query the payment details to get the preimage
PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
  -H "X-Api-Key: ${LNBITS_KEY}")

PREIMAGE=$(echo "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')

# FakeWallet may return the preimage directly in the pay response
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(echo "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
fi

echo "Preimage: $PREIMAGE"
[ -n "$PREIMAGE" ] && echo "OK: preimage captured" || echo "FAIL: preimage is empty"
```

**Note:** The FakeWallet backend generates deterministic preimages. If extraction fails, check the LNbits API docs for your version -- the response schema may differ.

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
docker compose -f docker-compose-lnbits.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| LNbits returns 401 on pay | Wrong API key | Re-run `scripts/setup-lnbits.sh` and restart the app |
| Preimage is empty | FakeWallet schema difference | Check `curl ${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}` manually |
| App returns 503 | Cannot reach LNbits | Verify `L402_LNBITS_URL` points to `http://lnbits:5000` inside Docker network |

---

## 3. Expiration Test

Verify that L402 credentials expire after the configured timeout.

### 3.1 Start LNbits environment with short timeout

This test uses LNbits for simplicity. Override the timeout to 30 seconds:

```bash
# Start the LNbits stack
docker compose -f docker-compose-lnbits.yml up -d lnbits
bash scripts/setup-lnbits.sh

# Start the app with a 30-second credential timeout
L402_DEFAULT_TIMEOUT_SECONDS=30 \
  docker compose -f docker-compose-lnbits.yml up -d l402-example-app

until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done
echo "App is ready."
```

**Alternative:** If the timeout is not configurable via environment variable at the container level, modify the `@L402Protected` annotation or set it in `application.yml` and rebuild:

```bash
# Override via Docker Compose environment (add to the l402-example-app service)
# L402_DEFAULT_TIMEOUT_SECONDS: "30"
```

### 3.2 Obtain and pay for a credential

```bash
# Get the 402 challenge
WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')
MACAROON=$(echo "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(echo "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

# Pay via LNbits
source .env
LNBITS_URL="http://localhost:${LNBITS_PORT:-5000}"
PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")

PAYMENT_HASH=$(echo "$PAY_RESULT" | jq -r '.payment_hash')
PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
  -H "X-Api-Key: ${LNBITS_API_KEY}")
PREIMAGE=$(echo "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(echo "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
fi
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
docker compose -f docker-compose-lnbits.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Still 200 after timeout | Timeout override not applied | Verify with `docker compose exec l402-example-app env \| grep TIMEOUT` |
| 401 immediately | Credential validation failed | Check preimage extraction in step 3.2 |

---

## 4. Tamper Detection

Verify that the server rejects tampered macaroons and mismatched preimages.

**Prerequisite:** Complete scenario 1 or 2 first to obtain a valid `$MACAROON` and `$PREIMAGE`. Or run the following to get a valid credential (using LNbits for speed):

```bash
# Quick setup (if not already running)
docker compose -f docker-compose-lnbits.yml up -d
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d l402-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done

# Get a valid credential
WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')
MACAROON=$(echo "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(echo "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

source .env
LNBITS_URL="http://localhost:${LNBITS_PORT:-5000}"
PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")
PAYMENT_HASH=$(echo "$PAY_RESULT" | jq -r '.payment_hash')
PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
  -H "X-Api-Key: ${LNBITS_API_KEY}")
PREIMAGE=$(echo "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(echo "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
fi

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
# Get a second 402 challenge
WWW_AUTH_2=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')
MACAROON_2=$(echo "$WWW_AUTH_2" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE_2=$(echo "$WWW_AUTH_2" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

# Pay the second invoice
PAY_RESULT_2=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE_2}\"}")
PAYMENT_HASH_2=$(echo "$PAY_RESULT_2" | jq -r '.payment_hash')
PAYMENT_DETAILS_2=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH_2}" \
  -H "X-Api-Key: ${LNBITS_API_KEY}")
PREIMAGE_2=$(echo "$PAYMENT_DETAILS_2" | jq -r '.preimage // .details.preimage // empty')
if [ -z "$PREIMAGE_2" ]; then
  PREIMAGE_2=$(echo "$PAY_RESULT_2" | jq -r '.preimage // .checking_id // empty')
fi

# Cross them: first macaroon with second preimage
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE_2}" \
  "$PROTECTED_ENDPOINT")

echo "Cross-token (mac1 + preimage2): $HTTP_STATUS"

# And the reverse: second macaroon with first preimage
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON_2}:${PREIMAGE}" \
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

Use LNbits for simplicity:

```bash
docker compose -f docker-compose-lnbits.yml up -d
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d l402-example-app
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

Verify the L402 flow works with the `l402-spring-security` module, confirming that the `SecurityContext` is populated with an `L402AuthenticationToken`.

### 7.1 Prerequisites

The example app must include the `l402-spring-security` dependency and have Spring Security enabled. Verify the app configuration includes:

```yaml
# In the example app's application.yml or via environment variables
spring.security.enabled: true
```

### 7.2 Start the environment

Use either LND or LNbits. This example uses LNbits:

```bash
docker compose -f docker-compose-lnbits.yml up -d
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d l402-example-app
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
docker compose -f docker-compose-lnbits.yml logs l402-example-app | grep -i "L402Auth"
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
docker compose -f docker-compose-lnbits.yml down -v
```

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| 403 instead of 402 | Spring Security rejecting before L402 filter | Check filter order -- L402 filter must run before default auth |
| 401 with valid L402 | `L402AuthenticationProvider` not registered | Verify `l402-spring-security` is on the classpath |
| 200 without auth | Security not enabled | Check `spring.security.enabled` and `@EnableWebSecurity` |

---

## 8. LSAT Backward Compatibility

Verify that the server accepts the legacy `LSAT` scheme in the `Authorization` header, maintaining backward compatibility with older clients.

### 8.1 Start any environment and obtain a valid credential

Use LNbits for speed:

```bash
docker compose -f docker-compose-lnbits.yml up -d
bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits.yml up -d l402-example-app
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do sleep 2; done

# Obtain a credential (same as scenario 2)
WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | grep -i "www-authenticate" | sed 's/^[^:]*: //')
MACAROON=$(echo "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(echo "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

source .env
LNBITS_URL="http://localhost:${LNBITS_PORT:-5000}"
PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")
PAYMENT_HASH=$(echo "$PAY_RESULT" | jq -r '.payment_hash')
PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
  -H "X-Api-Key: ${LNBITS_API_KEY}")
PREIMAGE=$(echo "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(echo "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
fi
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
docker compose -f docker-compose-lnbits.yml down -v
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
mkdir -p /tmp/l402-go-interop
cat > /tmp/l402-go-interop/main.go << 'GOEOF'
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

cat > /tmp/l402-go-interop/go.mod << 'MODEOF'
module l402-go-interop

go 1.21

require gopkg.in/macaroon.v2 v2.1.0
MODEOF

cd /tmp/l402-go-interop && go mod tidy && go build -o l402-go-interop .
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
/tmp/l402-go-interop/l402-go-interop verify "$MACAROON"
```

**Expected:**
- Go successfully deserializes the macaroon
- ID length is 66 bytes (2 version + 32 payment_hash + 32 token_id)
- Output ends with `OK: Go successfully deserialized Java macaroon`

### 9.3 Go-to-Java: Mint in Go, verify in Java

This test requires access to the app's root key, which is only practical in test mode. The unit-level cross-language tests in `l402-core` (see `src/test/resources/go-macaroon-fixtures/`) provide more rigorous coverage of this direction.

For a manual smoke test:

```bash
# Mint a simple macaroon with Go
GO_MACAROON=$(/tmp/l402-go-interop/l402-go-interop mint "test-root-key" "test-identifier")
echo "Go macaroon: ${GO_MACAROON:0:40}..."

# The Java app cannot verify this directly (different root key),
# but we can test deserialization via a dedicated test endpoint if available,
# or via the unit test suite:
cd /Users/mark/code/greenharborlabs/spring-boot-starter-l402
./gradlew :l402-core:test --tests "*MacaroonInterop*" --tests "*GoMacaroon*"
```

**Expected:** The unit tests that cover Go fixture files pass, confirming byte-level compatibility.

### 9.4 Cleanup

```bash
rm -rf /tmp/l402-go-interop
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

### Pay via LNbits and extract preimage (one-liner)

```bash
source .env && \
PAY=$(curl -s -X POST "http://localhost:${LNBITS_PORT:-5000}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" -H "Content-Type: application/json" \
  -d "{\"out\":true,\"bolt11\":\"${INVOICE}\"}") && \
PREIMAGE=$(curl -s "http://localhost:${LNBITS_PORT:-5000}/api/v1/payments/$(echo $PAY | jq -r '.payment_hash')" \
  -H "X-Api-Key: ${LNBITS_API_KEY}" | jq -r '.preimage // empty') && \
echo "PREIMAGE=$PREIMAGE"
```

### Full L402 request

```bash
curl -v -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" "$PROTECTED_ENDPOINT"
```

### Docker log inspection

```bash
# LND stack
docker compose -f docker-compose-lnd.yml logs -f l402-example-app

# LNbits stack
docker compose -f docker-compose-lnbits.yml logs -f l402-example-app
```

### Reset everything

```bash
docker compose -f docker-compose-lnd.yml down -v
docker compose -f docker-compose-lnbits.yml down -v
docker volume prune -f
```
