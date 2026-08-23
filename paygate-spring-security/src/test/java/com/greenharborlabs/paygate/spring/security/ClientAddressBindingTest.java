package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("Spring Security client-address binding")
class ClientAddressBindingTest {

  private static final String AUTHORIZATION = "L402 dGVzdG1hY2Fyb29u:" + "a".repeat(64);
  private static final PaygateEndpointConfig ENDPOINT =
      new PaygateEndpointConfig("GET", "/paid", 10, 60, "paid endpoint", "", "");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("authentication receives the canonical trusted address")
  void authenticationUsesTrustedAddress() throws Exception {
    var authenticationManager = mock(AuthenticationManager.class);
    var resolver = mock(ClientIpResolver.class);
    var registry = paidRegistry();
    var request = request();
    var authenticated = mock(Authentication.class);
    when(resolver.resolveBindingAddress(request)).thenReturn(Optional.of("2001:db8:0:0:0:0:0:7"));
    when(authenticationManager.authenticate(any())).thenReturn(authenticated);
    var filter = bindingFilter(authenticationManager, registry, resolver);
    var chain = mock(jakarta.servlet.FilterChain.class);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    var token = ArgumentCaptor.forClass(PaygateAuthenticationToken.class);
    verify(authenticationManager).authenticate(token.capture());
    assertThat(token.getValue().getRequestMetadata())
        .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, "2001:db8:0:0:0:0:0:7");
    verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("unavailable provenance denies before authentication or protected handler work")
  void unavailableProvenanceFailsClosedBeforeHandler() throws Exception {
    var authenticationManager = mock(AuthenticationManager.class);
    var resolver = mock(ClientIpResolver.class);
    var request = request();
    when(resolver.resolveBindingAddress(request)).thenReturn(Optional.empty());
    var filter = bindingFilter(authenticationManager, paidRegistry(), resolver);
    var chain = mock(jakarta.servlet.FilterChain.class);
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(503);
    verify(authenticationManager, never()).authenticate(any());
    verify(chain, never()).doFilter(any(), any());
  }

  private static PaygateAuthenticationFilter bindingFilter(
      AuthenticationManager authenticationManager,
      PaygateEndpointRegistry registry,
      ClientIpResolver resolver) {
    return new PaygateAuthenticationFilter(
        authenticationManager, List.of(), registry, resolver, "test", null, 8192, true);
  }

  private static PaygateEndpointRegistry paidRegistry() {
    var registry = new PaygateEndpointRegistry();
    registry.register(ENDPOINT);
    return registry;
  }

  private static MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/paid");
    request.setRequestURI("/paid");
    request.addHeader("Authorization", AUTHORIZATION);
    return request;
  }
}
