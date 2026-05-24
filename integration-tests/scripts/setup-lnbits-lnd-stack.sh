#!/usr/bin/env bash
#
# setup-lnbits-lnd-stack.sh — Start the full LNbits-over-LND integration stack.
#
# This is the one-command setup path for manual flows that need a real payment
# preimage: bitcoind, payee LND, payer LND, LNbits, and paygate-example-app.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
BUILD_APP="${BUILD_APP:-true}"
PAYGATE_DEFAULT_TIMEOUT_SECONDS="${PAYGATE_DEFAULT_TIMEOUT_SECONDS:-3600}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# shellcheck source=lib/docker.sh
. "$SCRIPT_DIR/lib/docker.sh"
require_docker_daemon

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
