#!/usr/bin/env bash
#
# start-example-app.sh — Start the example app for the selected Compose stack.
#
# The integration playbook uses multiple Compose files with the same project and
# service name. Remove any existing paygate-example-app container first so a
# stale container from another stack cannot keep old PAYGATE_BACKEND settings.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
WAIT_FOR_APP="${WAIT_FOR_APP:-true}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "==> Removing any stale paygate-example-app container..."
PROJECT_NAME="$(basename "$PROJECT_DIR")"
STALE_CONTAINERS=$(docker ps -aq \
  --filter "label=com.docker.compose.project=$PROJECT_NAME" \
  --filter "label=com.docker.compose.service=paygate-example-app" || true)
if [ -n "$STALE_CONTAINERS" ]; then
  docker rm -f $STALE_CONTAINERS > /dev/null 2>&1 || true
fi
docker compose -f "$COMPOSE_FILE" rm -sf paygate-example-app > /dev/null 2>&1 || true

echo "==> Starting paygate-example-app with $COMPOSE_FILE ..."
docker compose -f "$COMPOSE_FILE" up -d --force-recreate paygate-example-app

if [ "$WAIT_FOR_APP" = "true" ]; then
  COMPOSE_FILE="$COMPOSE_FILE" bash scripts/wait-for-app.sh
fi
