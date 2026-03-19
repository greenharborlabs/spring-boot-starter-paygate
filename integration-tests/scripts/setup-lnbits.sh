#!/usr/bin/env bash
#
# setup-lnbits.sh — Bootstrap the LNbits FakeWallet environment.
#
# Waits for LNbits to become ready, creates a wallet via the API, and prints
# the admin API key. Optionally writes it to the .env file so the example app
# can pick it up on next restart.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits.yml}"
LNBITS_PORT="${LNBITS_PORT:-5000}"
LNBITS_URL="http://localhost:${LNBITS_PORT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

cd "$PROJECT_DIR"

echo "==> Waiting for LNbits to be healthy at $LNBITS_URL ..."
MAX_ATTEMPTS=60
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

echo "==> Creating a new wallet..."
RESPONSE=$(curl -sf -X POST "${LNBITS_URL}/api/v1/wallet" \
  -H "Content-Type: application/json" \
  -d '{"name": "l402-test-wallet"}')

if [ -z "$RESPONSE" ]; then
  echo "ERROR: Failed to create wallet. LNbits may require a super-user key."
  echo "       Check the LNbits admin UI at ${LNBITS_URL} to create a wallet manually."
  echo "       Then set LNBITS_API_KEY in your .env file."
  exit 1
fi

# Extract the admin key from the response
ADMIN_KEY=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('adminkey',''))" 2>/dev/null || true)

if [ -z "$ADMIN_KEY" ]; then
  echo "WARNING: Could not parse adminkey from response."
  echo "         Raw response: $RESPONSE"
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
echo "      docker compose -f $COMPOSE_FILE up -d l402-example-app"
