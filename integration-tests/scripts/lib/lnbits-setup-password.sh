#!/usr/bin/env bash
# Shell helpers for the local LNbits bootstrap secret. They deliberately never print the secret.

lnbits_fail() {
  printf 'ERROR: %s\n' "$*" >&2
  return 1
}

lnbits_secret_file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

lnbits_read_secret_password() {
  local secret_file="$1"
  LNBITS_SECRET_FILE="$secret_file" python3 -c '
import json
import os
import stat
from pathlib import Path

path = Path(os.environ["LNBITS_SECRET_FILE"])
flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(path, flags)
try:
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(1)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, encoding="utf-8", closefd=False) as stream:
        value = json.load(stream)
finally:
    os.close(descriptor)
password = value.get("password")
if not isinstance(password, str) or not password.strip():
    raise SystemExit(1)
print(password, end="")
'
}

# Publishes a complete owner-only secret file only if no state exists yet. link(2) refuses to
# replace any existing entry, so concurrent first runs converge on whichever file landed first.
lnbits_publish_secret_password() {
  local secret_file="$1"
  local password="$2"
  local temporary_file
  local status=0

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
  if ! chmod 600 "$temporary_file"; then
    rm -f -- "$temporary_file"
    return 1
  fi
  LNBITS_SECRET_TEMPORARY_FILE="$temporary_file" LNBITS_SECRET_FILE="$secret_file" python3 -c '
import os

try:
    os.link(os.environ["LNBITS_SECRET_TEMPORARY_FILE"], os.environ["LNBITS_SECRET_FILE"])
except FileExistsError:
    pass
' || status=1
  rm -f -- "$temporary_file"
  return "$status"
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

  if [[ ! -e "$secret_file" && ! -L "$secret_file" ]]; then
    if ! generated_password="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32), end="")')"; then
      lnbits_fail 'could not generate the local LNbits setup secret'
      return 1
    fi
    if ! lnbits_publish_secret_password "$secret_file" "$generated_password"; then
      lnbits_fail 'could not atomically persist the local LNbits setup secret'
      return 1
    fi
  fi

  # Always read back the persisted state so every concurrent caller returns the published secret.
  if [[ -L "$secret_file" || -e "$secret_file" && ! -f "$secret_file" ]]; then
    lnbits_fail 'local LNbits setup-secret state must be a regular non-symlink file'
    return 1
  fi
  if ! LNBITS_RESOLVED_SETUP_PASSWORD="$(lnbits_read_secret_password "$secret_file")"; then
    lnbits_fail 'local LNbits setup-secret state is invalid; reset the disposable stack or provide LNBITS_SETUP_PASSWORD'
    return 1
  fi

  if [[ -z "${LNBITS_RESOLVED_SETUP_PASSWORD:-}" ]]; then
    lnbits_fail 'local LNbits setup secret must be nonblank'
    return 1
  fi
  if [[ "$(lnbits_secret_file_mode "$secret_file")" != "600" ]]; then
    lnbits_fail 'local LNbits setup secret must be owner-readable only'
    return 1
  fi
}
