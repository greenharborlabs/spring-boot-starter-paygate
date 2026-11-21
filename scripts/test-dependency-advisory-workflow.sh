#!/usr/bin/env bash
# Static advisory controls plus isolated removal mutations.
set -euo pipefail

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly WORKFLOW="${1:-$ROOT/.github/workflows/dependency-advisory.yml}"
readonly DEPENDABOT="${2:-$ROOT/.github/dependabot.yml}"
fail() { printf 'advisory workflow control failed: %s\n' "$*" >&2; exit 1; }
has() { grep -Fq -- "$2" <<< "$1" || fail "$3"; }
step() {
  awk -v name="$2" '$0 == "      - name: " name {found=1; next}
    found && /^      - name: / {exit}
    found {print}' "$1"
}

[[ $# -le 2 && -r "$WORKFLOW" && ! -L "$WORKFLOW" && -r "$DEPENDABOT" && ! -L "$DEPENDABOT" ]] || fail 'input missing or unsafe'
grep -q '^  schedule:' "$WORKFLOW" || fail 'schedule missing'
grep -Eq "cron: ['\"]?[0-5][0-9] [0-9*][0-9,*/-]* \* \* \*['\"]?" "$WORKFLOW" || fail 'daily off-minute schedule missing'
grep -q '^  workflow_dispatch:' "$WORKFLOW" || fail 'manual dispatch missing'
grep -q '^permissions:$' "$WORKFLOW" && grep -q '^  contents: read$' "$WORKFLOW" || fail 'least-privilege permission missing'
grep -q 'dependencyCheckAggregate' "$WORKFLOW" || fail 'Java scan missing'
if grep -n 'uses:' "$WORKFLOW" | grep -Eqv '@[0-9a-f]{40}([[:space:]#]|$)'; then fail 'mutable action reference'; fi
java_upload="$(step "$WORKFLOW" 'Upload Dependency-Check reports')"
for control in 'if: always()' 'path: build/reports/dependency-check/' 'if-no-files-found: error'; do
  has "$java_upload" "$control" "Java report upload lacks $control"
done
grep -Eq 'retention-days: ([1-9]|1[0-4])$' <<< "$java_upload" || fail 'Java report retention exceeds 14 days'

container="$(awk '/^  lnbits-container-scan:/ {found=1; next}
  found && /^  [a-zA-Z0-9_-]+:/ {exit}
  found {print}' "$WORKFLOW")"
[[ -n "$container" ]] || fail 'LNbits container job missing'
has "$container" '    permissions:' 'container job permissions missing'
has "$container" '      contents: read' 'container contents read missing'
image_step="$(step "$WORKFLOW" 'Resolve identical Compose image')"
for control in 'docker compose -f "$1" config --format json' 'integration-tests/docker-compose-lnbits.yml' 'integration-tests/docker-compose-lnbits-lnd.yml' '[[ "$fakewallet" == "$lnd" ]]' 'sha256:[0-9a-f]{64}' 'GITHUB_OUTPUT'; do
  has "$image_step" "$control" "Compose image control missing: $control"
done
manifest_step="$(step "$WORKFLOW" 'Resolve both platform manifests')"
for control in 'docker buildx imagetools inspect --raw "$LNBITS_IMAGE"' 'index.get("manifests")' '"amd64", "arm64"' 'set(manifests) != {"amd64", "arm64"}' 're.fullmatch(r"sha256:[0-9a-f]{64}", digest)' 'print(f"{arch}={repository}@{manifests[arch]}", file=output)'; do
  has "$manifest_step" "$control" "manifest control missing: $control"
done
scanner='ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
for arch in amd64 arm64; do
  scan="$(step "$WORKFLOW" "Scan linux/$arch")"
  [[ -n "$scan" ]] || fail "linux/$arch scan missing"
  for control in "steps.manifests.outputs.$arch" "$scanner" 'image --scanners vuln --format json --image-src remote "$LNBITS_MANIFEST"' "container-reports/lnbits-$arch.json" 'python3 scripts/validate-container-scan-report.py --image "$LNBITS_MANIFEST"' "steps.manifests.outcome == 'success'"; do
    has "$scan" "$control" "linux/$arch control missing: $control"
  done
done
container_upload="$(step "$WORKFLOW" 'Upload LNbits container scan reports')"
for control in 'if: always()' 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a' 'name: lnbits-container-scan-reports' 'path: container-reports/*.json' 'if-no-files-found: error'; do
  has "$container_upload" "$control" "container report upload lacks $control"
done
grep -Eq 'retention-days: ([1-9]|1[0-4])$' <<< "$container_upload" || fail 'container report retention exceeds 14 days'

[[ $(grep -c '^  - package-ecosystem: docker$' "$DEPENDABOT" || true) == 1 ]] || fail 'expected exactly one Docker Dependabot entry'
docker_entry="$(awk '/^  - package-ecosystem: docker$/ {found=1; next}
  found && /^  - package-ecosystem:/ {exit}
  found {print}' "$DEPENDABOT")"
for control in 'directory: /integration-tests' 'interval: weekly' 'target-branch: main' '- dependencies'; do
  has "$docker_entry" "$control" "Docker Dependabot entry lacks $control"
done

if [[ $# -eq 0 ]]; then
  tmpdir="$(mktemp -d)"
  trap 'rm -rf -- "$tmpdir"' EXIT
  reject_mutation() {
    local target="$1" needle="$2" replacement="$3" label="$4"
    cp "$WORKFLOW" "$tmpdir/workflow.yml"
    cp "$DEPENDABOT" "$tmpdir/dependabot.yml"
    python3 - "$tmpdir/$target.yml" "$needle" "$replacement" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text()
if sys.argv[2] not in text:
    raise SystemExit(f"mutation target missing: {sys.argv[2]}")
path.write_text(text.replace(sys.argv[2], sys.argv[3], 1))
PY
    if bash "$0" "$tmpdir/workflow.yml" "$tmpdir/dependabot.yml" >/dev/null 2>&1; then
      fail "accepted removed control: $label"
    fi
  }
  reject_mutation workflow '  lnbits-container-scan:' '  disabled-container-scan:' 'container job'
  reject_mutation workflow '[[ "$fakewallet" == "$lnd" ]]' 'true' 'Compose equality'
  reject_mutation workflow 'set(manifests) != {"amd64", "arm64"}' 'not manifests' 'missing platform'
  reject_mutation workflow 'docker buildx imagetools inspect --raw "$LNBITS_IMAGE"' 'true' 'manifest resolution'
  reject_mutation workflow '      - name: Scan linux/amd64' '      - name: Disabled linux/amd64' 'amd64 scan'
  reject_mutation workflow '      - name: Scan linux/arm64' '      - name: Disabled linux/arm64' 'arm64 scan'
  reject_mutation workflow 'image --scanners vuln --format json --image-src remote "$LNBITS_MANIFEST"' 'image --format json "$LNBITS_MANIFEST"' 'vulnerability scanner'
  reject_mutation workflow 'python3 scripts/validate-container-scan-report.py --image "$LNBITS_MANIFEST" container-reports/lnbits-amd64.json' 'true' 'severity gate'
  reject_mutation workflow "$scanner" 'ghcr.io/aquasecurity/trivy:latest' 'immutable scanner pin'
  reject_mutation workflow '      - name: Upload LNbits container scan reports' '      - name: Disabled LNbits report upload' 'container report upload'
  reject_mutation dependabot '  - package-ecosystem: docker' '  - package-ecosystem: disabled-docker' 'Docker Dependabot entry'
fi

printf 'Dependency advisory workflow controls passed.\n'
