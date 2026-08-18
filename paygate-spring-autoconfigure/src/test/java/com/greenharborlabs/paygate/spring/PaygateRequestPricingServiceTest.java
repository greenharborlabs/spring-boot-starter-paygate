package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

class PaygateRequestPricingServiceTest {

  private final PaygateEndpointConfig dynamicConfig =
      new PaygateEndpointConfig("POST", "/api/analyze", 10, 60, "", "dynamic", "");

  @Test
  void memoizesOneDynamicDecisionPerRequest() {
    var calls = new AtomicInteger();
    var service =
        service((request, ignored) -> calls.incrementAndGet() * 25, Duration.ofSeconds(1), 1);
    var request = new MockHttpServletRequest("POST", "/api/analyze");

    assertThat(service.resolve(request, dynamicConfig).amountSats()).isEqualTo(25);
    assertThat(service.resolve(request, dynamicConfig).amountSats()).isEqualTo(25);
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsTimedOutEvaluationAndCancelsIt() {
    var service =
        service(
            (request, ignored) -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return 25;
            },
            Duration.ofMillis(10),
            1);

    assertThatThrownBy(() -> service.resolve(new MockHttpServletRequest(), dynamicConfig))
        .isInstanceOf(PricingEvaluationException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void rejectsRatherThanQueuesWhenConcurrencyIsSaturated() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        service(
            (request, ignored) -> {
              started.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return 25;
            },
            Duration.ofSeconds(1),
            1);
    var first =
        Thread.ofVirtual()
            .start(() -> service.resolve(new MockHttpServletRequest(), dynamicConfig));
    started.await();

    assertThatThrownBy(() -> service.resolve(new MockHttpServletRequest(), dynamicConfig))
        .isInstanceOf(PricingEvaluationException.class)
        .hasMessageContaining("capacity");

    release.countDown();
    first.join();
  }

  @Test
  void strategyExceptionsAreIsolatedToTheFailedRequest() {
    var calls = new AtomicInteger();
    var service =
        service(
            (request, ignored) -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("bad strategy");
              }
              return 25;
            },
            Duration.ofSeconds(1),
            1);

    assertThatThrownBy(() -> service.resolve(new MockHttpServletRequest(), dynamicConfig))
        .isInstanceOf(PricingEvaluationException.class)
        .hasMessageContaining("failed");
    assertThat(service.resolve(new MockHttpServletRequest(), dynamicConfig).amountSats())
        .isEqualTo(25);
  }

  private static PaygateRequestPricingService service(
      PaygatePricingStrategy strategy, Duration timeout, int maxConcurrentEvaluations) {
    ApplicationContext context = mock(ApplicationContext.class);
    when(context.getBean("dynamic", PaygatePricingStrategy.class)).thenReturn(strategy);
    return new PaygateRequestPricingService(context, timeout, maxConcurrentEvaluations);
  }
}
