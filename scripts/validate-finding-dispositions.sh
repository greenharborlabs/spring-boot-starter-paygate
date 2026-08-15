#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEFAULT_LEDGER="$ROOT_DIR/specs/005-security-audit-hardening/finding-dispositions.md"

if (( $# > 1 )); then
  echo "Usage: scripts/validate-finding-dispositions.sh [ledger-path]" >&2
  exit 2
fi

LEDGER=${1:-$DEFAULT_LEDGER}
if [[ ! -f "$LEDGER" || ! -r "$LEDGER" ]]; then
  echo "Finding disposition ledger is missing or unreadable." >&2
  exit 1
fi

# The ledger is untrusted data. awk only parses its Markdown cells; it never
# evaluates them, passes them to a shell, or includes their contents in errors.
awk '
function trim(value) {
  sub(/^[[:space:]]+/, "", value)
  sub(/[[:space:]]+$/, "", value)
  return value
}

function canonical_header(value) {
  value = trim(value)
  return tolower(value)
}

function placeholder(value, normalized) {
  normalized = tolower(trim(value))
  gsub(/^[`*_]+|[`*_]+$/, "", normalized)
  normalized = trim(normalized)
  return normalized == "" || normalized == "-" || normalized == "—" ||
      normalized == "n/a" || normalized == "na" || normalized == "none" ||
      normalized == "not applicable" || normalized == "planned" ||
      normalized == "pending" || normalized == "tbd" || normalized == "todo"
}

function append_bounded(current, item, count, limit) {
  if (count > limit) {
    return current
  }
  return current (current == "" ? "" : ", ") item
}

function is_separator(cells, count, i, value) {
  for (i = 1; i <= count; i++) {
    value = trim(cells[i])
    if (value == "") {
      continue
    }
    if (value !~ /^:?-+:?$/) {
      return 0
    }
  }
  return 1
}

function mark_evidence(id, field, key) {
  key = id SUBSEP field
  if (!evidence_seen[key]++) {
    evidence_count++
    evidence_list = append_bounded(evidence_list, id " (" field ")", evidence_count, report_limit)
  }
}

function register_expected(id) {
  expected[id] = 1
  expected_order[++expected_count] = id
}

BEGIN {
  report_limit = 12
  for (i = 1; i <= 14; i++) register_expected("M-" i)
  for (i = 1; i <= 27; i++) register_expected("L-" i)
  for (i = 1; i <= 30; i++) register_expected(sprintf("INF-%02d", i))
}

{
  line = $0
  if (line !~ /^[[:space:]]*\|/) {
    if (in_table && data_started) in_table = 0
    next
  }

  cell_count = split(line, cells, "|")
  first = trim(cells[2])

  if (!header_seen && first == "Finding") {
    header_seen = 1
    in_table = 1
    for (i = 2; i < cell_count; i++) {
      header = canonical_header(cells[i])
      header_occurrences[header]++
      if (header == "finding") finding_column = i
      else if (header == "severity") severity_column = i
      else if (header == "disposition") disposition_column = i
      else if (header == "requirements") requirements_column = i
      else if (header == "implementation") implementation_column = i
      else if (header == "regression evidence") regression_column = i
      else if (header == "documentation evidence") documentation_column = i
      else if (header == "owner") owner_column = i
      else if (header == "status") status_column = i
      else if (header == "verification note") verification_column = i
    }
    next
  }

  if (!in_table || !header_seen || is_separator(cells, cell_count)) next
  data_started = 1

  id = trim(cells[finding_column])
  if (!(id in expected)) {
    if (id ~ /^(M|L|INF)-[0-9]+$/) {
      unexpected_count++
      unexpected_list = append_bounded(unexpected_list, id, unexpected_count, report_limit)
    } else {
      invalid_id_count++
      invalid_line_list = append_bounded(invalid_line_list, NR, invalid_id_count, report_limit)
    }
    next
  }

  row_count[id]++

  expected_severity = id ~ /^M-/ ? "Medium" : (id ~ /^L-/ ? "Low" : "Info")
  if (trim(cells[severity_column]) != expected_severity) {
    mark_evidence(id, "severity")
  }

  status = trim(cells[status_column])
  if (status != "verified" && !nonverified_seen[id]++) {
    nonverified_count++
    nonverified_list = append_bounded(nonverified_list, id, nonverified_count, report_limit)
  }

  disposition = trim(cells[disposition_column])
  if (disposition != "remediated" && disposition != "accepted-limitation") {
    mark_evidence(id, "disposition")
  }

  requirements = trim(cells[requirements_column])
  if (placeholder(requirements) || requirements !~ /FR-[0-9][0-9][0-9]/) {
    mark_evidence(id, "requirements")
  }

  implementation = trim(cells[implementation_column])
  implementation_na = tolower(implementation)
  gsub(/^[`*_]+|[`*_]+$/, "", implementation_na)
  implementation_na = trim(implementation_na)
  if (placeholder(implementation) &&
      !(disposition == "accepted-limitation" &&
        (implementation_na == "n/a" || implementation_na == "na" || implementation_na == "not applicable"))) {
    mark_evidence(id, "implementation")
  }

  if (placeholder(cells[regression_column])) {
    mark_evidence(id, "regression evidence")
  }

  documentation = trim(cells[documentation_column])
  if (placeholder(documentation)) {
    documentation_na = tolower(documentation)
    gsub(/^[`*_]+|[`*_]+$/, "", documentation_na)
    documentation_na = trim(documentation_na)
    if (disposition == "accepted-limitation" ||
        (documentation_na != "n/a" && documentation_na != "na" && documentation_na != "not applicable")) {
      mark_evidence(id, "documentation evidence")
    }
  }

  if (placeholder(cells[owner_column])) mark_evidence(id, "owner")
  if (placeholder(cells[verification_column])) mark_evidence(id, "verification note")
}

END {
  required_headers[1] = "finding"
  required_headers[2] = "severity"
  required_headers[3] = "disposition"
  required_headers[4] = "requirements"
  required_headers[5] = "implementation"
  required_headers[6] = "regression evidence"
  required_headers[7] = "documentation evidence"
  required_headers[8] = "owner"
  required_headers[9] = "status"
  required_headers[10] = "verification note"

  if (!header_seen) {
    schema_error = "contract table not found"
  } else {
    for (i = 1; i <= 10; i++) {
      header = required_headers[i]
      if (header_occurrences[header] != 1) {
        schema_issue_count++
        schema_issue_list = append_bounded(schema_issue_list, header, schema_issue_count, report_limit)
      }
    }
    if (schema_issue_count) schema_error = "missing or duplicate columns: " schema_issue_list
  }

  for (i = 1; i <= expected_count; i++) {
    id = expected_order[i]
    if (row_count[id] == 0) {
      missing_count++
      missing_list = append_bounded(missing_list, id, missing_count, report_limit)
    } else if (row_count[id] > 1) {
      duplicate_count++
      duplicate_list = append_bounded(duplicate_list, id " (x" row_count[id] ")", duplicate_count, report_limit)
    }
  }

  failed = schema_error != "" || missing_count || duplicate_count || unexpected_count ||
      invalid_id_count || nonverified_count || evidence_count
  if (!failed) {
    print "Finding disposition validation passed: " expected_count " verified findings."
    exit 0
  }

  print "Finding disposition validation failed:" > "/dev/stderr"
  if (schema_error != "") print "  - Invalid ledger schema: " schema_error "." > "/dev/stderr"
  if (missing_count) {
    suffix = missing_count > report_limit ? " (and " missing_count - report_limit " more)" : ""
    print "  - Missing expected findings: " missing_list suffix "." > "/dev/stderr"
  }
  if (duplicate_count) {
    suffix = duplicate_count > report_limit ? " (and " duplicate_count - report_limit " more)" : ""
    print "  - Duplicate findings: " duplicate_list suffix "." > "/dev/stderr"
  }
  if (unexpected_count) {
    suffix = unexpected_count > report_limit ? " (and " unexpected_count - report_limit " more)" : ""
    print "  - Unexpected finding IDs: " unexpected_list suffix "." > "/dev/stderr"
  }
  if (invalid_id_count) {
    suffix = invalid_id_count > report_limit ? " (and " invalid_id_count - report_limit " more)" : ""
    print "  - Invalid finding IDs on table lines: " invalid_line_list suffix "." > "/dev/stderr"
  }
  if (nonverified_count) {
    suffix = nonverified_count > report_limit ? " (and " nonverified_count - report_limit " more)" : ""
    print "  - Findings not exactly verified: " nonverified_list suffix "." > "/dev/stderr"
  }
  if (evidence_count) {
    suffix = evidence_count > report_limit ? " (and " evidence_count - report_limit " more)" : ""
    print "  - Missing or placeholder required content: " evidence_list suffix "." > "/dev/stderr"
  }
  exit 1
}
' "$LEDGER"
