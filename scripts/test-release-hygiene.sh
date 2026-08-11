#!/usr/bin/env bash
# Negative controls for prospective release-archive inputs. The controls create
# only test-owned files and reject them before any archive or publication step.
set -euo pipefail

readonly SENTINEL_NAME='.release-hygiene-test-owned'
workspace=''

fail() {
  printf 'release hygiene test failed: %s\n' "$*" >&2
  exit 1
}

workspace_is_safe() {
  [[ -n "$workspace" && "$workspace" = /* ]] || return 1
  [[ "$(basename -- "$workspace")" == release-hygiene-* ]] || return 1
  [[ ! -L "$workspace" && -f "$workspace/$SENTINEL_NAME" ]] || return 1
  [[ "$(cd -- "$workspace" && pwd -P)" == "$workspace" ]]
}

require_workspace_child() {
  local target="$1"
  workspace_is_safe || fail 'workspace is not validated'
  [[ -n "$target" && "$target" = "$workspace"/* && "$target" != "$workspace" ]] \
    || fail 'target must be a workspace descendant'
  [[ ! -L "$target" ]] || fail 'symlink targets are not permitted'
}

cleanup() {
  local status=$?
  trap - EXIT

  if [[ $status -eq 0 && -n "$workspace" ]]; then
    workspace_is_safe || fail 'refusing to clean an unvalidated workspace'
    rm -rf -- "$workspace"
  elif [[ $status -ne 0 && -n "$workspace" ]]; then
    printf 'preserving failed release-hygiene workspace: %s\n' "$workspace" >&2
  fi

  exit "$status"
}
trap cleanup EXIT

new_workspace() {
  local candidate
  candidate="$(mktemp -d "${TMPDIR:-/tmp}/release-hygiene-XXXXXXXX")" \
    || fail 'could not create workspace'
  workspace="$(cd -- "$candidate" && pwd -P)" || fail 'could not resolve workspace'
  [[ ! -L "$workspace" ]] || fail 'workspace must not be a symlink'
  : > "$workspace/$SENTINEL_NAME"
  workspace_is_safe || fail 'could not establish isolated workspace'
}

# The validator consumes prospective archive inputs only. It neither packages
# nor publishes them, and it reports categories rather than file contents.
validate_archive_inputs() {
  local archive_root="$1"
  local candidate relative

  require_workspace_child "$archive_root"
  [[ -d "$archive_root" ]] || return 1
  if [[ -n "$(find "$archive_root" -type l -print -quit)" ]]; then
    printf 'archive input contains a symbolic link\n' >&2
    return 1
  fi

  while IFS= read -r -d '' candidate; do
    [[ "$candidate" = "$archive_root"/* ]] || return 1
    relative="${candidate#"$archive_root/"}"
    [[ -n "$relative" && "$relative" != /* && "$relative" != *'..'* ]] || {
      printf 'archive input has an unsafe path\n' >&2
      return 1
    }

    case "$relative" in
      .gradle|.gradle/*|*/.gradle|*/.gradle/*|.cache|.cache/*|*/.cache|*/.cache/*|\
        .m2|.m2/*|*/.m2|*/.m2/*|__pycache__|__pycache__/*|*/__pycache__|*/__pycache__/*)
        printf 'archive input contains a cache\n' >&2
        return 1
        ;;
      .env|.env.*|*/.env|*/.env.*|*.env)
        printf 'archive input contains an environment file\n' >&2
        return 1
        ;;
      *.pem|*.key|*.p12|*.pfx|*.jks|*.crt|*.cert|*.macaroon|credentials/*|*/credentials/*|\
        secrets/*|*/secrets/*)
        printf 'archive input contains generated credentials\n' >&2
        return 1
        ;;
    esac

    # Example configuration may refer to an externally supplied value, but it
    # must never package a fixed payment-binding secret.
    if [[ "$relative" == examples/* && "$relative" =~ \.(yml|yaml|properties)$ ]] \
      && grep -Eq '^[[:space:]]*challenge-binding-secret:[[:space:]]*[^[:space:]${]' "$candidate"; then
      printf 'archive input contains a reusable example secret\n' >&2
      return 1
    fi
  done < <(find "$archive_root" -type f -print0)
}

expect_rejection_before_publication() {
  local description="$1"
  local archive_root="$2"
  local expected="$3"
  local marker="$workspace/publication-ran.marker"
  local output

  require_workspace_child "$archive_root"
  require_workspace_child "$marker"
  [[ ! -e "$marker" ]] || fail "$description marker existed before validation"
  if output="$(validate_archive_inputs "$archive_root" 2>&1)"; then
    fail "$description was accepted"
  fi
  [[ "$output" == *"$expected"* ]] || fail "$description did not report the expected rejection"
  [[ ! -e "$marker" ]] || fail "$description reached publication"
}

add_input() {
  local archive_root="$1"
  local relative="$2"
  local contents="${3:-inert test data}"
  local target="$archive_root/$relative"

  require_workspace_child "$archive_root"
  [[ -n "$relative" && "$relative" != /* && "$relative" != *'..'* && "$relative" != *$'\n'* ]] \
    || fail 'archive input name is unsafe'
  require_workspace_child "$target"
  mkdir -p -- "$(dirname -- "$target")"
  printf '%s\n' "$contents" > "$target"
}

remove_input() {
  local target="$1"
  require_workspace_child "$target"
  rm -rf -- "$target"
}

main() {
  local archive_root
  new_workspace
  archive_root="$workspace/prospective-release-inputs"
  require_workspace_child "$archive_root"
  mkdir -p -- "$archive_root"

  add_input "$archive_root" 'LICENSE'
  if ! validate_archive_inputs "$archive_root"; then
    fail 'safe prospective archive input was rejected'
  fi

  add_input "$archive_root" '.gradle/caches/modules-2/metadata.bin'
  expect_rejection_before_publication 'cache input' "$archive_root" 'contains a cache'
  remove_input "$archive_root/.gradle"

  add_input "$archive_root" '.env.release'
  expect_rejection_before_publication 'environment input' "$archive_root" 'environment file'
  remove_input "$archive_root/.env.release"

  add_input "$archive_root" 'credentials/generated-release-key.pem'
  expect_rejection_before_publication 'generated credential input' "$archive_root" 'generated credentials'
  remove_input "$archive_root/credentials"

  add_input "$archive_root" 'examples/application-dev.yml' 'challenge-binding-secret: fixed-test-value'
  expect_rejection_before_publication 'reusable example-secret input' "$archive_root" 'reusable example secret'

  printf 'release hygiene negative controls passed\n'
}

main "$@"
