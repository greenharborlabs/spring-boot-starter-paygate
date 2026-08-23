#!/usr/bin/env bash
# Statically checks producer/consumer access policy used by the Docker LND fixtures.
set -euo pipefail

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
fail() { printf 'LND credential-permission test failed: %s\n' "$*" >&2; exit 1; }
matches() {
  local pattern="$1"
  local file="$2"

  if command -v rg >/dev/null 2>&1; then
    rg -q -- "$pattern" "$file"
  else
    grep -Eq -- "$pattern" "$file"
  fi
}
has_regtest_group() {
  local file="$1"

  if command -v rg >/dev/null 2>&1; then
    rg -q -U 'group_add:\n\s+- "10001"' "$file"
  else
    awk '
      /^[[:space:]]*group_add:[[:space:]]*$/ { expecting_member = 1; next }
      expecting_member {
        if ($0 ~ /^[[:space:]]*-[[:space:]]*"10001"[[:space:]]*$/) found = 1
        exit
      }
      END { exit !found }
    ' "$file"
  fi
}

for script in integration-tests/scripts/setup-lnd.sh integration-tests/scripts/setup-lnd-channel.sh; do
  content="$ROOT/$script"
  [[ -r "$content" ]] || fail "missing producer script: $script"
  matches 'chgrp.*REGTEST_GID' "$content" && matches 'chmod 0750' "$content" && matches 'chmod 0640' "$content" \
    || fail "producer lacks group traversal and 0640 credential policy: $script"
  matches 'chmod o[+]' "$content" && fail "producer grants other-user access: $script"
done
for compose in integration-tests/docker-compose-lnd.yml integration-tests/docker-compose-lnd-two-node.yml integration-tests/docker-compose-lnbits-lnd.yml; do
  content="$ROOT/$compose"
  has_regtest_group "$content" \
    || fail "non-root consumer lacks fixed regtest group: $compose"
  matches 'lnd-data:/lnd:ro' "$content" || [[ "$compose" == *lnbits-lnd.yml ]] \
    || fail "consumer credential mount is not read-only: $compose"
done
matches 'useradd --system --gid appgroup --groups paygate-regtest appuser' "$ROOT/paygate-example-app/Dockerfile" \
  || fail 'example user does not join the supplemental regtest group'

printf 'LND credential-permission controls passed.\n'
