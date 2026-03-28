package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.spring.CapabilityCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationProviderTest {

    @Mock
    private L402Validator l402Validator;

    private PaygateAuthenticationProvider provider;

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SERVICE_NAME = "test-service";

    @BeforeEach
    void setUp() {
        provider = new PaygateAuthenticationProvider(l402Validator, SERVICE_NAME);
    }

    @Test
    void constructorRejectsNullValidator() {
        assertThatThrownBy(() -> new PaygateAuthenticationProvider(null, "svc"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void supportsPaygateAuthenticationToken() {
        assertThat(provider.supports(PaygateAuthenticationToken.class)).isTrue();
    }

    @Test
    void doesNotSupportOtherTokenTypes() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    void returnsNullForNonPaygateAuthentication() {
        var otherAuth = new UsernamePasswordAuthenticationToken("user", "pass");

        Authentication result = provider.authenticate(otherAuth);

        assertThat(result).isNull();
    }

    @Test
    void authenticatesValidL402Token() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);

        L402Credential credential = createTestCredential(List.of(new Caveat("service", "api")));
        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new PaygateAuthenticationToken(new L402HeaderComponents("L402", macaroonB64, preimageHex));

        Authentication result = provider.authenticate(unauthToken);

        assertThat(result).isInstanceOf(PaygateAuthenticationToken.class);
        assertThat(result.isAuthenticated()).isTrue();

        var authToken = (PaygateAuthenticationToken) result;
        assertThat(authToken.getTokenId()).isEqualTo(credential.tokenId());
        assertThat(authToken.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(authToken.getL402Credential()).isEqualTo(credential);
        assertThat(authToken.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
        assertThat(authToken.getAttribute("service")).isEqualTo("api");
    }

    @Test
    void throwsBadCredentialsOnValidationFailure() {
        String macaroonB64 = "badmac";
        String preimageHex = "b".repeat(64);

        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenThrow(new L402Exception(ErrorCode.INVALID_MACAROON, "bad sig", "tok1"));

        var unauthToken = new PaygateAuthenticationToken(new L402HeaderComponents("L402", macaroonB64, preimageHex));

        assertThatThrownBy(() -> provider.authenticate(unauthToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("L402 authentication failed")
                .hasMessageNotContaining("bad sig")
                .hasCauseInstanceOf(L402Exception.class);
    }

    @Test
    void throwsBadCredentialsWhenComponentsMissing() {
        L402Credential credential = createTestCredential(List.of());
        var authenticatedToken = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThatThrownBy(() -> provider.authenticate(authenticatedToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Payment token missing credentials");
    }

    @Test
    void allowsNullServiceName() {
        var providerNoService = new PaygateAuthenticationProvider(l402Validator, null);

        String macaroonB64 = "dGVzdA==";
        String preimageHex = "c".repeat(64);

        L402Credential credential = createTestCredential(List.of());
        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new PaygateAuthenticationToken(new L402HeaderComponents("L402", macaroonB64, preimageHex));
        Authentication result = providerNoService.authenticate(unauthToken);

        assertThat(result).isInstanceOf(PaygateAuthenticationToken.class);
        assertThat(((PaygateAuthenticationToken) result).getServiceName()).isNull();
    }

    @Test
    void passesRequestedCapabilityThroughToValidatorContext() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);
        L402Credential credential = createTestCredential(List.of(new Caveat("capabilities", "read")));
        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new PaygateAuthenticationToken(
                new L402HeaderComponents("L402", macaroonB64, preimageHex),
                Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "read"));

        Authentication result = provider.authenticate(unauthToken);

        assertThat(result.isAuthenticated()).isTrue();

        ArgumentCaptor<L402VerificationContext> contextCaptor =
                ArgumentCaptor.forClass(L402VerificationContext.class);
        verify(l402Validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());

        L402VerificationContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(capturedContext.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("read");
    }

    @Test
    void passesNullCapabilityWhenNotSpecified() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);

        L402Credential credential = createTestCredential(List.of());
        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new PaygateAuthenticationToken(new L402HeaderComponents("L402", macaroonB64, preimageHex));

        provider.authenticate(unauthToken);

        ArgumentCaptor<L402VerificationContext> contextCaptor =
                ArgumentCaptor.forClass(L402VerificationContext.class);
        verify(l402Validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());

        L402VerificationContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(capturedContext.getRequestMetadata()).doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
    }

    @Test
    void capabilityMismatchResultsInBadCredentialsException() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);

        when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenThrow(new L402Exception(ErrorCode.INVALID_SERVICE, "capability mismatch", "tok1"));

        var unauthToken = new PaygateAuthenticationToken(
                new L402HeaderComponents("L402", macaroonB64, preimageHex),
                Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write"));

        assertThatThrownBy(() -> provider.authenticate(unauthToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("L402 authentication failed")
                .hasCauseInstanceOf(L402Exception.class);
    }

    // ========== CapabilityCache integration tests ==========

    @Nested
    @DisplayName("CapabilityCache resolution")
    class CapabilityCacheTests {

        @Mock
        private CapabilityCache capabilityCache;

        @Test
        void l402WithCachedCapabilityAddsPaygateCapabilityAuthority() {
            L402Credential credential = createTestCredential(List.of());
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));
            when(capabilityCache.get(credential.tokenId())).thenReturn("search");

            var providerWithCache = new PaygateAuthenticationProvider(
                    l402Validator, List.of(), SERVICE_NAME, capabilityCache);

            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdA==", "a".repeat(64)));

            Authentication result = providerWithCache.authenticate(unauthToken);

            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("PAYGATE_CAPABILITY_search", "ROLE_PAYMENT", "ROLE_L402");
        }

        @Test
        void l402WithoutCachedCapabilityHasOnlyRoleAuthorities() {
            L402Credential credential = createTestCredential(
                    List.of(new Caveat(SERVICE_NAME + "_capabilities", "read")));
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));
            when(capabilityCache.get(credential.tokenId())).thenReturn(null);

            var providerWithCache = new PaygateAuthenticationProvider(
                    l402Validator, List.of(), SERVICE_NAME, capabilityCache);

            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdA==", "a".repeat(64)));

            Authentication result = providerWithCache.authenticate(unauthToken);

            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("ROLE_PAYMENT", "ROLE_L402", "L402_CAPABILITY_read")
                    .doesNotContain("PAYGATE_CAPABILITY_read");
        }

        @Test
        void nullCachePreservesExistingBehavior() {
            L402Credential credential = createTestCredential(List.of());
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));

            // provider from setUp() has null cache
            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdA==", "a".repeat(64)));

            Authentication result = provider.authenticate(unauthToken);

            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
            verifyNoInteractions(capabilityCache);
        }

        @Test
        void cacheThrowsRuntimeExceptionStillAuthenticates() {
            L402Credential credential = createTestCredential(List.of());
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));
            when(capabilityCache.get(credential.tokenId()))
                    .thenThrow(new RuntimeException("cache unavailable"));

            var providerWithCache = new PaygateAuthenticationProvider(
                    l402Validator, List.of(), SERVICE_NAME, capabilityCache);

            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdA==", "a".repeat(64)));

            Authentication result = providerWithCache.authenticate(unauthToken);

            assertThat(result.isAuthenticated()).isTrue();
            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
        }

        @Test
        void mppWithCachedCapabilityAddsPaygateCapabilityAuthority() {
            byte[] paymentHash = new byte[32];
            byte[] preimage = new byte[32];
            RNG.nextBytes(paymentHash);
            RNG.nextBytes(preimage);
            String tokenId = HexFormat.of().formatHex(preimage);

            PaymentCredential credential = new PaymentCredential(
                    paymentHash, preimage, tokenId, "Payment", null,
                    new ProtocolMetadata() {});

            PaymentProtocol mockProtocol = new PaymentProtocol() {
                @Override public String scheme() { return "Payment"; }
                @Override public boolean canHandle(String header) { return header.startsWith("Payment "); }
                @Override public PaymentCredential parseCredential(String header) { return credential; }
                @Override public void validate(PaymentCredential c, Map<String, String> ctx) { /* valid */ }
                @Override public com.greenharborlabs.paygate.api.ChallengeResponse formatChallenge(
                        com.greenharborlabs.paygate.api.ChallengeContext ctx) { return null; }
            };

            when(capabilityCache.get(tokenId)).thenReturn("analyze");

            var providerWithCache = new PaygateAuthenticationProvider(
                    l402Validator, List.of(mockProtocol), SERVICE_NAME, capabilityCache);

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    "Payment dGVzdA==", Map.of());

            Authentication result = providerWithCache.authenticate(unauthToken);

            assertThat(result.isAuthenticated()).isTrue();
            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("PAYGATE_CAPABILITY_analyze", "ROLE_PAYMENT");
        }
    }

    private L402Credential createTestCredential(List<Caveat> caveats) {
        byte[] paymentHash = new byte[32];
        byte[] tokenIdBytes = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(tokenIdBytes);

        var identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        byte[] idBytes = MacaroonIdentifier.encode(identifier);
        byte[] sig = new byte[32];
        RNG.nextBytes(sig);

        var macaroon = new Macaroon(idBytes, null, caveats, sig);
        var preimage = new PaymentPreimage(paymentHash);

        String tokenId = HexFormat.of().formatHex(tokenIdBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }
}
