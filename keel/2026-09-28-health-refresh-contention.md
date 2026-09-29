# Bound health refresh contention test
Status: working
Base revision: e6402b8646f72e21e8003eb7d0e5c90a0a922510
Review scope: paygate-spring-autoconfigure/src/test/java/com/greenharborlabs/paygate/spring/PaygateLightningHealthIndicatorTest.java

## Goal
Make the [health indicator test](../paygate-spring-autoconfigure/src/test/java/com/greenharborlabs/paygate/spring/PaygateLightningHealthIndicatorTest.java) prove that concurrent callers use cached UP while one stale-cache refresh is held, then observe its DOWN result, with bounded waits and surfaced worker failures. This is the test-only pilot for Keel's verification-reporting feature at `greenharborlabs/keel/keel/2026-09-28-verification-reporting.md`; its detailed execution is T002–T003 and T023–T029 there.

## Constraints
- Follow [AGENTS.md](../AGENTS.md) and [CLAUDE.md](../CLAUDE.md); preserve the three-workflow Keel record shape and approval before test edits. Current clean Paygate HEAD is `aea47f05fd42d2d3ab5b2f0308809f5114f273cc` on `feature/wavelength-poc`; recheck at approval.
- The current test sleeps 50 ms, waits indefinitely on `doneLatch`, ignores worker results, and only checks `callCount < 10`. This establishes a test weakness, not a measured flake rate or production defect.
- Keep implementation in the one test file with JDK/JUnit/AssertJ already available. A production defect or broader cold-start guarantee requires separate scope. Preserve unrelated work; none is currently dirty.
- Java 25.0.1 and Gradle Wrapper 9.4.1 launch locally. Dependency resolution, test results, and host behavior are not yet verified.

## Decisions
- Seed cached UP at TTL zero; hold a known stale refresh in a test-local backend; retrieve competing callers while it is held; release it and assert DOWN. Require exactly two backend calls (seed and refresh), checked worker results, observable backend coordination errors, and finite cleanup. This proves stale-refresh contention only.
- Use one shared five-second coordination/result deadline and a separate total five-second cleanup budget. Release latches in `finally`, interrupt unfinished workers, and bound joins. Demonstrate worker-error, backend-error, and nonfinishing-worker failure paths only in a disposable checkout.
- Required verification from this repository root: focused class with `./gradlew :paygate-spring-autoconfigure:test --tests 'com.greenharborlabs.paygate.spring.PaygateLightningHealthIndicatorTest' --rerun-tasks`, then `./gradlew :paygate-spring-autoconfigure:check`, `./gradlew check`, and `git diff --check`. Inspect test XML to confirm the nested case ran. The default check omits `paygate-integration-tests`; live Lightning and release gates are outside this fake-backend pilot.

## Tasks
- [x] Capture the existing-workflow baseline and run the focused class before test edits with a finite external timeout; record actual result, coverage, omissions, and limits.
- [x] Replace sleep-based contention with the held-refresh scenario and bounded coordination; verify exact calls, statuses, worker errors, and cleanup.
- [x] Demonstrate the three failure paths in a disposable checkout, then run focused, module, root, and whitespace checks; report any missing coverage or blocker.
- [ ] Commit the scoped test change, obtain fresh candidate-skill review with focused rerun, correct any findings, and close only after a valid latest pass.

## Review
Working. The pilot plan was approved after the clean `e6402b8646f72e21e8003eb7d0e5c90a0a922510` base was rechecked. Before test edits, the focused class command in Decisions ran from the Paygate root with a 180-second external timeout: exit 0 in 10 seconds, seven tests across two nested suites, zero skips/failures/errors; XML confirms the old concurrency case ran. This single pass does not measure flakiness or prove contention. The original plan named focused/module/root commands and the integration omission; it needed explicit result and tested-state reporting to make the baseline recoverable.

WORK on the base plus the dirty scoped test file: the focused class command with `--rerun-tasks` exited 0; XML shows seven tests, zero skips/failures/errors, including the renamed held-refresh case. `./gradlew :paygate-spring-autoconfigure:check` exited 0 and ran the module tests, coverage verification, and Spotless check. `git diff --check` passed. In a disposable worktree at `/tmp/keel-paygate-fault.abl6vfay`, synthetic worker, backend-coordination, and nonfinishing-worker faults each failed the focused test (exits 1 within 3.4, 2.3, and 7.2 seconds); the worktree was removed, and no injection remains. The test asserts cached UP for all competing callers while refresh is held, DOWN after release, exactly two backend calls, and bounded cleanup. Its coverage is stale-refresh contention, not cold-start contention or live Lightning.

Required root `./gradlew check` exited 1 at `:validateDependencyProvenance` before module tasks started: an exception review is expired. This is a real aggregate FAIL, with later root obligations NOT RUN due short-circuit; it is outside the approved test-only edit. The scoped test implementation is committed at `ec255be82ad8d3dfeaa404118bd3fe61efe06df4` and clean for fresh review. The pilot cannot receive a passing review or successful close while that required gate remains unresolved.

### Attempt 1
Reviewed revision: ec255be82ad8d3dfeaa404118bd3fe61efe06df4
Verdict: rework
Findings and dispositions:
1. Required root `./gradlew check` remains FAIL. WORK exited 1 at `:validateDependencyProvenance` before module tasks; REVIEW ran its validator directly with a temporary writable GPG home and confirmed `exception review is expired` (exit 1). The remaining aggregate checks did not run. Resolve the expired review through the appropriate separate scope, then rerun the root gate before another passing review; the reviewed test-only change does not authorize editing provenance inputs here.
2. The required fresh focused reviewer rerun is BLOCKED: `./gradlew :paygate-spring-autoconfigure:test --tests 'com.greenharborlabs.paygate.spring.PaygateLightningHealthIndicatorTest' --rerun-tasks` exited 1 before Gradle started because this review sandbox denies access to the wrapper lock under `~/.gradle`. Rerun in a writable Gradle environment. The earlier WORK pass cannot substitute for this reviewer run.

Verification: Scoped diff from the base through HEAD reviewed; scoped path clean and no test-design finding identified. Inspected WORK evidence: focused class PASS, seven tests across two nested suites with zero skips/failures/errors; its XML includes the held-refresh case. Module `:check` PASS with tests, coverage verification, and Spotless; synthetic worker, backend-coordination, and nonfinishing-worker fault injections failed as intended in the disposable checkout. Inputs to those results are unchanged by the implementation commit, but they are not fresh REVIEW executions. Fresh REVIEW `git diff --check` and scoped committed-diff whitespace check passed. Fresh direct provenance validator failed as above. Focused Gradle rerun was blocked before test execution; fresh module and aggregate Gradle checks were not run under the same wrapper access obstacle. Coverage remains the fake-backend stale-refresh scenario; cold-start contention, live Lightning, integration tests, and security behavior were not exercised by this pilot.

## Outcome
Pending.

## Next action
Resolve Attempt 1 findings, rerun required verification, and return the record to review with a committed scoped implementation revision.
