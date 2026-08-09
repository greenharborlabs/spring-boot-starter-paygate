package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Tests for security-sensitive bounds in {@link PaygateProperties}. */
@DisplayName("PaygateProperties security bounds")
class PaygatePropertiesSecurityBoundsTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  @DisplayName("requestBody.maxBytes defaults to 8192")
  void requestBodyMaxBytesDefaultsTo8192() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(PaygateProperties.class).getRequestBody().getMaxBytes())
                .isEqualTo(8_192));
  }

  @Test
  @DisplayName("requestBody.maxBytes accepts its documented bounds")
  void requestBodyMaxBytesAcceptsDocumentedBounds() {
    contextRunner
        .withPropertyValues("paygate.request-body.max-bytes=1")
        .run(
            context ->
                assertThat(context.getBean(PaygateProperties.class).getRequestBody().getMaxBytes())
                    .isEqualTo(1));
    contextRunner
        .withPropertyValues("paygate.request-body.max-bytes=16777216")
        .run(
            context ->
                assertThat(context.getBean(PaygateProperties.class).getRequestBody().getMaxBytes())
                    .isEqualTo(16_777_216));
  }

  @Test
  @DisplayName("requestBody.maxBytes rejects values below one byte")
  void requestBodyMaxBytesRejectsValuesBelowOneByte() {
    contextRunner
        .withPropertyValues("paygate.request-body.max-bytes=0")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalArgumentException.class));
  }

  @Test
  @DisplayName("requestBody.maxBytes rejects values above 16 MiB")
  void requestBodyMaxBytesRejectsValuesAbove16MiB() {
    contextRunner
        .withPropertyValues("paygate.request-body.max-bytes=16777217")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalArgumentException.class));
  }

  @Test
  @DisplayName("rateLimit.ipv6PrefixLength defaults to 64")
  void ipv6PrefixLengthDefaultsTo64() {
    contextRunner.run(
        context ->
            assertThat(
                    context.getBean(PaygateProperties.class).getRateLimit().getIpv6PrefixLength())
                .isEqualTo(64));
  }

  @Test
  @DisplayName("rateLimit.ipv6PrefixLength accepts its documented bounds")
  void ipv6PrefixLengthAcceptsDocumentedBounds() {
    contextRunner
        .withPropertyValues("paygate.rate-limit.ipv6-prefix-length=0")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(PaygateProperties.class)
                            .getRateLimit()
                            .getIpv6PrefixLength())
                    .isZero());
    contextRunner
        .withPropertyValues("paygate.rate-limit.ipv6-prefix-length=128")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(PaygateProperties.class)
                            .getRateLimit()
                            .getIpv6PrefixLength())
                    .isEqualTo(128));
  }

  @Test
  @DisplayName("rateLimit.ipv6PrefixLength rejects negative values")
  void ipv6PrefixLengthRejectsNegativeValues() {
    contextRunner
        .withPropertyValues("paygate.rate-limit.ipv6-prefix-length=-1")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalArgumentException.class));
  }

  @Test
  @DisplayName("rateLimit.ipv6PrefixLength rejects values above 128")
  void ipv6PrefixLengthRejectsValuesAbove128() {
    contextRunner
        .withPropertyValues("paygate.rate-limit.ipv6-prefix-length=129")
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
