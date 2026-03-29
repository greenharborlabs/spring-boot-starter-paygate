---
name: review
description: |
  Multi-mode code review with Java/Spring-specific checklists. Supports diff-based
  (default), scoped (package/module/directory), and endpoint-flow tracing review modes.
  Detects tech stack, applies checklist review, classifies findings as DESIGN or IMPL,
  and produces an actionable playbook. Use when reviewing code changes, analyzing a
  module for issues, or tracing an endpoint flow for correctness.
---

# Code Review

You are running the review workflow -- a multi-mode code review tool for this
Java/Spring Boot project.

## Modes

- **DIFF mode (default):** Review changes against a branch (default: origin/main)
- **SCOPE mode:** Review a specific directory, module, or class
- **FLOW mode:** Trace and review an endpoint end-to-end

## Step 1: Determine Review Scope

### DIFF mode

1. Run `git diff origin/main --name-only` to get the file list.
2. Run `git diff origin/main` to get the full diff.
3. If no diff, report "Nothing to review."

### SCOPE mode

Given a target (directory, module name, or glob pattern):
1. Use Glob to resolve files matching the target.
2. Read the resolved files for review.
3. Parse imports to identify direct dependency files as read-only context (cap at 30).

### FLOW mode

Given an HTTP method and path (e.g., `POST /api/orders`):
1. Grep for the matching controller annotation (`@PostMapping`, `@GetMapping`, etc.)
   accounting for class-level `@RequestMapping` prefix.
2. Trace the call chain: Controller -> Service(s) -> Repository/Client(s) -> Entities/DTOs -> Config.
3. Read all files in the chain for review.

## Step 2: Load Checklists

Read the checklist files bundled with this skill:
- [checklist-universal.md](checklist-universal.md) -- always loaded
- [checklist-java-spring.md](checklist-java-spring.md) -- loaded for Java files

## Step 3: Review

Apply a two-pass review:

**Pass 1 (CRITICAL):** Hardcoded secrets, race conditions, trust boundary violations,
transaction hazards, N+1 queries, SQL injection, missing authorization, broken BOLA.

**Pass 2 (INFORMATIONAL):** Dead code, test gaps, complexity, naming, error handling
hygiene, magic numbers, stale comments, crypto misuse.

For each finding, trace actively:
- Every constructor parameter: is the field actually read/called in any method?
- Every injected dependency: is it used?
- Every field with a setter: is it read anywhere?

### Output Format

Be terse. For each finding:

```
[file:line] SEVERITY Problem description
Fix: suggested fix
Files: comma-separated list of files the fix would touch
```

Where SEVERITY is: CRITICAL, HIGH, MEDIUM, LOW.

Skip preamble, summaries, and "looks good" comments. Only flag real problems.

## Step 4: Classify Findings

For every finding, classify by resolution path:

**DESIGN** (needs planning before implementation) if ANY apply:
- Requires a new abstraction, pattern, or architectural layer
- Affects multiple files across different packages (cross-cutting)
- Fix involves a choice between competing approaches with trade-offs
- Requires a schema migration or API contract change
- Scope of the fix is unclear

**IMPL** (surgical fix, ready for implementation) if ALL apply:
- Fix is well-defined and contained to 1-3 files
- No architectural decisions needed
- Examples: add missing `@Valid`, fix N+1 with `JOIN FETCH`, add null check

When in doubt, classify as DESIGN.

## Step 5: Build Report

```
## Review Report

### CRITICAL (blocking)
- [file:line] Problem description
  Fix: suggested fix
  Path: DESIGN | IMPL
  Files: affected files

### Issues (non-blocking)
- [file:line] Problem description
  Fix: suggested fix
  Path: DESIGN | IMPL
  Files: affected files

### Summary
| Category | Critical | Informational | Total |
|----------|----------|---------------|-------|
| DESIGN   | X        | X             | X     |
| IMPL     | X        | X             | X     |
```

## Step 6: Handle Critical Issues

For each CRITICAL IMPL finding, use AskUser:
- The problem and recommended fix
- Options: Fix it now, Acknowledge, False positive

For CRITICAL DESIGN findings:
- The problem and why it needs planning
- Options: Acknowledge (plan later), Acknowledge, False positive

If the user chooses to fix IMPL issues, apply the recommended fixes.

## Key Rules

- Read the FULL input before commenting. Do not flag issues already addressed.
- Only flag real problems. Skip anything that is fine.
- Respect scope. In SCOPE mode, only flag issues in resolved files, not context files.
- Follow the suppressions defined in each checklist.
