package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateLightningUnavailableException;
import com.greenharborlabs.paygate.spring.PaygateRateLimitedException;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationEntryPointTest {

  @Mock private PaygateChallengeService challengeService;

  @Mock private PaygateEndpointRegistry endpointRegistry;

  @Mock private PaymentProtocol protocol;

  private PaygateAuthenticationEntryPoint entryPoint;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  private static final PaygateEndpointConfig TEST_CONFIG =
      new PaygateEndpointConfig("GET", "/api/protected", 100, 3600, "Test endpoint", "", "");

  private static final ChallengeContext TEST_CONTEXT =
      new ChallengeContext(
          new byte[32],
          "aa".repeat(32),
          "lnbc1000n1test",
          100,
          "Test endpoint",
          "test-service",
          3600,
          "",
          new byte[32],
          null,
          null);

  private static final ChallengeResponse DEFAULT_CHALLENGE =
      new ChallengeResponse("L402 token=\"test-token\", invoice=\"lnbc1000n1test\"", "L402", null);

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient()
        .when(protocol.formatChallenge(any()))
        .thenReturn(DEFAULT_CHALLENGE);
    entryPoint =
        new PaygateAuthenticationEntryPoint(challengeService, endpointRegistry, List.of(protocol));
    request = new MockHttpServletRequest("GET", "/api/protected");
    request.setRequestURI("/api/protected");
    response = new MockHttpServletResponse();
  }

  @Test
  void constructorRejectsNullChallengeService() {
    assertThatThrownBy(() -> new PaygateAuthenticationEntryPoint(null, endpointRegistry, List.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("challengeService");
  }

  @Test
  void constructorRejectsNullEndpointRegistry() {
    assertThatThrownBy(() -> new PaygateAuthenticationEntryPoint(challengeService, null, List.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("endpointRegistry");
  }

  @Test
  void constructorRejectsNullProtocols() {
    assertThatThrownBy(
            () -> new PaygateAuthenticationEntryPoint(challengeService, endpointRegistry, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("protocols");
  }

  @Test
  void writes402WhenConfigFoundAndChallengeCreated() throws Exception {
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString()).contains("\"code\": 402");
    assertThat(response.getContentAsString()).contains("\"price_sats\": 100");
    assertThat(response.getContentAsString()).contains("\"invoice\": \"lnbc1000n1test\"");
  }

  @Test
  void writes402WithTestPreimageInOpaqueMap() throws Exception {
    var contextWithPreimage =
        new ChallengeContext(
            new byte[32],
            "aa".repeat(32),
            "lnbc1000n1test",
            100,
            "Test endpoint",
            "test-service",
            3600,
            "",
            new byte[32],
            Map.of(
                "test_preimage",
                "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"),
            null);

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(contextWithPreimage);

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
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 401, \"error\": \"UNAUTHORIZED\", \"message\": \"Authentication required\"}");
  }

  @Test
  void writes429WhenRateLimited() throws Exception {
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG)))
        .thenThrow(new PaygateRateLimitedException("Rate limit exceeded"));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 429, \"error\": \"RATE_LIMITED\", \"message\": \"Too many payment challenge requests. Please try again later.\"}");
  }

  @Test
  void writes503WhenLightningUnavailable() throws Exception {
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG)))
        .thenThrow(new PaygateLightningUnavailableException("Backend down"));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
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
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
  }

  @Test
  void writes400WhenRequestUriIsMalformed() throws Exception {
    HttpServletRequest malformedRequest = mock(HttpServletRequest.class);
    when(malformedRequest.getMethod()).thenReturn("GET");
    when(malformedRequest.getRequestURI()).thenThrow(new IllegalArgumentException("malformed"));

    entryPoint.commence(malformedRequest, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo("application/json");
    MockHttpServletResponse expected = new MockHttpServletResponse();
    PaygateResponseWriter.writeMalformedUri(expected);

    assertThat(response.getContentAsString()).isEqualTo(expected.getContentAsString());
  }

  @Test
  void normalizesPathBeforeLookup() throws Exception {
    request.setRequestURI("/api/../api/protected");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
  }

  @Test
  void normalizesPercentEncodedPathBeforeLookup() throws Exception {
    request.setRequestURI("/api/%2e%2e/api/protected");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
  }

  @Test
  void normalizePathReturnsSlashForNull() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath(null)).isEqualTo("/");
  }

  @Test
  void normalizePathReturnsSlashForEmpty() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("")).isEqualTo("/");
  }

  @Test
  void normalizePathCollapsesDoubleDots() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/a/b/../c")).isEqualTo("/a/c");
  }

  @Test
  void normalizePathCollapsesSingleDots() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/a/./b")).isEqualTo("/a/b");
  }

  @Test
  void normalizePathDecodesPercentEncoding() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/api/%2e%2e/secret"))
        .isEqualTo("/secret");
  }

  @Test
  void normalizePathHandlesDoubleEncoding() {
    // %252e decodes to %2e, which then decodes to .
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/api/%252e%252e/secret"))
        .isEqualTo("/secret");
  }

  @Test
  void normalizePathDoesNotTraverseAboveRoot() {
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/../../etc/passwd"))
        .isEqualTo("/etc/passwd");
  }

  @Test
  void normalizePathPreservesEncodedSlash() {
    // FR-003b: %2F must not be decoded to '/' -- it must survive normalization
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/api/v1%2Fbypass"))
        .isEqualTo("/api/v1%2Fbypass");
  }

  @Test
  void normalizePathPreservesLowercaseEncodedSlash() {
    // FR-003b: %2f (lowercase) uppercased and preserved per RFC 3986 Section 2.1
    assertThat(PaygateAuthenticationEntryPoint.normalizePath("/api/v1%2fbypass"))
        .isEqualTo("/api/v1%2Fbypass");
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
  void jsonBodyContainsExpectedFields() throws Exception {
    var context =
        new ChallengeContext(
            new byte[32],
            "aa".repeat(32),
            "lnbc500u1test",
            50,
            "A \"quoted\" description",
            "test-service",
            3600,
            "",
            new byte[32],
            null,
            null);

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(context);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    String body = response.getContentAsString();
    assertThat(body).startsWith("{\"code\": 402,");
    assertThat(body).contains("\"price_sats\": 50");
    assertThat(body).contains("\"description\": \"A \\\"quoted\\\" description\"");
    assertThat(body).doesNotContain("test_preimage");
  }

  @Test
  void multipleProtocolsProduceMultipleWwwAuthenticateHeaders() throws Exception {
    var l402Challenge =
        new ChallengeResponse(
            "L402 token=\"tok\", invoice=\"lnbc1\"", "L402", Map.of("macaroon", "abc123"));
    var mppChallenge =
        new ChallengeResponse(
            "Payment hash=\"deadbeef\", invoice=\"lnbc1\"",
            "Payment",
            Map.of("payment_hash", "deadbeef"));

    PaymentProtocol l402Protocol = mock(PaymentProtocol.class);
    PaymentProtocol mppProtocol = mock(PaymentProtocol.class);
    when(l402Protocol.formatChallenge(any())).thenReturn(l402Challenge);
    when(mppProtocol.formatChallenge(any())).thenReturn(mppChallenge);

    var multiEntryPoint =
        new PaygateAuthenticationEntryPoint(
            challengeService, endpointRegistry, List.of(l402Protocol, mppProtocol));

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_CONTEXT);

    multiEntryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate").stream().toList();
    assertThat(wwwAuthHeaders).hasSize(2);
    assertThat(wwwAuthHeaders)
        .containsExactly(
            "L402 token=\"tok\", invoice=\"lnbc1\"",
            "Payment hash=\"deadbeef\", invoice=\"lnbc1\"");
    assertThat(response.getContentAsString()).contains("\"protocols\":");
  }

  @Test
  void emptyProtocolListProducesNoWwwAuthenticateHeader() throws Exception {
    var emptyEntryPoint =
        new PaygateAuthenticationEntryPoint(challengeService, endpointRegistry, List.of());

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG))).thenReturn(TEST_CONTEXT);

    emptyEntryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
  }
}
