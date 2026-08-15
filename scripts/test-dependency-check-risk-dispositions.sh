#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
validator="$root/scripts/validate-dependency-check-risk-dispositions.sh"
"$validator"
for fixture in missing-approval broad unmatched unused expired absent-controls stale-scanner; do
  if "$validator" "$root/scripts/test-fixtures/dependency-check-risk/$fixture.xml" "$root/scripts/test-fixtures/dependency-check-risk/$fixture.md" >/dev/null 2>&1; then
    echo "unsafe dependency risk fixture accepted: $fixture" >&2; exit 1
  fi
done
echo 'Dependency-check risk negative controls passed'
