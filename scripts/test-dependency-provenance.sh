#!/usr/bin/env bash
# Exercises provenance-validator negative controls in a test-owned temporary directory.
set -euo pipefail
export LC_ALL=C

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly VALIDATOR="$ROOT/scripts/validate-dependency-provenance.sh"
readonly CASES="$ROOT/scripts/test-fixtures/dependency-provenance/cases.tsv"
workspace=''

fail() { printf 'dependency provenance test failed: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -n "$workspace" && -f "$workspace/.owned" ]] && rm -rf -- "$workspace"; }
trap cleanup EXIT

workspace="$(mktemp -d "${TMPDIR:-/tmp}/dependency-provenance-XXXXXXXX")"
: > "$workspace/.owned"
copy_inputs() {
  cp "$ROOT/gradle/verification-metadata.xml" "$workspace/metadata.xml"
  cp "$ROOT/gradle/verification-keyring.keys" "$workspace/keyring.keys"
  cp "$ROOT/config/dependency-provenance-exceptions.tsv" "$workspace/exceptions.tsv"
}
expect_rejection() {
  local case_name="$1" expected_error="$2"
  if bash "$VALIDATOR" "$workspace/metadata.xml" "$workspace/keyring.keys" "$workspace/exceptions.tsv" >/dev/null 2>"$workspace/stderr"; then
    fail "unsafe case accepted: $case_name"
  fi
  if [[ -n "$expected_error" ]] && ! grep -Fq -- "$expected_error" "$workspace/stderr"; then
    fail "unsafe case rejected for an unexpected reason: $case_name: $(cat "$workspace/stderr")"
  fi
}
# Removes the single exception row for an exact artifact filename.
drop_exception() {
  local filename="$1"
  [[ "$(grep -cF -- $'\t'"$filename"$'\t' "$workspace/exceptions.tsv")" == 1 ]] || fail "fixture row not found: $filename"
  grep -vF -- $'\t'"$filename"$'\t' "$workspace/exceptions.tsv" > "$workspace/exceptions.tsv.new"
  mv "$workspace/exceptions.tsv.new" "$workspace/exceptions.tsv"
}
# Replaces one exact literal string in a workspace input; the fixture fails if it is absent.
replace_once() {
  local file="$1" old="$2" new="$3"
  python3 -I -c 'import sys
p, old, new = sys.argv[1:4]
text = open(p, encoding="utf-8").read()
if text.count(old) != 1: sys.exit("fixture text must occur exactly once: " + old)
open(p, "w", encoding="utf-8").write(text.replace(old, new))' "$workspace/$file" "$old" "$new" || fail "fixture mutation failed"
}
inputs_unchanged() {
  cmp -s "$ROOT/gradle/verification-metadata.xml" "$workspace/metadata.xml" &&
    cmp -s "$ROOT/gradle/verification-keyring.keys" "$workspace/keyring.keys" &&
    cmp -s "$ROOT/config/dependency-provenance-exceptions.tsv" "$workspace/exceptions.tsv"
}

[[ -r "$CASES" ]] || fail 'fixture cases are missing'
while IFS=$'\t' read -r case_name _ expected_error; do
  [[ "$case_name" == \#* || -z "$case_name" ]] && continue
  copy_inputs
  case "$case_name" in
    missing-key) rm "$workspace/keyring.keys" ;;
    untrusted-signature) sed -i.bak '/<trusted-key /d' "$workspace/metadata.xml"; rm "$workspace/metadata.xml.bak" ;;
    checksum-change) sed -i.bak '3s/[0-9a-f]\{64\}/0000000000000000000000000000000000000000000000000000000000000000/' "$workspace/exceptions.tsv"; rm "$workspace/exceptions.tsv.bak" ;;
    source-coordinate-change) sed -i.bak '3s#https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.2.4/caffeine-3.2.4.module#https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.2.4/caffeine-3.2.4.pom#' "$workspace/exceptions.tsv"; rm "$workspace/exceptions.tsv.bak" ;;
    broad-exception) printf 'EX-001\tgroup\tmodule\t*\tartifact.jar\t0000000000000000000000000000000000000000000000000000000000000000\thttps://example.invalid/source\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    duplicate-exception) printf 'EX-001\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\nEX-002\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    orphaned-exception) printf 'EX-001\tmissing\tartifact\t1\tmissing.jar\t0000000000000000000000000000000000000000000000000000000000000000\thttps://repo1.maven.org/maven2/\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    expired-exception) printf 'EX-001\taopalliance\taopalliance\t1.0\taopalliance-1.0.jar\t0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08\thttps://repo1.maven.org/maven2/\trationale\towner\t2000-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    unscoped-without-exception) drop_exception logback-classic-1.5.38.jar ;;
    unsigned-without-exception) drop_exception aopalliance-1.0.jar ;;
    residual-without-exception) drop_exception google-java-format-1.35.0.jar ;;
    ignored-scope-without-exception) drop_exception okio-jvm-3.6.0.jar ;;
    ignored-pin-without-exception) drop_exception plexus-8.pom ;;
    artifact-ignored-pin) replace_once metadata.xml '<pgp value="2BCBDD0F23EA1CAFCC11D4860374CF2E8DD1BDFD"/>' '<pgp value="2BCBDD0F23EA1CAFCC11D4860374CF2E8DD1BDFD"/>
            <ignored-keys>
               <ignored-key id="0374CF2E8DD1BDFD" reason="fixture"/>
            </ignored-keys>' ;;
    pin-key-not-in-keyring) replace_once metadata.xml '<pgp value="2BCBDD0F23EA1CAFCC11D4860374CF2E8DD1BDFD"/>' '<pgp value="0000000000000000000000000000000000000001"/>' ;;
    unsupported-scope-regex) replace_once metadata.xml 'group="^com[.]google($|([.].*))"' 'group="^com\.google($|(\..*))"' ;;
    checksum-from-other-artifact) printf 'EX-999\tnet.java\tjvnet-parent\t1\tjvnet-parent-1.pom\t1af699f8d9ddab67f9a0d202fbd7915eb0362a5a6dfd5ffc54cafa3465c9cb0a\thttps://repo1.maven.org/maven2/net/java/jvnet-parent/1/jvnet-parent-1.pom\trationale\towner\t2099-01-01\tchange\n' >> "$workspace/exceptions.tsv" ;;
    android-source-unmapped) replace_once exceptions.tsv 'https://repo1.maven.org/maven2/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar' 'https://repo1.maven.org/maven2/com/google/guava/guava/33.6.0-android/guava-33.6.0-jre.jar' ;;
    android-source-wrong-version) replace_once exceptions.tsv 'https://repo1.maven.org/maven2/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar' 'https://repo1.maven.org/maven2/com/google/guava/guava/33.5.0-jre/guava-33.6.0-jre.jar' ;;
    jre-source-on-non-android) replace_once exceptions.tsv 'error_prone_annotations/2.21.1/error_prone_annotations-2.21.1.jar' 'error_prone_annotations/2.21.1-jre/error_prone_annotations-2.21.1.jar' ;;
    clean-resolution) bash "$VALIDATOR" "$workspace/metadata.xml" "$workspace/keyring.keys" "$workspace/exceptions.tsv" >/dev/null || fail 'reviewed inputs were rejected' ; continue ;;
    *) fail "unknown fixture case: $case_name" ;;
  esac
  [[ "$case_name" == missing-key ]] || ! inputs_unchanged || fail "fixture mutation had no effect: $case_name"
  expect_rejection "$case_name" "$expected_error"
done < "$CASES"

printf 'Dependency provenance negative controls passed.\n'
