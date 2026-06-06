package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link PaygateSecurityFilter} rate limiter fail-closed behavior. No Spring context
 * needed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygateSecurityFilter rate limiting")
class PaygateSecurityFilterRateLimitTest {

  private static final String PROTECTED_PATH = "/api/protected";

  @Mock private PaygateRateLimiter rateLimiter;
  @Mock private PaygateChallengeService challengeService;
  @Mock private RootKeyStore rootKeyStore;
  @Mock private LightningBackend lightningBackend;
  @Mock private FilterChain filterChain;

  private PaygateEndpointRegistry registry;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private PaymentProtocol stubProtocol;

  @BeforeEach
  void setUp() {
    registry = new PaygateEndpointRegistry();
    registry.register(
        new PaygateEndpointConfig("GET", PROTECTED_PATH, 10, 600, "Test endpoint", "", ""));

    stubProtocol = mock(PaymentProtocol.class);
    org.mockito.Mockito.lenient().when(stubProtocol.canHandle("L402 test-token")).thenReturn(true);

    request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI(PROTECTED_PATH);
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("Authorization", "L402 test-token");

    response = new MockHttpServletResponse();
  }

  @Test
  @DisplayName("rate limiter exception returns 429 (fail-closed)")
  void rateLimiterException_failsClosed429() throws ServletException, IOException {
    when(rateLimiter.tryAcquire("10.0.0.1")).thenThrow(new RuntimeException("limiter error"));

    var filter =
        new PaygateSecurityFilter(
            registry,
            List.of(stubProtocol),
            challengeService,
            "test-service",
            null,
            null,
            null,
            rateLimiter);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("rate limiter returns false yields 429")
  void rateLimiterDenied_returns429() throws ServletException, IOException {
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(false);

    var filter =
        new PaygateSecurityFilter(
            registry,
            List.of(stubProtocol),
            challengeService,
            "test-service",
            null,
            null,
            null,
            rateLimiter);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("null rate limiter allows request through")
  void nullRateLimiter_allowsRequest() throws ServletException, IOException {
    var filter =
        new PaygateSecurityFilter(
            registry,
            List.of(stubProtocol),
            challengeService,
            "test-service",
            null,
            null,
            null,
            null);

    filter.doFilter(request, response, filterChain);

    // With null rate limiter, the request proceeds to protocol validation (not 429)
    assertThat(response.getStatus()).isNotEqualTo(429);
  }

  @Test
  @DisplayName("MPP challenge rate limit denial returns 429 before reading body")
  void mppChallengeRateLimitDenied_doesNotReadBody() throws ServletException, IOException {
    var postRegistry = new PaygateEndpointRegistry();
    postRegistry.register(
        new PaygateEndpointConfig("POST", PROTECTED_PATH, 10, 600, "Test endpoint", "", ""));
    var mppProtocol = mock(PaymentProtocol.class);
    when(mppProtocol.scheme()).thenReturn("Payment");
    var throwingRequest = new ThrowingBodyRequest();
    throwingRequest.setMethod("POST");
    throwingRequest.setRequestURI(PROTECTED_PATH);
    throwingRequest.setRemoteAddr("10.0.0.1");
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(false);

    var filter =
        new PaygateSecurityFilter(
            postRegistry,
            List.of(mppProtocol),
            realChallengeService(),
            "test-service",
            null,
            null,
            null,
            null);

    filter.doFilter(throwingRequest, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(throwingRequest.bodyRead).isFalse();
    verify(rateLimiter, times(1)).tryAcquire("10.0.0.1");
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("oversized MPP challenge body consumes one rate-limit token before 400")
  void oversizedMppChallengeBody_consumesOneRateLimitToken() throws ServletException, IOException {
    var postRegistry = new PaygateEndpointRegistry();
    postRegistry.register(
        new PaygateEndpointConfig("POST", PROTECTED_PATH, 10, 600, "Test endpoint", "", ""));
    var mppProtocol = mock(PaymentProtocol.class);
    when(mppProtocol.scheme()).thenReturn("Payment");
    request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI(PROTECTED_PATH);
    request.setRemoteAddr("10.0.0.1");
    byte[] oversizedBody = new byte[RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1];
    Arrays.fill(oversizedBody, (byte) 'x');
    request.setContent(oversizedBody);
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(true);

    var filter =
        new PaygateSecurityFilter(
            postRegistry,
            List.of(mppProtocol),
            realChallengeService(),
            "test-service",
            null,
            null,
            null,
            null);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    verify(rateLimiter, times(1)).tryAcquire("10.0.0.1");
    verify(filterChain, never()).doFilter(any(), any());
  }

  private PaygateChallengeService realChallengeService() {
    return new PaygateChallengeService(
        rootKeyStore, lightningBackend, null, null, null, rateLimiter, null, null);
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
