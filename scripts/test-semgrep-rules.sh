#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly FIXTURE="$SCRIPT_DIR/test-fixtures/security/sensitive-log.java.fixture"
readonly CONFIG="$SCRIPT_DIR/../config/semgrep"
readonly SENTINEL='.semgrep-negative-control-owned'
WORKSPACE=''

fail() {
  printf 'Semgrep negative control failed: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  [[ -n "$WORKSPACE" && "$WORKSPACE" = /* ]] || return
  [[ "$(basename -- "$WORKSPACE")" == semgrep-negative-control-* ]] || return
  [[ -f "$WORKSPACE/$SENTINEL" ]] || return
  rm -rf -- "$WORKSPACE"
}
trap cleanup EXIT

command -v semgrep >/dev/null || fail 'semgrep is required'
[[ -f "$FIXTURE" && ! -L "$FIXTURE" ]] || fail 'fixture is missing'
WORKSPACE="$(mktemp -d "${TMPDIR:-/tmp}/semgrep-negative-control-XXXXXXXX")"
WORKSPACE="$(cd -P "$WORKSPACE" && pwd)"
[[ ! -L "$WORKSPACE" ]] || fail 'workspace must not be a symlink'
: > "$WORKSPACE/$SENTINEL"
cp -- "$FIXTURE" "$WORKSPACE/SensitiveLogFixture.java"

if semgrep scan --config "$CONFIG" --error --severity ERROR --no-git-ignore \
  "$WORKSPACE/SensitiveLogFixture.java" >/dev/null 2>&1; then
  fail 'ERROR finding did not block the scan'
fi

printf 'Semgrep ERROR negative control passed.\n'
