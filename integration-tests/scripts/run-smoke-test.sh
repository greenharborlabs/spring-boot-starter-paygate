#!/usr/bin/env bash
#
# run-smoke-test.sh — Automated L402 smoke test using LNbits FakeWallet.
#
# Exercises the full 402 -> pay -> 200 flow:
#   1. Request a protected endpoint (expect 402)
#   2. Extract macaroon and invoice from WWW-Authenticate header
#   3. Pay the invoice via the LNbits API
#   4. Retrieve the preimage
#   5. Access the endpoint with the L402 credential (expect 200)
#
# Prerequisites: docker, docker compose, curl, jq
# The LNbits stack must already be running (see PLAYBOOK.md "Quick Smoke Test").
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

pass() { printf "${GREEN}PASS${RESET} %s\n" "$1"; }
fail() { printf "${RED}FAIL${RESET} %s\n" "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf "${BOLD}---> %s${RESET}\n" "$1"; }

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
APP_PORT="${APP_PORT:-8080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"
HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-${APP_URL}/api/v1/health}"
LNBITS_PORT="${LNBITS_PORT:-5000}"
LNBITS_URL="${LNBITS_URL:-http://localhost:${LNBITS_PORT}}"
LNBITS_KEY="${LNBITS_API_KEY:-}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"

# ---------------------------------------------------------------------------
# Step 0: Check prerequisites
# ---------------------------------------------------------------------------
info "Checking prerequisites"

MISSING=""
for cmd in docker curl jq; do
  if ! command -v "$cmd" > /dev/null 2>&1; then
    MISSING="$MISSING $cmd"
  fi
done

# Check "docker compose" (v2 plugin style)
if ! docker compose version > /dev/null 2>&1; then
  MISSING="$MISSING docker-compose-v2"
fi

if [ -n "$MISSING" ]; then
  fail "Missing required tools:${MISSING}"
  echo "Install the missing tools and try again."
  exit 1
fi
pass "All prerequisites found"

if [ -z "$LNBITS_KEY" ]; then
  fail "LNBITS_API_KEY is not set. Run 'bash scripts/setup-lnbits.sh' first."
  exit 1
fi
pass "LNBITS_API_KEY is set"

# ---------------------------------------------------------------------------
# Step 1: Wait for the example app to be healthy
# ---------------------------------------------------------------------------
info "Waiting for example app at ${HEALTH_ENDPOINT} (timeout: ${HEALTH_TIMEOUT}s)"

ELAPSED=0
until curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; do
  if [ "$ELAPSED" -ge "$HEALTH_TIMEOUT" ]; then
    fail "App did not become healthy within ${HEALTH_TIMEOUT}s"
    echo "Check container logs: docker compose -f docker-compose-lnbits.yml logs l402-example-app"
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
# Step 3: Extract macaroon and invoice from WWW-Authenticate header
# ---------------------------------------------------------------------------
info "Extracting macaroon and invoice from WWW-Authenticate header"

WWW_AUTH=$(curl -sI "$PROTECTED_ENDPOINT" | tr -d '\r' | grep -i "www-authenticate" | sed 's/^[^:]*: //')

MACAROON=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
INVOICE=$(printf '%s' "$WWW_AUTH" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

if [ -n "$MACAROON" ]; then
  pass "Macaroon extracted (${#MACAROON} chars)"
else
  fail "Could not extract macaroon from WWW-Authenticate header"
  echo "Header value: $WWW_AUTH"
  exit 1
fi

if [ -n "$INVOICE" ]; then
  pass "Invoice extracted (${#INVOICE} chars)"
else
  fail "Could not extract invoice from WWW-Authenticate header"
  echo "Header value: $WWW_AUTH"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 4: Pay the invoice via LNbits API
# ---------------------------------------------------------------------------
info "Paying invoice via LNbits at ${LNBITS_URL}"

PAY_RESULT=$(curl -s -X POST "${LNBITS_URL}/api/v1/payments" \
  -H "X-Api-Key: ${LNBITS_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"out\": true, \"bolt11\": \"${INVOICE}\"}")

PAYMENT_HASH=$(printf '%s' "$PAY_RESULT" | jq -r '.payment_hash // empty')

if [ -n "$PAYMENT_HASH" ]; then
  pass "Invoice paid (payment_hash: ${PAYMENT_HASH:0:16}...)"
else
  fail "Payment failed — could not extract payment_hash"
  echo "LNbits response: $PAY_RESULT"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 5: Retrieve the preimage from payment details
# ---------------------------------------------------------------------------
info "Retrieving preimage from LNbits"

PAYMENT_DETAILS=$(curl -s "${LNBITS_URL}/api/v1/payments/${PAYMENT_HASH}" \
  -H "X-Api-Key: ${LNBITS_KEY}")

PREIMAGE=$(printf '%s' "$PAYMENT_DETAILS" | jq -r '.preimage // .details.preimage // empty')

# FakeWallet may return preimage directly in the pay response
if [ -z "$PREIMAGE" ]; then
  PREIMAGE=$(printf '%s' "$PAY_RESULT" | jq -r '.preimage // .checking_id // empty')
fi

if [ -n "$PREIMAGE" ]; then
  pass "Preimage retrieved (${PREIMAGE:0:16}...)"
else
  fail "Could not retrieve preimage"
  echo "Payment details: $PAYMENT_DETAILS"
  exit 1
fi

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
  printf "${GREEN}${BOLD}ALL CHECKS PASSED${RESET}\n"
  exit 0
else
  printf "${RED}${BOLD}${FAILURES} CHECK(S) FAILED${RESET}\n"
  exit 1
fi
