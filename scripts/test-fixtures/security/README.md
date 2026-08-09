# Security Negative-Test Fixture Safety

These fixtures support negative controls for repository and release hardening. A
test must prove that a tampered input is rejected **before** it can cause a
build, workflow, documentation command, hook, or other privileged action.

## Isolate every destructive control

- Copy the smallest required fixture set into a newly created, test-owned
  temporary workspace. Give each test invocation its own workspace.
- Treat the repository checkout and every file outside that workspace as
  read-only. Never tamper with tracked files, the real Gradle cache, Git hooks,
  workflow files, or the caller's environment in place.
- Use explicit paths rooted in the temporary workspace. Reject an empty,
  relative, or unexpected workspace path before modifying or removing anything.
- Change only the copied input needed for the negative case (for example a
  checksum, action reference, or `AGENTS.md` command-looking line). Do not
  execute text read from that copied input.
- Make the rejection assertion first: the validator must fail, report the
  expected safe failure, and stop before any build, shell, hook, publication,
  or network-capable command could run.

## Marker files prove non-execution

- A marker is an inert, uniquely named regular file path inside the temporary
  workspace, such as `<workspace>/payload-ran.marker`. It must never refer to a
  repository path, home directory, system temporary directory shared by other
  tests, or host-sensitive location.
- Malicious fixture text may *name* that marker as the purported effect of a
  shell substitution, command chain, redirection, init script, or relocated
  project. The test must not run the payload to create it.
- Create neither the marker nor a substitute before validation. Assert that it
  is absent immediately before invoking the validator and absent after the
  validator rejects the fixture. Its absence is the proof that the payload was
  not interpreted or executed.
- Keep marker assertions before any optional follow-up action. If validation
  unexpectedly succeeds, fail the test immediately; do not continue into a
  potentially dangerous action.

## Cleanup and failure behavior

- Cleanup may remove only the exact temporary workspace created by that test,
  after verifying that the resolved path is the expected test-owned directory.
  Never use broad cleanup targets, wildcards, or paths derived from fixture
  contents.
- Preserve the workspace on an unexpected validation result when practical so
  the failure can be inspected; cleanup must never mask a failed safety
  assertion.
- If isolation, path validation, fixture copying, or marker assertions cannot
  be established, fail closed and skip every potentially executing follow-up.

Phase 7 script tests use these rules for checksum tampering, mutable workflow
references, malicious `AGENTS.md` syntax, release-workflow omissions, and
release-hygiene controls.
