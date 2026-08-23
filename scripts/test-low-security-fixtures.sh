#!/usr/bin/env bash
# Runs isolated mutations against the static fixture-safety validator.
set -euo pipefail
export LC_ALL=C

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly VALIDATOR="$ROOT/scripts/validate-low-security-fixtures.sh"
readonly CASES="$ROOT/scripts/test-fixtures/low-security-fixtures/cases.tsv"
workspace=''

fail() { printf 'low-security fixture test failed: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -n "$workspace" && -f "$workspace/.owned" ]] && rm -rf -- "$workspace"; }
trap cleanup EXIT

[[ -r "$CASES" ]] || fail 'fixture case inventory is missing'
workspace="$(mktemp -d "${TMPDIR:-/tmp}/low-security-fixtures-XXXXXXXX")"
: > "$workspace/.owned"
copy_inputs() {
  cp -R "$ROOT/integration-tests" "$workspace/integration-tests"
  cp -R "$ROOT/paygate-example-app" "$workspace/paygate-example-app"
  cp "$ROOT/docker-compose.yml" "$ROOT/.gitignore" "$workspace/"
}
expect_rejection() {
  if bash "$VALIDATOR" "$workspace" >/dev/null 2>&1; then
    fail "unsafe fixture accepted: $1"
  fi
}

while IFS=$'\t' read -r case_name _; do
  [[ "$case_name" == \#* || -z "$case_name" ]] && continue
  rm -rf -- "$workspace/integration-tests" "$workspace/paygate-example-app"
  copy_inputs
  case "$case_name" in
    safe) bash "$VALIDATOR" "$workspace" >/dev/null || fail 'safe fixtures were rejected'; continue ;;
    missing-compose) rm "$workspace/integration-tests/docker-compose-lnd.yml" ;;
    external-app-bind) sed -i.bak 's/127\.0\.0\.1}:\${APP_PORT/0.0.0.0}:\${APP_PORT/' "$workspace/docker-compose.yml"; rm "$workspace/docker-compose.yml.bak" ;;
    missing-plaintext-warning) sed -i.bak '/# TEST ONLY: LNbits is an isolated local container/d' "$workspace/integration-tests/docker-compose-lnbits.yml"; rm "$workspace/integration-tests/docker-compose-lnbits.yml.bak" ;;
    stable-password) printf '\n# paygate-test-password\n' >> "$workspace/integration-tests/scripts/setup-lnbits.sh" ;;
    world-readable-credential) sed -i.bak 's/chmod 0640/chmod o+r/' "$workspace/integration-tests/scripts/setup-lnd.sh"; rm "$workspace/integration-tests/scripts/setup-lnd.sh.bak" ;;
    missing-fixed-group) sed -i.bak '/group_add:/,+1d' "$workspace/integration-tests/docker-compose-lnd.yml"; rm "$workspace/integration-tests/docker-compose-lnd.yml.bak" ;;
    *) fail "unknown fixture case: $case_name" ;;
  esac
  expect_rejection "$case_name"
done < "$CASES"

printf 'Low-security fixture negative controls passed.\n'
