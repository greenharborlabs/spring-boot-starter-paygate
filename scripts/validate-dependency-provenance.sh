#!/usr/bin/env bash
# Validates the reviewed, declarative Gradle dependency-provenance inputs. This validator never
# resolves dependencies and never evaluates metadata, exception, or keyring content as commands.
set -euo pipefail
export LC_ALL=C

# GitHub-hosted runner images do not guarantee ripgrep. The expressions in this
# validator are portable extended regular expressions, so grep is an equivalent fallback.
if ! command -v rg >/dev/null 2>&1; then
  rg() { grep -E "$@"; }
fi

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
command -v gpg >/dev/null 2>&1 || fail 'gpg is required to inspect the committed keyring'

keyring_fingerprints="$(gpg --show-keys --with-colons "$KEYRING" 2>/dev/null | awk -F: '$1 == "fpr" { print $10 }' | sort -u)"
missing_trusted_keys="$(
  comm -23 \
    <(sed -n 's/.*<trusted-key id="\([0-9A-F]\{40\}\)".*/\1/p' "$METADATA" | sort -u) \
    <(printf '%s\n' "$keyring_fingerprints")
)"
[[ -z "$missing_trusted_keys" ]] || fail "trusted metadata keys are missing from the committed keyring: $missing_trusted_keys"

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
    if ($7 !~ /^https:\/\// || $7 ~ /\/(cache|\.gradle)\//) fail("exception source is not authoritative")
    if ($10 !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/ || $10 < today) fail("exception review is expired")
    coordinate = $2 ":" $3 ":" $4 ":" $5
    if (seenId[$1]++ || seenCoordinate[coordinate]++) fail("exception is duplicate")
  }
' "$EXCEPTIONS"

while IFS=$'\t' read -r exception_id group module version filename checksum source _; do
  [[ "$exception_id" == \#* || -z "$exception_id" ]] && continue
  # Guava's module metadata redirects a -android component to its -jre jar, which is published
  # only under the -jre version. This is the single accepted source/version difference.
  source_version="$version"
  if [[ "$version" == *-android && "$filename" == "$module-${version%-android}-jre."* ]]; then
    source_version="${version%-android}-jre"
  fi
  source_path="${group//.//}/$module/$source_version/$filename"
  if [[ "$source" != "https://repo1.maven.org/maven2/$source_path" &&
        "$source" != "https://plugins.gradle.org/m2/$source_path" ]]; then
    fail "exception $exception_id source does not match its exact coordinate"
  fi
done < "$EXCEPTIONS"

# Every artifact Gradle can accept on its checksum alone must have an exact, checksum-pinned
# exception. An artifact needs one when its checksum carries any reason annotation, or when no
# non-ignored key covers it: neither a trusted-key scope matching its exact coordinates nor an
# artifact pin whose key is in the committed keyring. Signature coverage that cannot be decided
# from the metadata (a covering scope whose signer is a different key) must be annotated with a
# reason. Every exception must also name one exact metadata artifact and its checksum.
awk -F '\t' -v keyring="$(printf '%s' "$keyring_fingerprints" | tr '\n' ' ')" '
  function fail(message) { print "dependency provenance validation failed: " message > "/dev/stderr"; failed = 1; exit 1 }
  function attr(line, name,    pattern) {
    pattern = " " name "=\"[^\"]*\""
    if (!match(line, pattern)) return "\001"
    return substr(line, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
  }
  function isIgnored(id,    i) {
    id = toupper(id)
    for (i in ignoredIds) if (length(id) >= length(i) && substr(id, length(id) - length(i) + 1) == i) return 1
    for (i in artifactIgnoredIds) if (length(id) >= length(i) && substr(id, length(id) - length(i) + 1) == i) return 1
    return 0
  }
  # Gradle evaluates scope regexes as Java patterns. Only its generated group-prefix shape is
  # evaluated here; any other regex is treated as not covering, so the artifact needs an exception.
  function scopeMatches(pattern, value, regex,    literal) {
    if (pattern == "\001") return 1
    if (!regex) return pattern == value
    if (pattern !~ /^\^[A-Za-z0-9_-]+(\[\.\][A-Za-z0-9_-]+)*\(\$\|\(\[\.\]\.\*\)\)$/) return 0
    literal = substr(pattern, 2, length(pattern) - 12)
    gsub(/\[\.\]/, ".", literal)
    return value == literal || index(value, literal ".") == 1
  }
  function addScope(line, key) {
    scopes++
    scopeKey[scopes] = key
    scopeGroup[scopes] = attr(line, "group")
    scopeName[scopes] = attr(line, "name")
    scopeVersion[scopes] = attr(line, "version")
    scopeFile[scopes] = attr(line, "file")
    scopeRegex[scopes] = attr(line, "regex") == "true"
  }
  function closeArtifact(    covered, s, p, key) {
    if (checksum == "") return
    covered = 0
    for (s = 1; s <= scopes && !covered; s++) {
      if (scopeGroup[s] == "\001" || isIgnored(scopeKey[s])) continue
      if (scopeMatches(scopeGroup[s], group, scopeRegex[s]) && scopeMatches(scopeName[s], module, scopeRegex[s]) &&
          scopeMatches(scopeVersion[s], version, scopeRegex[s]) && scopeMatches(scopeFile[s], filename, scopeRegex[s])) covered = 1
    }
    for (p = 1; p <= pins && !covered; p++) {
      if (pin[p] ~ /^[0-9A-F]{40}$/ && (pin[p] in keyringIds) && !isIgnored(pin[p])) covered = 1
    }
    key = group SUBSEP module SUBSEP version SUBSEP filename SUBSEP checksum
    artifacts[key] = 1
    if ((annotated || !covered) && !(key in ledger)) {
      fail("checksum-only artifact lacks an exact exception for " group ":" module ":" version ":" filename)
    }
  }
  BEGIN { n = split(keyring, ids, " "); for (i = 1; i <= n; i++) if (ids[i] != "") keyringIds[ids[i]] = 1 }
  FNR == NR {
    if ($0 !~ /^#/ && $0 !~ /^[[:space:]]*$/) { ledger[$2 SUBSEP $3 SUBSEP $4 SUBSEP $5 SUBSEP $6] = $1 }
    next
  }
  /<components>/ { inComponents = 1 }
  !inComponents && /<ignored-key / { ignoredIds[toupper(attr($0, "id"))] = 1 }
  !inComponents && /<trusted-key / {
    currentKey = toupper(attr($0, "id"))
    if ($0 ~ /\/>[[:space:]]*$/) addScope($0, currentKey)
  }
  !inComponents && /<trusting / { addScope($0, currentKey) }
  inComponents && /<component / { group = attr($0, "group"); module = attr($0, "name"); version = attr($0, "version") }
  inComponents && /<artifact / {
    filename = attr($0, "name"); checksum = ""; annotated = 0; pins = 0
    split("", pin); split("", artifactIgnoredIds)
  }
  inComponents && /<ignored-key / { artifactIgnoredIds[toupper(attr($0, "id"))] = 1 }
  inComponents && /<pgp / { pin[++pins] = toupper(attr($0, "value")) }
  inComponents && /<sha256 / { checksum = attr($0, "value"); if (attr($0, "reason") != "\001") annotated = 1 }
  inComponents && /<\/artifact>/ { closeArtifact() }
  END {
    if (failed) exit 1
    if (!inComponents) fail("verification metadata has no components")
    for (key in ledger) {
      if (!(key in artifacts)) fail("exception " ledger[key] " does not name an exact metadata artifact and checksum")
    }
  }
' "$EXCEPTIONS" "$METADATA"

printf 'Dependency provenance validation passed.\n'
