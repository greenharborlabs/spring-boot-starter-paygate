# Java / Spring Boot Pre-Landing Review Checklist

## Instructions

Review the `git diff origin/main` output for Java/Spring-specific issues. Be specific -- cite `file:line` and suggest fixes. Skip anything that's fine. Only flag real problems.

---

## Review Categories

### Pass 1 -- CRITICAL

#### Transaction Hazards
- `@Transactional` wrapping external HTTP calls, AI/LLM calls, or message sends -- move external calls outside the transaction boundary
- `@Transactional` on private methods (silently ignored by Spring proxies)

#### N+1 Queries
- Lazy-loaded associations accessed in loops without `JOIN FETCH` or `@EntityGraph`
- Repository methods returning entities whose associations are accessed without eager fetching

#### SQL Injection
- String concatenation in `@Query` native queries -- use parameterized `:name` placeholders
- `EntityManager.createNativeQuery()` with string interpolation

#### Input Validation
- Missing `@Valid` or `@Validated` on `@RequestBody` / `@RequestParam` at controller boundaries
- Missing `@NotNull` / `@NotBlank` on DTO fields that must not be null

#### Broken Object-Level Authorization (BOLA)
- User-supplied IDs used to fetch records without ownership/authorization check
- Missing `@PreAuthorize` or manual authorization on endpoints that modify user-specific data

#### Missing Authorization
- Controller endpoints without any authorization annotation or programmatic check
- New endpoints not covered by `SecurityFilterChain` rules

### Pass 2 -- INFORMATIONAL

#### Transaction Efficiency
- Missing `readOnly = true` on `@Transactional` for read-only operations
- Overly broad transaction scope

#### Dependency Injection
- Field injection (`@Autowired` on fields) instead of constructor injection
- Missing `final` on constructor-injected fields

#### HTTP Client Resilience
- `RestTemplate` / `WebClient` calls without explicit connect/read timeouts
- Missing retry or circuit breaker on external service calls

#### Pagination
- Repository methods returning `List<Entity>` for potentially unbounded result sets
- Missing default/max page size limits

#### Virtual Thread Compatibility
- `synchronized` blocks or `ReentrantLock` in code that may run on Virtual Threads
- Thread-local storage assumptions in Virtual Thread context

#### Error Handling
- Missing `@ControllerAdvice` / `@ExceptionHandler` for custom exception types
- Catching generic `Exception` instead of specific types

#### Code Quality -- Java-Specific

**Unused & Dead Code:**
- Unused fields, constants, imports, or local variables
- Private methods never called from within the class
- Unused method parameters (especially in non-interface implementations)
- Constant/redundant parameters: trace every call site -- if a parameter always gets the same value, it's redundant
- Empty method bodies (other than intentional no-ops)

**Constructor & Dependency Bloat:**
- Constructor parameters assigned to fields but the field is never read
- Fields with setters but no getters or internal reads
- Injected dependencies never called -- remove the dependency
- Constructor parameters derivable from an already-injected config object

**Complexity & Structure:**
- Methods exceeding ~20 lines -- extract into focused private methods
- `if`/`else` chains with 4+ branches -- consider `switch` with pattern matching, strategy, or map dispatch
- Nested `if` depth > 3 -- flatten with guard clauses
- Classes with 10+ methods or 300+ lines -- candidate for splitting
- Long constructor parameter lists -- consider builder pattern

**Modern Java (Java 21+):**
- Mutable DTOs that could be `record` types
- `instanceof` chains that could use pattern matching
- Sealed interface opportunities for closed type hierarchies
- Text blocks for multiline strings
- `Optional` misuse: `.get()` without `.isPresent()`, or `Optional` as field type
- `.stream().collect(Collectors.toList())` instead of `.stream().toList()`

**Null Safety:**
- Methods returning `null` where `Optional` would be clearer
- Dereferencing a value that could be null without a null check
- Primitive wrapper types where primitives suffice

**Immutability & Thread Safety:**
- Mutable fields in classes used across threads without synchronization
- Returning mutable internal collections -- use `List.copyOf()`
- `Date` / `Calendar` usage -- use `java.time` types

**Logging:**
- String concatenation in log statements -- use parameterized logging
- Logging sensitive data (passwords, tokens, PII)
- Logging only `e.getMessage()` instead of the exception object

**Resource Management:**
- Streams, connections not in try-with-resources blocks
- Connection pools without proper max-size or timeout configuration

**Spring-Specific Code Smells:**
- `@Component` on classes that should be plain POJOs
- Multiple `@Configuration` classes in same package doing unrelated things
- `@Value` injection of complex expressions -- extract to `@ConfigurationProperties`
- Hardcoded `@Scheduled` cron expressions instead of externalized config

---

## Suppressions -- DO NOT flag these

- Threshold/constant value choices (tuned empirically)
- "Add Javadoc" suggestions
- Style-only changes with no functional impact
- Test utility patterns that look unusual but work correctly
- Framework-required patterns (e.g., empty no-arg constructors for JPA)
- ANYTHING already addressed in the diff
