#!/usr/bin/env bash
#
# setup-lnbits-lnd-stack.sh — Start the full LNbits-over-LND integration stack.
#
# This is the one-command setup path for manual flows that need a real payment
# preimage: bitcoind, lnd-payee, lnd-payer, LNbits, and paygate-example-app.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
RESET_STACK="${RESET_STACK:-true}"
BUILD_APP="${BUILD_APP:-true}"
GENERATE_CREDENTIAL="${GENERATE_CREDENTIAL:-false}"
PAYGATE_DEFAULT_TIMEOUT_SECONDS="${PAYGATE_DEFAULT_TIMEOUT_SECONDS:-3600}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# shellcheck source=lib/docker.sh
. "$SCRIPT_DIR/lib/docker.sh"
require_docker_daemon

if [ "$RESET_STACK" = "true" ]; then
  echo "==> Resetting $COMPOSE_FILE state..."
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
fi

echo "==> Setting up two-node LND channel stack..."
COMPOSE_FILE="$COMPOSE_FILE" bash scripts/setup-lnd-channel.sh

echo "==> Setting up LNbits wallet and API key..."
COMPOSE_FILE="$COMPOSE_FILE" bash scripts/setup-lnbits.sh

if [ "$BUILD_APP" = "true" ]; then
  echo "==> Building paygate-example-app image..."
  docker compose -f "$COMPOSE_FILE" build paygate-example-app
fi

echo "==> Starting paygate-example-app..."
PAYGATE_DEFAULT_TIMEOUT_SECONDS="$PAYGATE_DEFAULT_TIMEOUT_SECONDS" \
  COMPOSE_FILE="$COMPOSE_FILE" bash scripts/start-example-app.sh

echo ""
echo "==> Full LNbits-over-LND stack is ready."
echo "    App URL: http://localhost:${APP_PORT:-18080}"
echo "    LNbits URL: http://localhost:${LNBITS_PORT:-15000}"
echo "    Set RESET_STACK=false to reuse an existing clean stack on the next run."

if [ "$GENERATE_CREDENTIAL" = "true" ]; then
  echo ""
  echo "==> Generating an L402 credential for manual checks..."
  APP_URL="http://localhost:${APP_PORT:-18080}"
  PROTECTED_ENDPOINT="$APP_URL/api/v1/data"
  HEALTH_ENDPOINT="$APP_URL/api/v1/health"
  export APP_URL PROTECTED_ENDPOINT HEALTH_ENDPOINT

  # shellcheck source=proof-helper.sh
  . "$SCRIPT_DIR/proof-helper.sh"
  get_lnbits_lnd_l402_credential
fi
