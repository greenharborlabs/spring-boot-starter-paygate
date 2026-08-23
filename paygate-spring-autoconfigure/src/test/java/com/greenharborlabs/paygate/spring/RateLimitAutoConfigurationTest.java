package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/** Context-level coverage for the replaceable aggregate invoice limiter. */
@DisplayName("Aggregate invoice limiter auto-configuration")
class RateLimitAutoConfigurationTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true", "paygate.backend=lnbits", "paygate.root-key-store=memory")
          .withBean(LightningBackend.class, AutoConfigurationTest.StubLightningBackend::new);

  @Test
  @DisplayName("creates the bounded default aggregate limiter")
  void createsDefaultAggregateLimiter() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(AggregateInvoiceRateLimiter.class);
          assertThat(context.getBean(AggregateInvoiceRateLimiter.class))
              .isInstanceOf(TokenBucketAggregateInvoiceRateLimiter.class);
        });
  }

  @Test
  @DisplayName("uses an application supplied aggregate limiter")
  void userProvidedLimiterReplacesDefault() {
    AggregateInvoiceRateLimiter supplied = () -> true;

    contextRunner
        .withBean(AggregateInvoiceRateLimiter.class, () -> supplied)
        .run(
            context ->
                assertThat(context.getBean(AggregateInvoiceRateLimiter.class)).isSameAs(supplied));
  }

  @Test
  @DisplayName("does not create an aggregate limiter while Paygate is disabled")
  void disabledPaygateDoesNotCreateAggregateLimiter() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(AggregateInvoiceRateLimiter.class));
  }

  @Test
  @DisplayName("rejects non-positive aggregate settings during property binding")
  void invalidAggregatePropertiesFailContextStartup() {
    contextRunner
        .withPropertyValues("paygate.rate-limit.aggregate.requests-per-second=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
