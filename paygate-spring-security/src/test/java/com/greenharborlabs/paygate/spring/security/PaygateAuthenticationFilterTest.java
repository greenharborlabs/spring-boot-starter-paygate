package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.spring.ApplicationRelativeRequestResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.RequestDigestSupport;
import com.greenharborlabs.paygate.spring.ResolvedEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class PaygateAuthenticationFilterTest {

  @Mock private AuthenticationManager authenticationManager;

  @Mock private PaygateEndpointRegistry endpointRegistry;

  @Mock private FilterChain filterChain;

  @Mock private Authentication authenticatedResult;

  @Mock private PaygateAuthenticationEntryPoint authenticationEntryPoint;

  private PaygateAuthenticationFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  private static final String VALID_PREIMAGE = "a".repeat(64);
  private static final String VALID_MACAROON_B64 = "dGVzdG1hY2Fyb29u";
  private static final PaygateEndpointConfig DEFAULT_CONFIG =
      new PaygateEndpointConfig("GET", "/", 10, 3600, "default", "", null);

  @BeforeEach
  void setUp() {
    // Default: all endpoints are registered. Tests for unregistered endpoints override this.
    org.mockito.Mockito.lenient()
        .when(endpointRegistry.findConfig(anyString(), anyString()))
        .thenReturn(DEFAULT_CONFIG);
    org.mockito.Mockito.lenient()
        .when(endpointRegistry.resolve(any(HttpServletRequest.class)))
        .thenAnswer(
            invocation -> {
              HttpServletRequest resolvedRequest = invocation.getArgument(0);
              String method = resolvedRequest.getMethod();
              String path = ApplicationRelativeRequestResolver.resolve(resolvedRequest);
              PaygateEndpointConfig config = endpointRegistry.findConfig(method, path);
              return config == null
                  ? null
                  : new ResolvedEndpoint(config, config.pathPattern(), config.httpMethod());
            });
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager,
            List.of(),
            endpointRegistry,
            null,
            null,
            authenticationEntryPoint);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    SecurityContextHolder.clearContext();
  }

  @Test
  void constructorRejectsNullAuthenticationManager() {
    assertThatThrownBy(() -> new PaygateAuthenticationFilter(null, List.of(), endpointRegistry))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructorRejectsNullEndpointRegistry() {
    assertThatThrownBy(
            () -> new PaygateAuthenticationFilter(authenticationManager, List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructorAcceptsNullProtocols() {
    var f = new PaygateAuthenticationFilter(authenticationManager, null, endpointRegistry);
    assertThat(f).isNotNull();
  }

  @Test
  void skipsUnregisteredRouteWhenNoAuthorizationHeader() throws ServletException, IOException {
    request.setRequestURI("/api/unregistered");
    org.mockito.Mockito.lenient()
        .when(endpointRegistry.findConfig("GET", "/api/unregistered"))
        .thenReturn(null);
    when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(authenticationManager, never()).authenticate(any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void rejectsMissingCredentialForRegisteredPaidRoute() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/protected");
    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "paid", "", null);
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain, never()).doFilter(any(), any());
    verify(authenticationEntryPoint)
        .commence(
            org.mockito.ArgumentMatchers.same(request),
            org.mockito.ArgumentMatchers.same(response),
            any(ResolvedEndpoint.class));
  }

  @Test
  void skipsUnregisteredRouteWhenBlankAuthorizationHeader() throws ServletException, IOException {
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "   ");
    org.mockito.Mockito.lenient()
        .when(endpointRegistry.findConfig("GET", "/api/unregistered"))
        .thenReturn(null);
    when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(authenticationManager, never()).authenticate(any());
  }

  @Test
  void skipsUnregisteredRouteWhenNonL402AuthorizationHeader() throws ServletException, IOException {
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "Bearer some-jwt-token");
    org.mockito.Mockito.lenient()
        .when(endpointRegistry.findConfig("GET", "/api/unregistered"))
        .thenReturn(null);
    when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(authenticationManager, never()).authenticate(any());
  }

  @Test
  void rejectsUnrelatedAuthorizationSchemeForRegisteredPaidRoute()
      throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/protected");
    request.addHeader("Authorization", "Bearer unrelated-jwt");
    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "paid", "", null);
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void extractsL402CredentialAndAuthenticates() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    PaygateAuthenticationToken unauthToken = captor.getValue();
    assertThat(unauthToken.isAuthenticated()).isFalse();
    assertThat(unauthToken.getComponents()).isNotNull();
    assertThat(unauthToken.getComponents().macaroonBase64()).isEqualTo(VALID_MACAROON_B64);
    assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(VALID_PREIMAGE);
    assertThat(unauthToken.getComponents().scheme()).isEqualTo("L402");

    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void l402TokenIncludesConcretePathCanonicalRouteAndActualMethod()
      throws ServletException, IOException {
    request.setMethod("POST");
    request.setRequestURI("/api/orders/42");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var matchedConfig =
        new PaygateEndpointConfig("GET", "/api/orders/{orderId}", 10, 3600, "Order", "", null);
    when(endpointRegistry.findConfig("POST", "/api/orders/42")).thenReturn(matchedConfig);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    assertThat(captor.getValue().getRequestMetadata())
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/orders/42")
        .containsEntry(VerificationContextKeys.REQUEST_ROUTE, "/api/orders/{orderId}")
        .containsEntry(VerificationContextKeys.REQUEST_METHOD, "POST");
  }

  @Test
  void headUsesInheritedGetPolicyAndCanonicalRouteWhileCredentialMethodRemainsHead()
      throws ServletException, IOException {
    request.setMethod("HEAD");
    request.setRequestURI("/api/orders/42");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var getConfig =
        new PaygateEndpointConfig("GET", "/api/orders/{orderId}", 10, 3600, "Order", "", "read");
    org.mockito.Mockito.doReturn(new ResolvedEndpoint(getConfig, "/api/orders/{orderId}", "GET"))
        .when(endpointRegistry)
        .resolve(request);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(captor.getValue().getRequestMetadata())
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/orders/42")
        .containsEntry(VerificationContextKeys.REQUEST_ROUTE, "/api/orders/{orderId}")
        .containsEntry(VerificationContextKeys.REQUEST_METHOD, "HEAD")
        .containsEntry(VerificationContextKeys.REQUESTED_CAPABILITY, "read");
  }

  @Test
  void getBoundCredentialIsRejectedForHeadAndDoesNotContinueFilterChain()
      throws ServletException, IOException {
    request.setMethod("HEAD");
    request.setRequestURI("/api/protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    var getConfig =
        new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "Protected", "", null);
    org.mockito.Mockito.doReturn(new ResolvedEndpoint(getConfig, "/api/protected", "GET"))
        .when(endpointRegistry)
        .resolve(request);
    when(authenticationManager.authenticate(any()))
        .thenAnswer(
            invocation -> {
              PaygateAuthenticationToken token = invocation.getArgument(0);
              String actualMethod =
                  token.getRequestMetadata().get(VerificationContextKeys.REQUEST_METHOD);
              if (!"GET".equals(actualMethod)) {
                throw new BadCredentialsException("credential is bound to GET");
              }
              return authenticatedResult;
            });

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void explicitHeadPolicyWinsAndStillUsesActualHeadCredentialMethod()
      throws ServletException, IOException {
    request.setMethod("HEAD");
    request.setRequestURI("/api/protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    var headConfig =
        new PaygateEndpointConfig("HEAD", "/api/protected", 20, 3600, "Head", "", "head-read");
    org.mockito.Mockito.doReturn(new ResolvedEndpoint(headConfig, "/api/protected", "HEAD"))
        .when(endpointRegistry)
        .resolve(request);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(captor.getValue().getRequestMetadata())
        .containsEntry(VerificationContextKeys.REQUEST_METHOD, "HEAD")
        .containsEntry(VerificationContextKeys.REQUEST_ROUTE, "/api/protected")
        .containsEntry(VerificationContextKeys.REQUESTED_CAPABILITY, "head-read");
  }

  @Test
  void optionsDoesNotInheritGetPolicy() throws ServletException, IOException {
    request.setMethod("OPTIONS");
    request.setRequestURI("/api/protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    org.mockito.Mockito.doReturn(null).when(endpointRegistry).resolve(request);

    filter.doFilter(request, response, filterChain);

    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void authenticatesProtectedRouteUnderContextPathUsingApplicationRelativePath()
      throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/shop/api/protected");
    request.setContextPath("/shop");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(captor.getValue().getRequestMetadata())
        .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/protected");
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void extractsLsatCredentialAndAuthenticates() throws ServletException, IOException {
    request.addHeader("Authorization", "LSAT " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    verify(authenticationManager).authenticate(any(PaygateAuthenticationToken.class));
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void l402OnlyFlowDoesNotWrapOrBoundRequestBody() throws ServletException, IOException {
    request.setMethod("POST");
    request.setRequestURI("/api/protected");
    request.setContent(new byte[RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1]);
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void l402OnlyFlowPreservesBodyForDownstreamRead() throws ServletException, IOException {
    String payload =
        "streaming-payload-" + "x".repeat(RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1);
    request.setMethod("POST");
    request.setRequestURI("/api/protected");
    request.setContent(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    FilterChain readingChain =
        (req, res) ->
            assertThat(((HttpServletRequest) req).getInputStream().readAllBytes())
                .isEqualTo(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    filter.doFilter(request, response, readingChain);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void returns401WhenAuthenticationFails() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("Invalid L402 credential"));

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 401, \"error\": \"AUTHENTICATION_FAILED\", \"message\": \"L402 authentication failed\"}");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void rejectsMalformedPreimageForRegisteredPaidRoute() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":not-hex");

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  @Test
  void extractsUppercaseHexPreimageAndAuthenticates() throws ServletException, IOException {
    String uppercasePreimage = "A".repeat(64);
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + uppercasePreimage);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    PaygateAuthenticationToken unauthToken = captor.getValue();
    assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(uppercasePreimage);
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void extractsMixedCaseHexPreimageAndAuthenticates() throws ServletException, IOException {
    String mixedCasePreimage = "aAbBcCdD".repeat(8);
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + mixedCasePreimage);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    verify(authenticationManager).authenticate(any(PaygateAuthenticationToken.class));
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void rejectsEmptyMacaroonForRegisteredPaidRoute() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 :" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  @Test
  void returns503WhenRuntimeExceptionThrown() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any()))
        .thenThrow(new RuntimeException("gRPC channel unavailable"));

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doesNotLogCredentialMarkersFromUnexpectedAuthenticationExceptions()
      throws ServletException, IOException {
    String credentialMarker = "SPRING_SECURITY_SECRET_MACAROON_MARKER";
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any()))
        .thenThrow(new RuntimeException(credentialMarker));

    try (var logCapture = LogCapture.attach(PaygateAuthenticationFilter.class.getName())) {
      filter.doFilter(request, response, filterChain);

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(logCapture.contents())
          .contains(
              "Payment authentication encountered an unexpected error; failing closed with service unavailable")
          .doesNotContain(credentialMarker);
    }
  }

  @Test
  void rejectsOversizedMacaroonForRegisteredPaidRoute() throws ServletException, IOException {
    String oversizedMacaroon = "A".repeat(8193);
    request.addHeader("Authorization", "L402 " + oversizedMacaroon + ":" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  @Test
  void rejectsMalformedMacaroonForRegisteredPaidRoute() throws ServletException, IOException {
    request.addHeader("Authorization", "L402 mac:with:colons:" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  @Test
  void extractsMultiTokenHeaderAndAuthenticates() throws ServletException, IOException {
    String secondToken = "c2Vjb25kdG9rZW4=";
    request.addHeader(
        "Authorization", "L402 " + VALID_MACAROON_B64 + "," + secondToken + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    PaygateAuthenticationToken unauthToken = captor.getValue();
    assertThat(unauthToken.getComponents().macaroonBase64())
        .isEqualTo(VALID_MACAROON_B64 + "," + secondToken);
    assertThat(unauthToken.getComponents().preimageHex()).isEqualTo(VALID_PREIMAGE);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void rejectsOversizedMultiTokenForRegisteredPaidRoute() throws ServletException, IOException {
    String oversizedTokens = "A".repeat(4000) + "," + "B".repeat(4193);
    request.addHeader("Authorization", "L402 " + oversizedTokens + ":" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  // --- Capability lookup tests ---

  @Test
  void passesCapabilityFromRegistryToToken() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    assertThat(
            captor
                .getValue()
                .getRequestMetadata()
                .get(VerificationContextKeys.REQUESTED_CAPABILITY))
        .isEqualTo("read");
  }

  @Test
  void skipsAuthWhenConfigNotFound() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void passesNullCapabilityWhenConfigHasEmptyCapability() throws ServletException, IOException {
    request.setMethod("POST");
    request.setRequestURI("/api/no-capability");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("POST", "/api/no-capability", 10, 3600, "desc", "", "");
    when(endpointRegistry.findConfig("POST", "/api/no-capability")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    assertThat(captor.getValue().getRequestMetadata())
        .doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
  }

  @Test
  void passesNullCapabilityWhenConfigHasBlankCapability() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/blank-cap");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("GET", "/api/blank-cap", 10, 3600, "desc", "", "   ");
    when(endpointRegistry.findConfig("GET", "/api/blank-cap")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    assertThat(captor.getValue().getRequestMetadata())
        .doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
  }

  @Test
  void returns500WhenRegistryThrowsException() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/error-path");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    when(endpointRegistry.findConfig(anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("secret policy detail"));
    SecurityContextHolder.getContext().setAuthentication(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .contains("INTERNAL_ERROR")
        .doesNotContain("secret policy detail", "/api/error-path");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void returns400WhenRequestUriIsMalformed() throws ServletException, IOException {
    HttpServletRequest malformedRequest = mock(HttpServletRequest.class);
    when(malformedRequest.getHeader("Authorization"))
        .thenReturn("L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(malformedRequest.getRequestURI()).thenThrow(new IllegalArgumentException("bad uri"));

    filter.doFilter(malformedRequest, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
    verify(authenticationManager, never()).authenticate(any());
  }

  @Test
  void rejectsAmbiguousContextPrefixBeforeAuthenticationOrHandler()
      throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/application/api/protected");
    request.setContextPath("/app");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    verify(endpointRegistry, never()).findConfig(anyString(), anyString());
    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void normalizesPathTraversalBeforeRegistryLookup() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/admin/../protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    verify(endpointRegistry).findConfig("GET", "/api/protected");
    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(
            captor
                .getValue()
                .getRequestMetadata()
                .get(VerificationContextKeys.REQUESTED_CAPABILITY))
        .isEqualTo("read");
  }

  @Test
  void normalizesPercentEncodedTraversalBeforeRegistryLookup()
      throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/admin/%2e%2e/protected");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("GET", "/api/protected", 10, 3600, "desc", "", "write");
    when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    verify(endpointRegistry).findConfig("GET", "/api/protected");
    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(
            captor
                .getValue()
                .getRequestMetadata()
                .get(VerificationContextKeys.REQUESTED_CAPABILITY))
        .isEqualTo("write");
  }

  @Test
  void passesNullCapabilityWhenConfigHasNullCapability() throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/null-cap");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config = new PaygateEndpointConfig("GET", "/api/null-cap", 10, 3600, "desc", "", null);
    when(endpointRegistry.findConfig("GET", "/api/null-cap")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    assertThat(captor.getValue().getRequestMetadata())
        .doesNotContainKey(VerificationContextKeys.REQUESTED_CAPABILITY);
  }

  // --- Protocol-agnostic (MPP) detection tests ---

  private PaymentProtocol mockMppProtocol() {
    PaymentProtocol protocol = mock(PaymentProtocol.class);
    when(protocol.canHandle(anyString()))
        .thenAnswer(
            invocation -> {
              String header = invocation.getArgument(0);
              return header.startsWith("Payment ");
            });
    return protocol;
  }

  @Test
  void detectsMppCredentialViaProtocolCanHandle() throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.addHeader("Authorization", "Payment preimage=abc123");
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    PaygateAuthenticationToken token = captor.getValue();
    assertThat(token.isAuthenticated()).isFalse();
    assertThat(token.getAuthorizationHeader()).isEqualTo("Payment preimage=abc123");
    assertThat(token.getComponents()).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void mppTokenIncludesRequestMetadata() throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.setMethod("POST");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "Payment preimage=abc123");
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    Map<String, String> metadata = captor.getValue().getRequestMetadata();
    assertThat(metadata).containsEntry("request.path", "/api/resource");
    assertThat(metadata).containsEntry("request.method", "POST");
    assertThat(metadata).containsKey("request.client_ip");
    assertThat(metadata).containsKey(VerificationContextKeys.REQUEST_DIGEST);
  }

  @Test
  void mppTokenIncludesCapabilityFromRegistry() throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.setMethod("GET");
    request.setRequestURI("/api/premium");
    request.addHeader("Authorization", "Payment preimage=abc123");
    var config = new PaygateEndpointConfig("GET", "/api/premium", 10, 3600, "desc", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/premium")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(
            captor
                .getValue()
                .getRequestMetadata()
                .get(VerificationContextKeys.REQUESTED_CAPABILITY))
        .isEqualTo("read");
  }

  @Test
  void rejectsUnrecognizedCredentialSchemeForRegisteredPaidRoute()
      throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.addHeader("Authorization", "Bearer some-jwt-token");

    filter.doFilter(request, response, filterChain);

    assertRejectedWithoutAuthentication();
  }

  @Test
  void l402TakesPrecedenceOverProtocolMatch() throws ServletException, IOException {
    // Use a lenient mock since canHandle should NOT be called when L402 is detected first
    PaymentProtocol alwaysMatch =
        mock(PaymentProtocol.class, withSettings().strictness(Strictness.LENIENT));
    when(alwaysMatch.canHandle(anyString())).thenReturn(true);
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(alwaysMatch), endpointRegistry);

    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    ArgumentCaptor<PaygateAuthenticationToken> captor =
        ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(captor.capture());

    PaygateAuthenticationToken token = captor.getValue();
    assertThat(token.getComponents()).isNotNull();
    assertThat(token.getAuthorizationHeader()).isNull();
    verify(alwaysMatch, never()).canHandle(anyString());
  }

  @Test
  void mppAuthenticationFailureReturns401() throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.addHeader("Authorization", "Payment preimage=invalid");
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("Invalid payment credential"));

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void mppRuntimeExceptionReturns503() throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.addHeader("Authorization", "Payment preimage=abc123");
    when(authenticationManager.authenticate(any()))
        .thenThrow(new RuntimeException("backend unavailable"));

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  // --- Unregistered endpoint bypass tests ---

  @Test
  void skipsAuthenticationWhenEndpointNotRegisteredWithL402Credential()
      throws ServletException, IOException {
    request.setMethod("GET");
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(authenticationManager, never()).authenticate(any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void skipsAuthenticationWhenEndpointNotRegisteredWithMppCredential()
      throws ServletException, IOException {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mockMppProtocol()), endpointRegistry);

    request.setMethod("GET");
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "Payment preimage=abc123");

    when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(authenticationManager, never()).authenticate(any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  // --- shouldNotFilter tests ---

  @Test
  void shouldNotFilterWhenNoAuthorizationHeader() {
    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void shouldNotFilterWhenBlankAuthorizationHeader() {
    request.addHeader("Authorization", "   ");
    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void shouldNotFilterWhenUnrecognizedAuthScheme() {
    request.addHeader("Authorization", "Bearer some-jwt-token");
    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void shouldFilterWhenL402AuthorizationHeader() {
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);
    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  void shouldFilterWhenMppProtocolMatches() {
    filter = new PaygateAuthenticationFilter(authenticationManager, List.of(), endpointRegistry);
    request.addHeader("Authorization", "Payment preimage=abc123");
    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  // --- Receipt generation tests ---

  private PaymentProtocol mockMppProtocolWithScheme() {
    PaymentProtocol protocol =
        mock(PaymentProtocol.class, withSettings().strictness(Strictness.LENIENT));
    when(protocol.canHandle(anyString()))
        .thenAnswer(
            invocation -> {
              String header = invocation.getArgument(0);
              return header.startsWith("Payment ");
            });
    when(protocol.scheme()).thenReturn("Payment");
    return protocol;
  }

  private PaygateAuthenticationToken createAuthenticatedMppToken() {
    byte[] paymentHash = new byte[32];
    byte[] preimage = new byte[32];
    PaymentCredential credential =
        new PaymentCredential(
            paymentHash, preimage, "test-token-id", "Payment", null, new ProtocolMetadata() {});
    return PaygateAuthenticationToken.authenticated(credential, "test-service");
  }

  @Test
  void mppAuthenticationProducesPaymentReceiptHeader() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
    var receipt =
        new PaymentReceipt(
            "success", "challenge-123", "lightning", null, 100, "2026-03-26T00:00:00Z", "Payment");
    PaygateAuthenticationToken authenticatedToken =
        PaygateAuthenticationToken.authenticated(
            "test-token-id", "test-service", "Payment", Map.of(), List.of(), receipt);

    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "Payment preimage=abc123");

    var config =
        new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNotNull();
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void l402AuthenticationDoesNotProducePaymentReceiptHeader() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
    // L402 authenticated token has null paymentCredential
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    var config =
        new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNull();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void receiptCreationFailureDoesNotBlockRequest() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
    when(mppProtocol.createReceipt(any(PaymentCredential.class), any(ChallengeContext.class)))
        .thenThrow(new RuntimeException("receipt creation failed"));

    PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "Payment preimage=abc123");

    var config =
        new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNull();
    assertThat(response.getStatus()).isEqualTo(200);
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
  }

  @Test
  void missingEndpointConfigSkipsAuthAndReceipt() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();

    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/unregistered");
    request.addHeader("Authorization", "Payment preimage=abc123");

    when(endpointRegistry.findConfig("GET", "/api/unregistered")).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNull();
    assertThat(response.getStatus()).isEqualTo(200);
    verify(filterChain).doFilter(request, response);
    verify(authenticationManager, never()).authenticate(any());
    verify(mppProtocol, never()).createReceipt(any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void nonPaygateAuthenticationTokenSkipsReceiptAndSucceeds() throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();

    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "Payment preimage=abc123");

    var config =
        new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
    // Return a non-PaygateAuthenticationToken
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Payment-Receipt")).isNull();
    verify(filterChain)
        .doFilter(any(HttpServletRequest.class), org.mockito.ArgumentMatchers.eq(response));
    verify(mppProtocol, never()).createReceipt(any(), any());
  }

  @Test
  void filterDoesNotCreateReceiptsFromAuthenticatedCredentials()
      throws ServletException, IOException {
    PaymentProtocol mppProtocol = mockMppProtocolWithScheme();
    when(mppProtocol.createReceipt(any(PaymentCredential.class), any(ChallengeContext.class)))
        .thenReturn(Optional.empty());

    PaygateAuthenticationToken authenticatedToken = createAuthenticatedMppToken();

    filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(mppProtocol), endpointRegistry, null, "test-service");

    request.setMethod("GET");
    request.setRequestURI("/api/resource");
    request.addHeader("Authorization", "Payment preimage=abc123");

    var config =
        new PaygateEndpointConfig("GET", "/api/resource", 100, 3600, "Test resource", "", "read");
    when(endpointRegistry.findConfig("GET", "/api/resource")).thenReturn(config);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedToken);

    filter.doFilter(request, response, filterChain);

    verify(mppProtocol, never())
        .createReceipt(any(PaymentCredential.class), any(ChallengeContext.class));
  }

  private void assertRejectedWithoutAuthentication() throws IOException, ServletException {
    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(authenticationManager, never()).authenticate(any());
    verify(filterChain, never()).doFilter(any(), any());
  }

  private static final class LogCapture extends Handler implements AutoCloseable {
    private final Logger logger;
    private final List<LogRecord> records = new java.util.ArrayList<>();

    private LogCapture(Logger logger) {
      this.logger = logger;
    }

    static LogCapture attach(String loggerName) {
      Logger logger = Logger.getLogger(loggerName);
      var capture = new LogCapture(logger);
      logger.addHandler(capture);
      return capture;
    }

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      logger.removeHandler(this);
    }

    String contents() {
      return records.stream()
          .map(
              record ->
                  record.getMessage()
                      + java.util.Arrays.toString(record.getParameters())
                      + record.getThrown())
          .collect(java.util.stream.Collectors.joining("\n"));
    }
  }
}
