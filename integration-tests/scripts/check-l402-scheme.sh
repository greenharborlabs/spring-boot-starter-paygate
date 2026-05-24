#!/usr/bin/env bash
#
# check-l402-scheme.sh — Verify an L402/LSAT Authorization scheme returns the expected status.
#
# Usage from integration-tests/ after running proof-helper.sh:
#   scripts/check-l402-scheme.sh L402 "L402 baseline"
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CREDENTIAL_ENV="$PROJECT_DIR/.l402-credential.env"

SCHEME="${1:-}"
LABEL="${2:-$SCHEME}"
EXPECTED="${3:-200}"

if [ -z "$SCHEME" ]; then
  echo "FAIL: missing scheme argument."
  echo "Usage: scripts/check-l402-scheme.sh L402 \"L402 baseline\" [expected_status]"
  exit 1
fi

if [ -z "${MACAROON:-}" ] || [ -z "${PREIMAGE:-}" ] || [ -z "${PROTECTED_ENDPOINT:-}" ]; then
  if [ -f "$CREDENTIAL_ENV" ]; then
    # shellcheck disable=SC1090
    . "$CREDENTIAL_ENV"
  fi
fi

if [ -z "${MACAROON:-}" ] || [ -z "${PREIMAGE:-}" ] || [ -z "${PROTECTED_ENDPOINT:-}" ]; then
  echo "FAIL $LABEL: missing MACAROON, PREIMAGE, or PROTECTED_ENDPOINT."
  echo "Run these first:"
  echo "  APP_URL=\"http://localhost:\${APP_PORT:-18080}\""
  echo "  PROTECTED_ENDPOINT=\"\$APP_URL/api/v1/data\""
  echo "  export APP_URL PROTECTED_ENDPOINT"
  echo "  . scripts/proof-helper.sh"
  echo "  get_lnbits_lnd_l402_credential"
  echo "Expected credential cache: $CREDENTIAL_ENV"
  exit 1
fi

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: ${SCHEME} ${MACAROON}:${PREIMAGE}" \
  "$PROTECTED_ENDPOINT")

if [ "$HTTP_STATUS" = "$EXPECTED" ]; then
  echo "PASS $LABEL: HTTP $HTTP_STATUS"
else
  echo "FAIL $LABEL: expected HTTP $EXPECTED, got HTTP $HTTP_STATUS"
  exit 1
fi
