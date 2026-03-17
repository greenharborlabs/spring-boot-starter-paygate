package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L402AuthenticationFilterTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private L402EndpointRegistry endpointRegistry;

    @Mock
    private FilterChain filterChain;

    @Mock
    private Authentication authenticatedResult;

    private L402AuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    private static final String VALID_PREIMAGE = "a".repeat(64);
    private static final String VALID_MACAROON_B64 = "dGVzdG1hY2Fyb29u";

    @BeforeEach
    void setUp() {
        filter = new L402AuthenticationFilter(authenticationManager, endpointRegistry);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void constructorRejectsNullAuthenticationManager() {
        assertThatThrownBy(() -> new L402AuthenticationFilter(null, endpointRegistry))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullEndpointRegistry() {
        assertThatThrownBy(() -> new L402AuthenticationFilter(authenticationManager, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void skipsWhenNoAuthorizationHeader() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void skipsWhenBlankAuthorizationHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "   ");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void skipsWhenNonL402AuthorizationHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer some-jwt-token");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsL402CredentialAndAuthenticates() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        L402AuthenticationToken unauthToken = captor.getValue();
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

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationManager).authenticate(any(L402AuthenticationToken.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void returns401WhenAuthenticationFails() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Invalid L402 credential"));

        filter.doFilterInternal(request, response, filterChain);

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

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsUppercaseHexPreimageAndAuthenticates() throws ServletException, IOException {
        String uppercasePreimage = "A".repeat(64);
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + uppercasePreimage);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        L402AuthenticationToken unauthToken = captor.getValue();
        assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(uppercasePreimage);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void extractsMixedCaseHexPreimageAndAuthenticates() throws ServletException, IOException {
        String mixedCasePreimage = "aAbBcCdD".repeat(8);
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + mixedCasePreimage);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationManager).authenticate(any(L402AuthenticationToken.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsWhenMacaroonEmpty() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 :" + VALID_PREIMAGE);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void returns503WhenRuntimeExceptionThrown() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new RuntimeException("gRPC channel unavailable"));

        filter.doFilterInternal(request, response, filterChain);

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

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void skipsWhenMacaroonContainsInvalidCharacters() throws ServletException, IOException {
        request.addHeader("Authorization", "L402 mac:with:colons:" + VALID_PREIMAGE);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void extractsMultiTokenHeaderAndAuthenticates() throws ServletException, IOException {
        String secondToken = "c2Vjb25kdG9rZW4=";
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + "," + secondToken + ":" + VALID_PREIMAGE);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        L402AuthenticationToken unauthToken = captor.getValue();
        assertThat(unauthToken.getComponents().macaroonBase64()).isEqualTo(VALID_MACAROON_B64 + "," + secondToken);
        assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(VALID_PREIMAGE);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsWhenMultiTokenExceedsMaxLength() throws ServletException, IOException {
        String oversizedTokens = "A".repeat(4000) + "," + "B".repeat(4193);
        request.addHeader("Authorization", "L402 " + oversizedTokens + ":" + VALID_PREIMAGE);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationManager, never()).authenticate(any());
    }

    // --- Capability lookup tests ---

    @Test
    void passesCapabilityFromRegistryToToken() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/protected");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new L402EndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isEqualTo("read");
    }

    @Test
    void passesNullCapabilityWhenConfigNotFound() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/unregistered");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isNull();
    }

    @Test
    void passesNullCapabilityWhenConfigHasEmptyCapability() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/no-capability");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new L402EndpointConfig("POST", "/api/no-capability", 10, 3600, "desc", "", "");
        when(endpointRegistry.findConfig("POST", "/api/no-capability")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isNull();
    }

    @Test
    void passesNullCapabilityWhenConfigHasBlankCapability() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/blank-cap");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new L402EndpointConfig("GET", "/api/blank-cap", 10, 3600, "desc", "", "   ");
        when(endpointRegistry.findConfig("GET", "/api/blank-cap")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isNull();
    }

    @Test
    void passesNullCapabilityWhenRegistryThrowsException() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/error-path");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        when(endpointRegistry.findConfig(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("path normalization failed"));
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticatedResult);
    }

    @Test
    void passesNullCapabilityWhenConfigHasNullCapability() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/null-cap");
        request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

        var config = new L402EndpointConfig("GET", "/api/null-cap", 10, 3600, "desc", "", null);
        when(endpointRegistry.findConfig("GET", "/api/null-cap")).thenReturn(config);
        when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<L402AuthenticationToken> captor = ArgumentCaptor.forClass(L402AuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertThat(captor.getValue().getRequestedCapability()).isNull();
    }
}
