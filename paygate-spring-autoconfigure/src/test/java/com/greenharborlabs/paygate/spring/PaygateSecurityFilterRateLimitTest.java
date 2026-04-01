package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
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
    when(stubProtocol.canHandle("L402 test-token")).thenReturn(true);

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
}
