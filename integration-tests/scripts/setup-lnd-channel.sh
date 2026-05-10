#!/usr/bin/env bash
#
# setup-lnd-channel.sh — Bootstrap a two-node LND regtest channel.
#
# The payee node is named "lnd" and backs LNbits. The payer node is named
# "lnd-payer" and is used by smoke tests to pay app-created invoices with a
# real preimage proof.
#
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-lnbits-lnd.yml}"
PAYEE_LND_SERVICE="${PAYEE_LND_SERVICE:-lnd}"
PAYER_LND_SERVICE="${PAYER_LND_SERVICE:-lnd-payer}"
CHANNEL_CAPACITY_SATS="${CHANNEL_CAPACITY_SATS:-1000000}"
CHANNEL_CONFIRMATION_BLOCKS="${CHANNEL_CONFIRMATION_BLOCKS:-6}"
COMPOSE_WAIT_TIMEOUT_SECONDS="${COMPOSE_WAIT_TIMEOUT_SECONDS:-300}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

MAX_ATTEMPTS="${MAX_ATTEMPTS:-180}"

if ! command -v jq > /dev/null 2>&1; then
  echo "ERROR: jq is required by setup-lnd-channel.sh."
  exit 1
fi

compose_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T "$@"
}

compose_up_with_wait() {
  echo "==> Ensuring bitcoind and LND services are started..."
  if docker compose up --help 2>/dev/null | grep -q -- "--wait"; then
    docker compose -f "$COMPOSE_FILE" up -d --wait \
      --wait-timeout "$COMPOSE_WAIT_TIMEOUT_SECONDS" \
      bitcoind "$PAYEE_LND_SERVICE" "$PAYER_LND_SERVICE"
  else
    docker compose -f "$COMPOSE_FILE" up -d \
      bitcoind "$PAYEE_LND_SERVICE" "$PAYER_LND_SERVICE"
    echo "    Docker Compose does not support --wait; falling back to command-level waits."
  fi
}

wait_for_command() {
  local label="$1"
  shift

  echo "==> Waiting for ${label}..."
  local attempt=0
  until "$@" > /dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      echo "ERROR: ${label} not ready after ${MAX_ATTEMPTS} attempts. Aborting."
      exit 1
    fi
    sleep 2
  done
  echo "    ${label} is ready."
}

bitcoin_block_count() {
  compose_exec bitcoind bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass getblockcount
}

wait_for_lnd_chain_height() {
  local service="$1"
  local label="$2"

  echo "==> Waiting for ${label} to catch up to bitcoind..."
  local attempt=0
  until [ "$(compose_exec "$service" lncli --network=regtest getinfo | jq -r '.block_height // 0')" -ge "$(bitcoin_block_count)" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      echo "ERROR: ${label} did not catch up after ${MAX_ATTEMPTS} attempts. Aborting."
      echo "       bitcoind height: $(bitcoin_block_count)"
      echo "       ${label} info:"
      compose_exec "$service" lncli --network=regtest getinfo || true
      exit 1
    fi
    sleep 2
  done
  echo "    ${label} caught up."
}

extract_json_string() {
  local field="$1"
  grep -o "\"${field}\": *\"[^\"]*\"" | cut -d'"' -f4
}

has_active_channel() {
  local service="$1"
  local remote_pubkey="$2"

  compose_exec "$service" lncli --network=regtest listchannels \
    | jq -e --arg remote_pubkey "$remote_pubkey" \
      '.channels[]? | select(.remote_pubkey == $remote_pubkey and .active == true)' > /dev/null
}

has_peer() {
  local service="$1"
  local remote_pubkey="$2"

  compose_exec "$service" lncli --network=regtest listpeers \
    | jq -e --arg remote_pubkey "$remote_pubkey" \
      '.peers[]? | select(.pub_key == $remote_pubkey)' > /dev/null
}

wait_for_active_channel() {
  local service="$1"
  local label="$2"
  local remote_pubkey="$3"

  echo "==> Waiting for active channel on ${label}..."
  local attempt=0
  until has_active_channel "$service" "$remote_pubkey"; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      echo "ERROR: Channel on ${label} did not become active after ${MAX_ATTEMPTS} attempts."
      echo "       ${label} channels:"
      compose_exec "$service" lncli --network=regtest listchannels || true
      exit 1
    fi
    sleep 2
  done
  echo "    Channel on ${label} is active."
}

compose_up_with_wait

wait_for_command "bitcoind" \
  compose_exec bitcoind bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass getblockchaininfo
wait_for_command "${PAYEE_LND_SERVICE}" \
  compose_exec "$PAYEE_LND_SERVICE" lncli --network=regtest getinfo
wait_for_command "${PAYER_LND_SERVICE}" \
  compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest getinfo
wait_for_command "${PAYEE_LND_SERVICE} wallet" \
  compose_exec "$PAYEE_LND_SERVICE" lncli --network=regtest walletbalance
wait_for_command "${PAYER_LND_SERVICE} wallet" \
  compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest walletbalance

wait_for_lnd_chain_height "$PAYEE_LND_SERVICE" "$PAYEE_LND_SERVICE"
wait_for_lnd_chain_height "$PAYER_LND_SERVICE" "$PAYER_LND_SERVICE"

echo "==> Funding ${PAYER_LND_SERVICE} with spendable regtest coins..."
PAYER_ADDR=$(compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest newaddress p2wkh | extract_json_string address)
if [ -z "$PAYER_ADDR" ]; then
  echo "ERROR: Could not create payer mining address."
  exit 1
fi
compose_exec bitcoind bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass \
  generatetoaddress 101 "$PAYER_ADDR" > /dev/null
echo "    Mined 101 blocks to ${PAYER_ADDR}."

wait_for_lnd_chain_height "$PAYEE_LND_SERVICE" "$PAYEE_LND_SERVICE"
wait_for_lnd_chain_height "$PAYER_LND_SERVICE" "$PAYER_LND_SERVICE"

PAYEE_PUBKEY=$(compose_exec "$PAYEE_LND_SERVICE" lncli --network=regtest getinfo | extract_json_string identity_pubkey)
PAYER_PUBKEY=$(compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest getinfo | extract_json_string identity_pubkey)

if [ -z "$PAYEE_PUBKEY" ] || [ -z "$PAYER_PUBKEY" ]; then
  echo "ERROR: Could not read LND node public keys."
  exit 1
fi

if has_active_channel "$PAYER_LND_SERVICE" "$PAYEE_PUBKEY"; then
  echo "==> Active channel already exists."
else
  echo "==> Connecting ${PAYER_LND_SERVICE} to ${PAYEE_LND_SERVICE}..."
  if has_peer "$PAYER_LND_SERVICE" "$PAYEE_PUBKEY"; then
    echo "    Peer already connected."
  else
    ATTEMPT=0
    until compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest connect "${PAYEE_PUBKEY}@${PAYEE_LND_SERVICE}:9735" --timeout 5s > /dev/null 2>&1; do
      ATTEMPT=$((ATTEMPT + 1))
      if has_peer "$PAYER_LND_SERVICE" "$PAYEE_PUBKEY"; then
        echo "    Peer connected."
        break
      fi
      if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
        echo "ERROR: Could not connect ${PAYER_LND_SERVICE} to ${PAYEE_LND_SERVICE} after ${MAX_ATTEMPTS} attempts."
        compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest listpeers || true
        exit 1
      fi
      sleep 2
    done
    if has_peer "$PAYER_LND_SERVICE" "$PAYEE_PUBKEY"; then
      echo "    Connected."
    fi
  fi

  echo "==> Opening ${CHANNEL_CAPACITY_SATS} sat channel from ${PAYER_LND_SERVICE} to ${PAYEE_LND_SERVICE}..."
  ATTEMPT=0
  until compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest openchannel \
      --node_key="$PAYEE_PUBKEY" \
      --local_amt="$CHANNEL_CAPACITY_SATS"; do
    ATTEMPT=$((ATTEMPT + 1))
    if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
      echo "ERROR: Could not open channel after ${MAX_ATTEMPTS} attempts."
      compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest walletbalance || true
      compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest listpeers || true
      exit 1
    fi
    sleep 2
  done

  echo "==> Mining ${CHANNEL_CONFIRMATION_BLOCKS} blocks to confirm the channel..."
  CONFIRM_ADDR=$(compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest newaddress p2wkh | extract_json_string address)
  compose_exec bitcoind bitcoin-cli -regtest -rpcuser=devuser -rpcpassword=devpass \
    generatetoaddress "$CHANNEL_CONFIRMATION_BLOCKS" "$CONFIRM_ADDR" > /dev/null
fi

wait_for_lnd_chain_height "$PAYEE_LND_SERVICE" "$PAYEE_LND_SERVICE"
wait_for_lnd_chain_height "$PAYER_LND_SERVICE" "$PAYER_LND_SERVICE"
wait_for_active_channel "$PAYER_LND_SERVICE" "$PAYER_LND_SERVICE" "$PAYEE_PUBKEY"
wait_for_active_channel "$PAYEE_LND_SERVICE" "$PAYEE_LND_SERVICE" "$PAYER_PUBKEY"

echo ""
echo "==> Payer wallet balance:"
compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest walletbalance
echo ""
echo "==> Payer channel balance:"
compose_exec "$PAYER_LND_SERVICE" lncli --network=regtest channelbalance
echo ""
echo "==> Payee channel balance:"
compose_exec "$PAYEE_LND_SERVICE" lncli --network=regtest channelbalance
echo ""
echo "==> Making payee LND TLS cert and admin macaroon readable by the example app..."
compose_exec "$PAYEE_LND_SERVICE" sh -c '
  chmod o+rx /root/.lnd /root/.lnd/data /root/.lnd/data/chain /root/.lnd/data/chain/bitcoin /root/.lnd/data/chain/bitcoin/regtest
  chmod o+r /root/.lnd/tls.cert /root/.lnd/data/chain/bitcoin/regtest/admin.macaroon
'
echo ""
echo "==> Setup complete."
