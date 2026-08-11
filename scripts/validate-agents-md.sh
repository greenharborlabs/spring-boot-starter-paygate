#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
AGENTS_FILE="$ROOT_DIR/AGENTS.md"

if [[ ! -f "$AGENTS_FILE" ]]; then
  echo "AGENTS.md not found at $AGENTS_FILE" >&2
  exit 1
fi

# AGENTS.md is documentation, not a shell script.  Keep its command grammar
# deliberately small and turn accepted tokens into argv entries ourselves.
# In particular, do not use eval, source, command substitution, or sh -c here.
gradle_commands=()
compose_files=()

reject() {
  echo "Unsafe or unsupported AGENTS.md command: $1" >&2
  exit 1
}

trim() {
  local value=$1
  value="${value#"${value%%[![:space:]]*}"}"
  printf '%s' "${value%"${value##*[![:space:]]}"}"
}

parse_gradle_command() {
  local line=$1 remainder token normalized
  local -a raw_tokens argv
  local has_task=false has_test_selector=false expect_test_selector=false

  remainder=${line#./gradlew}
  [[ "$remainder" != "$line" && "$remainder" =~ ^[[:space:]]+ ]] || reject "$line"
  remainder=$(trim "$remainder")
  [[ -n "$remainder" ]] || reject "$line"

  # Reject shell syntax before tokenizing. Quoted arguments are supported only
  # when the complete argument has no whitespace, which covers the documented
  # Gradle test selectors without needing a shell lexer.
  [[ "$remainder" =~ [\$\`\\\;\&\|\>\<\(\)\{\}\[\]\!\'] ]] && reject "$line"
  read -r -a raw_tokens <<< "$remainder"
  [[ ${#raw_tokens[@]} -gt 0 ]] || reject "$line"

  argv=("$ROOT_DIR/gradlew")
  for token in "${raw_tokens[@]}"; do
    normalized=$token
    if [[ "$token" == \"* ]]; then
      [[ "$token" == *\" && ${#token} -gt 1 ]] || reject "$line"
      normalized=${token:1:${#token}-2}
    fi
    [[ "$normalized" != *\"* ]] || reject "$line"
    [[ "$normalized" =~ ^[A-Za-z0-9:._*=/+-]+$ ]] || reject "$line"

    if [[ "$expect_test_selector" == true ]]; then
      [[ "$normalized" =~ ^[A-Za-z0-9_.:*+-]+$ ]] || reject "$line"
      expect_test_selector=false
      has_test_selector=true
    elif [[ "$normalized" == "--tests" ]]; then
      expect_test_selector=true
    elif [[ "$normalized" == "--info" || "$normalized" == "--stacktrace" ]]; then
      :
    elif [[ "$normalized" =~ ^-P[A-Za-z][A-Za-z0-9_.-]*(=[A-Za-z0-9_.-]+)?$ ]]; then
      :
    elif [[ "$normalized" =~ ^(:[A-Za-z][A-Za-z0-9_.-]*)+$ || "$normalized" =~ ^[A-Za-z][A-Za-z0-9_.-]*$ ]]; then
      has_task=true
    else
      reject "$line"
    fi
    argv+=("$normalized")
  done
  [[ "$expect_test_selector" == false && "$has_task" == true ]] || reject "$line"

  if [[ "$has_test_selector" == true ]]; then
    argv+=("--test-dry-run")
  else
    argv+=("--dry-run")
  fi
  argv+=("--console=plain" "--warning-mode=none")

  # Safe tokens above contain neither a newline nor the separator.
  gradle_commands+=("$(IFS='|'; printf '%s' "${argv[*]}")")
}

parse_compose_command() {
  local line=$1 remainder compose_file
  local -a tokens

  remainder=${line#cd integration-tests && docker-compose}
  [[ "$remainder" != "$line" ]] || reject "$line"
  [[ "$remainder" =~ ^[[:space:]]+ ]] || reject "$line"
  [[ ! "$remainder" =~ [\$\`\\\;\&\|\>\<\(\)\{\}\[\]\!\'\"] ]] || reject "$line"
  read -r -a tokens <<< "$(trim "$remainder")"

  if [[ ${#tokens[@]} -eq 1 && "${tokens[0]}" == "up" ]]; then
    compose_file="docker-compose.yml"
  elif [[ ${#tokens[@]} -eq 4 && "${tokens[0]}" == "-f" && "${tokens[2]}" == "up" && "${tokens[3]}" == "--build" ]]; then
    compose_file=${tokens[1]}
    [[ "$compose_file" =~ ^docker-compose-[A-Za-z0-9_.-]+\.yml$ ]] || reject "$line"
  else
    reject "$line"
  fi
  compose_files+=("$compose_file")
}

# First parse every recognized command. This prevents a later hostile line from
# allowing an earlier, otherwise valid command to be executed first.
while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  line=$(trim "$raw_line")
  # Documentation examples may have an explanatory comment after a command.
  # A comment starts only after whitespace; '#' is not otherwise command data.
  if [[ "$line" =~ ^(.*)[[:space:]]# ]]; then
    line=$(trim "${BASH_REMATCH[1]}")
  fi
  case "$line" in
    ./gradlew*) parse_gradle_command "$line" ;;
    'cd integration-tests && docker-compose'*) parse_compose_command "$line" ;;
  esac
done < "$AGENTS_FILE"

[[ ${#gradle_commands[@]} -gt 0 ]] || { echo "No ./gradlew commands found in AGENTS.md" >&2; exit 1; }
[[ ${#compose_files[@]} -gt 0 ]] || { echo "No integration docker-compose command found in AGENTS.md" >&2; exit 1; }
[[ -d "$ROOT_DIR/integration-tests" ]] || { echo "integration-tests directory referenced by AGENTS.md does not exist" >&2; exit 1; }

for compose_file in "${compose_files[@]}"; do
  [[ -f "$ROOT_DIR/integration-tests/$compose_file" ]] || {
    echo "Compose file integration-tests/$compose_file referenced by AGENTS.md does not exist" >&2
    exit 1
  }
done

echo "Validating ${#gradle_commands[@]} Gradle commands in AGENTS.md..."
for serialized_argv in "${gradle_commands[@]}"; do
  IFS='|' read -r -a argv <<< "$serialized_argv"
  printf '  -'
  printf ' %q' "${argv[@]}"
  printf '\n'
  (
    cd "$ROOT_DIR"
    "${argv[@]}" >/dev/null
  )
done

echo "AGENTS.md validation passed."
