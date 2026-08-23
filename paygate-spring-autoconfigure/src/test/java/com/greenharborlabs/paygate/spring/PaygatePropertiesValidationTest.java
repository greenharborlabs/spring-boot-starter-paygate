package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaygatePropertiesValidationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void pricingEvaluationSettingsUseSafeDefaults() {
    contextRunner.run(
        context -> {
          var pricing = context.getBean(PaygateProperties.class).getPricing();
          assertThat(pricing.getEvaluationTimeout()).isEqualTo(Duration.ofSeconds(1));
          assertThat(pricing.getMaxConcurrentEvaluations()).isEqualTo(64);
        });
  }

  @Test
  void pricingEvaluationSettingsBindAtTheirInclusiveBounds() {
    contextRunner
        .withPropertyValues(
            "paygate.pricing.evaluation-timeout=1ms",
            "paygate.pricing.max-concurrent-evaluations=1")
        .run(
            context -> {
              var pricing = context.getBean(PaygateProperties.class).getPricing();
              assertThat(pricing.getEvaluationTimeout()).isEqualTo(Duration.ofMillis(1));
              assertThat(pricing.getMaxConcurrentEvaluations()).isOne();
            });
    contextRunner
        .withPropertyValues(
            "paygate.pricing.evaluation-timeout=60s",
            "paygate.pricing.max-concurrent-evaluations=1024")
        .run(
            context -> {
              var pricing = context.getBean(PaygateProperties.class).getPricing();
              assertThat(pricing.getEvaluationTimeout()).isEqualTo(Duration.ofSeconds(60));
              assertThat(pricing.getMaxConcurrentEvaluations()).isEqualTo(1024);
            });
  }

  @Test
  void pricingEvaluationSettingsRejectUnsafeValues() {
    assertRejected("paygate.pricing.evaluation-timeout=0s");
    assertRejected("paygate.pricing.evaluation-timeout=61s");
    assertRejected("paygate.pricing.max-concurrent-evaluations=0");
    assertRejected("paygate.pricing.max-concurrent-evaluations=1025");
  }

  private void assertRejected(String property) {
    contextRunner
        .withPropertyValues(property)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalArgumentException.class));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PaygateProperties.class)
  static class PropertiesConfiguration {}
}
