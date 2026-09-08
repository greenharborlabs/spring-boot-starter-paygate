# Eliminate duplicate Servlet test classes
Status: closed
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

### Attempt 1
Reviewed revision: 973c26d4d6f2ce36f766d3e91d76879d87aef8b8
Verdict: pass
Findings and dispositions:
1. No findings; no rework required. Scoped diff (base 50fd0f9 → HEAD) changes only the two scoped build files: root `build.gradle.kts` adds global `onDuplicateClassWarnings { severity("fail") }` inside `dependencyAnalysis.issues.all`, and `paygate-spring-security/build.gradle.kts` removes the redundant `testImplementation("jakarta.servlet:jakarta.servlet-api")` while retaining production `compileOnly`. DSL is valid for build-health 3.6.1; web test starter supplies Tomcat's Servlet classes (buildHealth advice lists `org.apache.tomcat.embed:tomcat-embed-core:11.0.25` on the module's test classpath). Constraint respected: implementation limited to the two build files; `TODOS.md` remains untouched staged work, per the record.
Verification: `./gradlew buildHealth` → BUILD SUCCESSFUL, report contains no duplicate-class advice (only pre-existing non-fatal transitive/unused advice); `./gradlew :paygate-spring-security:test --rerun-tasks` (forced recompile + test) → BUILD SUCCESSFUL; `./gradlew check --no-daemon` → BUILD SUCCESSFUL.

## Outcome
Removed the redundant test Servlet API and made duplicate-class warnings fail globally. Fresh review passed; `buildHealth` reported no duplicate classes, and module tests plus `check --no-daemon` passed.

## Next action
Open a pull request for fix/duplicate-servlet-test-classes.
