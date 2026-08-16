package com.greenharborlabs.paygate.example.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("Spring Security example application properties")
class SecurityExampleApplicationPropertiesTest {

  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  @DisplayName("default configuration does not activate dev or test mode")
  void defaultConfigurationDoesNotActivateDevOrTestMode() throws IOException {
    PropertySource<?> properties = load("application.yml");

    assertThat(properties.getProperty("spring.profiles.active")).isNull();
    assertThat(properties.getProperty("paygate.test-mode")).isNull();
  }

  @Test
  @DisplayName("packaged configuration obtains its binding secret only from the environment")
  void packagedConfigurationObtainsItsBindingSecretOnlyFromTheEnvironment() throws IOException {
    PropertySource<?> properties = load("application.yml");

    assertThat(properties.getProperty("paygate.protocols.mpp.challenge-binding-secret"))
        .isEqualTo("${PAYGATE_MPP_SECRET:}");
  }

  @Test
  @DisplayName("dev profile enables test mode without packaging a reusable binding secret")
  void devProfileEnablesTestModeWithoutPackagingAReusableBindingSecret() throws IOException {
    PropertySource<?> properties = load("application-dev.yml");

    assertThat(properties.getProperty("paygate.test-mode")).isEqualTo(true);
    assertThat(properties.getProperty("paygate.protocols.mpp.challenge-binding-secret")).isNull();
  }

  private PropertySource<?> load(String resourceName) throws IOException {
    return loader.load(resourceName, new ClassPathResource(resourceName)).getFirst();
  }
}
