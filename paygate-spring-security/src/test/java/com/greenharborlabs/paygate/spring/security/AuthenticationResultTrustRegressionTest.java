package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A non-conforming {@link AuthenticationManager} must never open a paid route. Whatever the manager
 * returns other than an authenticated {@link PaygateAuthenticationToken} fails closed with 503,
 * leaves the security context empty, and does not mark the request as a successful payment.
 */
@DisplayName("Spring Security filter verifies the AuthenticationManager result")
class AuthenticationResultTrustRegressionTest {

  private static final String SUCCESSFUL_PAID_HANDLER_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPaidHandler";
  private static final String SUCCESSFUL_POLICY_DECISION_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPolicyDecision";

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "manager returns {0}")
  @ValueSource(strings = {"null", "input", "foreign-authenticated"})
  void nonConformingResultFailsClosedBeforePaidHandler(String mode) throws Exception {
    var registry = new PaygateEndpointRegistry(3600);
    registry.register(new PaygateEndpointConfig("GET", "/paid", 10, 3600, "", "", ""));

    AuthenticationManager manager = authentication -> resultFor(mode, authentication);
    var filter =
        new PaygateAuthenticationFilter(
            manager,
            List.of(),
            registry,
            null,
            "regression",
            mock(PaygateAuthenticationEntryPoint.class));

    var request = new MockHttpServletRequest("GET", "/paid");
    // Structurally valid L402 header shape; no valid payment.
    request.addHeader("Authorization", "L402 dGVzdG1hY2Fyb29u:" + "a".repeat(64));
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("paid handler must not run").isNull();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("LIGHTNING_UNAVAILABLE");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE)).isNull();
    assertThat(request.getAttribute(SUCCESSFUL_POLICY_DECISION_ATTRIBUTE)).isNull();
  }

  private static Authentication resultFor(String mode, Authentication input) {
    return switch (mode) {
      case "null" -> null;
      case "input" -> input;
      case "foreign-authenticated" ->
          UsernamePasswordAuthenticationToken.authenticated("user", null, List.of());
      default -> throw new IllegalArgumentException(mode);
    };
  }
}
