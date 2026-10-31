package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Client-chosen HTTP method tokens must not create unbounded {@code paygate.security.decisions}
 * meters. The compressed-body rejection is emitted before rate limiting, so it is the cheapest path
 * for an unauthenticated client to drive the method tag.
 */
class DecisionMetricMethodCardinalityTest {

  @Test
  @DisplayName("arbitrary request methods on a wildcard paid route collapse into a bounded tag set")
  void arbitraryMethodsOnWildcardRouteProduceBoundedDecisionMeters() throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    meterRegistry.config().meterFilter(new PaygateMeterFilter(100, "_other"));
    var reporter = new PaygateSecurityDecisionReporter(meterRegistry);

    var context = mock(ApplicationContext.class);
    when(context.getBean("dynamic", PaygatePricingStrategy.class))
        .thenReturn((request, defaultPrice) -> defaultPrice);
    PaygateRateLimiter limiter = key -> false;
    var backend = mock(LightningBackend.class);

    try (var pricing =
        new PaygateRequestPricingService(context, Duration.ofSeconds(1), 64, reporter)) {
      var challengeService =
          new PaygateChallengeService(
              new InMemoryRootKeyStore(),
              backend,
              null,
              context,
              null,
              limiter,
              null,
              null,
              null,
              false,
              pricing);

      var registry = new PaygateEndpointRegistry();
      registry.register(new PaygateEndpointConfig("*", "/api/dyn", 10, 600, "d", "dynamic", ""));
      var filter =
          new PaygateSecurityFilter(
              registry, List.of(), challengeService, "svc", null, null, null, limiter);

      for (int i = 0; i < 2000; i++) {
        sendCompressedBody(filter, "M" + i);
      }
      sendCompressedBody(filter, "L".repeat(4000));
      sendCompressedBody(filter, "POST");

      List<Meter> decisionMeters =
          meterRegistry.getMeters().stream()
              .filter(m -> m.getId().getName().equals("paygate.security.decisions"))
              .toList();
      Set<String> methodTags =
          decisionMeters.stream()
              .map(m -> m.getId().getTag("method"))
              .collect(Collectors.toUnmodifiableSet());

      assertThat(methodTags).containsExactlyInAnyOrder("_other", "POST");
      assertThat(decisionMeters).hasSize(2);
      assertThat(
              meterRegistry
                  .get("paygate.security.decisions")
                  .tag("method", "_other")
                  .counter()
                  .count())
          .isEqualTo(2001.0);
    }
  }

  private static void sendCompressedBody(PaygateSecurityFilter filter, String method)
      throws Exception {
    var request = new MockHttpServletRequest(method, "/api/dyn");
    request.setRemoteAddr("192.0.2.10");
    request.addHeader("Content-Encoding", "gzip");
    request.setContent(new byte[] {1});
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(400);
  }
}
