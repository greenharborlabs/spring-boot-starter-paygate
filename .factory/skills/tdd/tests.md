# Good and Bad Tests

## Good Tests

**Integration-style**: Test through real interfaces, not mocks of internal parts.

```java
@Test
@DisplayName("macaroon verification succeeds with valid signature")
void verifyValidMacaroon() {
    var rootKey = generateRootKey();
    var macaroon = MacaroonService.create(rootKey, paymentHash, tokenId);
    var result = MacaroonService.verify(macaroon, rootKey);
    assertThat(result.isValid()).isTrue();
}
```

Characteristics:
- Tests behavior callers care about
- Uses public API only
- Survives internal refactors
- Describes WHAT, not HOW
- One logical assertion per test

## Bad Tests

**Implementation-detail tests**: Coupled to internal structure.

```java
// BAD: Tests implementation details
@Test
void verifyCallsHmacWithCorrectKey() {
    var mockCrypto = mock(MacaroonCrypto.class);
    service.verify(macaroon, rootKey);
    verify(mockCrypto).hmac(eq(derivedKey), any());
}
```

Red flags:
- Mocking internal collaborators
- Testing private methods
- Asserting on call counts/order
- Test breaks when refactoring without behavior change
- Test name describes HOW not WHAT

```java
// BAD: Bypasses interface to verify
@Test
void createMacaroonStoresInCache() {
    service.createMacaroon(paymentHash);
    var cached = cacheField.get(paymentHash); // accessing internal state
    assertThat(cached).isNotNull();
}

// GOOD: Verifies through interface
@Test
@DisplayName("created macaroon can be verified immediately")
void createdMacaroonIsVerifiable() {
    var macaroon = service.createMacaroon(paymentHash);
    var result = service.verify(macaroon);
    assertThat(result.isValid()).isTrue();
}
```
