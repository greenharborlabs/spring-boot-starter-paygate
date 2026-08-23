#!/usr/bin/env bash
#
# setup-lnbits.sh — Bootstrap the LNbits local Docker environment.
#
# Waits for LNbits to become ready, initializes first install when needed,
# creates a wallet via the API, and writes the admin API key to .env so the
# example app can pick it up on next restart. The first-install password is a
# generated, local-only secret and is never printed.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="${INTEGRATION_ENV_FILE:-$PROJECT_DIR/.env}"
LNBITS_SETUP_SECRET_FILE="${LNBITS_SETUP_SECRET_FILE:-$PROJECT_DIR/.lnbits-setup-secret.json}"

cd "$PROJECT_DIR"

# shellcheck source=lib/docker.sh
. "$SCRIPT_DIR/lib/docker.sh"
. "$SCRIPT_DIR/lib/lnbits-setup-password.sh"
require_docker_daemon

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a
  . "$ENV_FILE"
  set +a
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits.yml}"
COMPOSE_WAIT_TIMEOUT_SECONDS="${COMPOSE_WAIT_TIMEOUT_SECONDS:-300}"
LNBITS_PORT="${LNBITS_PORT:-15000}"
# Provision only through the host loopback mapping. The example container receives the resulting
# disposable key through .env; it never provisions through a Compose service hostname.
LNBITS_URL="http://localhost:${LNBITS_PORT}"
LNBITS_SETUP_USERNAME="${LNBITS_SETUP_USERNAME:-paygate-admin}"
resolve_lnbits_setup_password
LNBITS_SETUP_PASSWORD="$LNBITS_RESOLVED_SETUP_PASSWORD"
unset LNBITS_RESOLVED_SETUP_PASSWORD
umask 077
touch "$ENV_FILE"
chmod 600 "$ENV_FILE"

json_request_body() {
  LNBITS_REQUEST_USERNAME="$LNBITS_SETUP_USERNAME" LNBITS_REQUEST_PASSWORD="$LNBITS_SETUP_PASSWORD" \
    python3 -c '
import json
import os
print(json.dumps({
    "username": os.environ["LNBITS_REQUEST_USERNAME"],
    "password": os.environ["LNBITS_REQUEST_PASSWORD"],
    "password_repeat": os.environ["LNBITS_REQUEST_PASSWORD"],
}, separators=(",", ":")))
'
}

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
  -d "$(json_request_body)")
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
  echo "       HTTP $FIRST_INSTALL_HTTP_STATUS"
  exit 1
fi

echo "==> Logging in to LNbits..."
AUTH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${LNBITS_URL}/api/v1/auth" \
  -H "Content-Type: application/json" \
  -d "$(json_request_body)")
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
  echo "       HTTP $WALLET_HTTP_STATUS"
  echo "       Check the local LNbits admin UI to create a wallet manually."
  echo "       For an existing volume without .lnbits-setup-secret.json, reset the disposable stack"
  echo "       or provide its existing password through LNBITS_SETUP_PASSWORD."
  echo "       Then set LNBITS_API_KEY in your ignored .env file."
  exit 1
fi

# Extract the admin key from the response
ADMIN_KEY=$(echo "$WALLET_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('adminkey',''))" 2>/dev/null || true)

if [ -z "$ADMIN_KEY" ]; then
  echo "WARNING: Could not parse an admin key from the LNbits response."
  echo ""
  echo "         You may need to create a wallet manually via the LNbits UI at ${LNBITS_URL}"
  echo "         and copy the Admin API key into your .env file as LNBITS_API_KEY=<key>."
  exit 1
fi

echo "    Wallet created successfully; writing disposable key to ignored .env."

# Append or update the key in .env
if grep -q "^LNBITS_API_KEY=" "$ENV_FILE" 2>/dev/null; then
  # macOS-compatible sed (no -i'' trick needed with explicit backup)
  sed -i.bak "s/^LNBITS_API_KEY=.*/LNBITS_API_KEY=${ADMIN_KEY}/" "$ENV_FILE"
  rm -f "${ENV_FILE}.bak"
  echo "    Updated LNBITS_API_KEY in ignored local state."
else
  echo "LNBITS_API_KEY=${ADMIN_KEY}" >> "$ENV_FILE"
  echo "    Wrote LNBITS_API_KEY to ignored local state."
fi

echo ""
echo "==> Setup complete."
echo "    Restart the example app to pick up the new API key:"
echo "      COMPOSE_FILE=$COMPOSE_FILE bash scripts/start-example-app.sh"
