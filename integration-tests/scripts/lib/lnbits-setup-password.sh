#!/usr/bin/env bash
# Shell helpers for the local LNbits bootstrap secret. They deliberately never print the secret.

lnbits_fail() {
  printf 'ERROR: %s\n' "$*" >&2
  return 1
}

lnbits_secret_file_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

lnbits_acquire_secret_lock() {
  local lock_directory="$1.lock"
  local lock_pid=''
  local attempts=0

  while ! mkdir "$lock_directory" 2>/dev/null; do
    if [[ -r "$lock_directory/pid" ]]; then
      read -r lock_pid < "$lock_directory/pid" || true
      if [[ "$lock_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$lock_pid" 2>/dev/null; then
        rm -f -- "$lock_directory/pid"
        rmdir -- "$lock_directory" 2>/dev/null || true
        continue
      fi
    fi
    attempts=$((attempts + 1))
    [[ "$attempts" -lt 200 ]] || lnbits_fail 'timed out waiting for the local LNbits setup-secret lock'
    sleep 0.05
  done
  printf '%s\n' "$$" > "$lock_directory/pid"
  LNBITS_SECRET_LOCK_DIRECTORY="$lock_directory"
}

lnbits_release_secret_lock() {
  [[ -n "${LNBITS_SECRET_LOCK_DIRECTORY:-}" ]] || return 0
  rm -f -- "$LNBITS_SECRET_LOCK_DIRECTORY/pid"
  rmdir -- "$LNBITS_SECRET_LOCK_DIRECTORY" 2>/dev/null || true
  unset LNBITS_SECRET_LOCK_DIRECTORY
}

lnbits_read_secret_password() {
  local secret_file="$1"
  LNBITS_SECRET_FILE="$secret_file" python3 -c '
import json
import os
from pathlib import Path

value = json.loads(Path(os.environ["LNBITS_SECRET_FILE"]).read_text(encoding="utf-8"))
password = value.get("password")
if not isinstance(password, str) or not password.strip():
    raise SystemExit(1)
print(password, end="")
'
}

lnbits_write_secret_password() {
  local secret_file="$1"
  local password="$2"
  local temporary_file

  temporary_file="$(mktemp "${secret_file}.tmp.XXXXXXXX")" || return 1
  if ! LNBITS_SECRET_PASSWORD="$password" python3 -c '
import json
import os
import sys

json.dump({"password": os.environ["LNBITS_SECRET_PASSWORD"]}, sys.stdout, separators=(",", ":"))
' > "$temporary_file"; then
    rm -f -- "$temporary_file"
    return 1
  fi
  chmod 600 "$temporary_file"
  mv -f -- "$temporary_file" "$secret_file"
}

# Resolves an explicit secret or an atomically persisted local secret. The result is assigned to
# LNBITS_RESOLVED_SETUP_PASSWORD; the helper itself never writes a secret to stdout or stderr.
resolve_lnbits_setup_password() {
  local secret_file="${LNBITS_SETUP_SECRET_FILE:?LNBITS_SETUP_SECRET_FILE must be set}"
  local secret_directory
  local generated_password

  if [[ -n "${LNBITS_SETUP_PASSWORD:-}" ]]; then
    LNBITS_RESOLVED_SETUP_PASSWORD="$LNBITS_SETUP_PASSWORD"
    return 0
  fi

  secret_directory="$(dirname "$secret_file")"
  mkdir -p "$secret_directory"
  lnbits_acquire_secret_lock "$secret_file" || return 1

  if [[ -f "$secret_file" ]]; then
    chmod 600 "$secret_file"
    if ! LNBITS_RESOLVED_SETUP_PASSWORD="$(lnbits_read_secret_password "$secret_file")"; then
      lnbits_release_secret_lock
      lnbits_fail 'local LNbits setup-secret state is invalid; reset the disposable stack or provide LNBITS_SETUP_PASSWORD'
      return 1
    fi
  else
    if ! generated_password="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32), end="")')"; then
      lnbits_release_secret_lock
      lnbits_fail 'could not generate the local LNbits setup secret'
      return 1
    fi
    if ! lnbits_write_secret_password "$secret_file" "$generated_password"; then
      lnbits_release_secret_lock
      lnbits_fail 'could not atomically persist the local LNbits setup secret'
      return 1
    fi
    LNBITS_RESOLVED_SETUP_PASSWORD="$generated_password"
  fi

  if [[ -z "${LNBITS_RESOLVED_SETUP_PASSWORD:-}" ]]; then
    lnbits_release_secret_lock
    lnbits_fail 'local LNbits setup secret must be nonblank'
    return 1
  fi
  if [[ "$(lnbits_secret_file_mode "$secret_file")" != "600" ]]; then
    lnbits_release_secret_lock
    lnbits_fail 'local LNbits setup secret must be owner-readable only'
    return 1
  fi
  lnbits_release_secret_lock
}
