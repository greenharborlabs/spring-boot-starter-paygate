#!/usr/bin/env bash
#
# wait-for-app.sh — Wait for the local paygate example app health endpoint.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

cd "$PROJECT_DIR"

# shellcheck source=lib/docker.sh
. "$SCRIPT_DIR/lib/docker.sh"
require_docker_daemon

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a
  . "$ENV_FILE"
  set +a
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-${APP_URL}/api/v1/health}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-60}"
SLEEP_SECONDS="${SLEEP_SECONDS:-2}"

echo "==> Waiting for ${HEALTH_ENDPOINT} ..."
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  if curl -sf "$HEALTH_ENDPOINT" > /dev/null 2>&1; then
    echo "    App is ready."
    exit 0
  fi

  if [ "$attempt" -eq "$MAX_ATTEMPTS" ]; then
    echo "ERROR: App did not become ready at ${HEALTH_ENDPOINT} after ${MAX_ATTEMPTS} attempts."
    docker compose -f "$COMPOSE_FILE" ps || true
    docker compose -f "$COMPOSE_FILE" logs --no-log-prefix --tail=80 paygate-example-app || \
      docker compose -f "$COMPOSE_FILE" logs --tail=80 paygate-example-app || true
    exit 1
  fi

  sleep "$SLEEP_SECONDS"
done
