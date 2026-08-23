#!/usr/bin/env bash
# Tests the repository ignore policy without staging or modifying working-tree files.
set -euo pipefail
readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly FIXTURE_DIR='scripts/test-fixtures/secret-ignore-controls'

fail() { printf 'repository secret-ignore test failed: %s\n' "$*" >&2; exit 1; }
[[ -d "$ROOT/.git" ]] || fail 'must run from a git worktree'

for name in private.pem deploy.key server.keystore service.jks client.p12 bundle.pfx invoice.macaroon tls.cert; do
  candidate="$FIXTURE_DIR/$name"
  git -C "$ROOT" check-ignore -q -- "$candidate" || fail "$name is not ignored"
done

git -C "$ROOT" check-ignore -q -- "$FIXTURE_DIR/non-secret-fixture.txt" && fail 'non-secret fixture is unexpectedly ignored'
git -C "$ROOT" add --dry-run -f -- scripts/test-fixtures/low-security/README.md >/dev/null \
  || fail 'a reviewed non-secret fixture cannot be deliberately force-added'
printf 'Repository secret-ignore controls passed.\n'
