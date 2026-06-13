#!/usr/bin/env bash
#
# pay-lnd-payer-invoice.sh — Pay a BOLT11 invoice through the lnd-payer service.
#
# Prints shell assignments for PAYMENT_HASH and PREIMAGE on stdout so callers can
# import them with:
#   eval "$(COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/pay-lnd-payer-invoice.sh "$INVOICE")"
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
PAYER_LND_SERVICE="${PAYER_LND_SERVICE:-lnd-payer}"
INVOICE="${1:-${INVOICE:-}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

log() {
  printf '%s\n' "$*" >&2
}

compose_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T "$@"
}

if ! command -v jq > /dev/null 2>&1; then
  log "ERROR: jq is required by pay-lnd-payer-invoice.sh."
  exit 1
fi

if [ -z "$INVOICE" ]; then
  log "ERROR: INVOICE is empty. Pass the invoice as the first argument or set INVOICE."
  exit 1
fi

log "==> Invoice chars: ${#INVOICE}"

PAYMENT_HASH=$(compose_exec "$PAYER_LND_SERVICE" \
  lncli --network=regtest decodepayreq "$INVOICE" \
  | jq -r '.payment_hash // empty' \
  | tr '[:upper:]' '[:lower:]')

if [ -z "$PAYMENT_HASH" ] || [ "$PAYMENT_HASH" = "null" ]; then
  log "ERROR: Could not decode payment hash from invoice."
  exit 1
fi

log "==> Payment hash: $PAYMENT_HASH"
log "==> Paying invoice through ${PAYER_LND_SERVICE}..."

PAY_RESULT=""
if ! PAY_RESULT=$(compose_exec "$PAYER_LND_SERVICE" \
  lncli --network=regtest payinvoice --force "$INVOICE" 2>&1); then
  log "$PAY_RESULT"
  log "==> payinvoice returned non-zero; checking final status with trackpayment."
elif [ -n "$PAY_RESULT" ]; then
  log "$PAY_RESULT"
else
  log "==> payinvoice produced no output; checking final status with trackpayment."
fi

TRACK_RESULT=$(compose_exec "$PAYER_LND_SERVICE" \
  lncli --network=regtest trackpayment "$PAYMENT_HASH" 2>&1)

log "==> trackpayment output:"
log "$TRACK_RESULT"

PREIMAGE=$(printf '%s\n%s\n' "$PAY_RESULT" "$TRACK_RESULT" \
  | grep -ioE 'preimage:[[:space:]]*[0-9a-fA-F]{64}' \
  | tail -1 \
  | sed 's/.*:[[:space:]]*//' \
  | tr '[:upper:]' '[:lower:]')

if [ -z "$PREIMAGE" ]; then
  log "ERROR: Payment may be settled, but no payment preimage was returned."
  log "       Do not continue to the authenticated request; Paygate requires"
  log "       sha256(preimage) to match the challenge payment_hash."
  exit 1
fi

log "==> Payment proof captured."
printf 'PAYMENT_HASH=%s\n' "$PAYMENT_HASH"
printf 'PREIMAGE=%s\n' "$PREIMAGE"
