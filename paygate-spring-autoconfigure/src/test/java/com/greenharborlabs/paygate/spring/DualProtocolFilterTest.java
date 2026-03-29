package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link PaygateSecurityFilter} dual-protocol behavior. Uses mock {@link
 * PaymentProtocol} instances rather than real L402/MPP implementations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygateSecurityFilter — dual-protocol")
class DualProtocolFilterTest {

  private static final byte[] PAYMENT_HASH = new byte[32];
  private static final byte[] PREIMAGE = new byte[32];
  private static final byte[] ROOT_KEY = new byte[32];
  private static final String TOKEN_ID = "abc123";
  private static final String SERVICE_NAME = "test-service";
  private static final String BOLT11 = "lnbc100n1test";

  @Mock private PaymentProtocol l402Protocol;

  @Mock private PaymentProtocol mppProtocol;

  @Mock private PaygateChallengeService challengeService;

  @Mock private FilterChain filterChain;

  private PaygateEndpointRegistry registry;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  private PaygateEndpointConfig endpointConfig;

  @BeforeEach
  void setUp() {
    registry = new PaygateEndpointRegistry();
    endpointConfig =
        new PaygateEndpointConfig("GET", "/api/protected", 100L, 3600L, "Test endpoint", "", "");
    registry.register(endpointConfig);

    request = new MockHttpServletRequest("GET", "/api/protected");
    response = new MockHttpServletResponse();
  }

  private PaygateSecurityFilter createFilter(List<PaymentProtocol> protocols) {
    return new PaygateSecurityFilter(
        registry, protocols, challengeService, SERVICE_NAME, null, null, null, null);
  }

  private ChallengeContext createChallengeContext() {
    return new ChallengeContext(
        PAYMENT_HASH,
        TOKEN_ID,
        BOLT11,
        100L,
        "Test endpoint",
        SERVICE_NAME,
        3600L,
        "",
        ROOT_KEY,
        null,
        null);
  }

  // --- Test 1: Dual WWW-Authenticate headers on 402 ---

  @Test
  @DisplayName(
      "Both protocols enabled, no auth header -> response has two WWW-Authenticate headers")
  void dualWwwAuthenticateHeadersOn402() throws Exception {
    var challengeContext = createChallengeContext();
    when(challengeService.createChallenge(any(), any())).thenReturn(challengeContext);

    when(l402Protocol.formatChallenge(any()))
        .thenReturn(
            new ChallengeResponse(
                "L402 macaroon=\"abc\", invoice=\"lnbc\"", "L402", Map.of("macaroon", "abc")));
    when(mppProtocol.formatChallenge(any()))
        .thenReturn(
            new ChallengeResponse(
                "Payment method=\"lightning\", token=\"xyz\"", "Payment", Map.of("token", "xyz")));

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(402);
    List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate");
    assertThat(wwwAuthHeaders).hasSize(2);
    assertThat(wwwAuthHeaders).anyMatch(h -> h.startsWith("L402"));
    assertThat(wwwAuthHeaders).anyMatch(h -> h.startsWith("Payment"));
    verify(filterChain, never()).doFilter(any(), any());
  }

  // --- Test 2: L402 auth accepted ---

  @Test
  @DisplayName("Valid L402 auth header -> chain.doFilter called")
  void l402AuthAccepted() throws Exception {
    when(l402Protocol.scheme()).thenReturn("L402");
    when(l402Protocol.canHandle("L402 token:preimage")).thenReturn(true);
    when(l402Protocol.parseCredential("L402 token:preimage"))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "L402", null, mock(ProtocolMetadata.class)));
    doNothing().when(l402Protocol).validate(any(), anyMap());
    when(l402Protocol.createReceipt(any(), any())).thenReturn(Optional.empty());

    request.addHeader("Authorization", "L402 token:preimage");

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }

  // --- Test 3: MPP auth accepted ---

  @Test
  @DisplayName("Valid MPP auth header -> chain.doFilter called")
  void mppAuthAccepted() throws Exception {
    when(l402Protocol.canHandle("Payment method=\"lightning\"")).thenReturn(false);
    when(mppProtocol.scheme()).thenReturn("Payment");
    when(mppProtocol.canHandle("Payment method=\"lightning\"")).thenReturn(true);
    when(mppProtocol.parseCredential("Payment method=\"lightning\""))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "Payment", null, mock(ProtocolMetadata.class)));
    doNothing().when(mppProtocol).validate(any(), anyMap());
    when(mppProtocol.createReceipt(any(), any())).thenReturn(Optional.empty());

    request.addHeader("Authorization", "Payment method=\"lightning\"");

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
  }

  // --- Test 4: Payment-Receipt on MPP success ---

  @Test
  @DisplayName("MPP protocol returns receipt -> Payment-Receipt header present")
  void paymentReceiptOnMppSuccess() throws Exception {
    when(l402Protocol.canHandle("Payment method=\"lightning\"")).thenReturn(false);
    when(mppProtocol.scheme()).thenReturn("Payment");
    when(mppProtocol.canHandle("Payment method=\"lightning\"")).thenReturn(true);
    when(mppProtocol.parseCredential("Payment method=\"lightning\""))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "Payment", null, mock(ProtocolMetadata.class)));
    doNothing().when(mppProtocol).validate(any(), anyMap());

    var receipt =
        new PaymentReceipt(
            "success",
            "challenge-id-1",
            "lightning",
            "ref-123",
            100L,
            "2026-03-21T00:00:00Z",
            "Payment");
    when(mppProtocol.createReceipt(any(), any())).thenReturn(Optional.of(receipt));

    request.addHeader("Authorization", "Payment method=\"lightning\"");

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNotNull();
    assertThat(response.getHeader("Payment-Receipt")).isNotEmpty();
    verify(filterChain).doFilter(request, response);
  }

  // --- Test 5: Cache-Control no-store on 402 ---

  @Test
  @DisplayName("Unauthenticated request -> Cache-Control: no-store on 402")
  void cacheControlNoStoreOn402() throws Exception {
    var challengeContext = createChallengeContext();
    when(challengeService.createChallenge(any(), any())).thenReturn(challengeContext);

    when(l402Protocol.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc")));
    when(mppProtocol.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("Payment challenge", "Payment", Map.of("token", "xyz")));

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  // --- Test 6: Cache-Control private on receipt ---

  @Test
  @DisplayName("Successful MPP with receipt -> Cache-Control: private")
  void cacheControlPrivateOnReceipt() throws Exception {
    when(l402Protocol.canHandle("Payment token")).thenReturn(false);
    when(mppProtocol.scheme()).thenReturn("Payment");
    when(mppProtocol.canHandle("Payment token")).thenReturn(true);
    when(mppProtocol.parseCredential("Payment token"))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "Payment", null, mock(ProtocolMetadata.class)));
    doNothing().when(mppProtocol).validate(any(), anyMap());

    var receipt =
        new PaymentReceipt(
            "success", "chal-1", "lightning", null, 100L, "2026-03-21T00:00:00Z", "Payment");
    when(mppProtocol.createReceipt(any(), any())).thenReturn(Optional.of(receipt));

    request.addHeader("Authorization", "Payment token");

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
    verify(filterChain).doFilter(request, response);
  }

  // --- Test 7: 400 for METHOD_UNSUPPORTED ---

  @Test
  @DisplayName("Protocol throws METHOD_UNSUPPORTED -> 400 Bad Request")
  void methodUnsupported400() throws Exception {
    when(mppProtocol.scheme()).thenReturn("Payment");
    when(mppProtocol.canHandle("Payment bad-method")).thenReturn(true);
    when(mppProtocol.parseCredential("Payment bad-method"))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "Payment", null, mock(ProtocolMetadata.class)));
    doThrow(
            new PaymentValidationException(
                PaymentValidationException.ErrorCode.METHOD_UNSUPPORTED,
                "Only lightning method is supported"))
        .when(mppProtocol)
        .validate(any(), anyMap());

    request.addHeader("Authorization", "Payment bad-method");

    var filter = createFilter(List.of(mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getContentAsString()).contains("method-unsupported");
    verify(filterChain, never()).doFilter(any(), any());
  }

  // --- Test 8: 503 on unexpected error ---

  @Test
  @DisplayName("Protocol.validate() throws RuntimeException -> 503 Service Unavailable")
  void unexpectedError503() throws Exception {
    when(mppProtocol.scheme()).thenReturn("Payment");
    when(mppProtocol.canHandle("Payment crash")).thenReturn(true);
    when(mppProtocol.parseCredential("Payment crash"))
        .thenReturn(
            new PaymentCredential(
                PAYMENT_HASH, PREIMAGE, TOKEN_ID, "Payment", null, mock(ProtocolMetadata.class)));
    doThrow(new RuntimeException("Unexpected internal error"))
        .when(mppProtocol)
        .validate(any(), anyMap());

    request.addHeader("Authorization", "Payment crash");

    var filter = createFilter(List.of(mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("LIGHTNING_UNAVAILABLE");
    verify(filterChain, never()).doFilter(any(), any());
  }

  // --- Test 9: Single shared invoice (both protocols get same ChallengeContext) ---

  @Test
  @DisplayName("Both protocols receive the same ChallengeContext from a single invoice creation")
  void singleSharedInvoice() throws Exception {
    var challengeContext = createChallengeContext();
    when(challengeService.createChallenge(any(), any())).thenReturn(challengeContext);

    var l402ContextCaptor = ArgumentCaptor.forClass(ChallengeContext.class);
    var mppContextCaptor = ArgumentCaptor.forClass(ChallengeContext.class);

    when(l402Protocol.formatChallenge(l402ContextCaptor.capture()))
        .thenReturn(new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc")));
    when(mppProtocol.formatChallenge(mppContextCaptor.capture()))
        .thenReturn(new ChallengeResponse("Payment challenge", "Payment", Map.of("token", "xyz")));

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    // Both protocols received the exact same ChallengeContext instance
    assertThat(l402ContextCaptor.getValue()).isSameAs(mppContextCaptor.getValue());
    // The shared context has the expected invoice
    assertThat(l402ContextCaptor.getValue().bolt11Invoice()).isEqualTo(BOLT11);
  }

  // --- Test 10: No auth header match falls through to challenge ---

  @Test
  @DisplayName("Auth header that no protocol handles -> 402 challenge response")
  void noAuthHeaderMatchFallsThrough() throws Exception {
    when(l402Protocol.canHandle("Bearer some-jwt")).thenReturn(false);
    when(mppProtocol.canHandle("Bearer some-jwt")).thenReturn(false);

    var challengeContext = createChallengeContext();
    when(challengeService.createChallenge(any(), any())).thenReturn(challengeContext);

    when(l402Protocol.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc")));
    when(mppProtocol.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("Payment challenge", "Payment", Map.of("token", "xyz")));

    request.addHeader("Authorization", "Bearer some-jwt");

    var filter = createFilter(List.of(l402Protocol, mppProtocol));
    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(402);
    List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate");
    assertThat(wwwAuthHeaders).hasSize(2);
    verify(filterChain, never()).doFilter(any(), any());
  }
}
