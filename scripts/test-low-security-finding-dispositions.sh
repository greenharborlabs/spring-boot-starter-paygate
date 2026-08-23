#!/usr/bin/env bash
# Exercises each declared low-finding ledger mutation in an isolated temporary workspace.
set -euo pipefail
export LC_ALL=C

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly VALIDATOR="$ROOT/scripts/validate-low-security-finding-dispositions.sh"
readonly CASES="$ROOT/scripts/test-fixtures/low-security-findings/cases.tsv"
workspace=''
fail() { printf 'low-security finding test failed: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -n "$workspace" && -f "$workspace/.owned" ]] && rm -rf -- "$workspace"; }
trap cleanup EXIT

[[ -r "$CASES" && -x "$VALIDATOR" ]] || fail 'validator or mutation inventory is missing'
workspace="$(mktemp -d "${TMPDIR:-/tmp}/low-security-findings-XXXXXXXX")"
: > "$workspace/.owned"

while IFS=' ' read -r case_name expected; do
  [[ -z "$case_name" || "$case_name" == \#* ]] && continue
  ledger="$workspace/$case_name.md"
  evidence="$workspace/$case_name-evidence.md"
  cp "$ROOT/docs/security/KIMI-LOW-FINDING-DISPOSITIONS.md" "$ledger"
  cp "$ROOT/docs/security/KIMI-LOW-VALIDATION-EVIDENCE.md" "$evidence"
  case "$case_name" in
    valid) ;;
    missing) sed -i.bak '/| L-28 |/d' "$ledger" ;;
    duplicate) sed -i.bak '/| L-28 |/p' "$ledger" ;;
    unknown) sed -i.bak 's/| L-28 |/| L-29 |/' "$ledger" ;;
    non-low) sed -i.bak 's/| Low |/| Medium |/g' "$ledger" ;;
    placeholder) sed -i.bak 's/Core credential boundary/pending/g' "$ledger" ;;
    unmapped) sed -i.bak 's/FR-/RQ-/g' "$ledger" ;;
    stale) sed -i.bak 's/| passed | 0 |/| stale | 0 |/g' "$evidence" ;;
    failed) sed -i.bak 's/| passed | 0 |/| failed | 1 |/g' "$evidence" ;;
    premature-approval) sed -i.bak 's/| approved | approved | [^|]* | [^|]* | [^|]* |/| verified | approved | Reviewer | 2026-08-23 | LOW-EV-CORE |/g' "$ledger" ;;
    unapproved-tradeoff) sed -i.bak 's/| remediated |/| accepted-limitation |/g' "$ledger" ;;
    marker-leak) printf '\nLOW_SECURITY_MARKER_TOKEN_ID_8c3e5b10\n' >> "$ledger" ;;
    *) fail "unknown case in inventory: $case_name" ;;
  esac
  rm -f "$ledger.bak"
  if [[ "$expected" == pass ]]; then
    bash "$VALIDATOR" "$ledger" "$evidence" >/dev/null || fail "valid fixture rejected: $case_name"
  elif bash "$VALIDATOR" "$ledger" "$evidence" >/dev/null 2>&1; then
    fail "unsafe fixture accepted: $case_name"
  fi
done < "$CASES"

printf 'Kimi Low finding disposition negative controls passed.\n'
