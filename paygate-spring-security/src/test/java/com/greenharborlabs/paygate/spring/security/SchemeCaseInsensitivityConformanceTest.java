package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("Protocol conformance: case-insensitive auth scheme dispatch (Spring Security filter)")
class SchemeCaseInsensitivityConformanceTest {

  private static final String VALID_MACAROON_B64 = "dGVzdG1hY2Fyb29u";
  private static final String VALID_PREIMAGE = "a".repeat(64);
  private static final PaygateEndpointConfig DEFAULT_CONFIG =
      new PaygateEndpointConfig("GET", "/api/protected", 100, 3600, "test", "", null);

  @Mock private AuthenticationManager authenticationManager;
  @Mock private PaygateEndpointRegistry endpointRegistry;
  @Mock private FilterChain filterChain;
  @Mock private Authentication authenticatedResult;

  private PaygateAuthenticationFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    filter =
        new PaygateAuthenticationFilter(
            authenticationManager,
            List.of(new FakePaymentProtocol("L402 "), new FakePaymentProtocol("Payment ")),
            endpointRegistry);
    request = new MockHttpServletRequest("GET", "/api/protected");
    response = new MockHttpServletResponse();
    when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);
    when(authenticationManager.authenticate(any())).thenReturn(authenticatedResult);
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "[{index}] scheme=''{0}''")
  @CsvSource({"L402", "l402", "LsAt", "lsat"})
  void parsesL402AndLsatSchemesCaseInsensitivelyAtFilterLayer(String scheme) throws Exception {
    request.addHeader("Authorization", scheme + " " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE);

    filter.doFilter(request, response, filterChain);

    var tokenCaptor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());

    var token = tokenCaptor.getValue();
    assertThat(token.isAuthenticated()).isFalse();
    assertThat(token.getComponents()).isNotNull();
    assertThat(token.getComponents().scheme()).isEqualToIgnoringCase(scheme);
    assertThat(token.getAuthorizationHeader()).isNull();
  }

  @ParameterizedTest(name = "[{index}] scheme=''{0}''")
  @CsvSource({"Payment", "payment", "PAYMENT", "PaYmEnT"})
  void matchesPaymentSchemeCaseInsensitivelyAndBuildsUnauthenticatedRawHeaderToken(String scheme)
      throws Exception {
    request.addHeader("Authorization", scheme + " token=xyz");

    filter.doFilter(request, response, filterChain);

    var tokenCaptor = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());

    var token = tokenCaptor.getValue();
    assertThat(token.isAuthenticated()).isFalse();
    assertThat(token.getComponents()).isNull();
    assertThat(token.getAuthorizationHeader()).isEqualTo(scheme + " token=xyz");
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .isEqualTo(authenticatedResult);
  }

  private static final class FakePaymentProtocol implements PaymentProtocol {
    private final String prefix;

    private FakePaymentProtocol(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public String scheme() {
      return prefix.substring(0, prefix.length() - 1);
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      if (authorizationHeader == null || authorizationHeader.length() < prefix.length()) {
        return false;
      }
      return authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    @Override
    public com.greenharborlabs.paygate.api.PaymentCredential parseCredential(
        String authorizationHeader) {
      throw new UnsupportedOperationException("Not used in this filter conformance test");
    }

    @Override
    public com.greenharborlabs.paygate.api.ChallengeResponse formatChallenge(
        com.greenharborlabs.paygate.api.ChallengeContext context) {
      throw new UnsupportedOperationException("Not used in this filter conformance test");
    }

    @Override
    public void validate(
        com.greenharborlabs.paygate.api.PaymentCredential credential,
        java.util.Map<String, String> requestContext) {
      throw new UnsupportedOperationException("Not used in this filter conformance test");
    }
  }
}
