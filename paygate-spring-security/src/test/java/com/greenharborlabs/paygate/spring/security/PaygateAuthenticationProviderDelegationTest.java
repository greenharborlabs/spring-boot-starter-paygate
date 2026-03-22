package com.greenharborlabs.paygate.spring.security;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the Spring Security authentication path populates request metadata
 * (path, method, client IP) in the {@link L402VerificationContext} passed to the validator.
 *
 * <p>RED phase: these tests will FAIL because {@link PaygateAuthenticationProvider} does not
 * yet read request metadata from the token and include it in the verification context.
 */
@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationProviderDelegationTest {

    @Mock
    private L402Validator l402Validator;

    private PaygateAuthenticationProvider provider;

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SERVICE_NAME = "test-service";

    private static final String TEST_PATH = "/api/products";
    private static final String TEST_METHOD = "GET";
    private static final String TEST_CLIENT_IP = "192.168.1.42";

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

        // Use a plain token with no request metadata — simulates a legacy token
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

    // --- helpers ---

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
