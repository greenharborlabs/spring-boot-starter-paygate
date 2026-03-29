package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests verifying that {@link PaygateSecurityFilter} populates request metadata ({@code
 * request.path}, {@code request.method}, {@code request.client_ip}) in the request context map
 * passed to {@link PaymentProtocol#validate}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygateSecurityFilter delegation -- request metadata population")
class PaygateSecurityFilterDelegationTest {

  private static final String SERVICE_NAME = "test-service";
  private static final String PROTECTED_PATH = "/api/products";

  /**
   * A structurally valid L402 Authorization header. The actual content does not matter because the
   * protocol mock accepts anything.
   */
  private static final String VALID_AUTH_HEADER =
      "L402 " + "AAAA".repeat(20) + ":" + "ab".repeat(32);

  @Mock private PaygateEndpointRegistry registry;

  @Mock private PaymentProtocol protocol;

  @Mock private PaygateChallengeService challengeService;

  @Mock private FilterChain chain;

  @Captor private ArgumentCaptor<Map<String, String>> requestContextCaptor;

  private PaygateSecurityFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    filter =
        new PaygateSecurityFilter(
            registry, List.of(protocol), challengeService, SERVICE_NAME, null, null, null, null);

    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  /** Creates a minimal {@link PaymentCredential} suitable for test stubs. */
  private static PaymentCredential createStubCredential() {
    return new PaymentCredential(
        new byte[32], new byte[32], "ab".repeat(32), "L402", null, new ProtocolMetadata() {});
  }

  /**
   * Configures the registry to recognize the given method+path as a protected endpoint and the
   * protocol to accept any auth header and validate successfully.
   */
  private void stubProtectedEndpointWithSuccessfulValidation(String method, String path) {
    var config = new PaygateEndpointConfig(method, path, 10L, 600L, "test", "", "");
    when(registry.findConfig(any(), any())).thenReturn(config);
    when(protocol.canHandle(anyString())).thenReturn(true);
    when(protocol.scheme()).thenReturn("L402");
    when(protocol.parseCredential(anyString())).thenReturn(createStubCredential());
    doNothing().when(protocol).validate(any(PaymentCredential.class), any());
  }

  @Test
  @DisplayName("populates request.path in request context metadata")
  void filterPopulatesRequestPath() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

    request.setMethod("GET");
    request.setRequestURI(PROTECTED_PATH);
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("Request context should contain request.path metadata")
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/products");
  }

  @Test
  @DisplayName("populates request.method in request context metadata")
  void filterPopulatesRequestMethod() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("POST", "/api/data");

    request.setMethod("POST");
    request.setRequestURI("/api/data");
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("Request context should contain request.method metadata")
        .containsEntry(VerificationContextKeys.REQUEST_METHOD, "POST");
  }

  @Test
  @DisplayName("populates request.client_ip in request context metadata using remote addr")
  void filterPopulatesClientIp() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

    request.setMethod("GET");
    request.setRequestURI(PROTECTED_PATH);
    request.setRemoteAddr("192.168.1.42");
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("Request context should contain request.client_ip metadata")
        .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, "192.168.1.42");
  }

  @Test
  @DisplayName("normalizes request path with dot-segments before populating metadata")
  void filterNormalizesRequestPath() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("GET", "/products");

    request.setMethod("GET");
    request.setRequestURI("/api/../products");
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("Request context should contain normalized request.path (dot-segments resolved)")
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/products");
  }

  @Test
  @DisplayName("FR-003b: encoded slash %2F is preserved in request context request.path")
  void filterPreservesEncodedSlashInRequestPath() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("GET", "/api/v1%2Fbypass");

    request.setMethod("GET");
    request.setRequestURI("/api/v1%2Fbypass");
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("FR-003b: Encoded slash %2F must be preserved in request.path metadata")
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/v1%2Fbypass");
  }

  @Test
  @DisplayName("macaroon without path/method/ip caveats still passes validation (FR-024)")
  void macaroonWithoutNewCaveatsStillAuthorized() throws Exception {
    stubProtectedEndpointWithSuccessfulValidation("GET", PROTECTED_PATH);

    request.setMethod("GET");
    request.setRequestURI(PROTECTED_PATH);
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("Authorization", VALID_AUTH_HEADER);

    filter.doFilter(request, response, chain);

    // The filter should have called chain.doFilter (request passes through)
    verify(chain).doFilter(request, response);

    verify(protocol).validate(any(PaymentCredential.class), requestContextCaptor.capture());
    Map<String, String> ctx = requestContextCaptor.getValue();

    assertThat(ctx)
        .as("Request metadata should be populated even when macaroon has no delegation caveats")
        .containsKey(VerificationContextKeys.REQUEST_PATH)
        .containsKey(VerificationContextKeys.REQUEST_METHOD)
        .containsKey(VerificationContextKeys.REQUEST_CLIENT_IP);
  }
}
