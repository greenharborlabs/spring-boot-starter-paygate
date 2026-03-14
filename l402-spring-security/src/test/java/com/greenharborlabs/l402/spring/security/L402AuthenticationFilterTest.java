package com.greenharborlabs.l402.spring.security;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L402AuthenticationFilterTest {

    @Mock
    private AuthenticationManager authenticationManager;

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
        filter = new L402AuthenticationFilter(authenticationManager);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void constructorRejectsNullAuthenticationManager() {
        assertThatThrownBy(() -> new L402AuthenticationFilter(null))
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
        assertThat(unauthToken.getRawMacaroon()).isEqualTo(VALID_MACAROON_B64);
        assertThat(unauthToken.getRawPreimage()).isEqualTo(VALID_PREIMAGE);

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
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"L402 authentication failed\"}");
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
        assertThat(unauthToken.getRawPreimage()).isEqualTo(uppercasePreimage);
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
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Service temporarily unavailable\"}");
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
        assertThat(unauthToken.getRawMacaroon()).isEqualTo(VALID_MACAROON_B64 + "," + secondToken);
        assertThat(unauthToken.getRawPreimage()).isEqualTo(VALID_PREIMAGE);
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
}
