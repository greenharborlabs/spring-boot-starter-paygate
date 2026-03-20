# Integration Tests

Docker Compose environments for testing the L402 Spring Boot Starter against real Lightning Network backends.

## Prerequisites

- Docker Engine 24+ and Docker Compose v2
- ~2 GB free disk space for container images
- `curl` and `python3` on your host (used by setup scripts)

## Environments

| File | Backend | What it runs |
|------|---------|-------------|
| `docker-compose-lnd.yml` | LND (gRPC) | bitcoind (regtest) + LND + example app |
| `docker-compose-lnbits.yml` | LNbits (REST) | LNbits (FakeWallet) + example app |

## Quick Start: LND

```bash
cd integration-tests

# Start the stack (builds the example app image on first run)
docker compose -f docker-compose-lnd.yml up -d

# Bootstrap: fund the LND wallet with regtest coins
bash scripts/setup-lnd.sh

# Verify the example app is running
curl http://localhost:8080/actuator/health
```

## Quick Start: LNbits

```bash
cd integration-tests

# Start LNbits first (example app needs an API key)
docker compose -f docker-compose-lnbits.yml up -d lnbits

# Create a wallet and write the API key to .env
bash scripts/setup-lnbits.sh

# Now start the example app (picks up LNBITS_API_KEY from .env)
docker compose -f docker-compose-lnbits.yml up -d paygate-example-app

# Verify
curl http://localhost:8080/actuator/health
```

## Configuration

All host-side ports are configurable via the `.env` file in this directory:

| Variable | Default | Description |
|----------|---------|-------------|
| `BITCOIND_RPC_PORT` | 18443 | bitcoind JSON-RPC |
| `LND_GRPC_PORT` | 10009 | LND gRPC |
| `LND_REST_PORT` | 8081 | LND REST API |
| `LNBITS_PORT` | 5000 | LNbits HTTP |
| `APP_PORT` | 8080 | Example app HTTP |

Edit `.env` before starting to avoid port conflicts with services already running on your machine.

## Tearing Down

```bash
# Stop and remove containers + volumes for a clean slate
docker compose -f docker-compose-lnd.yml down -v
docker compose -f docker-compose-lnbits.yml down -v
```

## Troubleshooting

### Port already in use

Change the conflicting port in `.env` and restart:

```bash
# Example: move the example app to port 9090
echo 'APP_PORT=9090' >> .env
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

1. Open `http://localhost:5000` in a browser
2. Create a wallet through the UI
3. Copy the Admin API key from the wallet settings
4. Add it to `.env`: `LNBITS_API_KEY=<your-key>`
5. Restart the example app: `docker compose -f docker-compose-lnbits.yml up -d paygate-example-app`

### Example app fails to connect to LND

Ensure the LND TLS cert and macaroon are accessible. The compose file mounts the `lnd-data` volume read-only at `/lnd` inside the example app container:

```bash
# Verify the files exist inside the container
docker compose -f docker-compose-lnd.yml exec paygate-example-app ls -la /lnd/tls.cert
docker compose -f docker-compose-lnd.yml exec paygate-example-app ls -la /lnd/data/chain/bitcoin/regtest/admin.macaroon
```

## See Also

- [PLAYBOOK.md](PLAYBOOK.md) for step-by-step manual testing scenarios
- Root `docker-compose.yml` for a simpler test-mode-only setup
