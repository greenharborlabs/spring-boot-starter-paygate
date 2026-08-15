#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
suppressions="${1:-$root/config/dependency-check-suppressions.xml}"
records="${2:-$root/config/dependency-check-risk-dispositions.md}"
[[ -r "$suppressions" && -r "$records" ]] || { echo 'dependency risk evidence is missing' >&2; exit 1; }
count="$(grep -Ec '<suppress|<suppressions' "$suppressions" || true)"
if [[ "$count" -gt 1 ]]; then
  grep -Eq '<cve>|<vulnerabilityName>' "$suppressions" || { echo 'suppression lacks advisory scope' >&2; exit 1; }
  grep -Eq 'approved|Approval' "$records" && grep -Eq 'owner|Owner' "$records" && grep -Eq 'review|Review' "$records" \
    || { echo 'suppression lacks approved owned current risk record' >&2; exit 1; }
  grep -Eq '.*\*.*|regex="true"' "$suppressions" && { echo 'broad suppression is not permitted' >&2; exit 1; }
fi
echo 'Dependency-check risk dispositions passed'
