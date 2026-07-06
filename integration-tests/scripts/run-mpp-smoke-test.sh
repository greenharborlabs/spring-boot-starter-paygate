#!/usr/bin/env bash
#
# run-mpp-smoke-test.sh — Automated MPP (Modern Payment Protocol) smoke test
# for the Paygate starter.
#
# Works in two modes:
#   1. Docker Compose local testing — run against the two-node LNbits-over-LND
#      stack with PAYER_BACKEND=lnd-cli or PAYER_BACKEND=breez-spark for full proof verification
#   2. Live endpoint testing — point APP_URL and payer env vars at a running
#      instance (e.g., a testnet deployment with SPRING_PROFILES_ACTIVE=lnbits-testnet)
#
# Exercises the full 402 -> extract Payment challenge -> pay invoice ->
# build MPP credential -> retry -> assert 200 + Payment-Receipt flow:
#   1. Request a protected endpoint (expect 402)
#   2. Assert WWW-Authenticate contains a Payment scheme challenge
#   3. Parse the challenge object from protocols.Payment in the 402 JSON body
#   4. Assert the challenge contains a non-empty digest field
#   5. Enforce a spend cap (MAX_INVOICE_SATS) before paying
#   6. Decode the request field and extract the BOLT11 invoice
#   7. Pay the invoice via the configured payer backend
#   8. Retrieve the preimage
#   9. Build the MPP credential and retry the endpoint (expect 200)
#  10. Validate the Payment-Receipt response header
#
# Prerequisites: curl, jq, base64, python3
# When dual-protocol headers are present (L402 + MPP), this script selects
# the Payment challenge. Use run-smoke-test.sh for L402 testing.
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
# Portable base64url helpers
# ---------------------------------------------------------------------------
# Detect which base64 decode flag works (GNU: -d, BSD/macOS: -D)
B64_DECODE_FLAG=""
_detect_base64_flag() {
  if printf 'dGVzdA==' | base64 -d >/dev/null 2>&1; then
    B64_DECODE_FLAG="-d"
  elif printf 'dGVzdA==' | base64 -D >/dev/null 2>&1; then
    B64_DECODE_FLAG="-D"
  else
    echo "FATAL: Neither 'base64 -d' nor 'base64 -D' works on this system" >&2
    exit 1
  fi
}
_detect_base64_flag

# Decode base64url (no-pad) to raw bytes on stdout
b64url_decode() {
  local input="$1"
  # Translate base64url characters to standard base64 (hyphen last for macOS tr)
  local std
  std=$(printf '%s' "$input" | tr '_-' '/+')
  # Re-add padding
  local mod=$((${#std} % 4))
  if [ "$mod" -eq 2 ]; then
    std="${std}=="
  elif [ "$mod" -eq 3 ]; then
    std="${std}="
  fi
  printf '%s' "$std" | base64 "$B64_DECODE_FLAG"
}

# Encode raw bytes (stdin) to base64url (no-pad) on stdout
b64url_encode() {
  base64 | tr -d '\n' | tr '+/' '-_' | tr -d '='
}

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
for cmd in curl jq base64 python3; do
  if ! command -v "$cmd" > /dev/null 2>&1; then
    MISSING="$MISSING $cmd"
  fi
done
if [ "$PAYER_BACKEND" = "lnd-cli" ] && ! command -v docker > /dev/null 2>&1; then
  MISSING="$MISSING docker"
fi
if [ "$PAYER_BACKEND" = "breez-spark" ] && ! command -v python3 > /dev/null 2>&1; then
  MISSING="$MISSING python3"
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
if [ "$PAYER_BACKEND" = "breez-spark" ]; then
  if [ -z "${BREEZ_API_KEY:-}" ] || [ -z "${BREEZ_MNEMONIC:-}" ]; then
    fail "BREEZ_API_KEY and BREEZ_MNEMONIC are required for PAYER_BACKEND=breez-spark."
    exit 1
  fi
  pass "Breez Spark credentials are set"
fi

case "$PAYER_BACKEND" in
  lnbits|lnd-cli|breez-spark)
    pass "PAYER_BACKEND=${PAYER_BACKEND}"
    ;;
  *)
    fail "Unsupported PAYER_BACKEND=${PAYER_BACKEND}. Expected 'lnbits', 'lnd-cli', or 'breez-spark'."
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

HEADER_FILE=$(mktemp)
BODY_402=$(curl -s -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")
HTTP_STATUS=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')

if [ "$HTTP_STATUS" = "402" ]; then
  pass "Got HTTP 402 Payment Required"
else
  rm -f "$HEADER_FILE"
  fail "Expected HTTP 402, got $HTTP_STATUS"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Assert WWW-Authenticate contains a Payment scheme challenge
# ---------------------------------------------------------------------------
info "Checking for Payment scheme in WWW-Authenticate headers"

ALL_WWW_AUTH=$(tr -d '\r' < "$HEADER_FILE" | grep -i "^www-authenticate:" | sed 's/^[^:]*: //')
rm -f "$HEADER_FILE"

if [ -z "$ALL_WWW_AUTH" ]; then
  fail "No WWW-Authenticate header found in 402 response"
  exit 1
fi

# Select the header value starting with Payment (may coexist with L402)
PAYMENT_WWW_AUTH=$(printf '%s\n' "$ALL_WWW_AUTH" | grep "^Payment " | head -1)

if [ -z "$PAYMENT_WWW_AUTH" ]; then
  fail "No MPP challenge found in WWW-Authenticate headers"
  echo "Headers present: $ALL_WWW_AUTH"
  exit 1
fi
pass "Payment scheme found in WWW-Authenticate headers"

# ---------------------------------------------------------------------------
# Step 4: Parse the challenge object from protocols.Payment in 402 JSON body
# ---------------------------------------------------------------------------
info "Extracting Payment challenge from 402 response body"

CHALLENGE_JSON=$(printf '%s' "$BODY_402" | jq -c '.protocols.Payment // empty' 2>/dev/null)

if [ -z "$CHALLENGE_JSON" ]; then
  fail "No Payment protocol object in 402 response body"
  echo "Response body: $BODY_402"
  exit 1
fi
pass "Payment challenge extracted from response body"

# ---------------------------------------------------------------------------
# Step 4b: Assert the challenge contains a non-empty digest field
# ---------------------------------------------------------------------------
info "Checking for required digest field in challenge"

DIGEST=$(printf '%s' "$CHALLENGE_JSON" | jq -r '.digest // empty' 2>/dev/null)

if [ -z "$DIGEST" ]; then
  fail "MPP challenge missing required digest field — aborting before payment"
  exit 1
fi
pass "Challenge contains digest field"

# ---------------------------------------------------------------------------
# Step 4c: Enforce spend cap before paying
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
# Step 5: Decode the request field and extract the BOLT11 invoice
# ---------------------------------------------------------------------------
info "Decoding request field from challenge"

REQUEST_B64URL=$(printf '%s' "$CHALLENGE_JSON" | jq -r '.request // empty' 2>/dev/null)

if [ -z "$REQUEST_B64URL" ]; then
  fail "No request field in MPP challenge"
  exit 1
fi

REQUEST_JSON=$(b64url_decode "$REQUEST_B64URL" 2>/dev/null) || true

if [ -z "$REQUEST_JSON" ]; then
  fail "Invalid request field encoding"
  exit 1
fi
pass "Request field decoded"

INVOICE=$(printf '%s' "$REQUEST_JSON" | jq -r '.methodDetails.invoice // empty' 2>/dev/null)

if [ -z "$INVOICE" ]; then
  fail "No invoice in MPP challenge"
  echo "Decoded request: $REQUEST_JSON"
  exit 1
fi
pass "Invoice extracted (${#INVOICE} chars)"

# ---------------------------------------------------------------------------
# Step 6: Pay the invoice via payer backend
# ---------------------------------------------------------------------------
PREIMAGE=""
PAYMENT_DETAILS=""

PAYMENT_START_MS=$(($(date +%s) * 1000))

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
elif [ "$PAYER_BACKEND" = "breez-spark" ]; then
  info "Paying invoice via Breez SDK Spark"
  PAY_RESULT=$(bash "$SCRIPT_DIR/pay-breez-spark-invoice.sh" \
    "$INVOICE" \
    "${BREEZ_MAX_FEE_SATS:-$MAX_INVOICE_SATS}" \
    "${BREEZ_COMPLETION_TIMEOUT_SECONDS:-30}")
  PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | sed -n 's/^PAYMENT_HASH=//p' | tail -1)
  PREIMAGE=$(printf '%s' "$PAY_RESULT" | sed -n 's/^PREIMAGE=//p' | tail -1)
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

if [ -z "$PREIMAGE" ]; then
  if [ "$PAYER_BACKEND" != "lnbits" ]; then
    fail "Could not retrieve preimage from ${PAYER_BACKEND} payment response"
    echo "Payment response: $PAY_RESULT"
    exit 1
  fi

  # ---------------------------------------------------------------------------
  # Step 7: Retrieve the preimage from payment details
  # ---------------------------------------------------------------------------
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
  fail "Payment settled, but the payer API did not return a payment preimage; cannot construct an MPP credential. Spark-backed LNbits, local LNbits self-payments, and FakeWallet may omit or hide usable preimages."
  echo "Payment details: $PAYMENT_DETAILS"
  exit 1
fi

# Normalize preimage to lowercase hex, exactly 64 characters
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

PAYMENT_END_MS=$(($(date +%s) * 1000))
SETTLEMENT_MS=$((PAYMENT_END_MS - PAYMENT_START_MS))

# ---------------------------------------------------------------------------
# Step 8: Build MPP credential and retry with GET — expect HTTP 200
# ---------------------------------------------------------------------------
info "Building MPP credential and accessing protected endpoint (expect 200)"

# Build credential JSON: {"challenge":<verbatim>,"payload":{"preimage":"<hex>"}}
CREDENTIAL_JSON=$(printf '%s' "$CHALLENGE_JSON" | jq -c \
  --arg preimage "$PREIMAGE" \
  '{"challenge": ., "payload": {"preimage": $preimage}}')

# Base64url encode (no padding, no line wrapping)
CREDENTIAL_B64URL=$(printf '%s' "$CREDENTIAL_JSON" | b64url_encode)

RESPONSE_HEADER_FILE=$(mktemp)
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -D "$RESPONSE_HEADER_FILE" \
  -H "Authorization: Payment ${CREDENTIAL_B64URL}" \
  "$PROTECTED_ENDPOINT")

HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

if [ "$HTTP_STATUS" = "200" ]; then
  pass "Got HTTP 200 OK"
else
  rm -f "$RESPONSE_HEADER_FILE"
  fail "Expected HTTP 200, got $HTTP_STATUS"
  echo "Response body: $BODY"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 9: Validate Payment-Receipt response header
# ---------------------------------------------------------------------------
info "Validating Payment-Receipt response header"

RECEIPT_HEADER=$(tr -d '\r' < "$RESPONSE_HEADER_FILE" | grep -i "^payment-receipt:" | sed 's/^[^:]*: //' | head -1)
rm -f "$RESPONSE_HEADER_FILE"

if [ -z "$RECEIPT_HEADER" ]; then
  fail "No Payment-Receipt header in response"
  exit 1
fi
pass "Payment-Receipt header present"

# Base64url-decode and parse as JSON
RECEIPT_JSON=$(b64url_decode "$RECEIPT_HEADER" 2>/dev/null) || true

if [ -z "$RECEIPT_JSON" ]; then
  fail "Payment-Receipt header is not parseable"
  exit 1
fi

# Validate it is valid JSON
if ! printf '%s' "$RECEIPT_JSON" | jq . >/dev/null 2>&1; then
  fail "Payment-Receipt header is not parseable"
  exit 1
fi
pass "Payment-Receipt header decoded as valid JSON"

# Verify required wire-format fields
RECEIPT_REQUIRED_FIELDS="status challenge_id method amount_sats timestamp protocol_scheme"
for field in $RECEIPT_REQUIRED_FIELDS; do
  FIELD_VALUE=$(printf '%s' "$RECEIPT_JSON" | jq -r ".$field // empty" 2>/dev/null)
  if [ -z "$FIELD_VALUE" ]; then
    fail "Payment-Receipt missing required field: $field"
    echo "Receipt JSON: $RECEIPT_JSON"
    exit 1
  fi
done
pass "Payment-Receipt contains all required wire-format fields"

# Assert status == "success" and protocol_scheme == "Payment"
RECEIPT_STATUS=$(printf '%s' "$RECEIPT_JSON" | jq -r '.status')
RECEIPT_SCHEME=$(printf '%s' "$RECEIPT_JSON" | jq -r '.protocol_scheme')

if [ "$RECEIPT_STATUS" = "success" ]; then
  pass "Payment-Receipt status is 'success'"
else
  fail "Payment-Receipt status is '$RECEIPT_STATUS', expected 'success'"
  exit 1
fi

if [ "$RECEIPT_SCHEME" = "Payment" ]; then
  pass "Payment-Receipt protocol_scheme is 'Payment'"
else
  fail "Payment-Receipt protocol_scheme is '$RECEIPT_SCHEME', expected 'Payment'"
  exit 1
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
if [ "$FAILURES" -eq 0 ]; then
  printf -- "${GREEN}${BOLD}ALL CHECKS PASSED${RESET}\n"
  # Machine-readable JSON summary to stdout
  printf '{"protocol":"mpp","endpoint":"%s","payment_hash":"%s","settlement_ms":%d,"status":200,"receipt_present":true}\n' \
    "$PROTECTED_ENDPOINT" "$PAYMENT_HASH" "$SETTLEMENT_MS"
  exit 0
else
  printf -- "${RED}${BOLD}${FAILURES} CHECK(S) FAILED${RESET}\n"
  exit 1
fi
