#!/usr/bin/env bash
# Statically checks producer/consumer access policy used by the Docker LND fixtures.
set -euo pipefail

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
fail() { printf 'LND credential-permission test failed: %s\n' "$*" >&2; exit 1; }

for script in integration-tests/scripts/setup-lnd.sh integration-tests/scripts/setup-lnd-channel.sh; do
  content="$ROOT/$script"
  [[ -r "$content" ]] || fail "missing producer script: $script"
  rg -q 'chgrp.*REGTEST_GID' "$content" && rg -q 'chmod 0750' "$content" && rg -q 'chmod 0640' "$content" \
    || fail "producer lacks group traversal and 0640 credential policy: $script"
  rg -q 'chmod o[+]' "$content" && fail "producer grants other-user access: $script"
done
for compose in integration-tests/docker-compose-lnd.yml integration-tests/docker-compose-lnd-two-node.yml integration-tests/docker-compose-lnbits-lnd.yml; do
  content="$ROOT/$compose"
  rg -q -U 'group_add:\n\s+- "10001"' "$content" \
    || fail "non-root consumer lacks fixed regtest group: $compose"
  rg -q 'lnd-data:/lnd:ro' "$content" || [[ "$compose" == *lnbits-lnd.yml ]] \
    || fail "consumer credential mount is not read-only: $compose"
done
rg -q 'useradd --system --gid appgroup --groups paygate-regtest appuser' "$ROOT/paygate-example-app/Dockerfile" \
  || fail 'example user does not join the supplemental regtest group'

printf 'LND credential-permission controls passed.\n'
