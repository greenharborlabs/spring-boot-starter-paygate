package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.CanonicalRequestDigest;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.api.SecurityBounds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestDigestSupportTest {

  @Test
  void wrapForDigest_rejectsBodyAboveBound() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
    request.setContent(new byte[RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1]);

    assertThatThrownBy(() -> RequestDigestSupport.wrapForDigest(request))
        .isInstanceOf(RequestBodyTooLargeException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  void computeDigest_sameLogicalJsonDifferentWhitespace_producesDifferentDigest() throws Exception {
    MockHttpServletRequest compact = new MockHttpServletRequest("POST", "/api/pay");
    compact.setContent("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletRequest spaced = new MockHttpServletRequest("POST", "/api/pay");
    spaced.setContent("{ \"a\": 1, \"b\": 2 }".getBytes(StandardCharsets.UTF_8));

    String digestCompact = RequestDigestSupport.computeDigest(compact, "/api/pay");
    String digestSpaced = RequestDigestSupport.computeDigest(spaced, "/api/pay");

    assertThat(digestCompact).isNotEqualTo(digestSpaced);
  }

  @Test
  void computeDigest_usesTheApiCanonicalPrimitiveForExactRawQuery() throws Exception {
    byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
    request.setQueryString("a=1&a=1");
    request.setContent(body);

    assertThat(RequestDigestSupport.computeDigest(request, "/api/pay"))
        .isEqualTo(CanonicalRequestDigest.create("POST", "/api/pay", true, "a=1&a=1", body));
  }

  @Test
  void wrapForDigest_allowsReReadingCachedBody() throws Exception {
    byte[] payload = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
    request.setContent(payload);

    var wrapped = RequestDigestSupport.wrapForDigest(request);
    byte[] first = wrapped.getInputStream().readAllBytes();
    byte[] second = wrapped.getInputStream().readAllBytes();

    assertThat(first).isEqualTo(payload);
    assertThat(second).isEqualTo(payload);
  }

  @Test
  void configuredLowerBoundAllowsOneByteAndPreservesRereadableBody() {
    byte[] payload = new byte[] {42};
    assertConfiguredBodyPassesThrough(
        Math.toIntExact(SecurityBounds.MIN_REQUEST_BODY_SIZE_BYTES), payload);
  }

  @Test
  void configuredUpperBoundAllowsSixteenMiBAndPreservesRereadableBody() {
    byte[] payload = new byte[Math.toIntExact(SecurityBounds.MAX_REQUEST_BODY_SIZE_BYTES)];
    assertConfiguredBodyPassesThrough(
        Math.toIntExact(SecurityBounds.MAX_REQUEST_BODY_SIZE_BYTES), payload);
  }

  @Test
  void configuredBodyAboveLimitIsRejectedBeforeProtectedHandlerRuns() {
    int configuredMax = 1_024;
    new ApplicationContextRunner()
        .withUserConfiguration(RequestBodyPropertiesConfiguration.class)
        .withPropertyValues("paygate.request-body.max-bytes=" + configuredMax)
        .run(
            context -> {
              var properties = context.getBean(PaygateProperties.class);
              assertThat(properties.getRequestBody().getMaxBytes()).isEqualTo(configuredMax);

              var registry = new PaygateEndpointRegistry();
              registry.register(
                  new PaygateEndpointConfig("POST", "/api/pay", 10, 600, "Pay", "", ""));
              var mppProtocol = mock(PaymentProtocol.class);
              when(mppProtocol.scheme()).thenReturn("Payment");
              when(mppProtocol.canHandle(anyString())).thenReturn(true);
              when(mppProtocol.parseCredential(anyString()))
                  .thenReturn(
                      new PaymentCredential(
                          new byte[32],
                          new byte[32],
                          "configured-limit-token",
                          "Payment",
                          null,
                          new ProtocolMetadata() {}));
              var filter =
                  new PaygateAutoConfiguration()
                      .paygateSecurityFilter(
                          registry,
                          List.of(mppProtocol),
                          mock(PaygateChallengeService.class),
                          properties,
                          null,
                          null,
                          null,
                          null);
              var request = new MockHttpServletRequest("POST", "/api/pay");
              request.addHeader("Authorization", "Payment configured-limit-credential");
              request.setContent(new byte[configuredMax + 1]);
              var response = new MockHttpServletResponse();
              var filterChain = mock(FilterChain.class);

              filter.doFilter(request, response, filterChain);

              assertAll(
                  () -> assertThat(response.getStatus()).isEqualTo(400),
                  () -> verify(filterChain, never()).doFilter(any(), any()));
            });
  }

  private static void assertConfiguredBodyPassesThrough(int configuredMax, byte[] payload) {
    new ApplicationContextRunner()
        .withUserConfiguration(RequestBodyPropertiesConfiguration.class)
        .withPropertyValues("paygate.request-body.max-bytes=" + configuredMax)
        .run(
            context -> {
              var properties = context.getBean(PaygateProperties.class);
              assertThat(properties.getRequestBody().getMaxBytes()).isEqualTo(configuredMax);

              var registry = new PaygateEndpointRegistry();
              registry.register(
                  new PaygateEndpointConfig("POST", "/api/pay", 10, 600, "Pay", "", ""));
              var protocol = mock(PaymentProtocol.class);
              when(protocol.scheme()).thenReturn("Payment");
              when(protocol.canHandle(anyString())).thenReturn(true);
              when(protocol.parseCredential(anyString()))
                  .thenReturn(
                      new PaymentCredential(
                          new byte[32],
                          new byte[32],
                          "configured-bound-token",
                          "Payment",
                          null,
                          new ProtocolMetadata() {}));
              var filter =
                  new PaygateAutoConfiguration()
                      .paygateSecurityFilter(
                          registry,
                          List.of(protocol),
                          mock(PaygateChallengeService.class),
                          properties,
                          null,
                          null,
                          null,
                          null);
              var request = new MockHttpServletRequest("POST", "/api/pay");
              request.addHeader("Authorization", "Payment configured-bound-credential");
              request.setContent(payload);
              var response = new MockHttpServletResponse();
              var filterChain = mock(FilterChain.class);
              doAnswer(
                      invocation -> {
                        var forwardedRequest = (HttpServletRequest) invocation.getArgument(0);
                        assertThat(forwardedRequest.getInputStream().readAllBytes())
                            .isEqualTo(payload);
                        assertThat(forwardedRequest.getInputStream().readAllBytes())
                            .isEqualTo(payload);
                        return null;
                      })
                  .when(filterChain)
                  .doFilter(any(), any());

              assertThatCode(() -> filter.doFilter(request, response, filterChain))
                  .doesNotThrowAnyException();

              verify(filterChain).doFilter(any(HttpServletRequest.class), any());
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PaygateProperties.class)
  static class RequestBodyPropertiesConfiguration {}
}
