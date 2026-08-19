#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
validator="$root/scripts/validate-medium-security-finding-dispositions.sh"
ledger="$root/docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md"
evidence="$root/specs/007-medium-security-remediation/validation-evidence.md"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/kimi-medium-findings.XXXXXX")"
trap 'rm -rf "$fixture_dir"' EXIT

bash "$validator" "$ledger" "$evidence"

reject() {
  local name="$1"
  local expression="$2"
  local fixture="$fixture_dir/$name.md"
  sed "$expression" "$ledger" > "$fixture"
  if bash "$validator" "$fixture" "$evidence" >/dev/null 2>&1; then
    echo "negative Kimi Medium ledger fixture accepted: $name" >&2
    exit 1
  fi
}

reject missing '/| M-4 |/d'
reject duplicate '/| M-4 |/p'
reject placeholder 's/Authentication trust-boundary workstream/TODO/'
reject invalid-status 's/| implemented | pending |/| complete | pending |/'
reject unsubstantiated-verified 's/| implemented | pending |/| verified | pending |/'
reject premature-approved 's/| implemented | pending |/| implemented | approved |/'
reject unnamed-reviewer 's/| implemented | pending | — | — | — |/| verified | approved | — | 2026-08-18 | current evidence |/'
reject missing-approval-date 's/| implemented | pending | — | — | — |/| verified | approved | Release reviewer | — | current evidence |/'
reject stale-approval-evidence 's/| implemented | pending | — | — | — |/| verified | approved | Release reviewer | 2026-08-18 | stale failed evidence |/'
reject duplicate-approval 's/| M-2 | Medium | remediated/| M-1 | Medium | remediated/'

echo 'Kimi Medium finding disposition negative controls passed'
