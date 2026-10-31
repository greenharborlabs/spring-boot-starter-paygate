package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Behavioral coverage for successful-payment reuse during servlet redispatch. */
@DisplayName("Paygate servlet redispatch enforcement")
class PaygateDispatchEnforcementTest {

  private static final String FIRST_TARGET = "/paid/first";
  private static final String SECOND_TARGET = "/paid/second";

  @Test
  @DisplayName("reuses the successful decision for REQUEST to the same target")
  void reusesSameTargetForRequest() throws Exception {
    assertSameTargetReuse(DispatcherType.REQUEST);
  }

  @Test
  @DisplayName("reuses the successful decision for ASYNC to the same target")
  void reusesSameTargetForAsync() throws Exception {
    assertSameTargetReuse(DispatcherType.ASYNC);
  }

  @Test
  @DisplayName("reuses the successful decision for FORWARD to the same target")
  void reusesSameTargetForForward() throws Exception {
    assertSameTargetReuse(DispatcherType.FORWARD);
  }

  @Test
  @DisplayName("reuses the successful decision for ERROR to the same target")
  void reusesSameTargetForError() throws Exception {
    assertSameTargetReuse(DispatcherType.ERROR);
  }

  @Test
  @DisplayName("reauthorizes a changed target for every servlet redispatch type")
  void reauthorizesChangedTargetForEveryRedispatchType() throws Exception {
    for (var dispatcherType :
        new DispatcherType[] {
          DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR
        }) {
      var fixture = new Fixture();
      var request = request(FIRST_TARGET, DispatcherType.REQUEST);

      fixture.filter.doFilter(request, new MockHttpServletResponse(), fixture.chain);
      request.setRequestURI(SECOND_TARGET);
      request.setDispatcherType(dispatcherType);
      fixture.filter.doFilter(request, new MockHttpServletResponse(), fixture.chain);

      assertThat(fixture.validationCalls)
          .as("changed target must be reauthorized for %s", dispatcherType)
          .hasValue(2);
      assertThat(fixture.handlerCalls).hasValue(2);
    }
  }

  @Test
  @DisplayName("a FORWARD to another paid endpoint validates against that endpoint's price")
  void forwardToAnotherEndpointValidatesAgainstItsOwnPrice() throws Exception {
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", FIRST_TARGET, 1, 60, "first", "", ""));
    registry.register(new PaygateEndpointConfig("GET", SECOND_TARGET, 7, 60, "second", "", ""));
    var prices = new CopyOnWriteArrayList<String>();
    try (var pricing =
        new PaygateRequestPricingService(
            Mockito.mock(ApplicationContext.class), Duration.ofSeconds(1), 1)) {
      var challengeService = Mockito.mock(PaygateChallengeService.class);
      Mockito.when(
              challengeService.resolveTrustedPrice(
                  ArgumentMatchers.any(), ArgumentMatchers.any(PaygateEndpointConfig.class)))
          .thenAnswer(call -> pricing.resolve(call.getArgument(0), call.getArgument(1)));
      var filter =
          new PaygateSecurityFilter(
              registry,
              List.of(new PriceCapturingProtocol(prices)),
              challengeService,
              "test-service",
              null,
              null,
              null,
              null);
      var request = request(FIRST_TARGET, DispatcherType.REQUEST);

      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
      request.setRequestURI(SECOND_TARGET);
      request.setDispatcherType(DispatcherType.FORWARD);
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

      assertThat(prices).containsExactly("1", "7");
    }
  }

  private record PriceCapturingProtocol(List<String> prices) implements PaymentProtocol {
    @Override
    public String scheme() {
      return "Test";
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return true;
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader) {
      return new PaymentCredential(
          new byte[32], new byte[32], "test-token", scheme(), null, new ProtocolMetadata() {});
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      throw new AssertionError("a valid credential must not create a challenge");
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext) {
      prices.add(requestContext.get(VerificationContextKeys.CURRENT_PRICE_SATS));
    }
  }

  private static void assertSameTargetReuse(DispatcherType redispatchType) throws Exception {
    var fixture = new Fixture();
    var request = request(FIRST_TARGET, DispatcherType.REQUEST);

    fixture.filter.doFilter(request, new MockHttpServletResponse(), fixture.chain);
    request.setDispatcherType(redispatchType);
    fixture.filter.doFilter(request, new MockHttpServletResponse(), fixture.chain);

    assertThat(fixture.validationCalls)
        .as("same target must reuse the successful decision for %s", redispatchType)
        .hasValue(1);
    assertThat(fixture.handlerCalls).hasValue(2);
  }

  private static MockHttpServletRequest request(String path, DispatcherType dispatcherType) {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    request.setDispatcherType(dispatcherType);
    request.addHeader("Authorization", "Test accepted");
    return request;
  }

  private static final class Fixture {
    private final AtomicInteger validationCalls = new AtomicInteger();
    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final FilterChain chain = (request, response) -> handlerCalls.incrementAndGet();
    private final PaygateSecurityFilter filter;

    private Fixture() {
      var registry = new PaygateEndpointRegistry();
      registry.register(new PaygateEndpointConfig("GET", FIRST_TARGET, 1, 60, "first", "", ""));
      registry.register(new PaygateEndpointConfig("GET", SECOND_TARGET, 1, 60, "second", "", ""));
      filter =
          new PaygateSecurityFilter(
              registry,
              java.util.List.of(new AcceptingProtocol(validationCalls)),
              org.mockito.Mockito.mock(PaygateChallengeService.class),
              "test-service",
              null,
              null,
              null,
              null);
    }
  }

  private record AcceptingProtocol(AtomicInteger validationCalls) implements PaymentProtocol {
    @Override
    public String scheme() {
      return "Test";
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return true;
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader) {
      return new PaymentCredential(
          new byte[32], new byte[32], "test-token", scheme(), null, new ProtocolMetadata() {});
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      throw new AssertionError("a valid credential must not create a challenge");
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext)
        throws PaymentValidationException {
      validationCalls.incrementAndGet();
    }
  }
}
