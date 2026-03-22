package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD RED-phase tests verifying that {@link PaygateSecurityFilter} populates
 * request metadata ({@code request.path}, {@code request.method}, {@code request.client_ip})
 * in the {@link L402VerificationContext} passed to {@link L402Validator}.
 *
 * <p>These tests compile against the current filter constructor but FAIL on assertions
 * because the filter does not yet populate {@code requestMetadata} in the verification context.
 * They will turn GREEN once the filter is updated to set request metadata.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygateSecurityFilter delegation — request metadata population")
class PaygateSecurityFilterDelegationTest {

    private static final String SERVICE_NAME = "test-service";
    private static final String PROTECTED_PATH = "/api/products";

    /**
     * A structurally valid L402 Authorization header. The macaroon base64 and preimage hex
     * need not represent real cryptographic material -- the validator mock accepts anything.
     * Format: {@code "L402 <base64-macaroon>:<64-hex-preimage>"}
     */
    private static final String VALID_L402_HEADER =
            "L402 " + "AAAA".repeat(20) + ":" + "ab".repeat(32);

    @Mock
    private PaygateEndpointRegistry registry;

    @Mock
    private L402Validator validator;

    @Mock
    private PaygateChallengeService challengeService;

    @Mock
    private FilterChain chain;

    @Captor
    private ArgumentCaptor<L402VerificationContext> contextCaptor;

    private PaygateSecurityFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new PaygateSecurityFilter(
                registry, validator, challengeService, SERVICE_NAME,
                null, null, null, null);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    /**
     * Creates a minimal but valid {@link L402Credential} suitable for test stubs.
     * Uses a dummy 66-byte identifier and 32-byte signature with empty caveats.
     */
    private static L402Credential createStubCredential() {
        byte[] identifier = new byte[66]; // 2-byte version + 32-byte paymentHash + 32-byte tokenId
        byte[] signature = new byte[32];
        Macaroon macaroon = new Macaroon(identifier, null, List.of(), signature);
        PaymentPreimage preimage = new PaymentPreimage(new byte[32]);
        return new L402Credential(macaroon, preimage, "ab".repeat(32));
    }

    /**
     * Configures the registry to recognize the given method+path as a protected endpoint
     * and the validator to return a successful result so the filter proceeds through
     * the validation path (not the 402 challenge path).
     */
    private void stubProtectedEndpointWithSuccessfulValidation(String method, String path) {
        var config = new PaygateEndpointConfig(method, path, 10L, 600L, "test", "", "");
        when(registry.findConfig(any(), any())).thenReturn(config);

        var result = new L402Validator.ValidationResult(createStubCredential(), true);
        when(validator.validate(any(L402HeaderComponents.class), any(L402VerificationContext.class)))
                .thenReturn(result);
    }

    // -----------------------------------------------------------------------
    // T022-1: filterPopulatesRequestPath
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("populates request.path in verification context metadata")
    void filterPopulatesRequestPath() throws Exception {
        stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

        request.setMethod("GET");
        request.setRequestURI(PROTECTED_PATH);
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        assertThat(ctx.getRequestMetadata())
                .as("Verification context should contain request.path metadata")
                .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/products");
    }

    // -----------------------------------------------------------------------
    // T022-2: filterPopulatesRequestMethod
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("populates request.method in verification context metadata")
    void filterPopulatesRequestMethod() throws Exception {
        stubProtectedEndpointWithSuccessfulValidation("POST", "/api/data");

        request.setMethod("POST");
        request.setRequestURI("/api/data");
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        assertThat(ctx.getRequestMetadata())
                .as("Verification context should contain request.method metadata")
                .containsEntry(VerificationContextKeys.REQUEST_METHOD, "POST");
    }

    // -----------------------------------------------------------------------
    // T022-3: filterPopulatesClientIp
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("populates request.client_ip in verification context metadata using remote addr")
    void filterPopulatesClientIp() throws Exception {
        stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

        request.setMethod("GET");
        request.setRequestURI(PROTECTED_PATH);
        request.setRemoteAddr("192.168.1.42");
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        // The filter should use ClientIpResolver (or at minimum the request's remote addr)
        // to populate the client IP in the verification context metadata.
        assertThat(ctx.getRequestMetadata())
                .as("Verification context should contain request.client_ip metadata")
                .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, "192.168.1.42");
    }

    // -----------------------------------------------------------------------
    // T022-4: filterNormalizesRequestPath
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("normalizes request path with dot-segments before populating metadata")
    void filterNormalizesRequestPath() throws Exception {
        // The filter normalizes getRequestURI() which may contain dot-segments.
        // The metadata should contain the normalized path.
        stubProtectedEndpointWithSuccessfulValidation("GET", "/products");

        request.setMethod("GET");
        request.setRequestURI("/api/../products");
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        assertThat(ctx.getRequestMetadata())
                .as("Verification context should contain normalized request.path (dot-segments resolved)")
                .containsEntry(VerificationContextKeys.REQUEST_PATH, "/products");
    }

    // -----------------------------------------------------------------------
    // T022-5b: FR-003b — %2F preserved in verification context request.path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("FR-003b: encoded slash %2F is preserved in verification context request.path")
    void filterPreservesEncodedSlashInRequestPath() throws Exception {
        // The path /api/v1%2Fbypass should be normalized to /api/v1%2Fbypass (slash preserved),
        // NOT decoded to /api/v1/bypass which would change the segment structure.
        stubProtectedEndpointWithSuccessfulValidation("GET", "/api/v1%2Fbypass");

        request.setMethod("GET");
        request.setRequestURI("/api/v1%2Fbypass");
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        assertThat(ctx.getRequestMetadata())
                .as("FR-003b: Encoded slash %2F must be preserved in request.path metadata")
                .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/v1%2Fbypass");
    }

    // -----------------------------------------------------------------------
    // T022-5: macaroonWithoutNewCaveatsStillAuthorized (FR-024 regression)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("macaroon without path/method/ip caveats still passes validation (FR-024)")
    void macaroonWithoutNewCaveatsStillAuthorized() throws Exception {
        // A macaroon that has no delegation caveats (path, method, client_ip) should
        // still be accepted. The request metadata should be present in the context
        // (for verifiers to use if caveats exist), but lack of those caveats should
        // not cause rejection. This test verifies the filter passes through successfully.
        stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

        request.setMethod("GET");
        request.setRequestURI(PROTECTED_PATH);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("Authorization", VALID_L402_HEADER);

        filter.doFilter(request, response, chain);

        // The filter should have called chain.doFilter (request passes through)
        verify(chain).doFilter(request, response);

        // And the validator should have been invoked with a context that has metadata
        // (the metadata map itself should be populated even if the macaroon has no
        // delegation caveats -- verifiers simply won't find matching caveats to check)
        verify(validator).validate(any(L402HeaderComponents.class), contextCaptor.capture());
        L402VerificationContext ctx = contextCaptor.getValue();

        assertThat(ctx.getRequestMetadata())
                .as("Request metadata should be populated even when macaroon has no delegation caveats")
                .containsKey(VerificationContextKeys.REQUEST_PATH)
                .containsKey(VerificationContextKeys.REQUEST_METHOD)
                .containsKey(VerificationContextKeys.REQUEST_CLIENT_IP);
    }
}
