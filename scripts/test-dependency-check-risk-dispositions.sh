#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
validator="$root/scripts/validate-dependency-check-risk-dispositions.sh"
fixtures="$root/scripts/test-fixtures/dependency-check-risk"
export VALIDATION_DATE="${VALIDATION_DATE:-2026-09-20}"

expect_failure() {
  local label="$1" expected="$2"
  shift 2
  local output
  if output="$("$@" 2>&1)"; then
    echo "unsafe dependency risk fixture accepted: $label" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "dependency risk fixture $label failed for the wrong reason: $output" >&2
    exit 1
  fi
}

"$validator" "$fixtures/valid.xml" "$fixtures/valid.md" >/dev/null
for case in \
  'missing-approval:missing Approval' \
  'broad:broad or mismatched package URL' \
  'unmatched:no matching risk record' \
  'unused:orphan risk record' \
  'expired:expired until deadline 2026-09-15' \
  'absent-controls:missing Compensating controls' \
  'stale-scanner:stale Scanner evidence' \
  'malformed-xml:malformed suppression XML' \
  'malformed-markdown:malformed advisory heading' \
  'duplicate-record:duplicate advisory record' \
  'invalid-date:until is not a valid calendar date'; do
  fixture="${case%%:*}"
  reason="${case#*:}"
  expect_failure "$fixture" "$reason" "$validator" "$fixtures/$fixture.xml" "$fixtures/$fixture.md"
done
expect_failure missing-file 'cannot read' "$validator" "$fixtures/nonexistent.xml" "$fixtures/valid.md"
expect_failure missing-records 'cannot read' "$validator" "$fixtures/valid.xml" "$fixtures/nonexistent.md"
expect_failure missing-plugin-version 'Dependency-Check plugin version' \
  python3 "$root/scripts/validate-dependency-check-risk-dispositions.py" \
  "$fixtures/valid.xml" "$fixtures/valid.md" /dev/null "$root/gradle.properties"
for value in 2026-9-20 2026-02-30 2026-09-20Z; do
  expect_failure "VALIDATION_DATE=$value" 'VALIDATION_DATE' \
    env VALIDATION_DATE="$value" "$validator" "$fixtures/valid.xml" "$fixtures/valid.md"
done

python3 - "$root/build.gradle.kts" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text(encoding="utf-8")
required = (
    "dependsOn(validateDependencyCheckRiskDispositions)",
    'dependsOn("verifyDependencyCheckRiskNegativeControls")',
)

def section(source, task):
    match = re.search(r'tasks\.register\("' + task + r'"\) \{(.*?)\n\}',
                      source, re.DOTALL)
    return match.group(1).splitlines() if match else []

def check_graph(source):
    lines = section(source, "check")
    return all("    " + edge in lines for edge in required)

if not check_graph(text):
    raise SystemExit("root check is missing a direct dependency risk policy task")
if not all("    " + edge in section(text, "releaseReadiness") for edge in required):
    raise SystemExit("releaseReadiness is missing an explicit dependency risk policy task")
for edge in required:
    altered = text.replace("    " + edge + "\n", "", 1)
    if check_graph(altered):
        raise SystemExit(f"task-graph negative control failed: {edge}")
PY
echo 'Dependency-check risk negative controls passed'
