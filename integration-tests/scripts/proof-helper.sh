#!/usr/bin/env bash
#
# proof-helper.sh — Sourceable helpers for manual L402 proof walkthroughs.
#
# Usage from integration-tests/:
#   . scripts/proof-helper.sh
#   get_lnbits_lnd_l402_credential

proof_helper_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

get_lnbits_lnd_l402_credential() {
  local compose_file="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
  local app_url="${APP_URL:-http://localhost:${APP_PORT:-18080}}"
  local protected_endpoint="${PROTECTED_ENDPOINT:-${app_url}/api/v1/data}"
  local header_file body_402 http_status www_auth preimage_hash script_dir project_dir credential_env

  HEADER_FILE=$(mktemp)
  header_file="$HEADER_FILE"
  if ! BODY_402=$(curl -sS -D "$header_file" "$protected_endpoint"); then
    rm -f "$header_file"
    echo "Failed to call protected endpoint: $protected_endpoint"
    echo "Check that the example app is running:"
    echo "  COMPOSE_FILE=$compose_file bash scripts/wait-for-app.sh"
    return 1
  fi
  body_402="$BODY_402"
  HTTP_STATUS=$(tr -d '\r' < "$header_file" | grep -i "^HTTP/" | tail -1 | awk '{print $2}')
  http_status="$HTTP_STATUS"
  WWW_AUTH=$(tr -d '\r' < "$header_file" \
    | grep -i "^www-authenticate:[[:space:]]*L402 " \
    | sed 's/^[^:]*:[[:space:]]*//' \
    | head -1)
  www_auth="$WWW_AUTH"
  rm -f "$header_file"

  if [ "$http_status" != "402" ] || [ -z "$www_auth" ]; then
    echo "Failed to obtain L402 challenge from $protected_endpoint"
    echo "$body_402"
    return 1
  fi

  MACAROON=$(printf '%s' "$www_auth" | sed -n 's/.*macaroon="\([^"]*\)".*/\1/p')
  INVOICE=$(printf '%s' "$www_auth" | sed -n 's/.*invoice="\([^"]*\)".*/\1/p')

  if [ -z "$MACAROON" ] || [ -z "$INVOICE" ]; then
    echo "Failed to parse L402 macaroon or invoice from challenge."
    return 1
  fi

  eval "$(COMPOSE_FILE="$compose_file" bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"

  PREIMAGE_HASH=$(python3 - "$PREIMAGE" <<'PY'
import hashlib
import sys
print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())
PY
)
  preimage_hash="$PREIMAGE_HASH"

  if [ "$preimage_hash" != "$PAYMENT_HASH" ]; then
    echo "Payment proof mismatch: sha256(preimage)=$preimage_hash payment_hash=$PAYMENT_HASH"
    return 1
  fi

  PROTECTED_ENDPOINT="$protected_endpoint"
  APP_URL="$app_url"
  HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-${app_url}/api/v1/health}"
  export APP_URL PROTECTED_ENDPOINT HEALTH_ENDPOINT MACAROON INVOICE PAYMENT_HASH PREIMAGE

  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  project_dir="$(dirname "$script_dir")"
  credential_env="$project_dir/.l402-credential.env"
  {
    printf 'export APP_URL=%s\n' "$(proof_helper_quote "$APP_URL")"
    printf 'export PROTECTED_ENDPOINT=%s\n' "$(proof_helper_quote "$PROTECTED_ENDPOINT")"
    printf 'export HEALTH_ENDPOINT=%s\n' "$(proof_helper_quote "$HEALTH_ENDPOINT")"
    printf 'export MACAROON=%s\n' "$(proof_helper_quote "$MACAROON")"
    printf 'export INVOICE=%s\n' "$(proof_helper_quote "$INVOICE")"
    printf 'export PAYMENT_HASH=%s\n' "$(proof_helper_quote "$PAYMENT_HASH")"
    printf 'export PREIMAGE=%s\n' "$(proof_helper_quote "$PREIMAGE")"
  } > "$credential_env"
  chmod 600 "$credential_env"

  echo "Credential ready: macaroon=${#MACAROON} chars payment_hash=${PAYMENT_HASH:0:16}..."
  echo "Saved credential variables to $credential_env"
}
