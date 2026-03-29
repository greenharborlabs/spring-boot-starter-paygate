---
name: plan-work
description: |
  Battle-tested plan creator for this Java/Spring Boot project. Combines architect-level
  codebase exploration with independent sub-agent review and a confidence-gated feedback
  loop to produce orchestrate-ready plan files. Use when the user wants to plan a feature,
  fix, or refactoring before implementation. Modes: ceo (greenfield/ambitious),
  eng (fixes/small features), auto (detect from description).
---

# Plan Work

Create battle-tested, orchestrate-ready implementation plans.

## Arguments

1. **description (required):** What to build -- a sentence or paragraph.
2. **--mode ceo|eng|auto:** Planning mode. Default: auto.
3. **--out PATH:** Output file path. Default: `plans/<slugified-description>.md`.

## Phase 0: Requirements Discovery (Conditional)

**Trigger:** If description is brief (<50 words) and no spec doc is referenced, run
a focused interview to resolve key decisions before planning.

### 0a. Quick Codebase Scan

Before asking anything, spend max 3 Grep/Glob calls + 3 file reads to understand
what already exists in the area the description touches. If a question can be answered
by the codebase, don't ask it.

### 0b. Decision Tree Interview (4-6 AskUser calls)

For each question, target decisions that would fork the plan -- not implementation details.
Lead with a recommendation based on what the codebase scan revealed.

Focus areas (pick the 4-6 most relevant):
- Scope boundaries -- what's in vs out
- Key architectural fork -- fundamental approach choice
- Integration shape -- how it connects to existing code
- Data ownership -- source of truth
- Constraints -- hard limits from external systems

## Phase 1: Intake + Scoping

### 1a. Mode Resolution

If auto: description mentions "new", "build", "create" or no existing code matches -> ceo.
Description mentions "fix", "refactor", "update" or touches <8 files -> eng.

### 1b. Scoping Questions (1-4 AskUser calls)

- Q1: Scope intent (new capability / enhancement / fix-refactor)
- Q2: Constraints (minimal diff / build it right / move fast)
- Q3 (ceo only): Ambition level (10x / solid / MVP)

Lead with recommendation + WHY. One issue per question. Lettered options.

## Phase 2: Codebase Exploration

Read-only. No user interaction. Strict budget.

### 2a. System Context
```bash
git log --oneline -20
git diff main --stat
```
Read CLAUDE.md and any existing architecture/plan docs.

### 2b. Targeted Exploration
- **Max 8 Glob/Grep calls** (eng: 6)
- **Max 12 file reads** (eng: 8)
- Identify: existing code that solves sub-problems, patterns to follow, interfaces
  to conform to, test patterns, all callers/dependents of code being changed

### 2c. Completeness Sweep
After initial exploration, verify with max 3 additional searches + 3 reads.
Goal: a second run should NOT find materially different code.

### 2d. Premise Challenge
1. Is this the right problem?
2. What existing code already partially solves this?
3. CEO: What would the ideal end state look like in 12 months?
4. Eng: Can this be achieved with fewer files/abstractions?

## Phase 3: Draft Plan

### Output Structure (Orchestrate-Compatible)

Use `## Wave N: [Title]` headings with `### W{N}-{NN}: [Work unit title]` sub-items.
Units in Wave N depend on all units in Wave N-1. Units within the same wave are independent.

### Each Work Unit Must Include

```markdown
### W1-01: [Imperative title]
[2-3 sentence description of what to implement and why]

**Files:** [list of files to create/modify with relevant line ranges]
**Acceptance criteria:**
- [Specific, checkable condition -- not "implement correctly"]
**Error handling:** [Named failure modes and expected behavior]
**Tests:** [Test type (unit/integration) + what specifically to test]
**Test spec:**
- [Concrete behavioral test case with input/expected output]
- [Rejection case with input/expected error]
- [Mocking boundary: only mock at system boundaries]
```

### Plan Size Check

If plan exceeds 4 waves or 15 work units (eng: 3 waves or 10 units), ask user
whether to split, cut to MVP, or proceed as-is.

## Phase 4: Independent Review

Spawn a worker sub-agent via the Task tool to independently review the plan.
The sub-agent must:
1. Read the plan file
2. Explore the codebase independently (max 12 reads, 8 searches)
3. Check blast radius -- find ALL callers/consumers of modified files
4. Verify acceptance criteria are independently verifiable
5. Validate wave dependencies
6. Return findings with confidence scores

### Sub-Agent Review Prompt

Include in the Task prompt:
- Plan file path
- Original user description
- Mandatory review checklist (blast radius, missing work units, criteria depth,
  wave dependencies, error handling, API contract changes)
- Required output format with findings and confidence scores

### Confidence Dimensions

| Dimension | What it measures |
|-----------|------------------|
| Architecture | Component boundaries, coupling, blast radius |
| Error Handling | Named failure modes, acceptance criteria coverage |
| Test Strategy | Test type coverage, specificity of test requirements |

### Incorporate Findings

- IMPORTANT findings: apply fixes silently
- MINOR findings: apply if straightforward, otherwise add to Open Questions
- Missing work units: add to appropriate waves
- If all dimensions HIGH and no CRITICAL findings: proceed directly to output

## Phase 5: Output

### Plan File Template

```markdown
# [Plan Title]

**Created at:** `<commit-hash>` on `<date>` | **Mode:** `<ceo|eng>`

## Summary
[2-3 sentences]

## Existing Code Leverage
[What already exists that this plan reuses]

## Architecture
[ASCII diagram of component relationships]

## Blast Radius
| Modified File/Interface | Consumers | Covered by Work Unit? |
|------------------------|-----------|----------------------|

## Wave 1: [Foundation]
### W1-01: [Work unit title]
...

## NOT in Scope
- [Deferred item] -- [rationale]

## Failure Modes Summary
| Codepath | Failure Mode | Handled In | Tested? |
|----------|-------------|------------|---------|

## Architect Review Findings
[What was found, incorporated, and deferred]

## Confidence Assessment
| Dimension | Score | Notes |
|-----------|-------|-------|
```

## Critical Rules

1. **Context budget:** Protect your context window during exploration.
2. **Independent review is non-negotiable.** Always spawn a sub-agent for review.
3. **Orchestrate compatibility.** Wave headings MUST be `## Wave N: [Title]`.
   Work units MUST be `### W{N}-{NN}: [Title]`.
4. **One issue per AskUser call.** Lead with recommendation.
5. **No code.** Do not write implementation code. Plan only.
6. **CLAUDE.md constraints flow into acceptance criteria.**
7. **Test specs require concrete input/output pairs.** Not just "test that validation works."
