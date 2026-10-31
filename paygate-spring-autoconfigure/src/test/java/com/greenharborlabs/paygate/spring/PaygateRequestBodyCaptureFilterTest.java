package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("PaygateRequestBodyCaptureFilter")
class PaygateRequestBodyCaptureFilterTest {

  private static final byte[] FORM_BODY = "units=1".getBytes(StandardCharsets.UTF_8);

  private final AtomicReference<ServletRequest> forwarded = new AtomicReference<>();

  @Test
  @DisplayName("captures a paid route's body when MPP is enabled and replays it downstream")
  void capturesPaidRouteBodyWhenMppEnabled() throws Exception {
    var filter = new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(mpp()), 64);
    var request = formPut("/api/form");

    filter.doFilter(request, new MockHttpServletResponse(), this::recordForward);

    assertThat(forwarded.get()).isInstanceOf(BoundedRequestBody.class);
    var captured = (BoundedRequestBody) forwarded.get();
    assertThat(captured.getInputStream().readAllBytes()).isEqualTo(FORM_BODY);
    // A later Paygate capture reuses the early bytes even though the raw stream is drained.
    assertThat(BoundedRequestBody.capture(request, 64).observedBytes()).isEqualTo(FORM_BODY);
  }

  @Test
  @DisplayName("captures a named-pricing route's body without MPP")
  void capturesNamedPricingRouteWithoutMpp() throws Exception {
    var registry = new PaygateEndpointRegistry();
    registry.register(
        new PaygateEndpointConfig(
            "PUT",
            "/api/form",
            1,
            600,
            "Form",
            "sizePricer",
            "",
            PricingStability.REQUEST_DEPENDENT));
    var filter = new PaygateRequestBodyCaptureFilter(registry, List.of(), 64);

    filter.doFilter(formPut("/api/form"), new MockHttpServletResponse(), this::recordForward);

    assertThat(forwarded.get()).isInstanceOf(BoundedRequestBody.class);
  }

  @Test
  @DisplayName("leaves fixed-price L402-only routes to the container")
  void skipsFixedPriceRouteWithoutMpp() throws Exception {
    var filter = new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(), 64);
    var request = formPut("/api/form");

    filter.doFilter(request, new MockHttpServletResponse(), this::recordForward);

    assertThat(forwarded.get()).isSameAs(request);
  }

  @Test
  @DisplayName("passes unprotected routes through unchanged")
  void passesUnprotectedRouteThrough() throws Exception {
    var filter = new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(mpp()), 4);
    var request = formPut("/public/upload");
    request.setContent(new byte[1_024]);

    filter.doFilter(request, new MockHttpServletResponse(), this::recordForward);

    assertThat(forwarded.get()).isSameAs(request);
  }

  @Test
  @DisplayName("passes through when endpoint resolution fails so the security chain responds")
  void passesThroughWhenResolutionFails() throws Exception {
    var registry = mock(PaygateEndpointRegistry.class);
    when(registry.resolve(any(HttpServletRequest.class)))
        .thenThrow(new IllegalArgumentException("malformed path"));
    var filter = new PaygateRequestBodyCaptureFilter(registry, List.of(mpp()), 64);
    var request = formPut("/api/form");

    filter.doFilter(request, new MockHttpServletResponse(), this::recordForward);

    assertThat(forwarded.get()).isSameAs(request);
  }

  @Test
  @DisplayName("rejects an oversized paid body with the shared 400 response")
  void rejectsOversizedBody() throws Exception {
    var filter = new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(mpp()), 4);
    var response = new MockHttpServletResponse();

    filter.doFilter(formPut("/api/form"), response, this::recordForward);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("REQUEST_BODY_TOO_LARGE");
    assertThat(forwarded.get()).isNull();
  }

  @Test
  @DisplayName("rejects a paid body shorter than its declared Content-Length")
  void rejectsIncompleteBody() throws Exception {
    var filter = new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(mpp()), 64);
    var request =
        new MockHttpServletRequest("PUT", "/api/form") {
          @Override
          public long getContentLengthLong() {
            return FORM_BODY.length + 10L;
          }
        };
    request.setContentType("application/x-www-form-urlencoded");
    request.setContent(FORM_BODY);
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, this::recordForward);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("REQUEST_BODY_INCOMPLETE");
    assertThat(forwarded.get()).isNull();
  }

  @Test
  @DisplayName("requires a positive body bound")
  void requiresPositiveBound() {
    assertThatThrownBy(
            () -> new PaygateRequestBodyCaptureFilter(fixedPriceRegistry(), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void recordForward(ServletRequest request, ServletResponse response) {
    forwarded.set(request);
  }

  private static PaygateEndpointRegistry fixedPriceRegistry() {
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("PUT", "/api/form", 1, 600, "Form", "", ""));
    return registry;
  }

  private static PaymentProtocol mpp() {
    var protocol = mock(PaymentProtocol.class);
    when(protocol.scheme()).thenReturn("Payment");
    return protocol;
  }

  private static MockHttpServletRequest formPut(String path) {
    var request = new MockHttpServletRequest("PUT", path);
    request.setContentType("application/x-www-form-urlencoded");
    request.setContent(FORM_BODY);
    return request;
  }
}
