#!/usr/bin/env bash
# Validates the Kimi Low finding and executed-evidence ledgers as untrusted bounded data.
set -euo pipefail
export LC_ALL=C

# GitHub-hosted runner images do not guarantee ripgrep. This validator uses only
# fixed-string searches, so grep is an equivalent fallback.
if ! command -v rg >/dev/null 2>&1; then
  rg() { grep "$@"; }
fi

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly DEFAULT_LEDGER="$ROOT/docs/security/KIMI-LOW-FINDING-DISPOSITIONS.md"
readonly DEFAULT_EVIDENCE="$ROOT/docs/security/KIMI-LOW-VALIDATION-EVIDENCE.md"
readonly MARKERS="$ROOT/paygate-integration-tests/src/securityTest/resources/low-security/marker-secrets.txt"

[[ $# -le 2 ]] || { echo 'Usage: validate-low-security-finding-dispositions.sh [ledger] [evidence]' >&2; exit 2; }
readonly LEDGER="${1:-$DEFAULT_LEDGER}"
readonly EVIDENCE="${2:-$DEFAULT_EVIDENCE}"
[[ -r "$LEDGER" && -r "$EVIDENCE" && -r "$MARKERS" ]] || {
  echo 'Kimi Low finding ledger, evidence ledger, or marker corpus is missing or unreadable.' >&2
  exit 1
}

# Marker values are only read to reject disclosures; neither this script nor awk prints them.
while IFS= read -r marker; do
  [[ -z "$marker" || "$marker" == \#* ]] && continue
  if rg -qF -- "$marker" "$LEDGER" "$EVIDENCE"; then
    echo 'Kimi Low finding disposition validation failed: a prohibited diagnostic marker was recorded.' >&2
    exit 1
  fi
done < "$MARKERS"

awk -v evidence="$EVIDENCE" '
function trim(value) { sub(/^[[:space:]]+/, "", value); sub(/[[:space:]]+$/, "", value); return value }
function lower(value) { return tolower(trim(value)) }
function placeholder(value, normalized) {
  normalized=lower(value); gsub(/^[`*_]+|[`*_]+$/, "", normalized); normalized=trim(normalized)
  return normalized=="" || normalized=="-" || normalized=="—" || normalized=="pending" || normalized=="tbd" || normalized=="todo" || normalized=="n/a" || normalized ~ /^planned([: ]|$)/
}
function required(row, name) { return !placeholder(row[column[name]]) }
function evidence_ids(finding, value, found) {
  value=trim(value)
  while (match(value, /LOW-EV-[A-Z0-9-]+/)) {
    cited[finding SUBSEP substr(value, RSTART, RLENGTH)]=1
    value=substr(value, RSTART + RLENGTH)
  }
}
function parse_evidence( line, cells, count, first, i, status, revision) {
  while ((getline line < evidence) > 0) {
    if (line !~ /^[[:space:]]*\|/) continue
    count=split(line, cells, "|"); first=trim(cells[2])
    if (first=="Evidence ID") {
      for (i=2; i<count; i++) evidence_column[lower(cells[i])]=i
      continue
    }
    if (!("evidence id" in evidence_column) || first ~ /^-+$/) continue
    if (count!=14) { invalid=1; continue }
    for (i=2; i<count; i++) if (length(cells[i])>512) invalid=1
    status=lower(cells[evidence_column["result"]]); revision=trim(cells[evidence_column["implementation revision"]])
    if (first !~ /^LOW-EV-[A-Z0-9-]+$/ || status!="passed" || trim(cells[evidence_column["exit/status"]])!="0" || revision !~ /^[0-9a-f]{40}$/ || placeholder(cells[evidence_column["artifact/report"]]) || placeholder(cells[evidence_column["redaction attestation"]])) {
      invalid=1
    } else current_evidence[first]=1
  }
  close(evidence)
}
BEGIN {
  for (i=1; i<=28; i++) expected["L-" i]=1
  parse_evidence()
}
/^[[:space:]]*\|/ {
  count=split($0, row, "|"); first=trim(row[2])
  if (first=="Finding") {
    header=1
    for (i=2; i<count; i++) column[lower(row[i])]=i
    next
  }
  if (!header || first ~ /^-+$/) next
  if (count!=19) { invalid=1; next }
  for (i=2; i<count; i++) if (length(row[i])>512) invalid=1
  if (!(first in expected)) { invalid=1; next }
  seen[first]++
  if (trim(row[column["severity"]])!="Low" || lower(row[column["disposition"]])!="remediated") invalid=1
  fields="rationale|requirements|implementation evidence|executed regression evidence|documentation evidence|compatibility impact|residual risk|owner|review trigger"
  split(fields, names, "|")
  for (i in names) if (!required(row, names[i])) invalid=1
  if (row[column["requirements"]] !~ /FR-[0-9][0-9][0-9]/ || lower(row[column["implementation evidence"]]) ~ /planned/) invalid=1
  status=lower(row[column["status"]]); approval=lower(row[column["approval status"]])
  if (status!="planned" && status!="implemented" && status!="verified" && status!="approved") invalid=1
  if (approval!="pending" && approval!="approved") invalid=1
  evidence_ids(first, row[column["executed regression evidence"]])
  if (status=="verified" || status=="approved") needs_evidence[first]=1
  if (approval=="approved") {
    if (status!="approved" || !required(row, "reviewed by") || !required(row, "reviewed at") || !required(row, "approval evidence")) invalid=1
    evidence_ids(first, row[column["approval evidence"]])
  } else if (!placeholder(row[column["reviewed by"]]) || !placeholder(row[column["reviewed at"]]) || !placeholder(row[column["approval evidence"]])) invalid=1
}
END {
  fields="finding|severity|disposition|rationale|requirements|implementation evidence|executed regression evidence|documentation evidence|compatibility impact|residual risk|owner|status|approval status|reviewed by|reviewed at|approval evidence|review trigger"
  split(fields, names, "|")
  if (!header) invalid=1
  for (i in names) if (!(names[i] in column)) invalid=1
  for (id in expected) if (seen[id]!=1) invalid=1
  for (id in needs_evidence) {
    found=0
    for (key in cited) {
      split(key, parts, SUBSEP)
      if (parts[1]==id) {
        found=1
        if (!current_evidence[parts[2]]) invalid=1
      }
    }
    if (!found) invalid=1
  }
  if (invalid) { print "Kimi Low finding disposition validation failed." > "/dev/stderr"; exit 1 }
  print "Kimi Low finding disposition validation passed: 28 accountable Low findings."
}
' "$LEDGER"
