#!/usr/bin/env bash
# Negative controls for Gradle dependency and wrapper checksum verification.
#
# This script deliberately never invokes Gradle: each negative control must be
# rejected while it is still data, before a build executable could run.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
readonly SECURITY_FIXTURES="$SCRIPT_DIR/test-fixtures/security"
readonly VERIFICATION_METADATA="$REPOSITORY_ROOT/gradle/verification-metadata.xml"
readonly WRAPPER_PROPERTIES="$REPOSITORY_ROOT/gradle/wrapper/gradle-wrapper.properties"
readonly SENTINEL_NAME='.build-integrity-test-owned'
WORKSPACE=''

fail() {
  printf 'build integrity test failed: %s\n' "$*" >&2
  exit 1
}

require_safe_workspace() {
  local workspace="$1"
  [[ -n "$workspace" && "$workspace" = /* ]] || fail 'workspace must be absolute'
  [[ "$(basename -- "$workspace")" == build-integrity-* ]] || fail 'unexpected workspace name'
  [[ ! -L "$workspace" && -f "$workspace/$SENTINEL_NAME" ]] || fail 'workspace sentinel missing'
}

require_workspace_child() {
  local workspace="$1"
  local target="$2"
  require_safe_workspace "$workspace"
  [[ -n "$target" && "$target" = "$workspace"/* && "$target" != "$workspace" ]] \
    || fail 'target must be a workspace descendant'
  [[ ! -L "$target" ]] || fail 'symlink targets are not permitted'
}

cleanup() {
  local workspace="${1:-}"
  [[ -n "$workspace" ]] || return 0
  require_safe_workspace "$workspace"
  rm -rf -- "$workspace"
}

new_workspace() {
  local workspace
  workspace="$(mktemp -d /tmp/build-integrity-XXXXXX)" || fail 'could not create workspace'
  workspace="$(cd -- "$workspace" && pwd -P)" || fail 'could not resolve workspace'
  [[ ! -L "$workspace" ]] || fail 'workspace must not be a symlink'
  : > "$workspace/$SENTINEL_NAME"
  require_safe_workspace "$workspace"
  printf '%s\n' "$workspace"
}

checksum_lines() {
  # Extract literal checksum values while accepting Gradle metadata attributes
  # such as origin. Fixture text is never evaluated.
  sed -nE 's/.*<sha256[[:space:]]+value="([0-9a-f]{64})"[[:space:]][^>]*\/>.*/\1/p' "$1" \
    | LC_ALL=C sort -u
}

validate_dependency_metadata() {
  local candidate="$1"
  local trusted="$2"
  local candidate_checksums trusted_checksums
  candidate_checksums="$(checksum_lines "$candidate")"
  trusted_checksums="$(checksum_lines "$trusted")"
  [[ -n "$trusted_checksums" ]] || return 1
  [[ -n "$candidate_checksums" && "$candidate_checksums" = "$trusted_checksums" ]]
}

wrapper_checksum() {
  sed -nE 's/^distributionSha256Sum=([0-9a-f]{64})$/\1/p' "$1"
}

validate_wrapper_checksum() {
  local candidate="$1"
  local trusted="$2"
  local candidate_checksum trusted_checksum
  candidate_checksum="$(wrapper_checksum "$candidate")"
  trusted_checksum="$(wrapper_checksum "$trusted")"
  [[ "$trusted_checksum" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "$candidate_checksum" = "$trusted_checksum" ]]
}

assert_rejected_before_execution() {
  local description="$1"
  local marker="$2"
  shift 2
  [[ ! -e "$marker" ]] || fail "$description marker existed before validation"
  if "$@"; then
    fail "$description was accepted"
  fi
  [[ ! -e "$marker" ]] || fail "$description executed before rejection"
}

main() {
  [[ -f "$VERIFICATION_METADATA" ]] || fail 'missing gradle/verification-metadata.xml'
  [[ -f "$WRAPPER_PROPERTIES" ]] || fail 'missing gradle/wrapper/gradle-wrapper.properties'
  [[ -f "$SECURITY_FIXTURES/tampered-verification-metadata.xml" ]] \
    || fail 'missing tampered verification metadata fixture'

  local marker copied_metadata copied_wrapper
  WORKSPACE="$(new_workspace)"
  trap 'cleanup "$WORKSPACE"' EXIT
  marker="$WORKSPACE/payload-ran.marker"
  copied_metadata="$WORKSPACE/verification-metadata.xml"
  copied_wrapper="$WORKSPACE/gradle-wrapper.properties"
  require_workspace_child "$WORKSPACE" "$copied_metadata"
  require_workspace_child "$WORKSPACE" "$copied_wrapper"
  cp -- "$SECURITY_FIXTURES/tampered-verification-metadata.xml" "$copied_metadata"
  cp -- "$WRAPPER_PROPERTIES" "$copied_wrapper"

  assert_rejected_before_execution 'tampered dependency verification metadata' "$marker" \
    validate_dependency_metadata "$copied_metadata" "$VERIFICATION_METADATA"

  local wrapper_checksum_value
  wrapper_checksum_value="$(wrapper_checksum "$copied_wrapper")"
  [[ "$wrapper_checksum_value" =~ ^[0-9a-f]{64}$ ]] \
    || fail 'wrapper checksum must be present before testing tampering'
  # This is a test-owned copy; the tracked wrapper configuration is never changed.
  sed -i.bak 's/^distributionSha256Sum=.*/distributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000/' \
    "$copied_wrapper"
  require_workspace_child "$WORKSPACE" "$copied_wrapper.bak"
  rm -f -- "$copied_wrapper.bak"
  assert_rejected_before_execution 'tampered wrapper checksum' "$marker" \
    validate_wrapper_checksum "$copied_wrapper" "$WRAPPER_PROPERTIES"

  printf 'build integrity negative controls passed\n'
}

main "$@"
