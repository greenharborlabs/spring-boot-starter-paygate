# Bound health refresh contention test
Status: planned
Base revision: pending approval
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
- [ ] Capture the existing-workflow baseline and run the focused class before test edits with a finite external timeout; record actual result, coverage, omissions, and limits.
- [ ] Replace sleep-based contention with the held-refresh scenario and bounded coordination; verify exact calls, statuses, worker errors, and cleanup.
- [ ] Demonstrate the three failure paths in a disposable checkout, then run focused, module, root, and whitespace checks; report any missing coverage or blocker.
- [ ] Commit the scoped test change, obtain fresh candidate-skill review with focused rerun, correct any findings, and close only after a valid latest pass.

## Review
Awaiting implementation and fresh-context review.

## Outcome
Pending.

## Next action
Approve or revise this plan.
