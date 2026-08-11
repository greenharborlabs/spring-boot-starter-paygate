#!/usr/bin/env bash
# Negative controls for release safeguards. This script validates only a
# temporary workflow copy; it never invokes a workflow runner or Gradle.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly RELEASE_WORKFLOW="$SCRIPT_DIR/../.github/workflows/release.yml"
readonly SENTINEL_NAME='.release-workflow-test-owned'
readonly PINNED_SHA='0123456789abcdef0123456789abcdef01234567'

fail() {
  printf 'release workflow test failed: %s\n' "$*" >&2
  exit 1
}

require_workspace() {
  local workspace="$1"
  [[ -n "$workspace" && "$workspace" = /* ]] || fail 'workspace must be absolute'
  [[ "$(basename -- "$workspace")" == release-workflow-* ]] || fail 'unexpected workspace name'
  [[ ! -L "$workspace" && -f "$workspace/$SENTINEL_NAME" ]] || fail 'workspace sentinel missing'
}

require_workspace_child() {
  local workspace="$1"
  local target="$2"
  require_workspace "$workspace"
  [[ -n "$target" && "$target" = "$workspace"/* && "$target" != "$workspace" ]] \
    || fail 'target must be a workspace descendant'
  [[ ! -L "$target" ]] || fail 'symlink targets are not permitted'
}

cleanup() {
  local workspace="${1:-}"
  [[ -n "$workspace" ]] || return 0
  require_workspace "$workspace"
  rm -rf -- "$workspace"
}

new_workspace() {
  local workspace
  workspace="$(mktemp -d "${TMPDIR:-/tmp}/release-workflow-XXXXXXXX")" \
    || fail 'could not create workspace'
  workspace="$(cd -- "$workspace" && pwd -P)" || fail 'could not resolve workspace'
  [[ ! -L "$workspace" ]] || fail 'workspace must not be a symlink'
  : > "$workspace/$SENTINEL_NAME"
  require_workspace "$workspace"
  printf '%s\n' "$workspace"
}

# The validator is deliberately limited to release safeguards. It treats the
# workflow as inert text and accepts external actions only at lowercase SHA-1
# identities, so no workflow content can be executed by this test.
validate_release_workflow() {
  local workflow="$1"
  local line reference
  local has_sbom=0 has_manifest=0 has_attestation=0 has_environment=0

  [[ -f "$workflow" && ! -L "$workflow" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == *cyclonedxBom* || "$line" == *"sbom"* || "$line" == *"SBOM"* ]]; then
      has_sbom=1
    fi
    if [[ "$line" == *sha256sum* || "$line" == *'shasum -a 256'* ]]; then
      has_manifest=1
    fi
    if [[ "$line" =~ uses:[[:space:]]*actions/attest-build-provenance@ ]]; then
      has_attestation=1
    fi
    if [[ "$line" =~ ^[[:space:]]*environment:[[:space:]]*maven-central[[:space:]]*($|#) ]]; then
      has_environment=1
    fi
    if [[ "$line" =~ uses:[[:space:]]*(.+)$ ]]; then
      reference="${BASH_REMATCH[1]%%[[:space:]#]*}"
      if [[ "$reference" != ./* \
        && ! "$reference" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+@[0-9a-f]{40}$ ]]; then
        printf 'missing immutable action pin\n' >&2
        return 1
      fi
    fi
  done < "$workflow"

  ((has_sbom)) || { printf 'missing SBOM\n' >&2; return 1; }
  ((has_manifest)) || { printf 'missing SHA-256 manifest\n' >&2; return 1; }
  ((has_attestation)) || { printf 'missing artifact attestation\n' >&2; return 1; }
  ((has_environment)) || { printf 'missing maven-central environment approval\n' >&2; return 1; }
}

expect_rejection() {
  local workflow="$1"
  local marker="$2"
  local expected="$3"
  local output
  require_workspace_child "$WORKSPACE" "$workflow"
  [[ ! -e "$marker" ]] || fail 'execution marker existed before validation'
  if output="$(validate_release_workflow "$workflow" 2>&1)"; then
    fail "unsafe release workflow was accepted: $workflow"
  fi
  [[ "$output" == *"$expected"* ]] || fail "expected '$expected', got '$output'"
  [[ ! -e "$marker" ]] || fail 'workflow content was executed during validation'
}

prepare_safe_copy() {
  local target="$1"
  require_workspace_child "$WORKSPACE" "$target"
  cp -- "$RELEASE_WORKFLOW" "$target"
  sed -i.bak -E "s|(uses:[[:space:]]*[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+@)[^[:space:]#]+|\\1$PINNED_SHA|" "$target"
  rm -f -- "$target.bak"
  sed -i.bak '/^    steps:/i\
    environment: maven-central
' "$target"
  rm -f -- "$target.bak"
  printf '%s\n' \
    '      - name: Generate SHA-256 manifest' \
    '        run: sha256sum build/libs/* > build/SHA256SUMS' \
    '      - name: Attest release artifacts' \
    "        uses: actions/attest-build-provenance@$PINNED_SHA" \
    '        with:' \
    '          subject-path: build/libs/*' >> "$target"
}

main() {
  [[ -f "$RELEASE_WORKFLOW" && ! -L "$RELEASE_WORKFLOW" ]] || fail 'missing release workflow'

  WORKSPACE="$(new_workspace)"
  trap 'cleanup "$WORKSPACE"' EXIT
  local marker="$WORKSPACE/payload-ran.marker"
  local workflow

  workflow="$WORKSPACE/missing-sbom.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/[Ss][Bb][Oo][Mm]/d; /cyclonedxBom/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing SBOM'

  workflow="$WORKSPACE/missing-manifest.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/sha256sum build\/libs\/\*/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing SHA-256 manifest'

  workflow="$WORKSPACE/missing-attestation.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/actions\/attest-build-provenance@/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing artifact attestation'

  workflow="$WORKSPACE/mutable-action.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak "s/@$PINNED_SHA/@v4/" "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing immutable action pin'

  workflow="$WORKSPACE/missing-environment.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/^[[:space:]]*environment:[[:space:]]*maven-central[[:space:]]*$/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing maven-central environment approval'

  printf 'release workflow negative controls passed\n'
}

main "$@"
