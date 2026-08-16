#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
validator="$root/scripts/verify-example-artifact-safety.sh"
work="$(mktemp -d /tmp/paygate-artifact-safety-XXXXXX)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/src"
cp "$root/paygate-example-app/src/main/resources/application.yml" "$work/src/application.yml"
cp "$root/paygate-example-app/src/main/resources/application-dev.yml" "$work/src/application-dev.yml"
if ! "$validator" "$work/empty" "$work/src" >/dev/null; then echo 'safe controls rejected' >&2; exit 1; fi
for fixture in embedded-credential nonempty-secret management-exposure; do
  cp "$root/scripts/test-fixtures/example-artifact-safety/$fixture.yml" "$work/src/application.yml"
  if "$validator" "$work/empty" "$work/src" >/dev/null 2>&1; then echo "unsafe fixture accepted: $fixture" >&2; exit 1; fi
  cp "$root/paygate-example-app/src/main/resources/application.yml" "$work/src/application.yml"
done
echo 'Example artifact safety negative controls passed'
