# Universal Pre-Landing Review Checklist

## Instructions

Review the `git diff origin/main` output for the issues listed below. Be specific -- cite `file:line` and suggest fixes. Skip anything that's fine. Only flag real problems.

**Two-pass review:**
- **Pass 1 (CRITICAL):** Hardcoded secrets, race conditions, trust boundary violations. These block landing.
- **Pass 2 (INFORMATIONAL):** Dead code, test gaps, magic numbers, stale comments, crypto misuse, version consistency.

**Output format:**

```
Pre-Landing Review: N issues (X critical, Y informational)

**CRITICAL** (blocking):
- [file:line] Problem description
  Fix: suggested fix

**Issues** (non-blocking):
- [file:line] Problem description
  Fix: suggested fix
```

If no issues found: `Pre-Landing Review: No issues found.`

Be terse. For each issue: one line describing the problem, one line with the fix. No preamble, no summaries, no "looks good overall."

---

## Review Categories

### Pass 1 -- CRITICAL

#### Hardcoded Secrets & Credentials
- API keys, tokens, passwords, or connection strings committed in source (including test fixtures that look like real credentials)
- `.env` files or credential configs added to version control without `.gitignore` coverage
- Private keys, certificates, or signing secrets in the diff

#### Race Conditions
- Check-then-act patterns without atomicity (read a value, branch on it, then write -- without a lock, transaction, or atomic operation)
- `find_or_create` patterns without unique constraints or conflict handling
- Status transitions that don't use atomic compare-and-swap

#### Trust Boundary Violations
- Untrusted input (user input, LLM output, external API responses) used directly in security-sensitive operations without validation
- User-controlled values in SQL, shell commands, file paths, or redirect URLs without sanitization
- LLM-generated content written to DB or rendered in UI without format/type validation

### Pass 2 -- INFORMATIONAL

#### Dead Code & Unused Declarations
- Variables assigned but never read
- Imports/requires that are unused
- Functions/methods defined but never called within the diff scope
- Parameters declared but never referenced in the method body
- Constant/redundant parameters: method parameters that receive the same value at every call site
- Private fields/methods that are never accessed
- Unreachable code after `return`, `throw`, `break`, or `continue`
- Dead branches: `if` conditions that are always true/false based on preceding logic
- Empty `catch`, `then`, or `finally` blocks that silently swallow errors

#### Complexity
- Methods/functions exceeding ~20 lines -- candidates for extraction
- Cyclomatic complexity: methods with more than 4-5 branching paths
- Cognitive complexity: deeply nested logic (3+ levels of nesting) -- flatten with guard clauses
- Multi-break/multi-continue loops -- extract to a method with early returns
- Nested loops: inner loops should be extracted to named methods
- Long parameter lists (5+ parameters) -- consider a parameter object or builder

#### Duplication
- Repeated code blocks (3+ lines) in multiple places -- extract to shared method
- Copy-paste patterns where logic is identical except for one variable
- Repeated string literals used as identifiers -- extract to constants

#### Naming & Readability
- Boolean variables or methods without question-word prefixes (`is`, `has`, `should`, `can`)
- Abbreviated or single-letter variable names outside trivial loop indices
- Methods whose names don't describe what they do
- Inconsistent naming conventions within the same file or package

#### Method & Class Structure
- Classes with too many responsibilities -- candidate for splitting
- Utility classes that are dumping grounds for unrelated static methods
- Methods that mix different levels of abstraction
- Feature envy: methods that primarily operate on another class's data

#### Error Handling Hygiene
- Empty catch blocks that silently swallow exceptions
- Catching overly broad exception types when specific types are appropriate
- Logging an exception and then re-throwing it (double logging)
- Returning `null` where a failure should throw or return `Optional`
- Missing try-with-resources for closeable resources
- Logging only `e.getMessage()` instead of the full exception

#### Test Gaps
- New code paths without corresponding test coverage
- Tests that assert on type/status but not side effects
- Missing negative-path assertions
- Tests with no assertions
- Test methods testing multiple unrelated behaviors

#### Magic Numbers & String Coupling
- Bare numeric literals used in logic without named constants
- Error message strings matched elsewhere as query filters
- Hardcoded URLs, ports, or hostnames that should come from configuration

#### Stale Comments
- Comments or docstrings that describe old behavior after the code changed
- TODO comments referencing completed work
- Commented-out code blocks

#### Crypto & Entropy Misuse
- `rand()` / `Math.random()` / `Random` for security-sensitive values -- use `SecureRandom`
- Non-constant-time comparisons on secrets or tokens
- Truncation of data instead of hashing

#### Version & Changelog Consistency
- Version mismatch between build.gradle and CHANGELOG
- CHANGELOG entries that describe changes inaccurately

---

## Suppressions -- DO NOT flag these

- "X is redundant with Y" when the redundancy is harmless and aids readability
- "Add a comment explaining why this threshold/constant was chosen"
- "This assertion could be tighter" when the assertion already covers the behavior
- Suggesting consistency-only changes with no functional impact
- Harmless no-ops
- ANYTHING already addressed in the diff you're reviewing
