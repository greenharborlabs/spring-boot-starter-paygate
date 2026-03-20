# AGENTS.md

## Build, Lint & Test Commands

### Core Commands
```bash
./gradlew build          # Build all modules (includes tests)
./gradlew test           # Run all tests across all modules
./gradlew :l402-core:test  # Test core module only
```

### Running Single Tests
```bash
# By class name
./gradlew :l402-core:test --tests "com.greenharborlabs.l402.core.macaroon.MacaroonCryptoTest"

# By test method (within a class)
./gradlew :l402-core:test --tests "*MacaroonCryptoTest.deriveKey*"

# Filter by package
./gradlew :l402-spring-autoconfigure:test --tests "com.greenharborlabs.l402.spring.*"

# Run with reporting
./gradlew test --info          # Verbose output
./gradlew test --stacktrace    # Include stack traces on failure
```

### Coverage & Reports
```bash
./gradlew jacocoTestReport     # Generate coverage reports (HTML/XML)
./gradlew aggregateJavadoc     # Generate API documentation
./gradlew check                # Build + tests + coverage verification
```

### Integration Tests
```bash
cd integration-tests && docker-compose up --build   # Run Docker-based integration tests
```

---

## Code Style Guidelines

### Imports & Ordering
- Use **static imports** from `org.assertj.core.api.Assertions` (`assertThat`, `assertThatThrownBy`)
- Group imports: JDK (`javax.*`, `java.*`), then project packages in alphabetical order
- No wildcard imports except for static assertions
- Example ordering:
  ```java
  import javax.crypto.Mac;
  import java.nio.charset.StandardCharsets;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;
  
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;
  ```

### Java 25 Features
- Use **records** for immutable data carriers (e.g., `L402Challenge`, `Invoice`)
- Use **sealed classes** where type hierarchies are bounded
- Prefer **pattern matching** with `instanceof` and `switch` expressions
- Use **var** for local variables with clear inferred types

### Naming Conventions
- Classes: `PascalCase` (e.g., `MacaroonVerifier`, `L402ChallengeService`)
- Methods/variables: `camelCase` (e.g., `deriveKey`, `constantTimeEquals`)
- Constants: `UPPER_SNAKE_CASE` with `private static final` (e.g., `HMAC_SHA256`, `GENERATOR_KEY`)
- Test classes: End with `Test` suffix, use descriptive names

### Error Handling & Security
- **Fail closed**: Lightning unreachable → 503 status, never 200
- **Constant-time comparisons**: Use XOR accumulation (`constantTimeEquals()`), never `Arrays.equals()` for secrets
- **Never log full macaroon values** — only token IDs (first 8 bytes)
- **Zeroize sensitive data**: Always call `KeyMaterial.zeroize(byte[])` after using keys/preimages
- Wrap cryptographic operations with proper exception handling, throw domain-specific exceptions

### Testing Style (JUnit 5 + AssertJ)
- Use `@Nested` classes for logical grouping of related tests
- Use `@DisplayName` for readable test descriptions
- Prefer descriptive assertions over generic ones:
  ```java
  assertThat(derived).hasSize(32);
  assertThatThrownBy(() -> MacaroonCrypto.hmac(sb, data))
      .isInstanceOf(IllegalStateException.class);
  ```
- Test edge cases: empty inputs, null handling, binary data, concurrent access

### l402-core Constraints
- **ZERO external dependencies** — only JDK `javax.crypto`, `java.security`, `java.util`
- Macaroon V2 format must be byte-level compatible with Go `go-macaroon`
- Key derivation: `HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`
- Identifier layout: `[version:2 bytes BE][payment_hash:32][token_id:32]` = 66 bytes

### Spring Boot Patterns
- Configuration properties under `l402.*` prefix with clear defaults
- Use `@ConfigurationProperties` for type-safe configuration binding
- Auto-configurations follow conditional bean registration patterns
- Security mode auto-detection via `L402SecurityModeResolver` (servlet vs Spring Security)

### Code Quality Requirements
- **Coverage minimums**: l402-core ≥80%, other modules ≥60%, example app ≥40%
- No compilation warnings, clean code inspection passes
- Javadoc for public APIs, inline comments only where necessary
- Avoid unnecessary object creation; use `ThreadLocal` for reusable resources (e.g., `Mac` instances)

---

## Additional Resources

### Configuration Metadata
See project root `docs/` folder and `additional-spring-configuration-metadata.json` for all available properties.

### Key Security Patterns
1. **Key derivation**: Always use `MacaroonCrypto.deriveKey()` with the fixed generator key
2. **Binding signatures**: Use `bindForRequest(rootSig, dischargeSig)` to bind discharge macaroons
3. **Memory safety**: Wrap sensitive bytes in `SensitiveBytes` and ensure zeroization on close

### Testing Patterns
- Virtual thread tests for concurrency: `Thread.ofVirtual().start(...)`
- MockWebServer for HTTP backend testing (LNbits)
- gRPC stub mocking for LND integration tests
