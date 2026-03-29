---
name: tdd
description: |
  Test-driven development with red-green-refactor loop for Java/Spring Boot. Use when
  the user wants to build features or fix bugs using TDD, mentions "red-green-refactor",
  wants integration tests, or asks for test-first development.
---

# Test-Driven Development

## Philosophy

**Core principle**: Tests should verify behavior through public interfaces, not
implementation details. Code can change entirely; tests shouldn't.

**Good tests** are integration-style: they exercise real code paths through public APIs.
They describe _what_ the system does, not _how_. A good test reads like a specification.
These tests survive refactors because they don't care about internal structure.

**Bad tests** are coupled to implementation. They mock internal collaborators, test private
methods, or verify through external means. Warning sign: your test breaks when you refactor,
but behavior hasn't changed.

See [tests.md](tests.md) for examples and [mocking.md](mocking.md) for mocking guidelines.

## Anti-Pattern: Horizontal Slices

**DO NOT write all tests first, then all implementation.** This is "horizontal slicing" --
treating RED as "write all tests" and GREEN as "write all code."

**Correct approach**: Vertical slices via tracer bullets. One test -> one implementation -> repeat.

```
WRONG (horizontal):
  RED:   test1, test2, test3, test4, test5
  GREEN: impl1, impl2, impl3, impl4, impl5

RIGHT (vertical):
  RED->GREEN: test1->impl1
  RED->GREEN: test2->impl2
  RED->GREEN: test3->impl3
```

## Workflow

### 1. Planning

Before writing any code:

- Confirm with user what interface changes are needed
- Confirm which behaviors to test (prioritize)
- Design interfaces for testability
- List the behaviors to test (not implementation steps)
- Get user approval on the plan

**You can't test everything.** Confirm with the user which behaviors matter most.

### 2. Tracer Bullet

Write ONE test that confirms ONE thing:

```
RED:   Write test for first behavior -> test fails
GREEN: Write minimal code to pass -> test passes
```

### 3. Incremental Loop

For each remaining behavior:

```
RED:   Write next test -> fails
GREEN: Minimal code to pass -> passes
```

Rules:
- One test at a time
- Only enough code to pass current test
- Don't anticipate future tests
- Keep tests focused on observable behavior

### 4. Refactor

After all tests pass:
- Extract duplication
- Apply SOLID principles where natural
- Run tests after each refactor step

**Never refactor while RED.** Get to GREEN first.

## Checklist Per Cycle

```
[ ] Test describes behavior, not implementation
[ ] Test uses public interface only
[ ] Test would survive internal refactor
[ ] Code is minimal for this test
[ ] No speculative features added
```

## Java/Spring Testing Conventions

For this project, follow these conventions:

- **JUnit 5** with `@Nested` classes for logical grouping
- **`@DisplayName`** for readable test descriptions
- **AssertJ** for assertions: `assertThat(result).hasSize(32)`
- **`assertThatThrownBy`** for exception testing
- Test edge cases: empty inputs, null handling, binary data, concurrent access
- For `JCA` objects (`Mac`): call `Mac.getInstance()` fresh per operation, never `ThreadLocal`
- Virtual thread tests for concurrency: `Thread.ofVirtual().start(...)`
- Coverage minimums: paygate-core >= 80%, other modules >= 60%

### Running Tests

```bash
./gradlew :paygate-core:test --tests "com.greenharborlabs.paygate.core.SomeTest"
./gradlew :paygate-core:test --tests "*SomeTest.specificMethod*"
./gradlew test --info          # Verbose output
./gradlew test --stacktrace    # Stack traces on failure
```

## When to Mock

Mock at **system boundaries** only:
- External APIs (Lightning backends)
- Time/randomness
- gRPC stubs (LND)
- HTTP backends (MockWebServer for LNbits)

**Never mock:**
- Your own classes/modules
- Internal collaborators
- Anything you control

Use dependency injection. Pass external dependencies in rather than creating internally.
