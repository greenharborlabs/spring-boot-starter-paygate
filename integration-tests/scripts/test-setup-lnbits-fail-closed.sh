#!/usr/bin/env bash
# Exercise bootstrap failure responses without Docker or live credentials.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace="$(mktemp -d "${TMPDIR:-/tmp}/lnbits-login-failure-XXXXXXXX")"
trap 'python3 -c '\''import shutil,sys; shutil.rmtree(sys.argv[1])'\'' "$workspace"' EXIT
mkdir "$workspace/bin"

cat > "$workspace/bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
exit 0
MOCK_DOCKER

cat > "$workspace/bin/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
case "$*" in
  *'/api/v1/health'*) exit 0 ;;
  *'/api/v1/auth/first_install'*)
    if [ "$TEST_CASE" = first-install-404 ]; then
      printf '{"detail":"not found"}\n404'
    else
      printf '{}\n200'
    fi
    ;;
  *'/api/v1/auth'*)
    if [ "$TEST_CASE" = login-401 ]; then
      printf '{"detail":"invalid credentials"}\n401'
    else
      printf '{}\n200'
    fi
    ;;
  *'/api/v1/account'*|*'/api/v1/wallet'*)
    printf 'wallet-requested\n' >> "$TEST_CALLS_FILE"
    printf '{"adminkey":"mock-only-admin-key"}\n200'
    ;;
  *) exit 2 ;;
esac
MOCK_CURL
chmod +x "$workspace/bin/docker" "$workspace/bin/curl"

for case_name in first-install-404 login-401 login-no-token; do
  calls_file="$workspace/$case_name.calls"
  env_file="$workspace/$case_name.env"
  output_file="$workspace/$case_name.output"
  if PATH="$workspace/bin:$PATH" TEST_CASE="$case_name" TEST_CALLS_FILE="$calls_file" \
    INTEGRATION_ENV_FILE="$env_file" LNBITS_SETUP_PASSWORD=mock-only-password \
    bash "$SCRIPT_DIR/setup-lnbits.sh" > "$output_file" 2>&1; then
    echo "FAIL: $case_name bootstrap unexpectedly succeeded" >&2
    exit 1
  fi
  if [ -e "$calls_file" ] || grep -q '^LNBITS_API_KEY=' "$env_file"; then
    echo "FAIL: $case_name attempted wallet creation or wrote an API key" >&2
    exit 1
  fi
  if [ "$case_name" = first-install-404 ]; then
    grep -q 'Failed to initialize LNbits first install' "$output_file"
    grep -q 'HTTP 404' "$output_file"
  else
    grep -q 'LNbits login failed' "$output_file"
    if [ "$case_name" = login-401 ]; then
      grep -q 'HTTP 401' "$output_file"
    else
      grep -q 'HTTP 200' "$output_file"
    fi
  fi
done

echo 'LNbits bootstrap failure responses fail closed.'
