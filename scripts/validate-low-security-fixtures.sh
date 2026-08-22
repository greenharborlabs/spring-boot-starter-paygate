#!/usr/bin/env bash
# Validates static safety invariants for supplied, disposable Docker fixtures without starting them.
set -euo pipefail
export LC_ALL=C

readonly ROOT="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)}"
readonly APP_BIND='${APP_BIND_ADDRESS:-127.0.0.1}:${APP_PORT:-'
readonly REGTEST_GID='10001'

fail() { printf 'low-security fixture validation failed: %s\n' "$*" >&2; exit 1; }
require_file() { [[ -r "$1" && ! -L "$1" ]] || fail "required fixture is missing or unsafe: ${1#"$ROOT"/}"; }

root_compose="$ROOT/docker-compose.yml"
integration_directory="$ROOT/integration-tests"
compose_files=(
  "$root_compose"
  "$integration_directory/docker-compose-lnd.yml"
  "$integration_directory/docker-compose-lnbits.yml"
  "$integration_directory/docker-compose-lnbits-lnd.yml"
  "$integration_directory/docker-compose-lnd-two-node.yml"
)
for compose_file in "${compose_files[@]}"; do
  require_file "$compose_file"
  head -n 3 "$compose_file" | rg -q 'LOCAL.*(TEST|EXAMPLE)|REGTEST' \
    || fail "missing prominent local/regtest warning: ${compose_file#"$ROOT"/}"
done

rg -qF '${APP_BIND_ADDRESS:-127.0.0.1}:${APP_PORT:-8080}:8080' "$root_compose" \
  || fail 'root example application does not default to a loopback bind'
for compose_file in "${compose_files[@]:1}"; do
  rg -qF '${APP_BIND_ADDRESS:-127.0.0.1}:${APP_PORT:-18080}:8080' "$compose_file" \
    || fail "example application does not default to a loopback bind: ${compose_file#"$ROOT"/}"
done

for compose_file in "${compose_files[@]:1}"; do
  if rg -n '^\s+- "?\$\{?(APP_PORT|LND_|LNBITS_|BITCOIND_)' "$compose_file" >/dev/null; then
    fail "an integration host port lacks an explicit loopback bind: ${compose_file#"$ROOT"/}"
  fi
done

for compose_file in "$integration_directory/docker-compose-lnbits.yml" "$integration_directory/docker-compose-lnbits-lnd.yml"; do
  require_file "$compose_file"
  rg -U -q '# TEST ONLY:.*plaintext HTTP.*\n\s*PAYGATE_LNBITS_ALLOW_PLAINTEXT_HTTP: "true"' "$compose_file" \
    || fail "plaintext LNbits setting lacks an adjacent warning: ${compose_file#"$ROOT"/}"
done

password_library="$integration_directory/scripts/lib/lnbits-setup-password.sh"
setup_lnbits="$integration_directory/scripts/setup-lnbits.sh"
require_file "$password_library"
require_file "$setup_lnbits"
rg -q 'secrets\.token_urlsafe\(32\)' "$password_library" \
  || fail 'LNbits setup secret does not use a 256-bit CSPRNG value'
rg -q 'mktemp' "$password_library" && rg -q 'chmod 600' "$password_library" \
  || fail 'LNbits setup secret is not atomically persisted owner-only'
if rg -q 'paygate-test-password' "$setup_lnbits" "$password_library"; then
  fail 'retired stable LNbits administrator password remains'
fi
rg -qF '.lnbits-setup-secret.json' "$ROOT/.gitignore" \
  || fail 'generated LNbits setup secret path is not ignored'

for script in "$integration_directory/scripts/setup-lnd.sh" "$integration_directory/scripts/setup-lnd-channel.sh"; do
  require_file "$script"
  if rg -q 'chmod o[+]' "$script"; then
    fail "LND credential script grants world access: ${script#"$ROOT"/}"
  fi
  rg -q 'REGTEST_GID.*10001' "$script" && rg -q 'chmod 0750' "$script" && rg -q 'chmod 0640' "$script" \
    || fail "LND credential script lacks fixed-group 0750/0640 policy: ${script#"$ROOT"/}"
done
for compose_file in "$integration_directory/docker-compose-lnd.yml" "$integration_directory/docker-compose-lnd-two-node.yml" "$integration_directory/docker-compose-lnbits-lnd.yml"; do
  rg -q -F 'group_add:' "$compose_file" && rg -q -F "$REGTEST_GID" "$compose_file" \
    || fail "LND-consuming application lacks the fixed supplemental group: ${compose_file#"$ROOT"/}"
done
rg -q -F 'groupadd --system --gid 10001 paygate-regtest' "$ROOT/paygate-example-app/Dockerfile" \
  || fail 'example image lacks the fixed disposable regtest group'

printf 'Low-security fixture validation passed.\n'
