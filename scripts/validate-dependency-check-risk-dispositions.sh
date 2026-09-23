#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec python3 "$root/scripts/validate-dependency-check-risk-dispositions.py" \
  "${1:-$root/config/dependency-check-suppressions.xml}" \
  "${2:-$root/config/dependency-check-risk-dispositions.md}" \
  "$root/build.gradle.kts" "$root/gradle.properties"
