#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly VALIDATOR="$ROOT/scripts/validate-container-scan-report.py"
readonly FIXTURES="$ROOT/scripts/test-fixtures/container-scan"
readonly IMAGE='lnbits/lnbits@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

passes() {
  python3 "$VALIDATOR" --image "$IMAGE" "$FIXTURES/$1.json" >/dev/null || {
    printf 'Expected %s to pass\n' "$1" >&2
    exit 1
  }
}

fails() {
  if python3 "$VALIDATOR" --image "$IMAGE" "$FIXTURES/$1.json" >/dev/null 2>&1; then
    printf 'Expected %s to fail\n' "$1" >&2
    exit 1
  fi
}

passes clean
unfixed_output="$(python3 "$VALIDATOR" --image "$IMAGE" "$FIXTURES/unfixed-high.json")" || {
  printf 'Expected unfixed-high to pass\n' >&2
  exit 1
}
[[ "$unfixed_output" == *'1 unfixed, 0 fixable HIGH/CRITICAL'* ]] || {
  printf 'Expected unfixed finding to be reported\n' >&2
  exit 1
}
fails fixable-high
fails fixable-critical
fails malformed
fails empty
fails incomplete
fails partial
if python3 "$VALIDATOR" --image "$IMAGE" "$FIXTURES/missing.json" >/dev/null 2>&1; then
  printf 'Expected missing report to fail\n' >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf -- "$tmpdir"' EXIT
python3 - "$FIXTURES/clean.json" "$FIXTURES/fixable-high.json" "$tmpdir" <<'PY'
import json
import sys
from pathlib import Path

clean = json.loads(Path(sys.argv[1]).read_text())
high = json.loads(Path(sys.argv[2]).read_text())
out = Path(sys.argv[3])
cases = {
    "empty-results": (clean, lambda x: x.update(Results=[])),
    "wrong-reference": (clean, lambda x: x["Metadata"].update(Reference="lnbits/lnbits@sha256:" + "c" * 64)),
    "missing-severity": (high, lambda x: x["Results"][0]["Vulnerabilities"][0].pop("Severity")),
    "invalid-fixed-version": (high, lambda x: x["Results"][0]["Vulnerabilities"][0].update(FixedVersion=None)),
    "missing-package-inventory": (clean, lambda x: x["Results"][0].pop("Packages")),
    "missing-os-inventory": (clean, lambda x: x["Results"].pop(0)),
    "empty-python-inventory": (clean, lambda x: x["Results"][1].update(Packages=[])),
    "invalid-package": (clean, lambda x: x["Results"][1]["Packages"][0].pop("Version")),
}
for name, (source, mutate) in cases.items():
    document = json.loads(json.dumps(source))
    mutate(document)
    (out / (name + ".json")).write_text(json.dumps(document))
(out / "nonfinite.json").write_text(json.dumps(clean)[:-1] + ',"unexpected":NaN}')
PY
for case in empty-results wrong-reference missing-severity invalid-fixed-version missing-package-inventory missing-os-inventory empty-python-inventory invalid-package nonfinite; do
  if python3 "$VALIDATOR" --image "$IMAGE" "$tmpdir/$case.json" >/dev/null 2>&1; then
    printf 'Expected %s to fail\n' "$case" >&2
    exit 1
  fi
done
printf 'Container scan report negative controls passed.\n'
