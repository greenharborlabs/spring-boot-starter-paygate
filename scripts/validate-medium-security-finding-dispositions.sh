#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ledger="${1:-$root/docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md}"
evidence="${2:-$root/specs/007-medium-security-remediation/validation-evidence.md}"
[[ $# -le 2 && -r "$ledger" && -r "$evidence" ]] || {
  echo 'Kimi Medium finding ledger or validation evidence is missing or unreadable.' >&2
  exit 1
}

# Markdown cells are treated strictly as data. The validator neither evaluates nor emits their
# content, because finding ledgers may contain untrusted prose and paths.
awk -v evidence="$evidence" '
function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
function lower(s) { return tolower(trim(s)) }
function placeholder(s) { s=lower(s); return s=="" || s=="-" || s=="—" || s=="pending" || s=="tbd" || s=="todo" || s=="n/a" || s ~ /^planned[: ]/ }
function cell(row, name) { return row[column[name]] }
function evidenceIsCurrent(s, line) {
  if (placeholder(s) || lower(s) ~ /(planned|skipped|failed|stale)/ || s !~ /validation-evidence[.]md/) return 0
  while ((getline line < evidence) > 0) {
    if (line ~ /^Exit status:[[:space:]]*0[[:space:]]*$/) success=1
  }
  close(evidence)
  return success
}
BEGIN { split("M-1 M-2 M-3 M-4", ids, " "); for (i in ids) expected[ids[i]]=1 }
/^[[:space:]]*\|/ {
  count=split($0, fields, "|"); first=trim(fields[2])
  if (first=="Finding") {
    header=1
    for (i=2; i<count; i++) column[lower(fields[i])]=i
    next
  }
  if (!header || first ~ /^-+$/) next
  if (!(first in expected)) { invalid=1; next }
  seen[first]++
  if (trim(cell(fields, "severity")) != "Medium" || lower(cell(fields, "disposition")) != "remediated") invalid=1
  required="rationale|requirements|implementation evidence|regression evidence|documentation evidence|residual risk|owner|review trigger"
  split(required, names, "\\|")
  for (i in names) if (placeholder(cell(fields, names[i]))) invalid=1
  if (cell(fields, "requirements") !~ /FR-[0-9][0-9][0-9]/) invalid=1
  status=lower(cell(fields, "status")); approval=lower(cell(fields, "approval status"))
  if (status != "planned" && status != "implemented" && status != "verified") invalid=1
  if (approval != "pending" && approval != "approved") invalid=1
  if (status == "implemented" && (placeholder(cell(fields, "implementation evidence")) || placeholder(cell(fields, "regression evidence")))) invalid=1
  if (status == "verified" && !evidenceIsCurrent(cell(fields, "regression evidence"))) invalid=1
  if (approval == "approved") {
    if (status != "verified" || placeholder(cell(fields, "approved by")) || placeholder(cell(fields, "approved at")) || !evidenceIsCurrent(cell(fields, "approval evidence"))) invalid=1
    approvals[lower(cell(fields, "approved by")) SUBSEP trim(cell(fields, "approved at"))]++
  } else if (!placeholder(cell(fields, "approved by")) || !placeholder(cell(fields, "approved at")) || !placeholder(cell(fields, "approval evidence"))) invalid=1
}
END {
  required="finding|severity|disposition|rationale|requirements|implementation evidence|regression evidence|documentation evidence|residual risk|owner|status|approval status|approved by|approved at|approval evidence|review trigger"
  split(required, names, "\\|")
  if (!header) invalid=1
  for (i in ids) if (seen[ids[i]] != 1) invalid=1
  for (key in approvals) if (approvals[key] > 1) invalid=1
  if (invalid) { print "Kimi Medium finding disposition validation failed." > "/dev/stderr"; exit 1 }
  print "Kimi Medium finding disposition validation passed: 4 accountable findings."
}
' "$ledger"
