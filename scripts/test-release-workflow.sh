#!/usr/bin/env bash
# Negative controls for release safeguards. This script validates only a
# temporary workflow copy; it never invokes a workflow runner or Gradle.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly RELEASE_WORKFLOW="$SCRIPT_DIR/../.github/workflows/release.yml"
readonly CENTRAL_VERIFIER="$SCRIPT_DIR/verify-central-publication.py"
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
  local has_sbom=0 has_sbom_output_path=0 has_manifest=0 has_manifest_check=0
  local has_attestation=0 has_environment=0
  local has_dispatch=0 has_version_input=0 has_security_suite=0 has_staging=0 has_draft=0
  local has_central_bundle_upload=0 has_attestation_verify=0
  local has_repair_input=0 has_repair_upload_guard=0 has_central_byte_verification=0
  local actions_read_count=0
  local has_exact_repair_draft=0 has_exact_release_publish=0
  local contents_write_count=0

  [[ -f "$workflow" && ! -L "$workflow" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == *cyclonedxBom* || "$line" == *"sbom"* || "$line" == *"SBOM"* ]]; then
      has_sbom=1
    fi
    [[ "$line" == *'*/build/reports/cyclonedx/bom.json'* ]] && has_sbom_output_path=1
    if [[ "$line" == *sha256sum* || "$line" == *'shasum -a 256'* ]]; then
      has_manifest=1
    fi
    if [[ "$line" == *'sha256sum --check'* || "$line" == *'shasum -a 256 -c'* ]]; then
      has_manifest_check=1
    fi
    if [[ "$line" =~ uses:[[:space:]]*actions/attest-build-provenance@ ]]; then
      has_attestation=1
    fi
    if [[ "$line" =~ ^[[:space:]]*environment:[[:space:]]*maven-central[[:space:]]*($|#) ]]; then
      has_environment=1
    fi
    [[ "$line" =~ workflow_dispatch: ]] && has_dispatch=1
    [[ "$line" =~ ^[[:space:]]+version: ]] && has_version_input=1
    [[ "$line" == *security-suite.yml* ]] && has_security_suite=1
    [[ "$line" == *releaseStagingRepository* || "$line" == *staging-repository* ]] && has_staging=1
    [[ "$line" == *'draft=true'* || "$line" == *'draft: true'* ]] && has_draft=1
    [[ "$line" == *'/api/v1/publisher/upload'* ]] && has_central_bundle_upload=1
    [[ "$line" == *'gh attestation verify'* ]] && has_attestation_verify=1
    [[ "$line" =~ ^[[:space:]]+repair_run_id: ]] && has_repair_input=1
    [[ "$line" == *'Repair mode cannot upload a new Maven Central deployment'* ]] \
      && has_repair_upload_guard=1
    [[ "$line" == *'verify-central-publication.py'* ]] && has_central_byte_verification=1
    [[ "$line" =~ ^[[:space:]]+actions:[[:space:]]+read([[:space:]]*#.*)?$ ]] \
      && ((actions_read_count += 1))
    [[ "$line" == *'repos/$GITHUB_REPOSITORY/releases/$REPAIR_RELEASE_ID'* ]] \
      && has_exact_repair_draft=1
    [[ "$line" == *'--method PATCH "repos/$GITHUB_REPOSITORY/releases/$RELEASE_ID"'* ]] \
      && has_exact_release_publish=1
    [[ "$line" =~ ^[[:space:]]+contents:[[:space:]]+write([[:space:]]*#.*)?$ ]] \
      && ((contents_write_count += 1))
    if [[ "$line" == *'gh release upload'* || "$line" == *'gh release edit'* \
      || "$line" == *'gh release download'* ]]; then
      printf 'ambiguous tag-based draft release operation present\n' >&2
      return 1
    fi
    if [[ "$line" =~ ^[[:space:]]+tags: ]] || [[ "$line" == *publishToSonatype* ]]; then
      printf 'legacy tag trigger or rebuilding publisher present\n' >&2
      return 1
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
  ((has_sbom_output_path)) \
    || { printf 'missing current CycloneDX SBOM output path\n' >&2; return 1; }
  ((has_manifest)) || { printf 'missing SHA-256 manifest\n' >&2; return 1; }
  ((has_manifest_check)) || { printf 'missing SHA-256 manifest verification\n' >&2; return 1; }
  ((has_attestation)) || { printf 'missing artifact attestation\n' >&2; return 1; }
  ((has_environment)) || { printf 'missing maven-central environment approval\n' >&2; return 1; }
  ((has_dispatch && has_version_input)) || { printf 'missing manual version dispatch\n' >&2; return 1; }
  ((has_security_suite)) || { printf 'missing reusable security suite\n' >&2; return 1; }
  ((has_staging)) || { printf 'missing complete staged repository\n' >&2; return 1; }
  ((has_draft)) || { printf 'missing draft release\n' >&2; return 1; }
  ((has_central_bundle_upload)) || { printf 'missing Central bundle upload\n' >&2; return 1; }
  ((has_attestation_verify)) || { printf 'missing attestation verification\n' >&2; return 1; }
  ((has_repair_input)) || { printf 'missing failed-run repair input\n' >&2; return 1; }
  ((has_repair_upload_guard)) || { printf 'missing repair upload guard\n' >&2; return 1; }
  ((has_central_byte_verification)) \
    || { printf 'missing Central byte verification\n' >&2; return 1; }
  ((actions_read_count >= 2)) \
    || { printf 'missing prior-run Actions read permissions\n' >&2; return 1; }
  ((has_exact_repair_draft)) \
    || { printf 'missing exact repair draft lookup\n' >&2; return 1; }
  ((has_exact_release_publish)) \
    || { printf 'missing exact release-ID publication\n' >&2; return 1; }
  ((contents_write_count >= 2)) \
    || { printf 'missing push-capable draft release visibility\n' >&2; return 1; }
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

  workflow="$WORKSPACE/stale-sbom-output-path.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak 's|/build/reports/cyclonedx/bom.json|/build/reports/bom.json|g' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing current CycloneDX SBOM output path'

  workflow="$WORKSPACE/missing-manifest.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/sha256sum/d' "$workflow"
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

  workflow="$WORKSPACE/missing-dispatch.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/workflow_dispatch:/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing manual version dispatch'

  workflow="$WORKSPACE/missing-staging.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/releaseStagingRepository/d; /staging-repository/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing complete staged repository'

  workflow="$WORKSPACE/missing-repair-upload-guard.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/Repair mode cannot upload a new Maven Central deployment/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing repair upload guard'

  workflow="$WORKSPACE/missing-central-byte-verification.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/verify-central-publication.py/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing Central byte verification'

  workflow="$WORKSPACE/missing-actions-read.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/^[[:space:]]*actions:[[:space:]]*read/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing prior-run Actions read permissions'

  workflow="$WORKSPACE/missing-exact-repair-draft.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/repos\/$GITHUB_REPOSITORY\/releases\/$REPAIR_RELEASE_ID/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing exact repair draft lookup'

  workflow="$WORKSPACE/missing-draft-visibility.yml"
  prepare_safe_copy "$workflow"
  sed -i.bak '/^[[:space:]]*contents:[[:space:]]*write/d' "$workflow"
  rm -f -- "$workflow.bak"
  expect_rejection "$workflow" "$marker" 'missing push-capable draft release visibility'

  local central_repo="$WORKSPACE/central"
  local evidence="$WORKSPACE/evidence"
  local artifact='com/greenharborlabs/example/0.1.5/example-0.1.5.pom'
  mkdir -p "$central_repo/$(dirname -- "$artifact")" "$evidence"
  printf '<project/>\n' > "$central_repo/$artifact"
  local digest
  digest=$(sha256sum "$central_repo/$artifact" | awk '{print $1}')
  printf '%s  staging-repository/%s\n' "$digest" "$artifact" > "$evidence/SHA256SUMS"
  printf '%s\n' \
    '{"deploymentState":"PUBLISHED","purls":["pkg:maven/com.greenharborlabs/example@0.1.5?type=pom"]}' \
    > "$WORKSPACE/status.json"
  python3 "$CENTRAL_VERIFIER" \
    --status-json "$WORKSPACE/status.json" \
    --evidence-dir "$evidence" \
    --version 0.1.5 \
    --repository-url "file://$central_repo" \
    --attempts 1 \
    --delay-seconds 0 >/dev/null

  printf '%s\n' \
    '{"deploymentState":"PUBLISHED","purls":["pkg:maven/com.greenharborlabs/example@0.1.4"]}' \
    > "$WORKSPACE/status.json"
  if python3 "$CENTRAL_VERIFIER" \
    --status-json "$WORKSPACE/status.json" \
    --evidence-dir "$evidence" \
    --version 0.1.5 \
    --repository-url "file://$central_repo" \
    --attempts 1 \
    --delay-seconds 0 >/dev/null 2>&1; then
    fail 'Central verifier accepted a different component version'
  fi

  printf 'release workflow negative controls passed\n'
}

main "$@"
