package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.spring.CapabilityCache;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultCapabilityResolverTest {

  private static final String SERVICE_NAME = "test-service";
  private static final String TOKEN_ID = "abc123def456";

  @Mock private CapabilityCache capabilityCache;

  // -- Helpers --

  private static L402Credential credentialWithCaveats(List<Caveat> caveats) {
    var random = new SecureRandom();
    byte[] paymentHash = new byte[32];
    byte[] tokenIdBytes = new byte[32];
    random.nextBytes(paymentHash);
    random.nextBytes(tokenIdBytes);

    // Build 66-byte identifier: [version:2][paymentHash:32][tokenId:32]
    byte[] identifier = new byte[66];
    identifier[0] = 0;
    identifier[1] = 1;
    System.arraycopy(paymentHash, 0, identifier, 2, 32);
    System.arraycopy(tokenIdBytes, 0, identifier, 34, 32);

    byte[] signature = new byte[32];
    random.nextBytes(signature);

    var macaroon = new Macaroon(identifier, "https://test.example.com", caveats, signature);
    var preimage = new PaymentPreimage(new byte[32]);
    String tokenId = HexFormat.of().formatHex(tokenIdBytes);
    return new L402Credential(macaroon, preimage, tokenId);
  }

  // ---- Null tokenId ----

  @Nested
  @DisplayName("Null tokenId")
  class NullTokenId {

    @Test
    @DisplayName("returns empty set immediately when tokenId is null")
    void returnsEmptyForNullTokenId() {
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context = new CapabilityResolutionContext(null, SERVICE_NAME, null, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }
  }

  // ---- Strategy 1: Cache ----

  @Nested
  @DisplayName("Strategy 1: Cache lookup")
  class CacheLookup {

    @Test
    @DisplayName("returns cached capability when cache hit")
    void returnsCachedCapability() {
      when(capabilityCache.get(TOKEN_ID)).thenReturn("read");
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context = new CapabilityResolutionContext(TOKEN_ID, SERVICE_NAME, null, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("read");
    }

    @Test
    @DisplayName("skips cache when CapabilityCache is null")
    void skipsCacheWhenNull() {
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID,
              SERVICE_NAME,
              null,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"));

      Set<String> result = resolver.resolve(context);

      // Falls through to strategy 3 (request metadata) since no L402 credential
      assertThat(result).containsExactly("write");
    }

    @Test
    @DisplayName("falls through when cache returns null")
    void fallsThroughOnCacheMiss() {
      when(capabilityCache.get(TOKEN_ID)).thenReturn(null);
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID,
              SERVICE_NAME,
              null,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("write");
    }

    @Test
    @DisplayName("falls through when cache throws RuntimeException")
    void fallsThroughOnCacheException() {
      when(capabilityCache.get(TOKEN_ID)).thenThrow(new RuntimeException("cache unavailable"));
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID,
              SERVICE_NAME,
              null,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "admin"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("admin");
    }
  }

  // ---- L402 fallback isolation ----

  @Nested
  @DisplayName("L402 fallback isolation")
  class L402FallbackIsolation {

    @Test
    @DisplayName("does not derive authorities from raw capability caveats")
    void ignoresRawCapabilityCaveat() {
      var credential =
          credentialWithCaveats(List.of(new Caveat(SERVICE_NAME + "_capabilities", "read")));
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(credential.tokenId(), SERVICE_NAME, credential, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not derive authorities from comma-separated raw caveats")
    void ignoresCommaSeparatedRawCapabilities() {
      var credential =
          credentialWithCaveats(
              List.of(new Caveat(SERVICE_NAME + "_capabilities", "read,write,admin")));
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(credential.tokenId(), SERVICE_NAME, credential, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not union repeated raw capability caveats")
    void doesNotUnionRepeatedCaveats() {
      var credential =
          credentialWithCaveats(
              List.of(
                  new Caveat(SERVICE_NAME + "_capabilities", "read"),
                  new Caveat(SERVICE_NAME + "_capabilities", "write")));
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(credential.tokenId(), SERVICE_NAME, credential, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("preserves metadata fallback when l402Credential is null")
    void skipsWhenNoCredential() {
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID,
              SERVICE_NAME,
              null,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "fallback"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("fallback");
    }

    @Test
    @DisplayName("does not use metadata fallback for L402 when serviceName is null")
    void ignoresMetadataForL402WhenServiceNameNull() {
      var credential =
          credentialWithCaveats(List.of(new Caveat(SERVICE_NAME + "_capabilities", "read")));
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(
              credential.tokenId(),
              null,
              credential,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ignores caveats with non-matching key")
    void ignoresNonMatchingCaveats() {
      var credential =
          credentialWithCaveats(
              List.of(
                  new Caveat("other_capabilities", "read"),
                  new Caveat("expires_at", "2099-01-01")));
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(
              credential.tokenId(),
              SERVICE_NAME,
              credential,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "fallback"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not consult cache or request metadata for an L402 credential")
    void ignoresCacheAndRequestMetadataForL402() {
      var credential =
          credentialWithCaveats(
              List.of(new Caveat(SERVICE_NAME + "_capabilities", "raw-capability")));
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context =
          new CapabilityResolutionContext(
              credential.tokenId(),
              SERVICE_NAME,
              credential,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "requested-capability"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
      verifyNoInteractions(capabilityCache);
    }
  }

  // ---- Strategy 2: Request metadata ----

  @Nested
  @DisplayName("Strategy 3: Request metadata fallback")
  class RequestMetadata {

    @Test
    @DisplayName("returns capability from request metadata")
    void returnsFromMetadata() {
      var resolver = new DefaultCapabilityResolver(null);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID, null, null, Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("search");
    }

    @Test
    @DisplayName("returns empty when metadata has no requested capability")
    void returnsEmptyWhenNoMetadata() {
      var resolver = new DefaultCapabilityResolver(null);
      var context = new CapabilityResolutionContext(TOKEN_ID, null, null, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }
  }

  // ---- Strategy ordering ----

  @Nested
  @DisplayName("Strategy ordering")
  class StrategyOrdering {

    @Test
    @DisplayName("L402 context bypasses cache, caveats, and metadata")
    void l402BypassesAllFallbackStrategies() {
      var credential =
          credentialWithCaveats(List.of(new Caveat(SERVICE_NAME + "_capabilities", "caveat-cap")));
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context =
          new CapabilityResolutionContext(
              credential.tokenId(),
              SERVICE_NAME,
              credential,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
      verifyNoInteractions(capabilityCache);
    }

    @Test
    @DisplayName("cache still takes priority over metadata for non-L402 protocols")
    void cacheWinsOverMetadataForNonL402() {
      when(capabilityCache.get(TOKEN_ID)).thenReturn("cached-cap");
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context =
          new CapabilityResolutionContext(
              TOKEN_ID,
              SERVICE_NAME,
              null,
              Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

      Set<String> result = resolver.resolve(context);

      assertThat(result).containsExactly("cached-cap");
    }

    @Test
    @DisplayName("returns empty when all strategies produce nothing")
    void allStrategiesEmpty() {
      when(capabilityCache.get(TOKEN_ID)).thenReturn(null);
      var resolver = new DefaultCapabilityResolver(capabilityCache);
      var context = new CapabilityResolutionContext(TOKEN_ID, SERVICE_NAME, null, Map.of());

      Set<String> result = resolver.resolve(context);

      assertThat(result).isEmpty();
    }
  }
}
