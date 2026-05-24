#!/usr/bin/env bash
#
# run-go-interop-test.sh - Start the local stack and verify Java macaroons with Go.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
RESET_STACK="${RESET_STACK:-true}"
BUILD_APP="${BUILD_APP:-true}"
GO_INTEROP_DIR="${GO_INTEROP_DIR:-/tmp/paygate-go-interop}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "==> Building Go interop helper..."
GO_INTEROP_DIR="$GO_INTEROP_DIR" bash scripts/setup-go-interop.sh

echo "==> Starting full LNbits-over-LND stack..."
COMPOSE_FILE="$COMPOSE_FILE" \
  RESET_STACK="$RESET_STACK" \
  BUILD_APP="$BUILD_APP" \
  bash scripts/setup-lnbits-lnd-stack.sh

if [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_DIR/.env"
  set +a
fi

APP_PORT="${APP_PORT:-18080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
PROTECTED_ENDPOINT="${PROTECTED_ENDPOINT:-${APP_URL}/api/v1/data}"
HEADER_FILE="$(mktemp)"
trap 'rm -f "$HEADER_FILE"' EXIT

echo "==> Requesting L402 challenge from $PROTECTED_ENDPOINT ..."
BODY_402="$(curl -sS -D "$HEADER_FILE" "$PROTECTED_ENDPOINT")"
HTTP_STATUS="$(tr -d '\r' < "$HEADER_FILE" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')"
WWW_AUTH="$(tr -d '\r' < "$HEADER_FILE" \
  | grep -i "^www-authenticate:[[:space:]]*L402 " \
  | sed 's/^[^:]*:[[:space:]]*//' \
  | head -1)"

if [ "$HTTP_STATUS" != "402" ] || [ -z "$WWW_AUTH" ]; then
  echo "FAIL: expected a 402 response with an L402 WWW-Authenticate header."
  echo "HTTP status: ${HTTP_STATUS:-unknown}"
  echo "$BODY_402"
  exit 1
fi

MACAROON="$(printf '%s' "$WWW_AUTH" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')"
if [ -z "$MACAROON" ]; then
  echo "FAIL: could not parse macaroon from L402 challenge."
  echo "$WWW_AUTH"
  exit 1
fi

echo "==> Verifying Java macaroon with Go..."
"$GO_INTEROP_DIR/paygate-go-interop" verify "$MACAROON"

echo "PASS Go interop: Java macaroon deserialized successfully"
