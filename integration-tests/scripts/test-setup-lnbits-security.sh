#!/usr/bin/env bash
# Exercises generated LNbits first-install secret handling without Docker or a real credential.
set -euo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly LIBRARY="$SCRIPT_DIRECTORY/lib/lnbits-setup-password.sh"
workspace=''

fail() { printf 'LNbits setup-secret test failed: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -n "$workspace" && -f "$workspace/.owned" ]] && rm -rf -- "$workspace"; }
trap cleanup EXIT
mode() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }
resolve() {
  unset LNBITS_SETUP_PASSWORD LNBITS_RESOLVED_SETUP_PASSWORD
  LNBITS_SETUP_SECRET_FILE="$1"
  resolve_lnbits_setup_password
  printf '%s' "$LNBITS_RESOLVED_SETUP_PASSWORD"
}

[[ -r "$LIBRARY" ]] || fail 'LNbits secret helper is missing'
workspace="$(mktemp -d "${TMPDIR:-/tmp}/lnbits-setup-secret-XXXXXXXX")"
: > "$workspace/.owned"
# shellcheck source=lib/lnbits-setup-password.sh
. "$LIBRARY"

secret_file="$workspace/secret.json"
first="$(resolve "$secret_file")"
[[ ${#first} -ge 43 ]] || fail 'fresh secret is not at least 256 bits of token material'
[[ "$(mode "$secret_file")" == 600 ]] || fail 'fresh secret file is not mode 0600'
python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); assert value["password"]' "$secret_file" \
  || fail 'secret state is not safe JSON'
second="$(resolve "$secret_file")"
[[ "$first" == "$second" ]] || fail 'persisted environment did not reuse one secret'

printf '{"password":"linked-secret"}' > "$workspace/link-target.json"
chmod 640 "$workspace/link-target.json"
ln -s "$workspace/link-target.json" "$workspace/linked.json"
if LNBITS_SETUP_SECRET_FILE="$workspace/linked.json" bash -c '
  source "$1"
  resolve_lnbits_setup_password
' -- "$LIBRARY" >/dev/null 2>&1; then
  fail 'symbolic-link setup-secret state was accepted'
fi
[[ "$(mode "$workspace/link-target.json")" == 640 ]] \
  || fail 'symbolic-link rejection changed the target permissions'

printf '{"password":"interrupted"' > "$workspace/interrupted.json.tmp"
third="$(resolve "$workspace/interrupted.json")"
[[ -n "$third" && "$third" != interrupted ]] || fail 'interrupted temporary state was accepted'

explicit_output="$(LNBITS_SETUP_PASSWORD='explicit-secret' LNBITS_SETUP_SECRET_FILE="$workspace/explicit.json" bash -c '
  source "$1"
  resolve_lnbits_setup_password
  test "$LNBITS_RESOLVED_SETUP_PASSWORD" = explicit-secret
' -- "$LIBRARY")"
[[ -z "$explicit_output" && ! -e "$workspace/explicit.json" ]] || fail 'explicit secret was printed or persisted'

concurrent_file="$workspace/concurrent.json"
for _ in 1 2; do
  LNBITS_SETUP_SECRET_FILE="$concurrent_file" bash -c '
    source "$1"
    resolve_lnbits_setup_password
    printf "%s" "$LNBITS_RESOLVED_SETUP_PASSWORD" > "$2"
  ' -- "$LIBRARY" "$workspace/result.$_" &
done
wait
[[ "$(cat "$workspace/result.1")" == "$(cat "$workspace/result.2")" ]] \
  || fail 'concurrent setup did not converge on one secret'

printf 'LNbits setup-secret controls passed.\n'
