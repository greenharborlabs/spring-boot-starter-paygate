package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationProviderDelegationTest {

    @Mock
    private L402Validator l402Validator;

    @Mock
    private PaymentProtocol mppProtocol;

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SERVICE_NAME = "test-service";

    private static final String TEST_PATH = "/api/products";
    private static final String TEST_METHOD = "GET";
    private static final String TEST_CLIENT_IP = "192.168.1.42";

    // ========== L402 delegation tests (preserved from original) ==========

    @Nested
    @DisplayName("L402 delegation path")
    class L402DelegationTests {

        private PaygateAuthenticationProvider provider;

        @BeforeEach
        void setUp() {
            provider = new PaygateAuthenticationProvider(l402Validator, SERVICE_NAME);
        }

        @Test
        void providerIncludesRequestMetadataInContext() {
            L402Credential credential = createTestCredential(List.of());
            stubValidatorSuccess(credential);

            var unauthToken = createUnauthenticatedTokenWithMetadata(TEST_PATH, TEST_METHOD, TEST_CLIENT_IP);

            provider.authenticate(unauthToken);

            L402VerificationContext captured = captureVerificationContext();
            Map<String, String> metadata = captured.getRequestMetadata();
            assertThat(metadata)
                    .containsEntry(VerificationContextKeys.REQUEST_PATH, TEST_PATH)
                    .containsEntry(VerificationContextKeys.REQUEST_METHOD, TEST_METHOD)
                    .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, TEST_CLIENT_IP);
        }

        @Test
        void providerPassesPathFromTokenToContext() {
            L402Credential credential = createTestCredential(List.of());
            stubValidatorSuccess(credential);

            var unauthToken = createUnauthenticatedTokenWithMetadata(TEST_PATH, TEST_METHOD, TEST_CLIENT_IP);

            provider.authenticate(unauthToken);

            L402VerificationContext captured = captureVerificationContext();
            assertThat(captured.getRequestMetadata())
                    .containsEntry(VerificationContextKeys.REQUEST_PATH, TEST_PATH);
        }

        @Test
        void providerPassesMethodFromTokenToContext() {
            L402Credential credential = createTestCredential(List.of());
            stubValidatorSuccess(credential);

            var unauthToken = createUnauthenticatedTokenWithMetadata(TEST_PATH, TEST_METHOD, TEST_CLIENT_IP);

            provider.authenticate(unauthToken);

            L402VerificationContext captured = captureVerificationContext();
            assertThat(captured.getRequestMetadata())
                    .containsEntry(VerificationContextKeys.REQUEST_METHOD, TEST_METHOD);
        }

        @Test
        void providerPassesClientIpFromTokenToContext() {
            L402Credential credential = createTestCredential(List.of());
            stubValidatorSuccess(credential);

            var unauthToken = createUnauthenticatedTokenWithMetadata(TEST_PATH, TEST_METHOD, TEST_CLIENT_IP);

            provider.authenticate(unauthToken);

            L402VerificationContext captured = captureVerificationContext();
            assertThat(captured.getRequestMetadata())
                    .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, TEST_CLIENT_IP);
        }

        @Test
        void tokenWithoutDelegationCaveatsStillAuthenticates() {
            L402Credential credential = createTestCredential(List.of(new Caveat("service", "api")));
            stubValidatorSuccess(credential);

            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdG1hY2Fyb29u", "a".repeat(64)));

            Authentication result = provider.authenticate(unauthToken);

            assertThat(result).isNotNull();
            assertThat(result.isAuthenticated()).isTrue();

            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getTokenId()).isEqualTo(credential.tokenId());
            assertThat(authToken.getServiceName()).isEqualTo(SERVICE_NAME);
        }

        @Test
        @DisplayName("FR-003b Spring Security path: encoded slash %2F preserved in verification context")
        void encodedSlashPreservedInVerificationContext() {
            String pathWithEncodedSlash = "/api/products%2Fadmin";
            L402Credential credential = createTestCredential(List.of());
            stubValidatorSuccess(credential);

            var unauthToken = createUnauthenticatedTokenWithMetadata(
                    pathWithEncodedSlash, TEST_METHOD, TEST_CLIENT_IP);

            provider.authenticate(unauthToken);

            L402VerificationContext captured = captureVerificationContext();
            assertThat(captured.getRequestMetadata())
                    .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/products%2Fadmin");
        }

        private void stubValidatorSuccess(L402Credential credential) {
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));
        }

        private L402VerificationContext captureVerificationContext() {
            ArgumentCaptor<L402VerificationContext> captor =
                    ArgumentCaptor.forClass(L402VerificationContext.class);
            verify(l402Validator).validate(any(L402HeaderComponents.class), captor.capture());
            return captor.getValue();
        }
    }

    // ========== MPP / protocol-agnostic delegation tests ==========

    @Nested
    @DisplayName("Protocol-agnostic delegation path")
    class ProtocolDelegationTests {

        private PaygateAuthenticationProvider provider;

        @BeforeEach
        void setUp() {
            provider = new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
        }

        @Test
        void delegatesToMatchingProtocolForMppToken() {
            String authHeader = "Payment token123";
            PaymentCredential credential = createPaymentCredential("Payment");

            when(mppProtocol.canHandle(authHeader)).thenReturn(true);
            when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, "read", Map.of());

            Authentication result = provider.authenticate(unauthToken);

            assertThat(result).isInstanceOf(PaygateAuthenticationToken.class);
            assertThat(result.isAuthenticated()).isTrue();

            var authToken = (PaygateAuthenticationToken) result;
            assertThat(authToken.getPaymentCredential()).isEqualTo(credential);
            assertThat(authToken.getTokenId()).isEqualTo(credential.tokenId());
            assertThat(authToken.getServiceName()).isEqualTo(SERVICE_NAME);
            assertThat(authToken.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("ROLE_PAYMENT");
        }

        @Test
        void passesRequestMetadataToProtocolValidate() {
            String authHeader = "Payment token123";
            PaymentCredential credential = createPaymentCredential("Payment");
            Map<String, String> metadata = Map.of(
                    VerificationContextKeys.REQUEST_PATH, TEST_PATH,
                    VerificationContextKeys.REQUEST_METHOD, TEST_METHOD);

            when(mppProtocol.canHandle(authHeader)).thenReturn(true);
            when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, "read", metadata);

            provider.authenticate(unauthToken);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> metadataCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(mppProtocol).validate(eq(credential), metadataCaptor.capture());

            assertThat(metadataCaptor.getValue())
                    .containsEntry(VerificationContextKeys.REQUEST_PATH, TEST_PATH)
                    .containsEntry(VerificationContextKeys.REQUEST_METHOD, TEST_METHOD);
        }

        @Test
        void throwsBadCredentialsWhenProtocolValidationFails() {
            String authHeader = "Payment badtoken";
            PaymentCredential credential = createPaymentCredential("Payment");

            when(mppProtocol.canHandle(authHeader)).thenReturn(true);
            when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);
            doThrow(new PaymentValidationException(
                    PaymentValidationException.ErrorCode.INVALID_PREIMAGE, "bad preimage"))
                    .when(mppProtocol).validate(eq(credential), any());

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, null, Map.of());

            assertThatThrownBy(() -> provider.authenticate(unauthToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Payment authentication failed")
                    .hasCauseInstanceOf(PaymentValidationException.class);
        }

        @Test
        void throwsBadCredentialsWhenParseCredentialFails() {
            String authHeader = "Payment malformed";

            when(mppProtocol.canHandle(authHeader)).thenReturn(true);
            when(mppProtocol.parseCredential(authHeader))
                    .thenThrow(new PaymentValidationException(
                            PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL, "cannot parse"));

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, null, Map.of());

            assertThatThrownBy(() -> provider.authenticate(unauthToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Payment authentication failed")
                    .hasCauseInstanceOf(PaymentValidationException.class);
        }

        @Test
        void throwsBadCredentialsWhenNoProtocolCanHandle() {
            String authHeader = "Unknown xyz";

            when(mppProtocol.canHandle(authHeader)).thenReturn(false);

            var unauthToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, null, Map.of());

            assertThatThrownBy(() -> provider.authenticate(unauthToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("No payment protocol found");
        }

        @Test
        void throwsBadCredentialsWhenNeitherComponentsNorHeaderSet() {
            L402Credential credential = createTestCredential(List.of());
            var authenticatedToken = PaygateAuthenticationToken.authenticated(credential, "svc");

            assertThatThrownBy(() -> provider.authenticate(authenticatedToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Payment token missing credentials");
        }

        @Test
        void emptyProtocolListStillFallsBackToL402Path() {
            var providerNoProtocols = new PaygateAuthenticationProvider(
                    l402Validator, List.of(), SERVICE_NAME);

            L402Credential credential = createTestCredential(List.of());
            when(l402Validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                    .thenReturn(new L402Validator.ValidationResult(credential, true));

            var unauthToken = new PaygateAuthenticationToken(
                    new L402HeaderComponents("L402", "dGVzdA==", "a".repeat(64)));

            Authentication result = providerNoProtocols.authenticate(unauthToken);

            assertThat(result).isNotNull();
            assertThat(result.isAuthenticated()).isTrue();
        }
    }

    // --- shared helpers ---

    private PaygateAuthenticationToken createUnauthenticatedTokenWithMetadata(
            String path, String method, String clientIp) {
        Map<String, String> metadata = Map.of(
                VerificationContextKeys.REQUEST_PATH, path,
                VerificationContextKeys.REQUEST_METHOD, method,
                VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
        return new PaygateAuthenticationToken(
                new L402HeaderComponents("L402", "dGVzdG1hY2Fyb29u", "a".repeat(64)),
                "read",
                metadata);
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

    private PaymentCredential createPaymentCredential(String scheme) {
        byte[] paymentHash = new byte[32];
        byte[] preimage = new byte[32];
        byte[] tokenIdBytes = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(preimage);
        RNG.nextBytes(tokenIdBytes);

        String tokenId = HexFormat.of().formatHex(tokenIdBytes);
        return new PaymentCredential(paymentHash, preimage, tokenId, scheme, null,
                new ProtocolMetadata() {});
    }
}
