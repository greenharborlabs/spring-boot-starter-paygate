#!/usr/bin/env bash
#
# run-smoke-test.sh — Automated L402 smoke test for the Paygate starter.
#
# Works in two modes:
#   1. Docker Compose local testing — run against the two-node LNbits-over-LND
#      stack with PAYER_BACKEND=lnd-cli for full proof verification
#   2. Live endpoint testing — point APP_URL and LNBITS_URL at a running
#      instance (e.g., a testnet deployment with SPRING_PROFILES_ACTIVE=lnbits-testnet)
#
# Exercises the full 402 -> pay -> 200 flow:
#   1. Request a protected endpoint (expect 402)
#   2. Extract the L402 challenge from WWW-Authenticate headers
#   3. Enforce a spend cap (MAX_INVOICE_SATS) before paying
#   4. Pay the invoice via the configured payer backend
#   5. Retrieve the preimage
#   6. Access the endpoint with the L402 credential (expect 200)
#
# Prerequisites: curl, jq, python3
# When dual-protocol headers are present (L402 + MPP), this script selects
# the L402 challenge. Use run-mpp-smoke-test.sh for MPP testing.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Colors (portable: works on macOS and Linux terminals)
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  YELLOW='\033[0;33m'
  BOLD='\033[1m'
  RESET='\033[0m'
else
  GREEN='' RED='' YELLOW='' BOLD='' RESET=''
fi

pass() { printf -- "${GREEN}PASS${RESET} %s\n" "$1"; }
fail() { printf -- "${RED}FAIL${RESET} %s\n" "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf -- "${BOLD}---> %s${RESET}\n" "$1"; }
sha256_preimage() {
  python3 - "$1" <<'PY'
import hashlib
import sys

try:
    preimage = bytes.fromhex(sys.argv[1])
except ValueError:
    sys.exit(2)
print(hashlib.sha256(preimage).hexdigest())
PY
}
json_string_field() {
  local field="$1"
  jq -r --arg field "$field" '.[$field] // empty' 2>/dev/null || true
}
lnd_text_payment_hash() {
  grep -ioE 'Payment hash:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//'
}
lnd_text_preimage() {
  grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' | tail -1 | sed 's/.*:[[:space:]]*//'
}

FAILURES=0

# ---------------------------------------------------------------------------
# Source .env if it exists (for LNBITS_API_KEY, ports, etc.)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
if [ -f "$PROJECT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  set -a
  . "$PROJECT_DIR/.env"
  set +a
fi

# ---------------------------------------------------------------------------
# Configuration (override via environment or .env)
# ---------------------------------------------------------------------------
APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"
HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-${APP_URL}/api/v1/health}"
LNBITS_PORT="${LNBITS_PORT:-15000}"
LNBITS_URL="${LNBITS_URL:-http://localhost:${LNBITS_PORT}}"
LNBITS_KEY="${LNBITS_API_KEY:-}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
MAX_INVOICE_SATS="${MAX_INVOICE_SATS:-50}"
PAYER_BACKEND="${PAYER_BACKEND:-lnbits}"
PAYER_COMPOSE_FILE="${PAYER_COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
PAYER_LND_SERVICE="${PAYER_LND_SERVICE:-lnd-payer}"

# ---------------------------------------------------------------------------
# Step 0: Check prerequisites
# ---------------------------------------------------------------------------
info "Checking prerequisites"

MISSING=""
for cmd in curl jq python3; do
  if ! command -v "$cmd" > /dev/null 2>&1; then
    MISSING="$MISSING $cmd"
  fi
done
if [ "$PAYER_BACKEND" = "lnd-cli" ] && ! command -v docker > /dev/null 2>&1; then
  MISSING="$MISSING docker"
fi

if [ -n "$MISSING" ]; then
  fail "Missing required tools:${MISSING}"
  echo "Install the missing tools and try again."
  exit 1
fi
pass "All prerequisites found"

if [ "$PAYER_BACKEND" = "lnbits" ] && [ -z "$LNBITS_KEY" ]; then
  fail "LNBITS_API_KEY is not set. Run 'bash scripts/setup-lnbits.sh' first."
  exit 1
fi
if [ "$PAYER_BACKEND" = "lnbits" ]; then
  pass "LNBITS_API_KEY is set"
fi

case "$PAYER_BACKEND" in
  lnbits|lnd-cli)
    pass "PAYER_BACKEND=${PAYER_BACKEND}"
    ;;
  *)
    fail "Unsupported PAYER_BACKEND=${PAYER_BACKEND}. Expected 'lnbits' or 'lnd-cli'."
    exit 1
    ;;
esac

# ---------------------------------------------------------------------------
# Step 1: Wait for the example app to be healthy
# ---------------------------------------------------------------------------
info "Waiting for example app at ${HEALTH_ENDPOINT} (timeout: ${HEALTH_TIMEOUT}s)"

ELAPSED=0
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do
  if [ "$ELAPSED" -ge "$HEALTH_TIMEOUT" ]; then
    fail "App did not become healthy within ${HEALTH_TIMEOUT}s"
    echo "Check application logs for errors."
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
pass "App is healthy (waited ${ELAPSED}s)"

# ---------------------------------------------------------------------------
# Step 2: Request the protected endpoint — expect HTTP 402
# ---------------------------------------------------------------------------
info "Requesting protected endpoint (expect 402)"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED_ENDPOINT")

if [ "$HTTP_STATUS" = "402" ]; then
  pass "Got HTTP 402 Payment Required"
else
  fail "Expected HTTP 402, got $HTTP_STATUS"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Extract L402 challenge from WWW-Authenticate headers
# ---------------------------------------------------------------------------
info "Extracting L402 challenge from WWW-Authenticate headers"

# Capture both headers and body in a single request using -D for headers
HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")

# Extract all WWW-Authenticate headers, then filter for L402 scheme
ALL_WWW_AUTH=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:" | sed 's/^[^:]*: //')
rm -f "$HEADER_FILE"

if [ -z "$ALL_WWW_AUTH" ]; then
  fail "No WWW-Authenticate header found in 402 response"
  exit 1
fi

# Select the header value starting with L402 (may coexist with Payment/MPP)
WWW_AUTH=$(printf '%s\n' "$ALL_WWW_AUTH" | grep "^L402 " | head -1)

if [ -z "$WWW_AUTH" ]; then
  fail "No L402 challenge found in WWW-Authenticate headers"
  echo "Headers present: $ALL_WWW_AUTH"
  exit 1
fi
pass "L402 challenge selected from WWW-Authenticate headers"

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

if [ -n "$MACAROON" ]; then
  pass "Macaroon extracted (${#MACAROON} chars)"
else
  fail "Could not extract macaroon from L402 challenge"
  echo "Header value: $WWW_AUTH"
  exit 1
fi

if [ -n "$INVOICE" ]; then
  pass "Invoice extracted (${#INVOICE} chars)"
else
  fail "Could not extract invoice from L402 challenge"
  echo "Header value: $WWW_AUTH"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3b: Enforce spend cap before paying
# ---------------------------------------------------------------------------
info "Checking invoice amount against spend cap (MAX_INVOICE_SATS=${MAX_INVOICE_SATS})"

PRICE_SATS=$(printf '%s' "$BODY_402" | jq -r '.price_sats // empty' 2>/dev/null)

if [ -n "$PRICE_SATS" ]; then
  if [ "$PRICE_SATS" -gt "$MAX_INVOICE_SATS" ]; then
    fail "Invoice amount ${PRICE_SATS} sats exceeds MAX_INVOICE_SATS cap"
    exit 1
  fi
  pass "Invoice amount ${PRICE_SATS} sats within spend cap"
else
  info "No price_sats in response body — skipping spend cap check"
fi

# ---------------------------------------------------------------------------
# Step 4: Pay the invoice via payer backend
# ---------------------------------------------------------------------------
PREIMAGE=""
PAYMENT_DETAILS=""

if [ "$PAYER_BACKEND" = "lnd-cli" ]; then
  info "Paying invoice via ${PAYER_LND_SERVICE} lncli"
  PAY_RESULT=$(docker compose -f "$PAYER_COMPOSE_FILE" exec -T "$PAYER_LND_SERVICE" \
    lncli --network=regtest payinvoice --force "$INVOICE")
  PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | json_string_field payment_hash)
  PREIMAGE=$(printf '%s' "$PAY_RESULT" | json_string_field payment_preimage)
  if [ -z "$PAYMENT_HASH" ]; then
    PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | lnd_text_payment_hash)
  fi
  if [ -z "$PREIMAGE" ]; then
    PREIMAGE=$(printf '%s' "$PAY_RESULT" | json_string_field preimage)
  fi
  if [ -z "$PREIMAGE" ]; then
    PREIMAGE=$(printf '%s' "$PAY_RESULT" | lnd_text_preimage)
  fi
else
  info "Paying invoice via LNbits at ${LNBITS_URL}"
  PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
    -H "X-Api-Key: ${LNBITS_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")
  PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | json_string_field payment_hash)
fi

PAYMENT_HASH=$(printf '%s' "$PAYMENT_HASH" | tr '[:upper:]' '[:lower:]')

if [ -n "$PAYMENT_HASH" ]; then
  pass "Invoice paid (payment_hash: ${PAYMENT_HASH:0:16}...)"
else
  fail "Payment failed — could not extract payment_hash"
  echo "Payer response: $PAY_RESULT"
  exit 1
fi

if ! printf '%s' "$PAYMENT_HASH" | grep -qE '^[0-9a-f]{64}$'; then
  fail "Payment hash is not valid lowercase hex (64 chars): ${PAYMENT_HASH}"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 5: Retrieve the preimage from payment details
# ---------------------------------------------------------------------------
if [ -z "$PREIMAGE" ]; then
  if [ "$PAYER_BACKEND" != "lnbits" ]; then
    fail "Could not retrieve preimage from ${PAYER_BACKEND} payment response"
    echo "Payment response: $PAY_RESULT"
    exit 1
  fi

  info "Retrieving preimage from LNbits"
  PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
    -H "X-Api-Key: ${LNBITS_KEY}")

  PREIMAGE=$(printf '%s' "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')

  # FakeWallet may return preimage directly in the pay response.
  if [ -z "$PREIMAGE" ]; then
    PREIMAGE=$(printf '%s' "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
  fi
fi

if [ -n "$PREIMAGE" ]; then
  pass "Preimage retrieved (${PREIMAGE:0:16}...)"
else
  fail "Payment settled, but the payer API did not return a payment preimage; cannot construct an L402 credential. Spark-backed LNbits, local LNbits self-payments, and FakeWallet may omit or hide usable preimages."
  echo "Payment details: $PAYMENT_DETAILS"
  exit 1
fi

PREIMAGE=$(printf '%s' "$PREIMAGE" | tr '[:upper:]' '[:lower:]')

if ! printf '%s' "$PREIMAGE" | grep -qE '^[0-9a-f]{64}$'; then
  fail "Preimage is not valid lowercase hex (64 chars): ${PREIMAGE}"
  exit 1
fi
pass "Preimage is valid lowercase hex (64 chars)"

PREIMAGE_HASH=$(sha256_preimage "$PREIMAGE" 2>/dev/null || true)
if [ "$PREIMAGE_HASH" != "$PAYMENT_HASH" ]; then
  fail "Payer returned an unusable preimage: sha256(preimage)=${PREIMAGE_HASH:-<invalid>} does not match payment_hash=${PAYMENT_HASH}. Spark-backed LNbits, local LNbits self-payments, and FakeWallet are only suitable for setup/invoice checks when they do not expose a matching preimage; full local proof verification requires PAYER_BACKEND=lnd-cli with a distinct payer node."
  exit 1
fi
pass "Preimage hash matches payment_hash"

# ---------------------------------------------------------------------------
# Step 6: Access the protected endpoint with L402 credential — expect HTTP 200
# ---------------------------------------------------------------------------
info "Accessing protected endpoint with L402 credential (expect 200)"

RESPONSE=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

if [ "$HTTP_STATUS" = "200" ]; then
  pass "Got HTTP 200 OK"
else
  fail "Expected HTTP 200, got $HTTP_STATUS"
  echo "Response body: $BODY"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 7: Repeat with same credential (cache hit) — expect HTTP 200
# ---------------------------------------------------------------------------
info "Repeating request with same credential (cache hit, expect 200)"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

if [ "$HTTP_STATUS" = "200" ]; then
  pass "Cache hit — got HTTP 200 OK"
else
  fail "Expected HTTP 200 on cache hit, got $HTTP_STATUS"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
if [ "$FAILURES" -eq 0 ]; then
  printf -- "${GREEN}${BOLD}ALL CHECKS PASSED${RESET}\n"
  exit 0
else
  printf -- "${RED}${BOLD}${FAILURES} CHECK(S) FAILED${RESET}\n"
  exit 1
fi
