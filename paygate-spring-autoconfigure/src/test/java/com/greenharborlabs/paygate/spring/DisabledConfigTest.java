package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Verifies that no L402 beans are created when the auto-configuration is disabled.
 *
 * <p>{@link PaygateAutoConfiguration} is guarded by {@code @ConditionalOnProperty(name =
 * "paygate.enabled", havingValue = "true", matchIfMissing = false)}, so the entire configuration
 * class — and all beans it declares — must be absent when {@code paygate.enabled=false} or the
 * property is not set at all.
 */
@DisplayName("PaygateAutoConfiguration disabled")
class DisabledConfigTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PaygateAutoConfiguration.class, TestModeAutoConfiguration.class));

  @Test
  @DisplayName("no L402 beans when paygate.enabled=false")
  void noBeansWhenExplicitlyDisabled() {
    contextRunner
        .withPropertyValues("paygate.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(RootKeyStore.class);
              assertThat(context).doesNotHaveBean(CredentialStore.class);
              assertThat(context).doesNotHaveBean(PaygateSecurityFilter.class);
              assertThat(context).doesNotHaveBean(PaygateEndpointRegistry.class);
              assertThat(context).doesNotHaveBean(PaygateAutoConfiguration.class);
            });
  }

  @Test
  @DisplayName("no L402 beans when paygate.enabled property is absent")
  void noBeansWhenPropertyNotSet() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(RootKeyStore.class);
          assertThat(context).doesNotHaveBean(CredentialStore.class);
          assertThat(context).doesNotHaveBean(PaygateSecurityFilter.class);
          assertThat(context).doesNotHaveBean(PaygateEndpointRegistry.class);
          assertThat(context).doesNotHaveBean(PaygateAutoConfiguration.class);
        });
  }

  @Test
  @DisplayName("no test-mode beans when paygate.test-mode=true but paygate.enabled=false")
  void noTestModeBeansWhenMasterSwitchDisabled() {
    contextRunner
        .withPropertyValues("paygate.enabled=false", "paygate.test-mode=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(LightningBackend.class);
              assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
              assertThat(context).doesNotHaveBean(TestModeAutoConfiguration.class);
            });
  }

  @Test
  @DisplayName("no test-mode beans when paygate.test-mode=true and paygate.enabled is absent")
  void noTestModeBeansWhenMasterSwitchAbsent() {
    contextRunner
        .withPropertyValues("paygate.test-mode=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(LightningBackend.class);
              assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
              assertThat(context).doesNotHaveBean(TestModeAutoConfiguration.class);
            });
  }
}
