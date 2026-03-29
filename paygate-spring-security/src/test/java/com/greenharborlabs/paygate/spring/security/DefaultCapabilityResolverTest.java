package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.spring.CapabilityCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCapabilityResolverTest {

    private static final String SERVICE_NAME = "test-service";
    private static final String TOKEN_ID = "abc123def456";

    @Mock
    private CapabilityCache capabilityCache;

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
            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
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
            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(TOKEN_ID, SERVICE_NAME, null, Map.of());

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("read");
        }

        @Test
        @DisplayName("skips cache when CapabilityCache is null")
        void skipsCacheWhenNull() {
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    TOKEN_ID, SERVICE_NAME, null,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"));

            Set<String> result = resolver.resolve(context);

            // Falls through to strategy 3 (request metadata) since no L402 credential
            assertThat(result).containsExactly("write");
        }

        @Test
        @DisplayName("falls through when cache returns null")
        void fallsThroughOnCacheMiss() {
            when(capabilityCache.get(TOKEN_ID)).thenReturn(null);
            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    TOKEN_ID, SERVICE_NAME, null,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("write");
        }

        @Test
        @DisplayName("falls through when cache throws RuntimeException")
        void fallsThroughOnCacheException() {
            when(capabilityCache.get(TOKEN_ID)).thenThrow(new RuntimeException("cache unavailable"));
            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    TOKEN_ID, SERVICE_NAME, null,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "admin"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("admin");
        }
    }

    // ---- Strategy 2: L402 caveat extraction ----

    @Nested
    @DisplayName("Strategy 2: L402 caveat extraction")
    class CaveatExtraction {

        @Test
        @DisplayName("extracts capabilities from service_capabilities caveat")
        void extractsCapabilitiesFromCaveat() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "read")));
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential, Map.of());

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("read");
        }

        @Test
        @DisplayName("extracts multiple capabilities from comma-separated caveat value")
        void extractsMultipleCapabilities() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "read,write,admin")));
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential, Map.of());

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactlyInAnyOrder("read", "write", "admin");
        }

        @Test
        @DisplayName("merges capabilities from multiple matching caveats")
        void mergesMultipleCaveats() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "read"),
                    new Caveat(SERVICE_NAME + "_capabilities", "write")));
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential, Map.of());

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactlyInAnyOrder("read", "write");
        }

        @Test
        @DisplayName("skips strategy 2 when l402Credential is null")
        void skipsWhenNoCredential() {
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    TOKEN_ID, SERVICE_NAME, null,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "fallback"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("fallback");
        }

        @Test
        @DisplayName("skips strategy 2 when serviceName is null")
        void skipsWhenServiceNameNull() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "read")));
            var resolver = new DefaultCapabilityResolver(null, null);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), null, credential,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

            Set<String> result = resolver.resolve(context);

            // Falls through to strategy 3
            assertThat(result).containsExactly("metadata-cap");
        }

        @Test
        @DisplayName("ignores caveats with non-matching key")
        void ignoresNonMatchingCaveats() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat("other_capabilities", "read"),
                    new Caveat("expires_at", "2099-01-01")));
            var resolver = new DefaultCapabilityResolver(null, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "fallback"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("fallback");
        }
    }

    // ---- Strategy 3: Request metadata ----

    @Nested
    @DisplayName("Strategy 3: Request metadata fallback")
    class RequestMetadata {

        @Test
        @DisplayName("returns capability from request metadata")
        void returnsFromMetadata() {
            var resolver = new DefaultCapabilityResolver(null, null);
            var context = new CapabilityResolutionContext(
                    TOKEN_ID, null, null,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("search");
        }

        @Test
        @DisplayName("returns empty when metadata has no requested capability")
        void returnsEmptyWhenNoMetadata() {
            var resolver = new DefaultCapabilityResolver(null, null);
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
        @DisplayName("cache hit takes priority over caveat extraction and metadata")
        void cacheWinsOverCaveatsAndMetadata() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "caveat-cap")));
            when(capabilityCache.get(credential.tokenId())).thenReturn("cached-cap");

            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("cached-cap");
        }

        @Test
        @DisplayName("caveat extraction takes priority over metadata when cache misses")
        void caveatsWinOverMetadata() {
            var credential = credentialWithCaveats(List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "caveat-cap")));
            when(capabilityCache.get(credential.tokenId())).thenReturn(null);

            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(
                    credential.tokenId(), SERVICE_NAME, credential,
                    Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "metadata-cap"));

            Set<String> result = resolver.resolve(context);

            assertThat(result).containsExactly("caveat-cap");
        }

        @Test
        @DisplayName("returns empty when all strategies produce nothing")
        void allStrategiesEmpty() {
            when(capabilityCache.get(TOKEN_ID)).thenReturn(null);
            var resolver = new DefaultCapabilityResolver(capabilityCache, SERVICE_NAME);
            var context = new CapabilityResolutionContext(TOKEN_ID, SERVICE_NAME, null, Map.of());

            Set<String> result = resolver.resolve(context);

            assertThat(result).isEmpty();
        }
    }
}
