package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Starts a real {@code SpringApplication} so the guard is loaded through {@code spring.factories},
 * without Paygate auto-configuration, exactly as when Paygate is disabled.
 */
@DisplayName("Paygate switch property spelling guard")
class PaygateSwitchPropertyGuardTest {

  @ParameterizedTest(name = "paygate.enabled={0}")
  @ValueSource(strings = {"on", "yes", "1", "enabled", "", " true", "true "})
  @DisplayName("fails startup for a non-boolean paygate.enabled spelling")
  void rejectsNonBooleanMasterSwitch(String value) {
    assertThatThrownBy(() -> start("--paygate.enabled=" + value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.enabled must be exactly 'true' or 'false'");
  }

  @ParameterizedTest(name = "paygate.protocols.l402.enabled={0}")
  @ValueSource(strings = {"on", "yes", "off", "no", "0"})
  @DisplayName("fails startup for a non-boolean L402 switch while Paygate is disabled")
  void rejectsNonBooleanL402SwitchWhilePaygateDisabled(String value) {
    assertThatThrownBy(
            () -> start("--paygate.enabled=false", "--paygate.protocols.l402.enabled=" + value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.protocols.l402.enabled must be exactly 'true' or 'false'");
  }

  @Test
  @DisplayName("fails startup for a non-boolean L402 switch when paygate.enabled is unset")
  void rejectsNonBooleanL402SwitchWhenMasterSwitchUnset() {
    assertThatThrownBy(() -> start("--paygate.protocols.l402.enabled=on"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.protocols.l402.enabled");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"true", "false", "TRUE", "False"})
  @DisplayName("accepts true and false in any letter case")
  void acceptsBooleanSpellings(String value) {
    try (var context =
        start("--paygate.enabled=" + value, "--paygate.protocols.l402.enabled=" + value)) {
      assertThat(context.isActive()).isTrue();
    }
  }

  @Test
  @DisplayName("starts when both switches are unset")
  void acceptsUnsetSwitches() {
    try (var context = start()) {
      assertThat(context.isActive()).isTrue();
    }
  }

  private static ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(EmptyConfig.class)
        .web(WebApplicationType.NONE)
        .logStartupInfo(false)
        .run(args);
  }

  @Configuration(proxyBeanMethods = false)
  static class EmptyConfig {}
}
