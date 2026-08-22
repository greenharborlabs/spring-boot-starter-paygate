#!/usr/bin/env bash
# Validates the reviewed, declarative Gradle dependency-provenance inputs. This validator never
# resolves dependencies and never evaluates metadata, exception, or keyring content as commands.
set -euo pipefail
export LC_ALL=C

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly METADATA="${1:-$ROOT/gradle/verification-metadata.xml}"
readonly KEYRING="${2:-$ROOT/gradle/verification-keyring.keys}"
readonly EXCEPTIONS="${3:-$ROOT/config/dependency-provenance-exceptions.tsv}"

fail() {
  printf 'dependency provenance validation failed: %s\n' "$*" >&2
  exit 1
}

[[ $# -le 3 ]] || fail 'expected optional metadata, keyring, and exception paths'
[[ -r "$METADATA" && ! -L "$METADATA" ]] || fail 'verification metadata is missing or unsafe'
[[ -r "$KEYRING" && ! -L "$KEYRING" ]] || fail 'committed verification keyring is missing or unsafe'
[[ -r "$EXCEPTIONS" && ! -L "$EXCEPTIONS" ]] || fail 'exception ledger is missing or unsafe'

rg -q '<verify-metadata>true</verify-metadata>' "$METADATA" || fail 'metadata verification is disabled'
rg -q '<verify-signatures>true</verify-signatures>' "$METADATA" || fail 'signature verification is disabled'
rg -q -- '-----BEGIN PGP PUBLIC KEY BLOCK-----' "$KEYRING" || fail 'keyring has no armored public key'

if rg -q '<trusted-artifact([^>]*(group|name|version|file)="\*"|[^>]*/>)' "$METADATA"; then
  fail 'broad or unscoped trusted artifact is forbidden'
fi

awk '
  /<sha256[[:space:]]/ {
    if ($0 !~ /value="[0-9a-f]{64}"/) exit 1
    checksums++
  }
  /<trusted-key[[:space:]]/ {
    if ($0 !~ /id="[0-9A-F]{40}"/) exit 1
    keys++
  }
  END { exit checksums > 0 && keys > 0 ? 0 : 1 }
' "$METADATA" || fail 'checksums or scoped full-fingerprint keys are incomplete'

today="$(date -u +%F)"
awk -F '\t' -v today="$today" '
  function fail(message) { print "dependency provenance validation failed: " message > "/dev/stderr"; exit 1 }
  /^#/ || /^[[:space:]]*$/ { next }
  NR == 1 {
    fail("exception ledger header must be a comment")
  }
  {
    if (NF != 11) fail("exception row must contain exactly 11 tab-separated fields")
    for (fieldIndex = 1; fieldIndex <= NF; fieldIndex++) {
      if ($fieldIndex == "" || $fieldIndex == "-" || tolower($fieldIndex) ~ /^(tbd|todo|pending|n\/a)$/) fail("exception row has placeholder field")
    }
    if ($1 !~ /^EX-[0-9]{3}$/) fail("exception ID is invalid")
    if ($2 ~ /[*?]/ || $3 ~ /[*?]/ || $4 ~ /[*?]/ || $5 ~ /[*?]/) fail("exception coordinate is broad")
    if ($6 !~ /^[0-9a-f]{64}$/) fail("exception checksum is invalid")
    if ($7 !~ /^https:\/\// || $7 ~ /(cache|\.gradle)/) fail("exception source is not authoritative")
    if ($10 !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/ || $10 < today) fail("exception review is expired")
    coordinate = $2 ":" $3 ":" $4 ":" $5
    if (seenId[$1]++ || seenCoordinate[coordinate]++) fail("exception is duplicate")
  }
' "$EXCEPTIONS"

while IFS=$'\t' read -r exception_id group module version filename checksum _; do
  [[ "$exception_id" == \#* || -z "$exception_id" ]] && continue
  escaped_group="${group//./\\.}"
  escaped_module="${module//./\\.}"
  escaped_version="${version//./\\.}"
  if ! rg -q "<component group=\"$escaped_group\" name=\"$escaped_module\" version=\"$escaped_version\">" "$METADATA"; then
    fail "exception $exception_id is orphaned from verification metadata"
  fi
  if ! rg -q "<artifact name=\"${filename//./\\.}\">" "$METADATA"; then
    fail "exception $exception_id references an unknown artifact"
  fi
  rg -q "<sha256 value=\"$checksum\"" "$METADATA" || fail "exception $exception_id checksum is not verified"
done < "$EXCEPTIONS"

# Gradle records artifacts whose detached signature exists but whose publisher key could not be
# retrieved. Each must have an exact, checksum-pinned exception; this is intentionally checked
# against artifact coordinates rather than accepting an unscoped key or artifact bypass.
while IFS=$'\t' read -r group module version filename checksum; do
  exception_prefix="$group"$'\t'"$module"$'\t'"$version"$'\t'"$filename"$'\t'"$checksum"$'\t'
  grep -Fq -- "$exception_prefix" "$EXCEPTIONS" \
    || fail "unavailable publisher key lacks an exact exception for $group:$module:$version:$filename"
done < <(
  awk '
    /<component group=/ { n=split($0, a, "\""); group=a[2]; module=a[4]; version=a[6] }
    /<artifact name=/ { n=split($0, a, "\""); filename=a[2] }
    /reason="A key couldn.t be downloaded"/ { n=split($0, a, "\""); print group "\t" module "\t" version "\t" filename "\t" a[2] }
  ' "$METADATA"
)

printf 'Dependency provenance validation passed.\n'
