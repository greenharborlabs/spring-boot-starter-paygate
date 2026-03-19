#!/usr/bin/env bash
#
# setup-lnd.sh — Bootstrap the LND regtest environment.
#
# Waits for bitcoind and LND to become ready, then mines 101 blocks so the
# coinbase outputs are spendable. Prints the admin macaroon path inside the
# lnd container for reference.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnd.yml}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

MAX_ATTEMPTS=60

echo "==> Waiting for bitcoind to be healthy..."
ATTEMPT=0
until docker compose -f "$COMPOSE_FILE" exec -T bitcoind \
  bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass getblockchaininfo > /dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "ERROR: bitcoind not ready after $MAX_ATTEMPTS attempts. Aborting."
    exit 1
  fi
  sleep 2
done
echo "    bitcoind is ready."

echo "==> Waiting for LND to be healthy..."
ATTEMPT=0
until docker compose -f "$COMPOSE_FILE" exec -T lnd \
  lncli --network=regtest getinfo > /dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "ERROR: LND not ready after $MAX_ATTEMPTS attempts. Aborting."
    exit 1
  fi
  sleep 2
done
echo "    LND is ready."

echo "==> Creating a new mining address..."
ADDR=$(docker compose -f "$COMPOSE_FILE" exec -T lnd \
  lncli --network=regtest newaddress p2wkh | grep -o '"address": *"[^"]*"' | cut -d'"' -f4)
echo "    Mining address: $ADDR"

echo "==> Mining 101 blocks to address $ADDR..."
docker compose -f "$COMPOSE_FILE" exec -T bitcoind \
  bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass \
  generatetoaddress 101 "$ADDR" > /dev/null
echo "    101 blocks mined."

echo "==> Checking LND wallet balance..."
docker compose -f "$COMPOSE_FILE" exec -T lnd \
  lncli --network=regtest walletbalance

echo ""
echo "==> Setup complete."
echo "    Admin macaroon (inside lnd container): /root/.lnd/data/chain/bitcoin/regtest/admin.macaroon"
echo "    TLS cert (inside lnd container):       /root/.lnd/tls.cert"
echo ""
echo "    The example app mounts the lnd-data volume at /lnd, so it reads:"
echo "      tls.cert      -> /lnd/tls.cert"
echo "      admin.macaroon -> /lnd/data/chain/bitcoin/regtest/admin.macaroon"
