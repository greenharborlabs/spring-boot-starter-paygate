package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link PaygateProperties.Lnbits} configuration property validation. */
class PaygatePropertiesLnbitsTest {

  private static final String TEST_API_KEY = "test-lnbits-api-key";

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.lnbits.api-key=" + TEST_API_KEY);

  @Test
  @DisplayName("Lnbits rejects zero requestTimeoutSeconds")
  void requestTimeoutSecondsRejectsZero() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request-timeout-seconds must be > 0");
  }

  @Test
  @DisplayName("Lnbits rejects negative requestTimeoutSeconds")
  void requestTimeoutSecondsRejectsNegative() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(-3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request-timeout-seconds must be > 0");
  }

  @Test
  @DisplayName("Lnbits accepts null requestTimeoutSeconds")
  void requestTimeoutSecondsAcceptsNull() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setRequestTimeoutSeconds(null);
    assertThat(lnbits.getRequestTimeoutSeconds()).isNull();
  }

  @Test
  @DisplayName("Lnbits accepts positive requestTimeoutSeconds")
  void requestTimeoutSecondsAcceptsPositive() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setRequestTimeoutSeconds(10);
    assertThat(lnbits.getRequestTimeoutSeconds()).isEqualTo(10);
  }

  @Test
  @DisplayName("Lnbits rejects zero connectTimeoutSeconds")
  void connectTimeoutSecondsRejectsZero() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThatThrownBy(() -> lnbits.setConnectTimeoutSeconds(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connect-timeout-seconds must be > 0");
  }

  @Test
  @DisplayName("Lnbits rejects negative connectTimeoutSeconds")
  void connectTimeoutSecondsRejectsNegative() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThatThrownBy(() -> lnbits.setConnectTimeoutSeconds(-3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connect-timeout-seconds must be > 0");
  }

  @Test
  @DisplayName("Lnbits accepts null connectTimeoutSeconds")
  void connectTimeoutSecondsAcceptsNull() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setConnectTimeoutSeconds(null);
    assertThat(lnbits.getConnectTimeoutSeconds()).isNull();
  }

  @Test
  @DisplayName("Lnbits accepts positive connectTimeoutSeconds")
  void connectTimeoutSecondsAcceptsPositive() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setConnectTimeoutSeconds(10);
    assertThat(lnbits.getConnectTimeoutSeconds()).isEqualTo(10);
  }

  @Test
  @DisplayName("Lnbits allowPlaintextHttp defaults false")
  void allowPlaintextHttpDefaultsFalse() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThat(lnbits.isAllowPlaintextHttp()).isFalse();
  }

  @Test
  @DisplayName("Lnbits allowPlaintextHttp accepts explicit true")
  void allowPlaintextHttpAcceptsExplicitTrue() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setAllowPlaintextHttp(true);
    assertThat(lnbits.isAllowPlaintextHttp()).isTrue();
  }

  @Test
  @DisplayName(
      "LNbits localhost HTTP backend starts with explicit plaintext opt-in and dev profile")
  void lnbitsLocalhostHttpBackendStartsWithExplicitPlaintextOptIn() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "paygate.lnbits.url=http://localhost:5000",
            "paygate.lnbits.allow-plaintext-http=true")
        .run(context -> assertThat(context).hasSingleBean(LightningBackend.class));
  }

  @Test
  @DisplayName("LNbits Compose service HTTP backend starts under the dev profile")
  void lnbitsComposeServiceHttpBackendStartsUnderDevProfile() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "paygate.lnbits.url=http://lnbits:5000",
            "paygate.lnbits.allow-plaintext-http=true")
        .run(context -> assertThat(context).hasSingleBean(LightningBackend.class));
  }

  @ParameterizedTest(name = "spring.profiles.active=\"{0}\"")
  @ValueSource(strings = {"", "prod", "production", "dev,prod", "staging"})
  @DisplayName("LNbits plaintext opt-in fails startup outside an all-development profile set")
  void lnbitsPlaintextOptInFailsStartupOutsideDevelopmentProfiles(String profiles) {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=" + profiles,
            "paygate.lnbits.url=http://lnbits:5000",
            "paygate.lnbits.allow-plaintext-http=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .rootCause()
                  .hasMessage("Unsafe development configuration category: active-profiles");
            });
  }

  @Test
  @DisplayName("LNbits plaintext opt-in fails startup without an active profile even for HTTPS")
  void lnbitsPlaintextOptInFailsStartupWithoutProfileEvenForHttps() {
    contextRunner
        .withPropertyValues(
            "paygate.lnbits.url=https://lnbits.example.com",
            "paygate.lnbits.allow-plaintext-http=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .rootCause()
                  .hasMessage("Unsafe development configuration category: active-profiles");
            });
  }

  @Test
  @DisplayName("LNbits HTTPS backend without plaintext opt-in needs no development profile")
  void lnbitsHttpsBackendWithoutPlaintextOptInNeedsNoProfile() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=prod", "paygate.lnbits.url=https://lnbits.example.com")
        .run(context -> assertThat(context).hasSingleBean(LightningBackend.class));
  }

  @Test
  @DisplayName("LNbits localhost HTTP backend fails without explicit plaintext opt-in")
  void lnbitsLocalhostHttpBackendFailsWithoutExplicitPlaintextOptIn() {
    contextRunner
        .withPropertyValues("paygate.lnbits.url=http://localhost:5000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("HTTPS")
                  .hasMessageContaining("unsafe URL scheme 'http'")
                  .hasMessageContaining("paygate.lnbits.allow-plaintext-http")
                  .hasMessageContaining("allowPlaintextHttp");
            });
  }
}
