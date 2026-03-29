#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENTS_FILE="$ROOT_DIR/AGENTS.md"

if [[ ! -f "$AGENTS_FILE" ]]; then
  echo "AGENTS.md not found at $AGENTS_FILE"
  exit 1
fi

gradle_commands=()
while IFS= read -r cmd; do
  gradle_commands+=("$cmd")
done < <(
  sed -nE 's/^[[:space:]]*(\.\/gradlew[^#]*).*/\1/p' "$AGENTS_FILE" \
    | sed -E 's/[[:space:]]+$//' \
    | awk 'NF' \
    | sort -u
)

if [[ ${#gradle_commands[@]} -eq 0 ]]; then
  echo "No ./gradlew commands found in AGENTS.md"
  exit 1
fi

echo "Validating ${#gradle_commands[@]} Gradle commands in AGENTS.md..."
for cmd in "${gradle_commands[@]}"; do
  gradle_validation_flag="--dry-run"
  if [[ "$cmd" == *"--tests "* ]]; then
    gradle_validation_flag="--test-dry-run"
  fi

  echo "  - $cmd $gradle_validation_flag"
  (
    cd "$ROOT_DIR"
    bash -o pipefail -c "$cmd $gradle_validation_flag --console=plain --warning-mode=none >/dev/null"
  )
done

integration_commands=()
while IFS= read -r cmd; do
  integration_commands+=("$cmd")
done < <(
  sed -nE 's/^[[:space:]]*(cd integration-tests && docker-compose[^#]*).*/\1/p' "$AGENTS_FILE" \
    | sed -E 's/[[:space:]]+$//' \
    | awk 'NF'
)

if [[ ${#integration_commands[@]} -eq 0 ]]; then
  echo "No integration docker-compose command found in AGENTS.md"
  exit 1
fi

if [[ ! -d "$ROOT_DIR/integration-tests" ]]; then
  echo "integration-tests directory referenced by AGENTS.md does not exist"
  exit 1
fi

for integration_cmd in "${integration_commands[@]}"; do
  compose_file="$(echo "$integration_cmd" | sed -nE 's/.*-f[[:space:]]+([^[:space:]]+).*/\1/p')"

  if [[ -n "$compose_file" ]]; then
    if [[ ! -f "$ROOT_DIR/integration-tests/$compose_file" ]]; then
      echo "Compose file integration-tests/$compose_file referenced by AGENTS.md does not exist"
      exit 1
    fi
  elif [[ ! -f "$ROOT_DIR/integration-tests/docker-compose.yml" ]]; then
    echo "integration-tests/docker-compose.yml referenced by AGENTS.md does not exist"
    exit 1
  fi
done

echo "AGENTS.md validation passed."
