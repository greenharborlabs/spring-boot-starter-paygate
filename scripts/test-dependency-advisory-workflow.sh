#!/usr/bin/env bash
# Statically verifies the daily Dependency-Check workflow and rejects mutation fixtures.
set -euo pipefail
export LC_ALL=C

# GitHub-hosted runner images do not guarantee ripgrep. The checks below only use
# portable extended regular expressions, so fall back to grep when needed.
if ! command -v rg >/dev/null 2>&1; then
  rg() { grep -E "$@"; }
fi

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly WORKFLOW="${1:-$ROOT/.github/workflows/dependency-advisory.yml}"

fail() { printf 'dependency advisory workflow test failed: %s\n' "$*" >&2; exit 1; }
[[ $# -le 1 && -r "$WORKFLOW" && ! -L "$WORKFLOW" ]] || fail 'workflow is missing or unsafe'

rg -q '^  schedule:' "$WORKFLOW" || fail 'daily schedule is missing'
rg -q "cron: ['\"]?[0-5][0-9] [0-9*][0-9,*/-]* \* \* \*['\"]?" "$WORKFLOW" || fail 'schedule is not daily at an off-minute'
rg -q '^  workflow_dispatch:' "$WORKFLOW" || fail 'manual dispatch is missing'
rg -q '^permissions:$' "$WORKFLOW" && rg -q '^  contents: read$' "$WORKFLOW" || fail 'least-privilege contents read permission is missing'
rg -q 'dependencyCheckAggregate' "$WORKFLOW" || fail 'Dependency-Check task is missing'
rg -q 'if: always()' "$WORKFLOW" || fail 'failure report upload is missing'
rg -q 'retention-days: ([1-9]|1[0-4])$' "$WORKFLOW" || fail 'report retention must be bounded to 14 days'
if rg -n 'uses:' "$WORKFLOW" | rg -qv '@[0-9a-f]{40}([[:space:]#]|$)'; then
  fail 'workflow action is not pinned to a full SHA'
fi
printf 'Dependency advisory workflow controls passed.\n'
