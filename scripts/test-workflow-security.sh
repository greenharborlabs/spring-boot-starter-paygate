#!/usr/bin/env bash
# Negative controls for workflow input validation. This script never invokes a
# workflow runner; it validates copied fixtures only.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -P "$SCRIPT_DIR/.." && pwd)"
readonly FIXTURE="$SCRIPT_DIR/test-fixtures/security/mutable-action.yml"
readonly SENTINEL_NAME='.workflow-security-test-owned'
WORKSPACE=''

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -z "$WORKSPACE" ]]; then
    return
  fi
  [[ "$WORKSPACE" = /* && "$(basename "$WORKSPACE")" == workflow-security-* ]] || return
  [[ -f "$WORKSPACE/$SENTINEL_NAME" ]] || return
  rm -rf -- "$WORKSPACE"
}
trap cleanup EXIT

create_workspace() {
  local candidate
  candidate="$(mktemp -d "${TMPDIR:-/tmp}/workflow-security-XXXXXXXX")" || fail 'cannot create workspace'
  WORKSPACE="$(cd -P "$candidate" && pwd)" || fail 'cannot resolve workspace'
  [[ "$WORKSPACE" = /* && "$(basename "$WORKSPACE")" == workflow-security-* ]] \
    || fail 'unsafe workspace path'
  : > "$WORKSPACE/$SENTINEL_NAME"
  [[ -f "$WORKSPACE/$SENTINEL_NAME" && ! -L "$WORKSPACE" ]] || fail 'workspace ownership check failed'
}

require_workspace_target() {
  local target="$1"
  [[ -n "$WORKSPACE" && "$WORKSPACE" = /* && -f "$WORKSPACE/$SENTINEL_NAME" ]] \
    || fail 'workspace is not validated'
  [[ "$target" == "$WORKSPACE"/* && "$target" != "$WORKSPACE" && ! -L "$target" ]] \
    || fail 'unsafe fixture target'
}

# This deliberately small validator covers the workflow identities and
# permission levels governed by the supply-chain contract. It reads no values
# as shell input and accepts an external action only at a lowercase 40-hex SHA.
validate_workflow() {
  local workflow="$1"
  local line reference permission_name permission_value in_permissions=0 permission_indent=0
  local leading_whitespace line_indent
  local line_number=0
  local workflow_name

  workflow_name="$(basename -- "$workflow")"

  [[ -f "$workflow" && ! -L "$workflow" ]] || {
    printf 'invalid workflow input\n' >&2
    return 1
  }

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))

    if ((in_permissions)) && [[ "$line" =~ [^[:space:]#] ]]; then
      leading_whitespace="${line%%[![:space:]]*}"
      line_indent=${#leading_whitespace}
      if ((line_indent <= permission_indent)); then
        in_permissions=0
      fi
    fi

    if [[ "$line" =~ ^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*(.+)$ ]]; then
      reference="${BASH_REMATCH[2]%%[[:space:]#]*}"
      if [[ "$reference" != ./* \
        && ! "$reference" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+@[0-9a-f]{40}$ ]]; then
        printf 'invalid action reference at line %d\n' "$line_number" >&2
        return 1
      fi
    fi

    if [[ "$line" =~ pull_request_target: ]] || [[ "$line" =~ runs-on:.*self-hosted ]]; then
      printf 'unsafe workflow execution context at line %d\n' "$line_number" >&2
      return 1
    fi

    if [[ "$line" =~ ^([[:space:]]*)permissions:[[:space:]]*(.*)$ ]]; then
      permission_value="${BASH_REMATCH[2]%%[[:space:]#]*}"
      in_permissions=1
      permission_indent=${#BASH_REMATCH[1]}
      if [[ -n "$permission_value" && "$permission_value" != '{}' ]]; then
        printf 'excessive or malformed permissions at line %d\n' "$line_number" >&2
        return 1
      fi
      continue
    fi

    if ((in_permissions)); then
      if [[ "$line" =~ ^[[:space:]]{2,}([A-Za-z-]+):[[:space:]]*([A-Za-z-]+)[[:space:]]*(#.*)?$ ]]; then
        permission_name="${BASH_REMATCH[1]}"
        permission_value="${BASH_REMATCH[2]}"
        if [[ "$permission_value" == write \
          && "$permission_name" != security-events \
          && "$permission_name" != id-token \
          && !( "$workflow_name" == release.yml && "$permission_name" == attestations ) \
          && !( "$workflow_name" == release.yml && "$permission_name" == contents ) ]]; then
          printf 'excessive permission %s at line %d\n' "$permission_name" "$line_number" >&2
          return 1
        fi
        if [[ "$permission_value" != read && "$permission_value" != none && "$permission_value" != write ]]; then
          printf 'malformed permission at line %d\n' "$line_number" >&2
          return 1
        fi
      fi
    fi
  done < "$workflow"

  if rg -q 'uses:[[:space:]]*actions/checkout@' "$workflow"; then
    if ! awk '
      /uses:[[:space:]]*actions\/checkout@/ {
        checkout = 1
        remaining = 8
        protected = 0
        next
      }
      checkout {
        if ($0 ~ /persist-credentials:[[:space:]]*false/) {
          protected = 1
          checkout = 0
        } else if (--remaining == 0) {
          exit 1
        }
      }
      END { if (checkout && !protected) exit 1 }
    ' "$workflow"; then
      printf 'checkout persists credentials\n' >&2
      return 1
    fi
  fi
}

expect_rejection() {
  local workflow="$1"
  local expected="$2"
  local output
  require_workspace_target "$workflow"
  [[ ! -e "$WORKSPACE/payload-ran.marker" ]] || fail 'fixture marker exists before validation'
  if output="$(validate_workflow "$workflow" 2>&1)"; then
    fail "unsafe workflow was accepted: $workflow"
  fi
  [[ "$output" == *"$expected"* ]] || fail "expected '$expected', got '$output'"
  [[ ! -e "$WORKSPACE/payload-ran.marker" ]] || fail 'workflow payload executed during validation'
}

copy_fixture() {
  local target="$1"
  require_workspace_target "$target"
  cp -- "$FIXTURE" "$target"
}

main() {
  local workflow
  [[ -f "$FIXTURE" && ! -L "$FIXTURE" ]] || fail 'missing mutable-action fixture'

  while IFS= read -r workflow; do
    validate_workflow "$workflow" || fail "repository workflow failed security validation: $workflow"
  done < <(find "$REPOSITORY_ROOT/.github/workflows" -type f \( -name '*.yml' -o -name '*.yaml' \) | LC_ALL=C sort)

  create_workspace
  workflow="$WORKSPACE/mutable-action.yml"

  copy_fixture "$workflow"
  expect_rejection "$workflow" 'invalid action reference'

  copy_fixture "$workflow"
  require_workspace_target "$workflow.bak"
  sed -i.bak 's#actions/checkout@v4#actions/checkout@not-a-sha#' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" 'invalid action reference'

  copy_fixture "$WORKSPACE/least-privilege.yml"
  require_workspace_target "$WORKSPACE/least-privilege.yml.bak"
  sed -i.bak 's#actions/checkout@v4#actions/checkout@0123456789abcdef0123456789abcdef01234567#' \
    "$WORKSPACE/least-privilege.yml"
  rm -f -- "$WORKSPACE/least-privilege.yml.bak"
  expect_rejection "$WORKSPACE/least-privilege.yml" 'excessive permission contents'

  printf 'PASS: workflow security negative controls\n'
}

main "$@"
