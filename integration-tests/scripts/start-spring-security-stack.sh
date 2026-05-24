#!/usr/bin/env bash
#
# start-spring-security-stack.sh — Start the Spring Security example integration stack.
#
# This is the one-command setup path for the Spring Security playbook scenario:
# clean LNbits-over-LND regtest state, open a payer/payee LND channel, create
# the LNbits API key, then run paygate-example-app-spring-security locally.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
RESET_STACK="${RESET_STACK:-true}"
SPRING_SECURITY_APP_PORT="${SPRING_SECURITY_APP_PORT:-8081}"
PAYGATE_DEFAULT_TIMEOUT_SECONDS="${PAYGATE_DEFAULT_TIMEOUT_SECONDS:-3600}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$PROJECT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

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

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: Expected $ENV_FILE to exist after setup-lnbits.sh."
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

if [ -z "${LNBITS_API_KEY:-}" ]; then
  echo "ERROR: LNBITS_API_KEY is empty after sourcing $ENV_FILE."
  exit 1
fi

echo ""
echo "==> Starting paygate-example-app-spring-security on http://localhost:${SPRING_SECURITY_APP_PORT}"
echo "    Leave this process running while you execute the remaining playbook steps."

cd "$REPO_ROOT"
SPRING_PROFILES_ACTIVE=dev \
PAYGATE_TEST_MODE=false \
PAYGATE_BACKEND=lnbits \
PAYGATE_ROOT_KEY_STORE=memory \
PAYGATE_DEFAULT_TIMEOUT_SECONDS="$PAYGATE_DEFAULT_TIMEOUT_SECONDS" \
PAYGATE_LNBITS_URL="http://localhost:${LNBITS_PORT:-15000}" \
PAYGATE_LNBITS_API_KEY="$LNBITS_API_KEY" \
SERVER_PORT="$SPRING_SECURITY_APP_PORT" \
exec ./gradlew :paygate-example-app-spring-security:bootRun
