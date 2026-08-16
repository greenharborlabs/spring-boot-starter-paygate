package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.spring.ApplicationRelativeRequestResolver;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateLightningUnavailableException;
import com.greenharborlabs.paygate.spring.PaygateRateLimitedException;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import com.greenharborlabs.paygate.spring.RequestDigestSupport;
import com.greenharborlabs.paygate.spring.ResolvedEndpoint;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
  void setUp() throws Exception {
    org.mockito.Mockito.lenient()
        .when(protocol.formatChallenge(any()))
        .thenReturn(DEFAULT_CHALLENGE);
    entryPoint =
        new PaygateAuthenticationEntryPoint(challengeService, endpointRegistry, List.of(protocol));
    request = new MockHttpServletRequest("GET", "/api/protected");
    request.setRequestURI("/api/protected");
    response = new MockHttpServletResponse();
    org.mockito.Mockito.lenient()
        .doAnswer(
            invocation -> {
              HttpServletRequest endpointRequest = invocation.getArgument(0);
              String method = endpointRequest.getMethod();
              String path = ApplicationRelativeRequestResolver.resolve(endpointRequest);
              PaygateEndpointConfig config = endpointRegistry.findConfig(method, path);
              return config == null
                  ? null
                  : new ResolvedEndpoint(config, config.pathPattern(), config.httpMethod());
            })
        .when(endpointRegistry)
        .resolve(any(HttpServletRequest.class));
    org.mockito.Mockito.lenient()
        .doAnswer(
            invocation -> {
              HttpServletRequest challengeRequest = invocation.getArgument(0);
              ResolvedEndpoint resolvedEndpoint = invocation.getArgument(1);
              PaygateChallengeService.ChallengeOptions options = invocation.getArgument(2);
              return challengeService.createChallenge(
                  challengeRequest, resolvedEndpoint.config(), options);
            })
        .when(challengeService)
        .createChallenge(
            any(HttpServletRequest.class),
            any(ResolvedEndpoint.class),
            any(PaygateChallengeService.ChallengeOptions.class));
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
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString()).contains("\"code\": 402");
    assertThat(response.getContentAsString()).contains("\"price_sats\": 100");
    assertThat(response.getContentAsString()).contains("\"invoice\": \"lnbc1000n1test\"");
    verify(endpointRegistry).resolve(request);
    verify(endpointRegistry, never()).resolve(any(String.class), any(String.class));
  }

  @Test
  void resolvedEndpointChallengeDoesNotResolveEndpointAgain() throws Exception {
    var resolvedEndpoint =
        new ResolvedEndpoint(TEST_CONFIG, TEST_CONFIG.pathPattern(), TEST_CONFIG.httpMethod());
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, resolvedEndpoint);

    assertThat(response.getStatus()).isEqualTo(402);
    verify(endpointRegistry, never()).resolve(any(String.class), any(String.class));
    verify(challengeService)
        .createChallenge(
            request,
            resolvedEndpoint,
            PaygateChallengeService.ChallengeOptions.rateLimitAlreadyConsumed());
  }

  @Test
  void rejectsPresentedUnsupportedCredentialWithoutCreatingChallengeState() throws Exception {
    request.addHeader("Authorization", "Bearer opaque-credential");

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    verify(endpointRegistry, never()).resolve(any(String.class), any(String.class));
    verify(challengeService, never()).acquireChallengeRateLimit(any());
    verify(challengeService, never())
        .createChallenge(
            any(HttpServletRequest.class),
            any(ResolvedEndpoint.class),
            any(PaygateChallengeService.ChallengeOptions.class));
  }

  @Test
  void rejectsBlankPresentedCredentialWithoutCreatingChallengeState() throws Exception {
    request.addHeader("Authorization", "   ");

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    verify(endpointRegistry, never()).resolve(any(String.class), any(String.class));
    verify(challengeService, never()).acquireChallengeRateLimit(any());
    verify(challengeService, never())
        .createChallenge(
            any(HttpServletRequest.class),
            any(ResolvedEndpoint.class),
            any(PaygateChallengeService.ChallengeOptions.class));
  }

  @Test
  void headChallengeUsesInheritedGetPolicyCanonicalRouteAndActualHeadMethod() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/shop/api/orders/42");
    request.setContextPath("/shop");
    var getConfig =
        new PaygateEndpointConfig("GET", "/api/orders/{orderId}", 100, 3600, "Order", "", "read");
    var resolvedEndpoint = new ResolvedEndpoint(getConfig, "/api/orders/{orderId}", "GET");
    org.mockito.Mockito.doReturn(resolvedEndpoint).when(endpointRegistry).resolve(request);
    org.mockito.Mockito.doReturn(TEST_CONTEXT)
        .when(challengeService)
        .createChallenge(
            any(HttpServletRequest.class),
            eq(resolvedEndpoint),
            any(PaygateChallengeService.ChallengeOptions.class));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(request.getMethod()).isEqualTo("HEAD");
    verify(endpointRegistry).resolve(request);
    verify(challengeService)
        .createChallenge(
            request,
            resolvedEndpoint,
            PaygateChallengeService.ChallengeOptions.rateLimitAlreadyConsumed());
  }

  @Test
  void explicitHeadPolicyWinsForHeadChallenge() throws Exception {
    request.setMethod("HEAD");
    var headConfig =
        new PaygateEndpointConfig("HEAD", "/api/protected", 200, 3600, "Head", "", "head-read");
    var resolvedEndpoint = new ResolvedEndpoint(headConfig, "/api/protected", "HEAD");
    org.mockito.Mockito.doReturn(resolvedEndpoint).when(endpointRegistry).resolve(request);
    org.mockito.Mockito.doReturn(TEST_CONTEXT)
        .when(challengeService)
        .createChallenge(
            any(HttpServletRequest.class),
            eq(resolvedEndpoint),
            any(PaygateChallengeService.ChallengeOptions.class));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    verify(challengeService)
        .createChallenge(
            request,
            resolvedEndpoint,
            PaygateChallengeService.ChallengeOptions.rateLimitAlreadyConsumed());
  }

  @Test
  void optionsChallengeDoesNotInheritGetPolicy() throws Exception {
    request.setMethod("OPTIONS");
    org.mockito.Mockito.doReturn(null).when(endpointRegistry).resolve(request);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(401);
    verify(challengeService, never()).acquireChallengeRateLimit(any());
    verify(challengeService, never())
        .createChallenge(
            any(HttpServletRequest.class),
            any(ResolvedEndpoint.class),
            any(PaygateChallengeService.ChallengeOptions.class));
  }

  @Test
  void writes402ForProtectedRouteUnderPathServletMapping() throws Exception {
    request.setRequestURI("/gateway/api/protected");
    request.setServletPath("/gateway");
    request.setHttpServletMapping(
        new MockHttpServletMapping("", "/gateway/*", "dispatcher", MappingMatch.PATH));
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    verify(endpointRegistry).findConfig("GET", "/api/protected");
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
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any()))
        .thenReturn(contextWithPreimage);

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
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any()))
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
  void mppChallengeRateLimitDenied_doesNotReadBody() throws Exception {
    PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
    RootKeyStore rootKeyStore = mock(RootKeyStore.class);
    LightningBackend lightningBackend = mock(LightningBackend.class);
    PaygateChallengeService realChallengeService =
        new PaygateChallengeService(
            rootKeyStore, lightningBackend, null, null, null, rateLimiter, null, null);
    PaymentProtocol mppProtocol = mock(PaymentProtocol.class);
    when(mppProtocol.scheme()).thenReturn("Payment");
    PaygateEndpointRegistry registry = mock(PaygateEndpointRegistry.class);
    var postConfig =
        new PaygateEndpointConfig("POST", "/api/protected", 100, 3600, "Test endpoint", "", "");
    when(registry.resolve(any(HttpServletRequest.class)))
        .thenReturn(new ResolvedEndpoint(postConfig, "/api/protected", "POST"));
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(false);
    var throwingRequest = new ThrowingBodyRequest();
    throwingRequest.setMethod("POST");
    throwingRequest.setRequestURI("/api/protected");
    throwingRequest.setRemoteAddr("10.0.0.1");
    var localResponse = new MockHttpServletResponse();
    var localEntryPoint =
        new PaygateAuthenticationEntryPoint(realChallengeService, registry, List.of(mppProtocol));

    localEntryPoint.commence(throwingRequest, localResponse, new BadCredentialsException("test"));

    assertThat(localResponse.getStatus()).isEqualTo(429);
    assertThat(throwingRequest.bodyRead).isFalse();
    verify(rateLimiter, times(1)).tryAcquire("10.0.0.1");
    verify(lightningBackend, never()).isHealthy();
  }

  @Test
  void oversizedMppChallengeBody_consumesOneRateLimitToken() throws Exception {
    PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
    RootKeyStore rootKeyStore = mock(RootKeyStore.class);
    LightningBackend lightningBackend = mock(LightningBackend.class);
    PaygateChallengeService realChallengeService =
        new PaygateChallengeService(
            rootKeyStore, lightningBackend, null, null, null, rateLimiter, null, null);
    PaymentProtocol mppProtocol = mock(PaymentProtocol.class);
    when(mppProtocol.scheme()).thenReturn("Payment");
    PaygateEndpointRegistry registry = mock(PaygateEndpointRegistry.class);
    var postConfig =
        new PaygateEndpointConfig("POST", "/api/protected", 100, 3600, "Test endpoint", "", "");
    when(registry.resolve(any(HttpServletRequest.class)))
        .thenReturn(new ResolvedEndpoint(postConfig, "/api/protected", "POST"));
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(true);
    var oversizedRequest = new MockHttpServletRequest("POST", "/api/protected");
    oversizedRequest.setRequestURI("/api/protected");
    oversizedRequest.setRemoteAddr("10.0.0.1");
    byte[] oversizedBody = new byte[RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1];
    Arrays.fill(oversizedBody, (byte) 'x');
    oversizedRequest.setContent(oversizedBody);
    var localResponse = new MockHttpServletResponse();
    var localEntryPoint =
        new PaygateAuthenticationEntryPoint(realChallengeService, registry, List.of(mppProtocol));

    localEntryPoint.commence(oversizedRequest, localResponse, new BadCredentialsException("test"));

    assertThat(localResponse.getStatus()).isEqualTo(400);
    verify(rateLimiter, times(1)).tryAcquire("10.0.0.1");
    verify(lightningBackend, never()).isHealthy();
  }

  @Test
  void writes503WhenLightningUnavailable() throws Exception {
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any()))
        .thenThrow(new PaygateLightningUnavailableException("Backend down"));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
  }

  @Test
  void writesSanitized500WhenEndpointResolutionFailsWithoutChallengeSideEffects() throws Exception {
    when(endpointRegistry.resolve(request))
        .thenThrow(new IllegalStateException("secret policy detail"));
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("user", "secret"));

    entryPoint.commence(request, response, new BadCredentialsException("credential secret"));

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .contains("INTERNAL_ERROR")
        .doesNotContain("secret policy detail", "credential secret", "/api/protected");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(challengeService, never()).acquireChallengeRateLimit(any());
    verify(challengeService, never()).createChallenge(any(), any(ResolvedEndpoint.class), any());
  }

  @Test
  void writes503OnUnexpectedException() throws Exception {
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any()))
        .thenThrow(new RuntimeException("Unexpected error"));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
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
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    MockHttpServletResponse expected = new MockHttpServletResponse();
    PaygateResponseWriter.writeMalformedUri(expected);

    assertThat(response.getContentAsString()).isEqualTo(expected.getContentAsString());
  }

  @Test
  void normalizesPathBeforeLookup() throws Exception {
    request.setRequestURI("/api/../api/protected");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
  }

  @Test
  void normalizesPercentEncodedPathBeforeLookup() throws Exception {
    request.setRequestURI("/api/%2e%2e/api/protected");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

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
  void writes500WhenEndpointRegistryThrows() throws Exception {
    when(endpointRegistry.resolve(request)).thenThrow(new RuntimeException("Registry broken"));

    entryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString()).contains("INTERNAL_ERROR");
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
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(context);

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
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

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
  void oneFormatterFailurePreservesAnotherUsableChallenge() throws Exception {
    PaymentProtocol successfulProtocol = mock(PaymentProtocol.class);
    when(successfulProtocol.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("L402 usable", "L402", Map.of()));
    PaymentProtocol failingProtocol = mock(PaymentProtocol.class);
    when(failingProtocol.formatChallenge(any())).thenThrow(new IllegalStateException("secret"));
    var multiEntryPoint =
        new PaygateAuthenticationEntryPoint(
            challengeService, endpointRegistry, List.of(successfulProtocol, failingProtocol));

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    multiEntryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeaders("WWW-Authenticate")).containsExactly("L402 usable");
    assertThat(response.getContentAsString()).doesNotContain("secret");
    verify(challengeService, never()).discardChallenge(any());
  }

  @Test
  void noSafeChallengeDiscardsGeneratedStateAndReturnsUnavailable() throws Exception {
    var emptyEntryPoint =
        new PaygateAuthenticationEntryPoint(challengeService, endpointRegistry, List.of());

    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(TEST_CONFIG);
    when(challengeService.createChallenge(any(), eq(TEST_CONFIG), any())).thenReturn(TEST_CONTEXT);

    emptyEntryPoint.commence(request, response, new BadCredentialsException("test"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
    verify(challengeService).discardChallenge(TEST_CONTEXT);
  }

  private static final class ThrowingBodyRequest extends MockHttpServletRequest {
    private boolean bodyRead;

    @Override
    public ServletInputStream getInputStream() {
      bodyRead = true;
      throw new UncheckedIOException(new IOException("body must not be read"));
    }
  }
}
