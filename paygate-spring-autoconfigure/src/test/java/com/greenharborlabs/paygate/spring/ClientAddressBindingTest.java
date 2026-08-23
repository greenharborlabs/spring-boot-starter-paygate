package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Servlet client-address binding")
class ClientAddressBindingTest {

  private static final PaygateEndpointConfig ENDPOINT =
      new PaygateEndpointConfig("GET", "/paid", 10, 60, "paid endpoint", "", "");

  @Test
  @DisplayName("a trusted direct address is carried into the challenge context")
  void trustedDirectAddressIsCarriedIntoChallenge() throws Exception {
    var backend = mock(LightningBackend.class);
    when(backend.isHealthy()).thenReturn(true);
    when(backend.createInvoice(anyLong(), anyString()))
        .thenReturn(PaygateTestSupport.createStubInvoice(10));
    var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
    var request = new MockHttpServletRequest("GET", "/paid");
    request.setRemoteAddr("198.51.100.7");

    try (var context =
        bindingService(
                new PaygateTestSupport.InMemoryTestRootKeyStore(new byte[32]), backend, resolver)
            .createChallenge(request, ENDPOINT)) {
      assertThat(context.trustedClientAddress()).isEqualTo("198.51.100.7");
    }
  }

  @Test
  @DisplayName("unavailable address provenance fails before health, invoice, or root-key work")
  void unavailableAddressProvenanceFailsBeforeAllChallengeSideEffects() {
    var backend = mock(LightningBackend.class);
    var rootKeys = mock(RootKeyStore.class);
    var resolver = mock(ClientIpResolver.class);
    var request = new MockHttpServletRequest("GET", "/paid");
    when(resolver.resolveBindingAddress(request)).thenReturn(Optional.empty());

    var service = bindingService(rootKeys, backend, resolver);

    assertThatThrownBy(() -> service.createChallenge(request, ENDPOINT))
        .isInstanceOf(PaygateLightningUnavailableException.class);
    verify(backend, never()).isHealthy();
    verify(backend, never()).createInvoice(anyLong(), anyString());
    verify(rootKeys, never()).generateRootKey();
  }

  @Test
  @DisplayName("servlet validation receives the same trusted address and reaches the handler")
  void servletValidationUsesTrustedAddress() throws Exception {
    var registry = new PaygateEndpointRegistry();
    registry.register(ENDPOINT);
    var challengeService = mock(PaygateChallengeService.class);
    var resolver = new ClientIpResolver(false, List.of());
    var credential =
        new PaymentCredential(
            new byte[32], new byte[32], "token", "Payment", null, new ProtocolMetadata() {});
    var request = new MockHttpServletRequest("GET", "/paid");
    request.setRequestURI("/paid");
    request.setRemoteAddr("2001:db8:0:0:0:0:0:7");
    request.addHeader("Authorization", "Payment credential");
    var contexts = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
    PaymentProtocol protocol =
        new PaymentProtocol() {
          @Override
          public String scheme() {
            return "Payment";
          }

          @Override
          public boolean canHandle(String authorizationHeader) {
            return true;
          }

          @Override
          public PaymentCredential parseCredential(String authorizationHeader) {
            return credential;
          }

          @Override
          public void validate(PaymentCredential supplied, Map<String, String> requestContext) {
            contexts.set(requestContext);
          }

          @Override
          public com.greenharborlabs.paygate.api.ChallengeResponse formatChallenge(
              com.greenharborlabs.paygate.api.ChallengeContext context) {
            return null;
          }
        };
    var chain = mock(jakarta.servlet.FilterChain.class);
    var filter =
        new PaygateSecurityFilter(
            registry,
            List.of(protocol),
            challengeService,
            "test",
            resolver,
            null,
            null,
            null,
            8192,
            true);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(contexts.get())
        .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, "2001:db8:0:0:0:0:0:7");
    verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private static PaygateChallengeService bindingService(
      RootKeyStore rootKeys, LightningBackend backend, ClientIpResolver resolver) {
    var properties = new PaygateProperties();
    properties.getProtocols().getL402().setClientAddressBindingEnabled(true);
    return new PaygateChallengeService(
        rootKeys, backend, properties, null, null, null, null, resolver, null, false);
  }
}
