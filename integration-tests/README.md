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
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd-payee lnd-payer
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

### Breez Spark payer option

The smoke scripts can also pay invoices with Breez SDK Spark instead of a local
`lnd-payer` node. This is a payer-side option only: the example app can continue
issuing invoices through LNbits or LND, while Breez pays the BOLT11 invoice and
returns the preimage needed to build the Paygate credential.

```bash
cd integration-tests

export BREEZ_API_KEY="<breez api key>"
export BREEZ_MNEMONIC="<funded breez wallet mnemonic>"
export BREEZ_STORAGE_DIR="${HOME}/.paygate/breez-spark-payer"
export BREEZ_NETWORK=MAINNET
export BREEZ_MAX_FEE_SATS=10
export BREEZ_COMPLETION_TIMEOUT_SECONDS=30

PAYER_BACKEND=breez-spark bash scripts/run-smoke-test.sh
PAYER_BACKEND=breez-spark bash scripts/run-mpp-smoke-test.sh
```

`scripts/pay-breez-spark-invoice.sh` creates a versioned virtual environment
under `~/.cache/paygate`, installs `breez-sdk-spark==0.17.1` on first use, pays
with `prefer_spark=false`, rejects fees above `BREEZ_MAX_FEE_SATS`, and verifies
`sha256(preimage) == payment_hash` before printing proof variables.

The broader `paygate-client` repository also has Breez diagnostics under
`/Users/mark/code/greenharborlabs/paygate-client/scripts`, including
`breez-preimage-doctor.py`, `check-breez-wallet.sh`, and
`breez-payment-history.sh`. Use those for client-side investigation and wallet
diagnostics. The local `pay-breez-spark-invoice.sh` wrapper exists only to give
these smoke tests a stable shell output contract: `PAYMENT_HASH`, `PREIMAGE`,
and `FEE_SATS`.

### Proven reference service flow: Paygate Agent Trust + Breez

This flow was verified against
`/Users/mark/code/greenharborlabs/paygate-agent-trust` running locally as the
Paygate reference service. The reference service issues real mainnet LNbits
payee invoices; Breez SDK Spark is used only as the payer.

Start the reference service in one terminal:

```bash
cd /Users/mark/code/greenharborlabs/paygate-agent-trust

source ~/.zshrc
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_TEST_MODE=false
export PAYGATE_BACKEND=lnbits
export PAYGATE_LNBITS_URL="<your LNbits payee URL>"
export PAYGATE_LNBITS_API_KEY="<your LNbits payee wallet api key>"
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="${PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET:-$(openssl rand -base64 32)}"

./gradlew bootRun
```

Confirm the service is healthy:

```bash
curl -s http://localhost:8080/healthz
```

Run both proof-verifying smoke tests from this repository:

```bash
cd /Users/mark/code/greenharborlabs/spring-boot-starter-l402/integration-tests

source ~/.zshrc

export APP_URL="http://localhost:8080"
export HEALTH_ENDPOINT="http://localhost:8080/healthz"
export PROTECTED_ENDPOINT="http://localhost:8080/api/v1/trust/report?domain=example.com&checks=dns"

export PAYER_BACKEND=breez-spark
export BREEZ_NETWORK=MAINNET
export BREEZ_MAX_FEE_SATS=10
export BREEZ_COMPLETION_TIMEOUT_SECONDS=30

bash scripts/run-smoke-test.sh
bash scripts/run-mpp-smoke-test.sh
```

Expected result for both scripts: `ALL CHECKS PASSED`. The L402 script should
pay via Breez, verify the preimage hash, retry the protected endpoint with an
`Authorization: L402 ...` credential, and receive `200`. The MPP script should
do the same with `Authorization: Payment ...` and also validate a
`Payment-Receipt` response header.

If this flow fails with `Invoice network does not match`, the reference service
is issuing non-mainnet invoices. Breez mainnet can pay hosted/mainnet LNbits
invoices, but it cannot pay the local Docker regtest invoices.

The `docker-compose-lnbits.yml` FakeWallet stack remains useful for fast setup
and invoice checks, but it does not provide usable proof preimages for the full
L402/MPP credential flow. A wallet in the same local LNbits instance also cannot
prove app-created invoices in this stack because LNbits records local
self-payments with an unusable preimage. The same rule applies to any hosted or
external LNbits funding source, including Spark-backed LNbits, when the payer API
does not return the settled payment preimage: the payment may be settled, but the
client cannot build a valid Paygate credential.

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
export PAYER_BACKEND=breez-spark
export BREEZ_API_KEY="<breez api key>"
export BREEZ_MNEMONIC="<funded breez wallet mnemonic>"
export BREEZ_STORAGE_DIR="${HOME}/.paygate/breez-spark-payer"

# L402 smoke test
bash scripts/run-smoke-test.sh

# MPP smoke test
bash scripts/run-mpp-smoke-test.sh
```

The deployed testnet host must set `SPRING_PROFILES_ACTIVE=lnbits-testnet` to override
the default `dev` profile.

An optional `MAX_INVOICE_SATS` environment variable (default `50`) enforces a spend cap
in `run-smoke-test.sh` before paying any invoice.

`PAYER_BACKEND=lnbits` is only valid when that payer wallet returns the settled
payment preimage. Spark-backed LNbits and other funding sources that omit the
preimage can pay the invoice, but the smoke scripts will fail closed because
Paygate cannot construct or validate the credential proof.

Use `PAYER_BACKEND=breez-spark` when you want a nodeless payer that returns the
preimage directly. Breez still requires a funded wallet and API key, but it does
not require running or hosting a separate payer Lightning node.

## See Also

- [PLAYBOOK.md](PLAYBOOK.md) for step-by-step manual testing scenarios
- Root `docker-compose.yml` for a simpler test-mode-only setup
