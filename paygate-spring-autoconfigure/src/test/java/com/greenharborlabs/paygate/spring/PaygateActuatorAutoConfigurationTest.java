package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

@DisplayName("PaygateActuatorAutoConfiguration")
class PaygateActuatorAutoConfigurationTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PaygateAutoConfiguration.class,
                  PaygateActuatorAutoConfiguration.class,
                  EndpointAutoConfiguration.class,
                  WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "management.endpoints.web.exposure.include=paygate")
          .withBean(
              LightningBackend.class,
              () -> new PaygateActuatorEndpointTest.StubLightningBackend(true));

  @Test
  @DisplayName("does not register endpoint when actuator property is missing")
  void doesNotRegisterEndpointWhenPropertyMissing() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(PaygateActuatorEndpoint.class);
        });
  }

  @Test
  @DisplayName("does not register endpoint when actuator property is false")
  void doesNotRegisterEndpointWhenPropertyFalse() {
    contextRunner
        .withPropertyValues("paygate.actuator.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(PaygateActuatorEndpoint.class);
            });
  }

  @Test
  @DisplayName("registers endpoint when actuator property is true")
  void registersEndpointWhenPropertyTrue() {
    contextRunner
        .withPropertyValues("paygate.actuator.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(PaygateActuatorEndpoint.class);
            });
  }

  @Nested
  @DisplayName("Lightning health indicator")
  class LightningHealthIndicatorRegistration {

    private final ApplicationContextRunner healthContextRunner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PaygateActuatorAutoConfiguration.class))
            .withBean(PaygateEndpointRegistry.class, PaygateEndpointRegistry::new);

    @Test
    @DisplayName("does not register when a Lightning backend is unavailable")
    void doesNotRegisterHealthIndicatorWithoutBackend() {
      healthContextRunner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaygateLightningHealthIndicator.class);
          });
    }

    @Test
    @DisplayName("registers status-only health when Actuator and a Lightning backend are available")
    void registersStatusOnlyHealthIndicatorWhenBackendAvailable() {
      healthContextRunner
          .withBean(
              LightningBackend.class,
              () -> new PaygateActuatorEndpointTest.StubLightningBackend(true))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(PaygateLightningHealthIndicator.class);

                var health = context.getBean(PaygateLightningHealthIndicator.class).health();
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails()).isEmpty();
              });
    }

    @Test
    @DisplayName("redacts backend exception details from health output")
    void redactsBackendExceptionDetails() {
      String secret = "lnbc1secret-health-marker";
      healthContextRunner
          .withBean(
              LightningBackend.class,
              () ->
                  new PaygateActuatorEndpointTest.StubLightningBackend(true) {
                    @Override
                    public boolean isHealthy() {
                      throw new IllegalStateException("backend failed: " + secret);
                    }
                  })
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var health = context.getBean(PaygateLightningHealthIndicator.class).health();

                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).isEmpty();
                assertThat(health.toString()).doesNotContain(secret);
              });
    }
  }
}
