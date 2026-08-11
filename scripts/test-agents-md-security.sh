#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly FIXTURE="$REPOSITORY_ROOT/scripts/test-fixtures/security/malicious-AGENTS.md"
workspace=""

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

workspace_is_safe() {
  [[ -n "$workspace" ]] || return 1
  [[ "$workspace" = /* ]] || return 1
  [[ "${workspace##*/}" == l402-agents-security.* ]] || return 1
  [[ -f "$workspace/.l402-agents-security-sentinel" ]] || return 1
  [[ "$(cd "$workspace" && pwd -P)" == "$workspace" ]] || return 1
}

cleanup() {
  local status=$?
  trap - EXIT

  if [[ $status -eq 0 && -n "$workspace" ]]; then
    workspace_is_safe || fail "refusing to clean an unvalidated workspace"
    rm -rf -- "$workspace"
  elif [[ $status -ne 0 && -n "$workspace" ]]; then
    echo "Preserving failed test workspace: $workspace" >&2
  fi

  exit "$status"
}
trap cleanup EXIT

[[ -f "$FIXTURE" ]] || fail "missing hostile AGENTS.md fixture: $FIXTURE"

created_workspace="$(mktemp -d /tmp/l402-agents-security.XXXXXX)"
workspace="$(cd "$created_workspace" && pwd -P)"
[[ "${workspace##*/}" == l402-agents-security.* ]] || fail "unexpected workspace name"
: > "$workspace/.l402-agents-security-sentinel"
workspace_is_safe || fail "could not establish isolated workspace"

marker="$workspace/payload-ran.marker"
[[ ! -e "$marker" ]] || fail "marker unexpectedly exists before validation"

mkdir -p "$workspace/scripts" "$workspace/integration-tests"
cp "$REPOSITORY_ROOT/scripts/validate-agents-md.sh" "$workspace/scripts/validate-agents-md.sh"
chmod +x "$workspace/scripts/validate-agents-md.sh"
printf '# intentionally inert test double; hostile arguments must never reach it\nexit 0\n' \
  > "$workspace/gradlew"
chmod +x "$workspace/gradlew"
printf 'services: {}\n' > "$workspace/integration-tests/docker-compose.yml"

# The placeholder is expanded only by this test into a test-owned direct child
# of the validated workspace. It is never sourced or executed as fixture data.
sed "s|__MARKER_PATH__|$marker|g; s|__WORKSPACE__|$workspace|g" "$FIXTURE" \
  > "$workspace/AGENTS.md"

[[ ! -e "$marker" ]] || fail "marker unexpectedly exists before validator invocation"

set +e
validation_output="$(cd "$workspace" && bash scripts/validate-agents-md.sh 2>&1)"
validation_status=$?
set -e

if [[ $validation_status -eq 0 ]]; then
  printf '%s\n' "$validation_output" >&2
  fail "validator accepted hostile AGENTS.md grammar"
fi

if [[ -e "$marker" ]]; then
  printf '%s\n' "$validation_output" >&2
  fail "hostile AGENTS.md content created marker $marker"
fi

echo "PASS: hostile AGENTS.md grammar was rejected without executing fixture content"
