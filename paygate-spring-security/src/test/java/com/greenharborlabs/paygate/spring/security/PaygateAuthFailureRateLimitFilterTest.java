package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class PaygateAuthFailureRateLimitFilterTest {

  private static final String VALID_MACAROON =
      "AgELbXktc2VydmljZQJCAAA"
          + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
          + "AAAAAAAAAAABkAAAAAAAVtZXhwPTE3NDkxMDAwMDAA";
  private static final String VALID_PREIMAGE =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
  private static final String VALID_L402_HEADER = "L402 " + VALID_MACAROON + ":" + VALID_PREIMAGE;

  @Mock private PaygateRateLimiter rateLimiter;

  @Mock private ClientIpResolver clientIpResolver;

  @Mock private PaygateEndpointRegistry endpointRegistry;

  @Mock private FilterChain filterChain;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private List<PaymentProtocol> protocols;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/api/v1/data");
    request.setRemoteAddr("192.168.1.1");
    response = new MockHttpServletResponse();
    protocols = List.of();
  }

  @Test
  void preCheckRateLimitExhausted_returns429() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(clientIpResolver.resolve(any())).thenReturn("192.168.1.1");
    when(rateLimiter.tryAcquire("192.168.1.1")).thenReturn(false);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    verify(filterChain, never()).doFilter(any(), any());
    verify(rateLimiter, times(1)).tryAcquire("192.168.1.1");
  }

  @Test
  void authFailure401_consumesPenaltyToken() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(clientIpResolver.resolve(any())).thenReturn("192.168.1.1");
    when(rateLimiter.tryAcquire("192.168.1.1")).thenReturn(true);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);

    // Simulate downstream setting 401
    FilterChain failChain =
        (req, res) -> {
          ((MockHttpServletResponse) res).setStatus(401);
        };

    filter.doFilter(request, response, failChain);

    verify(rateLimiter, times(2)).tryAcquire("192.168.1.1");
  }

  @Test
  void authFailure503_consumesPenaltyToken() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(clientIpResolver.resolve(any())).thenReturn("192.168.1.1");
    when(rateLimiter.tryAcquire("192.168.1.1")).thenReturn(true);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);

    FilterChain failChain =
        (req, res) -> {
          ((MockHttpServletResponse) res).setStatus(503);
        };

    filter.doFilter(request, response, failChain);

    verify(rateLimiter, times(2)).tryAcquire("192.168.1.1");
  }

  @Test
  void authSuccess_doesNotConsumePenalty() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(clientIpResolver.resolve(any())).thenReturn("192.168.1.1");
    when(rateLimiter.tryAcquire("192.168.1.1")).thenReturn(true);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);

    FilterChain successChain =
        (req, res) -> {
          ((MockHttpServletResponse) res).setStatus(200);
        };

    filter.doFilter(request, response, successChain);

    verify(rateLimiter, times(1)).tryAcquire("192.168.1.1");
  }

  @Test
  void nonProtectedEndpoint_passthrough() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(endpointRegistry.findConfig("GET", "/api/v1/data")).thenReturn(null);

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verify(rateLimiter, never()).tryAcquire(anyString());
  }

  @Test
  void nullRateLimiter_passthrough() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);

    var filter =
        new PaygateAuthFailureRateLimitFilter(null, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }

  @Test
  void noAuthHeader_shouldNotFilter() throws ServletException, IOException {
    // No Authorization header set
    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);

    assertThat(filter.shouldNotFilter(request)).isTrue();
  }

  @Test
  void rateLimiterThrows_failsClosed429() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(clientIpResolver.resolve(any())).thenReturn("192.168.1.1");
    when(rateLimiter.tryAcquire("192.168.1.1")).thenThrow(new RuntimeException("limiter error"));
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void endpointRegistryThrows_failsClosed503() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenThrow(new RuntimeException("registry error"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void malformedRequestUri_returns400MalformedUri() throws ServletException, IOException {
    HttpServletRequest malformedRequest = mock(HttpServletRequest.class);
    when(malformedRequest.getHeader("Authorization")).thenReturn(VALID_L402_HEADER);
    when(malformedRequest.getRequestURI()).thenThrow(new IllegalArgumentException("bad uri"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);
    filter.doFilter(malformedRequest, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void nullClientIpResolver_fallsBackToRemoteAddr() throws ServletException, IOException {
    request.addHeader("Authorization", VALID_L402_HEADER);
    request.setRemoteAddr("10.0.0.1");
    when(rateLimiter.tryAcquire("10.0.0.1")).thenReturn(false);
    when(endpointRegistry.findConfig("GET", "/api/v1/data"))
        .thenReturn(
            new PaygateEndpointConfig("GET", "/api/v1/data", 10, 3600, "Data access", "", "read"));

    var filter =
        new PaygateAuthFailureRateLimitFilter(rateLimiter, null, endpointRegistry, protocols);
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    verify(rateLimiter).tryAcquire("10.0.0.1");
  }

  @Test
  void mppAuthHeader_isNotFiltered() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mock(PaymentProtocol.class);
    when(mppProtocol.canHandle("Payment token=abc123")).thenReturn(true);

    request.addHeader("Authorization", "Payment token=abc123");

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, List.of(mppProtocol));

    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void nonPaymentAuthHeader_shouldNotFilter() throws ServletException, IOException {
    request.addHeader("Authorization", "Bearer some-jwt-token");

    var filter =
        new PaygateAuthFailureRateLimitFilter(
            rateLimiter, clientIpResolver, endpointRegistry, protocols);

    assertThat(filter.shouldNotFilter(request)).isTrue();
  }
}
