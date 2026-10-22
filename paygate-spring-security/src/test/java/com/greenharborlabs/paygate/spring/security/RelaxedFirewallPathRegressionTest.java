package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import jakarta.servlet.FilterChain;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Regression for SR-01, SR-03, SR-04 and SR-17 (2026-10-18 security review) in Spring Security
 * mode. The default {@link StrictHttpFirewall} rejects every vector; an application that relaxes
 * the firewall must still not reach a paid route through them, because {@link
 * PaygateAuthenticationFilter} rejects the request itself.
 */
@DisplayName("Spring Security path identity with a relaxed firewall")
class RelaxedFirewallPathRegressionTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/fn/premium/..",
        "/fn/premium/%2e%2e",
        "/fn/premium/%252e%252e",
        "/fn/premium/%25252e%25252e",
        "/fn/premium/..;x=1",
        "/fn/premium/%2e%2e;x=1",
        "/fn/premium/.",
        "/fn/premium/%252e",
        "/fn/premiumall/a/%252e%252e/%252e%252e/api/free",
        "/files/private/%252e%252e/public/report",
        "/files/private/x/..;jsessionid=1/../public/report"
      })
  @DisplayName("the default StrictHttpFirewall rejects every vector")
  void defaultFirewallRejects(String rawUri) {
    var request = tomcatRequest(rawUri);

    assertThatThrownBy(() -> new StrictHttpFirewall().getFirewalledRequest(request))
        .isInstanceOf(RequestRejectedException.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/fn/premium/%2e%2e",
        "/fn/premium/%252e%252e",
        "/fn/premium/%25252e%25252e",
        "/fn/premium/%2e%2e;x=1",
        "/fn/premium/%252e",
        "/fn/premium/x;tier=premium",
        "/fn/premiumall/a/%252e%252e/%252e%252e/api/free",
        "/files/private/%252e%252e/public/report"
      })
  @DisplayName("Paygate rejects with 400 what a relaxed firewall lets through")
  void paygateRejectsWhatRelaxedFirewallAllows(String rawUri) throws Exception {
    var firewalled = relaxedFirewall().getFirewalledRequest(tomcatRequest(rawUri));
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", "/fn/premium/{name}", 10, 60, "", "", ""));
    registry.register(new PaygateEndpointConfig("GET", "/fn/premiumall/**", 10, 60, "", "", ""));
    registry.register(new PaygateEndpointConfig("GET", "/files/**", 10, 60, "", "", ""));
    var authenticationManager = mock(AuthenticationManager.class);
    var filter =
        new PaygateAuthenticationFilter(
            authenticationManager, List.of(), registry, null, "svc", null);
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    filter.doFilter(firewalled, response, chain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("MALFORMED_URI");
    verify(authenticationManager, never()).authenticate(any());
    verify(chain, never()).doFilter(any(), any());
  }

  private static StrictHttpFirewall relaxedFirewall() {
    var firewall = new StrictHttpFirewall();
    firewall.setAllowUrlEncodedPercent(true);
    firewall.setAllowUrlEncodedPeriod(true);
    firewall.setAllowSemicolon(true);
    firewall.setAllowUrlEncodedDoubleSlash(true);
    return firewall;
  }

  /** Mirrors Tomcat: the servlet path is decoded and normalized while the request URI stays raw. */
  private static MockHttpServletRequest tomcatRequest(String rawUri) {
    var request = new MockHttpServletRequest("GET", rawUri);
    String decoded = rawUri.replace("%2e", ".").replaceAll(";[^/]*", "");
    var segments = new ArrayDeque<String>();
    for (String segment : decoded.substring(1).split("/", -1)) {
      if (".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (!segments.isEmpty()) {
          segments.removeLast();
        }
        continue;
      }
      segments.addLast(segment);
    }
    request.setServletPath("/" + String.join("/", segments));
    return request;
  }
}
