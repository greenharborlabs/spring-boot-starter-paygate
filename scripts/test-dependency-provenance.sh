#!/usr/bin/env bash
# Exercises provenance-validator negative controls in a test-owned temporary directory.
set -euo pipefail
export LC_ALL=C

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly VALIDATOR="$ROOT/scripts/validate-dependency-provenance.sh"
readonly CASES="$ROOT/scripts/test-fixtures/dependency-provenance/cases.tsv"
workspace=''

fail() { printf 'dependency provenance test failed: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -n "$workspace" && -f "$workspace/.owned" ]] && rm -rf -- "$workspace"; }
trap cleanup EXIT

workspace="$(mktemp -d "${TMPDIR:-/tmp}/dependency-provenance-XXXXXXXX")"
: > "$workspace/.owned"
copy_inputs() {
  cp "$ROOT/gradle/verification-metadata.xml" "$workspace/metadata.xml"
  cp "$ROOT/gradle/verification-keyring.keys" "$workspace/keyring.keys"
  cp "$ROOT/config/dependency-provenance-exceptions.tsv" "$workspace/exceptions.tsv"
}
expect_rejection() {
  local case_name="$1"
  if bash "$VALIDATOR" "$workspace/metadata.xml" "$workspace/keyring.keys" "$workspace/exceptions.tsv" >/dev/null 2>&1; then
    fail "unsafe case accepted: $case_name"
  fi
}

[[ -r "$CASES" ]] || fail 'fixture cases are missing'
while IFS=$'\t' read -r case_name _; do
  [[ "$case_name" == \#* || -z "$case_name" ]] && continue
  copy_inputs
  case "$case_name" in
    missing-key) rm "$workspace/keyring.keys" ;;
    untrusted-signature) sed -i.bak '/<trusted-key /d' "$workspace/metadata.xml"; rm "$workspace/metadata.xml.bak" ;;
    checksum-change) sed -i.bak '3s/[0-9a-f]\{64\}/0000000000000000000000000000000000000000000000000000000000000000/' "$workspace/exceptions.tsv"; rm "$workspace/exceptions.tsv.bak" ;;
    broad-exception) printf 'EX-001\tgroup\tmodule\t*\tartifact.jar\t0000000000000000000000000000000000000000000000000000000000000000\thttps://example.invalid/source\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    duplicate-exception) printf 'EX-001\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\nEX-002\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    orphaned-exception) printf 'EX-001\tmissing\tartifact\t1\tmissing.jar\t0000000000000000000000000000000000000000000000000000000000000000\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    expired-exception) printf 'EX-001\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2000-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    clean-resolution) bash "$VALIDATOR" "$workspace/metadata.xml" "$workspace/keyring.keys" "$workspace/exceptions.tsv" >/dev/null || fail 'reviewed inputs were rejected' ; continue ;;
    *) fail "unknown fixture case: $case_name" ;;
  esac
  expect_rejection "$case_name"
done < "$CASES"

printf 'Dependency provenance negative controls passed.\n'
