# Integration Tests

Docker Compose environments for testing the Paygate Spring Boot Starter (L402 + MPP dual-protocol) against real Lightning Network backends.

## Prerequisites

- Docker Engine 24+ and Docker Compose v2
- ~2 GB free disk space for container images
- `curl`, `jq`, and `python3` on your host (used by setup and smoke scripts)

## Environments

| File | Backend | What it runs |
|------|---------|-------------|
| `docker-compose-lnd.yml` | LND (gRPC) | bitcoind (regtest) + LND + example app |
| `docker-compose-lnbits.yml` | LNbits (REST) | LNbits (FakeWallet) + example app |
| `docker-compose-lnbits-lnd.yml` | LNbits (REST) over LND | bitcoind (regtest) + payee LND + payer LND + LNbits + example app |

## Quick Start: LND

```bash
cd integration-tests

# Start the stack (builds the example app image on first run)
docker compose -f docker-compose-lnd.yml up -d

# Bootstrap: fund the LND wallet with regtest coins
bash scripts/setup-lnd.sh

# Verify the example app is running
curl http://localhost:18080/api/v1/health
```

## Quick Start: LNbits

Use the two-node LND-backed LNbits stack for end-to-end proof verification. The
example app creates invoices through LNbits backed by the payee LND node, while
the smoke scripts pay those invoices through a distinct payer LND node. That
lets the scripts verify `sha256(preimage) == payment_hash` before retrying the
protected endpoint.

```bash
cd integration-tests

# Start bitcoind + both LND nodes, then fund the payer and open a channel
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh

# Start LNbits (example app needs an API key)
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits

# Create a wallet and write the API key to .env
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh

# Now start the example app (picks up LNBITS_API_KEY from .env)
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app

# Verify
curl http://localhost:18080/api/v1/health

# Run proof-verifying smoke tests
PAYER_BACKEND=lnd-cli bash scripts/run-smoke-test.sh
PAYER_BACKEND=lnd-cli bash scripts/run-mpp-smoke-test.sh
```

The `docker-compose-lnbits.yml` FakeWallet stack remains useful for fast setup
and invoice checks, but it does not provide usable proof preimages for the full
L402/MPP credential flow. A wallet in the same local LNbits instance also cannot
prove app-created invoices in this stack because LNbits records local
self-payments with an unusable preimage.

For a command-by-command manual walkthrough with where/what/why/how-to-verify
notes for both L402 and MPP, see
[PLAYBOOK.md](PLAYBOOK.md#manual-local-walkthrough).

## Configuration

All host-side ports are configurable via the `.env` file in this directory:

| Variable | Default | Description |
|----------|---------|-------------|
| `BITCOIND_RPC_PORT` | 18443 | bitcoind JSON-RPC |
| `LND_GRPC_PORT` | 10009 | LND gRPC |
| `LND_REST_PORT` | 18081 | LND REST API |
| `LND_PAYER_GRPC_PORT` | 11009 | Payer LND gRPC |
| `LND_PAYER_REST_PORT` | 18082 | Payer LND REST API |
| `LNBITS_PORT` | 15000 | LNbits HTTP |
| `APP_PORT` | 18080 | Example app HTTP |

Edit `.env` before starting to avoid port conflicts with services already running on your machine.

## Tearing Down

```bash
# Stop and remove containers + volumes for a clean slate
docker compose -f docker-compose-lnd.yml down -v
docker compose -f docker-compose-lnbits.yml down -v
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

## Troubleshooting

### Port already in use

Change the conflicting port in `.env` and restart:

```bash
# Example: move the example app to port 19090
echo 'APP_PORT=19090' >> .env
docker compose -f docker-compose-lnd.yml up -d
```

### LND never becomes healthy

LND waits for bitcoind to be fully synced. Check bitcoind logs first:

```bash
docker compose -f docker-compose-lnd.yml logs bitcoind
docker compose -f docker-compose-lnd.yml logs lnd
```

Common causes:
- bitcoind is still starting (give it 10-20 seconds on first run)
- ZMQ ports are misconfigured (should not happen with the provided compose file)

### LNbits wallet creation fails

LNbits 0.12.x may require a super-user key for API wallet creation. If `setup-lnbits.sh` fails:

1. Open `http://localhost:15000` in a browser
2. Create a wallet through the UI
3. Copy the Admin API key from the wallet settings
4. Add it to `.env`: `LNBITS_API_KEY=<your-key>`
5. Restart the example app with the compose file you are using, for example: `docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app`

For local Docker testing, `setup-lnbits.sh` initializes the first-install
superuser automatically when LNbits redirects to `/first_install`, logs in, and
stores a fresh wallet admin key in `.env`.

### Example app fails to connect to LND

Ensure the LND TLS cert and macaroon are accessible. The compose file mounts the `lnd-data` volume read-only at `/lnd` inside the example app container:

```bash
# Verify the files exist inside the container
docker compose -f docker-compose-lnd.yml exec paygate-example-app ls -la /lnd/tls.cert
docker compose -f docker-compose-lnd.yml exec paygate-example-app ls -la /lnd/data/chain/bitcoin/regtest/admin.macaroon
```

## Live Endpoint Testing

Both smoke scripts can run against a live endpoint without Docker. Set the following
environment variables directly:

```bash
export APP_URL="https://your-testnet-host:8080"
export LNBITS_URL="https://your-lnbits-instance"
export LNBITS_API_KEY="<payer-wallet-admin-key>"
export PAYER_BACKEND=lnbits

# L402 smoke test
bash scripts/run-smoke-test.sh

# MPP smoke test
bash scripts/run-mpp-smoke-test.sh
```

The deployed testnet host must set `SPRING_PROFILES_ACTIVE=lnbits-testnet` to override
the default `dev` profile.

An optional `MAX_INVOICE_SATS` environment variable (default `50`) enforces a spend cap
in `run-smoke-test.sh` before paying any invoice.

## See Also

- [PLAYBOOK.md](PLAYBOOK.md) for step-by-step manual testing scenarios
- Root `docker-compose.yml` for a simpler test-mode-only setup
