package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * An exception thrown downstream of a successful payment decision belongs to the application. It
 * must propagate unchanged to the container's error handling rather than be reported as a 503
 * "Lightning unavailable".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygateSecurityFilter — exceptions downstream of a successful payment decision")
class PaygateSecurityFilterDownstreamExceptionTest {

  private static final String AUTH_HEADER = "Payment method=\"lightning\"";

  @Mock private PaymentProtocol protocol;
  @Mock private PaygateChallengeService challengeService;
  @Mock private PaygateMetrics metrics;
  @Mock private FilterChain filterChain;

  private PaygateSecurityFilter filter;
  private PaymentCredential credential;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() throws Exception {
    var registry = new PaygateEndpointRegistry();
    registry.register(
        new PaygateEndpointConfig("GET", "/api/protected", 100L, 3600L, "Test", "", ""));
    filter =
        new PaygateSecurityFilter(
            registry, List.of(protocol), challengeService, "test", null, metrics, null, null);

    credential =
        new PaymentCredential(
            new byte[32], new byte[32], "token", "Payment", null, mock(ProtocolMetadata.class));
    when(protocol.scheme()).thenReturn("Payment");
    when(protocol.canHandle(AUTH_HEADER)).thenReturn(true);
    when(protocol.parseCredential(AUTH_HEADER)).thenReturn(credential);
    doNothing().when(protocol).validate(any(), anyMap());
    when(protocol.createReceipt(any(), any())).thenReturn(Optional.empty());

    request = new MockHttpServletRequest("GET", "/api/protected");
    request.addHeader("Authorization", AUTH_HEADER);
    response = new MockHttpServletResponse();
  }

  @Test
  @DisplayName("an unchecked handler exception propagates unchanged instead of becoming a 503")
  void uncheckedHandlerExceptionPropagatesUnchanged() throws Exception {
    var applicationFailure = new IllegalStateException("application failure");
    doThrow(applicationFailure).when(filterChain).doFilter(any(), any());

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
        .isSameAs(applicationFailure);

    assertNoPaygateResponseAndNoPassedMetric();
  }

  @Test
  @DisplayName("a ServletException from the chain propagates unchanged instead of becoming a 503")
  void servletExceptionPropagatesUnchanged() throws Exception {
    var dispatchFailure = new ServletException("handler dispatch failed");
    doThrow(dispatchFailure).when(filterChain).doFilter(any(), any());

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
        .isSameAs(dispatchFailure);

    assertNoPaygateResponseAndNoPassedMetric();
  }

  @Test
  @DisplayName("a chain that completes normally is counted as passed after it returns")
  void normalCompletionIsCountedAsPassed() throws Exception {
    filter.doFilter(request, response, filterChain);

    verify(metrics).recordCaveatVerifyDuration(anyLong());
    verify(metrics).recordPassed("/api/protected", 100L, "Payment");
    assertThat(credential.isDestroyed()).isTrue();
  }

  private void assertNoPaygateResponseAndNoPassedMetric() {
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.isCommitted()).isFalse();
    assertThat(response.getContentLength()).isZero();
    assertThat(credential.isDestroyed()).isTrue();
    assertThat(
            request.getAttribute(
                "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPolicyDecision"))
        .isNotNull();
    verify(metrics).recordCaveatVerifyDuration(anyLong());
    verify(metrics, never()).recordPassed(anyString(), anyLong(), anyString());
  }
}
