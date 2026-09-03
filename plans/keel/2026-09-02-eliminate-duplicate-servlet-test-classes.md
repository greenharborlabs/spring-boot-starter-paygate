# Eliminate duplicate Servlet test classes
Status: review
Base revision: 50fd0f97d1c81e5ba1c0510af1a2acd505d58456
Review scope: build.gradle.kts; paygate-spring-security/build.gradle.kts

## Goal
Remove duplicate Jakarta Servlet classes from `paygate-spring-security` test compile and runtime classpaths. `./gradlew buildHealth` reports no duplicate-class warning and will fail on any future duplicate-class warning; `./gradlew :paygate-spring-security:test` and `./gradlew check --no-daemon` pass.

## Constraints
Limit implementation to the root and `paygate-spring-security` Gradle build files. Preserve all unrelated work, including `TODOS.md`.

## Decisions
Remove the module's redundant `testImplementation` Servlet API, retaining its production `compileOnly` API; the web test starter already supplies Tomcat's Servlet classes for tests. Configure dependency analysis globally to fail duplicate-class warnings, rather than suppressing these known duplicates or adding a module-specific check.

## Tasks
- [x] Updated the two Gradle build files to remove the redundant test Servlet API and make duplicate-class warnings fail; `:paygate-spring-security:test`, `buildHealth` (no duplicate-class warnings), and `check --no-daemon` passed.

## Review
Implementation commit: c1262dc. Verification passed: `./gradlew :paygate-spring-security:test`, `./gradlew buildHealth` (no duplicate-class warnings), and `./gradlew check --no-daemon`.

## Outcome
Pending.

## Next action
Request fresh-context review.
