#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
validator="$root/scripts/validate-address-security-finding-dispositions.sh"
fixture="$root/scripts/test-fixtures/address-security-findings/valid.md"
[[ -x "$validator" || -f "$validator" ]] || exit 1
bash "$validator" "$fixture"
for name in missing duplicate unexpected placeholder premature-verified unmapped unowned stale-review; do
  if bash "$validator" "$root/scripts/test-fixtures/address-security-findings/$name.md" >/dev/null 2>&1; then
    echo "negative DeepSeek ledger fixture accepted: $name" >&2; exit 1
  fi
done
echo 'DeepSeek finding disposition negative controls passed'
