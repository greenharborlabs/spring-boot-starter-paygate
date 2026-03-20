package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.spring.L402ChallengeResult;
import com.greenharborlabs.l402.spring.L402ChallengeService;
import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import com.greenharborlabs.l402.spring.L402LightningUnavailableException;
import com.greenharborlabs.l402.spring.L402RateLimitedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L402AuthenticationEntryPointTest {

    @Mock
    private L402ChallengeService challengeService;

    @Mock
    private L402EndpointRegistry endpointRegistry;

    private L402AuthenticationEntryPoint entryPoint;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    private static final L402EndpointConfig TEST_CONFIG = new L402EndpointConfig(
            "GET", "/api/protected", 100, 3600, "Test endpoint", "", "");

    private static final L402ChallengeResult TEST_RESULT = new L402ChallengeResult(
            "bWFjYXJvb24=",
            "lnbc1000n1test",
            "L402 version=\"0\", token=\"bWFjYXJvb24=\", macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc1000n1test\"",
            100,
            "Test endpoint",
            null
    );

    @BeforeEach
    void setUp() {
        entryPoint = new L402AuthenticationEntryPoint(challengeService, endpointRegistry);
        request = new MockHttpServletRequest("GET", "/api/protected");
        request.setRequestURI("/api/protected");
        response = new MockHttpServletResponse();
    }

    @Test
    void constructorRejectsNullChallengeService() {
        assertThatThrownBy(() -> new L402AuthenticationEntryPoint(null, endpointRegistry))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("challengeService");
    }

    @Test
    void constructorRejectsNullEndpointRegistry() {
        assertThatThrownBy(() -> new L402AuthenticationEntryPoint(challengeService, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpointRegistry");
    }

    @Test
    void writes402WhenConfigFoundAndChallengeCreated() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_RESULT);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(402);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(TEST_RESULT.wwwAuthenticateHeader());
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 402, \"message\": \"Payment required\", \"price_sats\": 100, \"description\": \"Test endpoint\", \"invoice\": \"lnbc1000n1test\"}");
    }

    @Test
    void writes402WithTestPreimageInTestMode() throws Exception {
        var resultWithPreimage = new L402ChallengeResult(
                "bWFjYXJvb24=", "lnbc1000n1test",
                "L402 version=\"0\", token=\"bWFjYXJvb24=\", macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc1000n1test\"",
                100, "Test endpoint", "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234");

        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(resultWithPreimage);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(402);
        assertThat(response.getContentAsString()).contains("\"test_preimage\": \"abcd1234");
    }

    @Test
    void writes401WhenNoConfigFound() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(null);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 401, \"error\": \"UNAUTHORIZED\", \"message\": \"Authentication required\"}");
    }

    @Test
    void writes429WhenRateLimited() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG)))
                .thenThrow(new L402RateLimitedException("Rate limit exceeded"));

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 429, \"error\": \"RATE_LIMITED\", \"message\": \"Too many payment challenge requests. Please try again later.\"}");
    }

    @Test
    void writes503WhenLightningUnavailable() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG)))
                .thenThrow(new L402LightningUnavailableException("Backend down"));

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
    }

    @Test
    void writes503OnUnexpectedException() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG)))
                .thenThrow(new RuntimeException("Unexpected error"));

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
    }

    @Test
    void normalizesPathBeforeLookup() throws Exception {
        request.setRequestURI("/api/../api/protected");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_RESULT);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(402);
    }

    @Test
    void normalizesPercentEncodedPathBeforeLookup() throws Exception {
        request.setRequestURI("/api/%2e%2e/api/protected");
        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_RESULT);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(402);
    }

    @Test
    void normalizePathReturnsSlashForNull() {
        assertThat(L402AuthenticationEntryPoint.normalizePath(null)).isEqualTo("/");
    }

    @Test
    void normalizePathReturnsSlashForEmpty() {
        assertThat(L402AuthenticationEntryPoint.normalizePath("")).isEqualTo("/");
    }

    @Test
    void normalizePathCollapsesDoubleDots() {
        assertThat(L402AuthenticationEntryPoint.normalizePath("/a/b/../c")).isEqualTo("/a/c");
    }

    @Test
    void normalizePathCollapsesSingleDots() {
        assertThat(L402AuthenticationEntryPoint.normalizePath("/a/./b")).isEqualTo("/a/b");
    }

    @Test
    void normalizePathDecodesPercentEncoding() {
        assertThat(L402AuthenticationEntryPoint.normalizePath("/api/%2e%2e/secret")).isEqualTo("/secret");
    }

    @Test
    void normalizePathHandlesDoubleEncoding() {
        // %252e decodes to %2e, which then decodes to .
        assertThat(L402AuthenticationEntryPoint.normalizePath("/api/%252e%252e/secret")).isEqualTo("/secret");
    }

    @Test
    void normalizePathDoesNotTraverseAboveRoot() {
        assertThat(L402AuthenticationEntryPoint.normalizePath("/../../etc/passwd")).isEqualTo("/etc/passwd");
    }

    @Test
    void writes503WhenEndpointRegistryThrows() throws Exception {
        when(endpointRegistry.findConfig("GET", "/api/protected"))
                .thenThrow(new RuntimeException("Registry broken"));

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("LIGHTNING_UNAVAILABLE");
    }

    @Test
    void jsonBodyMatchesFilterFormatExactly() throws Exception {
        var result = new L402ChallengeResult(
                "bWFjYXJvb24=", "lnbc500u1test",
                "L402 version=\"0\", token=\"bWFjYXJvb24=\", macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc500u1test\"",
                50, "A \"quoted\" description", null);

        when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
        when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(result);

        entryPoint.commence(request, response, new BadCredentialsException("test"));

        // The description should be JSON-escaped (quotes escaped)
        String body = response.getContentAsString();
        assertThat(body).startsWith("{\"code\": 402,");
        assertThat(body).contains("\"price_sats\": 50");
        assertThat(body).contains("\"description\": \"A \\\"quoted\\\" description\"");
        assertThat(body).doesNotContain("test_preimage");
    }
}
