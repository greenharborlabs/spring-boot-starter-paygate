#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
artifact="${1:-$root/paygate-example-app/build/libs}"
source_root="${2:-$root/paygate-example-app/src/main/resources}"
sources=("$source_root/application.yml" "$source_root/application-dev.yml")
for source in "${sources[@]}"; do
  [[ -r "$source" ]] || { echo 'example source configuration missing' >&2; exit 1; }
  if grep -Eiq '(api[-_]?key|secret|macaroon|preimage):[[:space:]]*[^${[:space:]][^[:space:]]+' "$source"; then
    echo 'example source contains a nonempty secret default' >&2; exit 1
  fi
  if grep -Eiq 'management:|actuator:' "$source"; then echo 'example source enables management exposure' >&2; exit 1; fi
done
for jar in "$artifact"/*.jar; do
  [[ -e "$jar" ]] || continue
  if unzip -p "$jar" BOOT-INF/classes/application.yml BOOT-INF/classes/application-dev.yml 2>/dev/null | grep -Eiq '(adminkey|macaroon|preimage|api[-_]?key:[[:space:]]*[A-Za-z0-9]{8,})'; then
    echo 'built example artifact contains credential material' >&2; exit 1
  fi
done
echo 'Example artifact safety validation passed'
