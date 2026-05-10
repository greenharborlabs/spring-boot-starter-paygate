#!/usr/bin/env bash
#
# setup-lnbits.sh — Bootstrap the LNbits local Docker environment.
#
# Waits for LNbits to become ready, initializes first install when needed,
# creates a wallet via the API, and writes the admin API key to .env so the
# example app can pick it up on next restart.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

cd "$PROJECT_DIR"

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a
  . "$ENV_FILE"
  set +a
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits.yml}"
COMPOSE_WAIT_TIMEOUT_SECONDS="${COMPOSE_WAIT_TIMEOUT_SECONDS:-300}"
LNBITS_PORT="${LNBITS_PORT:-15000}"
LNBITS_URL="${LNBITS_URL:-http://localhost:${LNBITS_PORT}}"
LNBITS_SETUP_USERNAME="${LNBITS_SETUP_USERNAME:-paygate-admin}"
LNBITS_SETUP_PASSWORD="${LNBITS_SETUP_PASSWORD:-paygate-test-password}"

echo "==> Ensuring LNbits service is started..."
if docker compose up --help 2>/dev/null | grep -q -- "--wait"; then
  docker compose -f "$COMPOSE_FILE" up -d --wait \
    --wait-timeout "$COMPOSE_WAIT_TIMEOUT_SECONDS" \
    lnbits
else
  docker compose -f "$COMPOSE_FILE" up -d lnbits
  echo "    Docker Compose does not support --wait; falling back to HTTP health checks."
fi

echo "==> Waiting for LNbits to be healthy at $LNBITS_URL ..."
MAX_ATTEMPTS="${MAX_ATTEMPTS:-120}"
ATTEMPT=0
until curl -sf "${LNBITS_URL}/api/v1/health" > /dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "ERROR: LNbits did not become healthy after ${MAX_ATTEMPTS} attempts."
    echo "       Check container logs: docker compose -f $COMPOSE_FILE logs lnbits"
    exit 1
  fi
  sleep 2
done
echo "    LNbits is ready."

echo "==> Checking LNbits first-install state..."
FIRST_INSTALL_RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "${LNBITS_URL}/api/v1/auth/first_install" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${LNBITS_SETUP_USERNAME}\",\"password\":\"${LNBITS_SETUP_PASSWORD}\",\"password_repeat\":\"${LNBITS_SETUP_PASSWORD}\"}")
FIRST_INSTALL_HTTP_STATUS=$(printf '%s' "$FIRST_INSTALL_RESPONSE" | tail -1)
FIRST_INSTALL_BODY=$(printf '%s' "$FIRST_INSTALL_RESPONSE" | sed '$d')

if [ "$FIRST_INSTALL_HTTP_STATUS" = "200" ]; then
  echo "    LNbits first install initialized."
elif [ "$FIRST_INSTALL_HTTP_STATUS" = "401" ]; then
  echo "    LNbits first install already completed."
elif [ "$FIRST_INSTALL_HTTP_STATUS" = "404" ] || [ "$FIRST_INSTALL_HTTP_STATUS" = "405" ]; then
  echo "    LNbits first-install endpoint unavailable; continuing."
else
  echo "ERROR: Failed to initialize LNbits first install."
  echo "       HTTP $FIRST_INSTALL_HTTP_STATUS: $FIRST_INSTALL_BODY"
  exit 1
fi

echo "==> Logging in to LNbits..."
AUTH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${LNBITS_URL}/api/v1/auth" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${LNBITS_SETUP_USERNAME}\",\"password\":\"${LNBITS_SETUP_PASSWORD}\"}")
AUTH_HTTP_STATUS=$(printf '%s' "$AUTH_RESPONSE" | tail -1)
AUTH_BODY=$(printf '%s' "$AUTH_RESPONSE" | sed '$d')
ACCESS_TOKEN=$(printf '%s' "$AUTH_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null || true)

if [ "$AUTH_HTTP_STATUS" = "200" ] && [ -n "$ACCESS_TOKEN" ]; then
  echo "    LNbits login succeeded."
else
  echo "    LNbits login skipped or unavailable; falling back to wallet creation endpoint."
fi

echo "==> Creating a new wallet..."
if [ -n "${ACCESS_TOKEN:-}" ]; then
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${LNBITS_URL}/api/v1/account" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "paygate-test-wallet"}')
else
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${LNBITS_URL}/api/v1/wallet" \
    -H "Content-Type: application/json" \
    -d '{"name": "paygate-test-wallet"}')
fi
WALLET_HTTP_STATUS=$(printf '%s' "$RESPONSE" | tail -1)
WALLET_BODY=$(printf '%s' "$RESPONSE" | sed '$d')

if [ "$WALLET_HTTP_STATUS" != "200" ] && [ "$WALLET_HTTP_STATUS" != "201" ]; then
  echo "ERROR: Failed to create wallet."
  echo "       HTTP $WALLET_HTTP_STATUS: $WALLET_BODY"
  echo "       Check the LNbits admin UI at ${LNBITS_URL} to create a wallet manually."
  echo "       Then set LNBITS_API_KEY in your .env file."
  exit 1
fi

# Extract the admin key from the response
ADMIN_KEY=$(echo "$WALLET_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('adminkey',''))" 2>/dev/null || true)

if [ -z "$ADMIN_KEY" ]; then
  echo "WARNING: Could not parse adminkey from response."
  echo "         Raw response: $WALLET_BODY"
  echo ""
  echo "         You may need to create a wallet manually via the LNbits UI at ${LNBITS_URL}"
  echo "         and copy the Admin API key into your .env file as LNBITS_API_KEY=<key>."
  exit 1
fi

echo "    Wallet created successfully."
echo ""
echo "==> Admin API Key: $ADMIN_KEY"
echo ""

# Append or update the key in .env
if grep -q "^LNBITS_API_KEY=" "$ENV_FILE" 2>/dev/null; then
  # macOS-compatible sed (no -i'' trick needed with explicit backup)
  sed -i.bak "s/^LNBITS_API_KEY=.*/LNBITS_API_KEY=${ADMIN_KEY}/" "$ENV_FILE"
  rm -f "${ENV_FILE}.bak"
  echo "    Updated LNBITS_API_KEY in $ENV_FILE"
else
  echo "LNBITS_API_KEY=${ADMIN_KEY}" >> "$ENV_FILE"
  echo "    Wrote LNBITS_API_KEY to $ENV_FILE"
fi

echo ""
echo "==> Setup complete."
echo "    Restart the example app to pick up the new API key:"
echo "      COMPOSE_FILE=$COMPOSE_FILE bash scripts/start-example-app.sh"
