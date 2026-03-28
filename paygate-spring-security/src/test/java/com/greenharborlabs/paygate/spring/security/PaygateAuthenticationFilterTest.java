package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationFilterTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private PaygateEndpointRegistry endpointRegistry;

    @Mock
    private FilterChain filterChain;

    @Mock
    private Authentication authenticatedResult;

    private PaygateAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    private static final String VALID_PREIMAGE = "a".repeat(64);
    private static final String VALID_MACAROON_B64 = "dGVzdG1hY2Fyb29u";

    @BeforeEach
    void setUp() {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(), endpointRegistry);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void constructorRejectsNullAuthenticationManager() {
        assertThatThrownBy(() -> new PaygateAuthenticationFilter(null, List.of(), endpointRegistry))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullEndpointRegistry() {
        assertThatThrownBy(() -> new PaygateAuthenticationFilter(authenticationManager, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorAcceptsNullProtocols() {
        var f = new PaygateAuthenticationFilter(authenticationManager, null, endpointRegistry);
        assertThat(f).isNotNull();
    }

    @Test
    void skipsWhenNoAuthorizationHeader() throws ServletException, IOException {
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void skipsWhenBlankAuthorizationHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "   ");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void skipsWhenNonL402AuthorizationHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer some-jwt-token");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsL402CredentialAndAuthenticates() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        PaygateAuthenticationToken unauthToken = captor.getValue();
        assertThat(unauthToken.isAuthenticated()).isFalse();
        assertThat(unauthToken.getComponents()).isNotNull();
        assertThat(unauthToken.getComponents().macaroonBase64()).isEqualTo(VALID_MACAROON_B64);
        assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(VALID_PREIMAGE);
        assertThat(unauthToken.getComponents().scheme()).isEqualTo("L402");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void extractsLsatCredentialAndAuthenticates() throws ServletException, IOException {
        request.addHeader("Authorization", "LSAT " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        verify(authenticationManager).authenticate(any(PaygateAuthenticationToken.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void returns401WhenAuthenticationFails() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Invalid L402 credential"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"code\": 401, \"error\": \"AUTHENTICATION_FAILED\", \"message\": \"L402 authentication failed\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void skipsWhenPreimageNotHex() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":not-hex");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsUppercaseHexPreimageAndAuthenticates() throws ServletException, IOException {
        String uppercasePreimage = "A".repeat(64);
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + uppercasePreimage);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        PaygateAuthenticationToken unauthToken = captor.getValue();
        assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(uppercasePreimage);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void extractsMixedCaseHexPreimageAndAuthenticates() throws ServletException, IOException {
        String mixedCasePreimage = "aAbBcCdD".repeat(8);
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + mixedCasePreimage);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        verify(authenticationManager).authenticate(any(PaygateAuthenticationToken.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsWhenMacaroonEmpty() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 :" + VALID_PREIMAGE);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void returns503WhenRuntimeExceptionThrown() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new RuntimeException("gRPC channel unavailable"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void skipsWhenMacaroonExceedsMaxLength() throws ServletException, IOException {
        String oversizedMacaroon = "A".repeat(8193);
        request.addHeader("Authorization", "L402 " + oversizedMacaroon + ":" + VALID_PREIMAGE);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void skipsWhenMacaroonContainsInvalidCharacters() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 mac:with:colons:" + VALID_PREIMAGE);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsMultiTokenHeaderAndAuthenticates() throws ServletException, IOException {
        String secondToken = "c2Vjb25kdG9rZW4=";
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + "," + secondToken + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        PaygateAuthenticationToken unauthToken = captor.getValue();
        assertThat(unauthToken.getComponents().macaroonBase64()).isEqualTo(VALID_MACAROON_B64 + "," + secondToken);
        assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(VALID_PREIMAGE);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsWhenMultiTokenExceedsMaxLength() throws ServletException, IOException {
        String oversizedTokens = "A".repeat(4000) + "," + "B".repeat(4193);
        request.addHeader("Authorization", "L402 " + oversizedTokens + ":" + VALID_PREIMAGE);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    // --- Capability lookup tests ---

    @Test
    void passesCapabilityFromRegistryToToken() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/protected");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("read");
    }

    @Test
    void passesNullCapabilityWhenConfigNotFound() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/unregistered");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestMetadata()).doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
    }

    @Test
    void passesNullCapabilityWhenConfigHasEmptyCapability() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/no-capability");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("POST", "/api/no-capability", 10, 3600, "desc", "", "");
        when(endpointRegistry.findConfig("POST", "/api/no-capability")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestMetadata()).doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
    }

    @Test
    void passesNullCapabilityWhenConfigHasBlankCapability() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/blank-cap");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/blank-cap", 10, 3600, "desc", "", "   ");
        when(endpointRegistry.findConfig("GET", "/api/blank-cap")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestMetadata()).doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
    }

    @Test
    void returns503WhenRegistryThrowsException() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/error-path");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        when(endpointRegistry.findConfig(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("path normalization failed"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticationManager, never()).authenticate(any());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void normalizesPathTraversalBeforeRegistryLookup() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/admin/../protected");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        verify(endpointRegistry).findConfig("GET", "/api/protected");
        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("read");
    }

    @Test
    void normalizesPercentEncodedTraversalBeforeRegistryLookup() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/admin/%2e%2e/protected");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "write");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        verify(endpointRegistry).findConfig("GET", "/api/protected");
        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("write");
    }

    @Test
    void passesNullCapabilityWhenConfigHasNullCapability() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/null-cap");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/null-cap", 10, 3600, "desc", "", null);
        when(endpointRegistry.findConfig("GET", "/api/null-cap")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestMetadata()).doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
    }

    // --- Protocol-agnostic (MPP) detection tests ---

    private PaymentProtocol mockMppProtocol() {
        PaymentProtocol protocol = mock(PaymentProtocol.class);
        when(protocol.canHandle(anyString())).thenAnswer(invocation -> {
            String header = invocation.getArgument(0);
            return header.startsWith("Payment ");
        });
        return protocol;
    }

    @Test
    void detectsMppCredentialViaProtocolCanHandle() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.addHeader("Authorization", "Payment preimage=abc123");
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        PaygateAuthenticationToken token = captor.getValue();
        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.getAuthorizationHeader()).isEqualTo("Payment preimage=abc123");
        assertThat(token.getComponents()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void mppTokenIncludesRequestMetadata() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.setMethod("POST");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "Payment preimage=abc123");
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        Map<String, String> metadata = captor.getValue().getRequestMetadata();
        assertThat(metadata).containsEntry("request.path", "/api/resource");
        assertThat(metadata).containsEntry("request.method", "POST");
        assertThat(metadata).containsKey("request.client_ip");
    }

    @Test
    void mppTokenIncludesCapabilityFromRegistry() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.setMethod("GET");
        request.setRequestURI("/api/premium");
        request.addHeader("Authorization", "Payment preimage=abc123");
        var config = new PaygateEndpointConfig("GET", "/api/premium", 10, 3600, "desc", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/premium")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("read");
    }

    @Test
    void skipsWhenNoProtocolMatchesHeader() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.addHeader("Authorization", "Bearer some-jwt-token");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void l402TakesPrecedenceOverProtocolMatch() throws ServletException, IOException {
        // Use a lenient mock since canHandle should NOT be called when L402 is detected first
        PaymentProtocol alwaysMatch = mock(PaymentProtocol.class, withSettings().lenient());
        when(alwaysMatch.canHandle(anyString())).thenReturn(true);
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(alwaysMatch), endpointRegistry);

        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<PaygateAuthenticationToken> captor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        PaygateAuthenticationToken token = captor.getValue();
        assertThat(token.getComponents()).isNotNull();
        assertThat(token.getAuthorizationHeader()).isNull();
        verify(alwaysMatch, never()).canHandle(anyString());
    }

    @Test
    void mppAuthenticationFailureReturns401() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.addHeader("Authorization", "Payment preimage=invalid");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Invalid payment credential"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void mppRuntimeExceptionReturns503() throws ServletException, IOException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

        request.addHeader("Authorization", "Payment preimage=abc123");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new RuntimeException("backend unavailable"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    // --- shouldNotFilter tests ---

    @Test
    void shouldNotFilterWhenNoAuthorizationHeader() throws ServletException {
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilterWhenBlankAuthorizationHeader() throws ServletException {
        request.addHeader("Authorization", "   ");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilterWhenUnrecognizedAuthScheme() throws ServletException {
        request.addHeader("Authorization", "Bearer some-jwt-token");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldFilterWhenL402AuthorizationHeader() throws ServletException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void shouldFilterWhenMppProtocolMatches() throws ServletException {
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mockMppProtocol()), endpointRegistry);
        request.addHeader("Authorization", "Payment preimage=abc123");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    // --- Receipt generation tests ---

    private PaymentProtocol mockMppProtocolWithScheme() {
        PaymentProtocol protocol = mock(PaymentProtocol.class, withSettings().lenient());
        when(protocol.canHandle(anyString())).thenAnswer(invocation -> {
            String header = invocation.getArgument(0);
            return header.startsWith("Payment ");
        });
        when(protocol.scheme()).thenReturn("Payment");
        return protocol;
    }

    private PaygateAuthenticationToken createAuthenticatedMppToken() {
        byte[] paymentHash = new byte[32];
        byte[] preimage = new byte[32];
        PaymentCredential credential = new PaymentCredential(
                paymentHash, preimage, "test-token-id", "Payment", null,
                new ProtocolMetadata() {});
        return PaygateAuthenticationToken.authenticated(credential, "test-service");
    }

    @Test
    void mppAuthenticationProducesPaymentReceiptHeader() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
        var receipt = new PaymentReceipt("success", "challenge-123", "lightning",
                null, 100, "2026-03-26T00:00:00Z", "Payment");
        when(mppProtocol.createReceipt(any(PaymentCredential.class), any(ChallengeContext.class)))
                .thenReturn(Optional.of(receipt));

        PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "Payment preimage=abc123");

        var config = new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Payment-Receipt")).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void l402AuthenticationDoesNotProducePaymentReceiptHeader() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
        // L402 authenticated token has null paymentCredential
        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Payment-Receipt")).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void receiptCreationFailureDoesNotBlockRequest() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
        when(mppProtocol.createReceipt(any(PaymentCredential.class), any(ChallengeContext.class)))
                .thenThrow(new RuntimeException("receipt creation failed"));

        PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "Payment preimage=abc123");

        var config = new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Payment-Receipt")).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingEndpointConfigSkipsReceiptButSucceeds() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
        PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/unregistered");
        request.addHeader("Authorization", "Payment preimage=abc123");

        when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Payment-Receipt")).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
        verify(mppProtocol, never()).createReceipt(any(), any());
    }

    @Test
    void nonPaygateAuthenticationTokenSkipsReceiptAndSucceeds() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();

        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "Payment preimage=abc123");

        var config = new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
        // Return a non-PaygateAuthenticationToken
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Payment-Receipt")).isNull();
        verify(filterChain).doFilter(request, response);
        verify(mppProtocol, never()).createReceipt(any(), any());
    }

    @Test
    void challengeContextBolt11InvoiceIsEmptyString() throws ServletException, IOException {
        PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
        when(mppProtocol.createReceipt(any(PaymentCredential.class), any(ChallengeContext.class)))
                .thenReturn(Optional.empty());

        PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

        filter = new PaygateAuthenticationFilter(authenticationManager, List.of(mppProtocol),
                endpointRegistry, null, "test-service");

        request.setMethod("GET");
        request.setRequestURI("/api/resource");
        request.addHeader("Authorization", "Payment preimage=abc123");

        var config = new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<ChallengeContext> contextCaptor = ArgumentCaptor.forClass(ChallengeContext.class);
        verify(mppProtocol).createReceipt(any(PaymentCredential.class), contextCaptor.capture());

        ChallengeContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.bolt11Invoice()).isEqualTo("");
        assertThat(capturedContext.priceSats()).isEqualTo(100);
        assertThat(capturedContext.description()).isEqualTo("Test resource");
        assertThat(capturedContext.serviceName()).isEqualTo("test-service");
        assertThat(capturedContext.timeoutSeconds()).isEqualTo(3600);
        assertThat(capturedContext.capability()).isEqualTo("read");
        assertThat(capturedContext.rootKeyBytes()).isNull();
        assertThat(capturedContext.opaque()).isNull();
        assertThat(capturedContext.digest()).isNull();
    }
}
