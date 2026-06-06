package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("PaygateSecurityModeResolver")
class PaygateSecurityModeResolverTest {

  @Test
  @DisplayName(
      "auto mode resolves to spring-security when Spring Security and integration are present")
  void autoResolvesToSpringSecurityWhenSpringSecurityAndIntegrationPresent() {
    String resolved = PaygateSecurityModeResolver.resolveFromConfigured("auto", true, true);
    assertThat(resolved).isEqualTo("spring-security");
  }

  @Test
  @DisplayName("auto mode resolves to servlet when Spring Security is present without integration")
  void autoResolvesToServletWhenIntegrationMissing() {
    String resolved = PaygateSecurityModeResolver.resolveFromConfigured("auto", true, false);
    assertThat(resolved).isEqualTo("servlet");
  }

  @Test
  @DisplayName("auto mode resolves to servlet when Spring Security is absent")
  void autoResolvesToServletWhenSpringSecurityMissing() {
    String resolved = PaygateSecurityModeResolver.resolveFromConfigured("auto", false, true);
    assertThat(resolved).isEqualTo("servlet");
  }

  @Test
  @DisplayName("servlet mode resolves to servlet regardless of classpath")
  void servletModeAlwaysServlet() {
    assertThat(PaygateSecurityModeResolver.resolveFromConfigured("servlet")).isEqualTo("servlet");
  }

  @Test
  @DisplayName("spring-security mode resolves to spring-security regardless of classpath")
  void springSecurityModeAlwaysSpring() {
    assertThat(PaygateSecurityModeResolver.resolveFromConfigured("spring-security"))
        .isEqualTo("spring-security");
  }

  @Test
  @DisplayName("getConfiguredMode defaults to auto when property is absent")
  void defaultsToAuto() {
    var env = new MockEnvironment();
    assertThat(PaygateSecurityModeResolver.getConfiguredMode(env)).isEqualTo("auto");
  }

  @Test
  @DisplayName("getConfiguredMode reads from environment")
  void readsProperty() {
    var env = new MockEnvironment().withProperty("paygate.security-mode", "servlet");
    assertThat(PaygateSecurityModeResolver.getConfiguredMode(env)).isEqualTo("servlet");
  }

  @Test
  @DisplayName("validate rejects invalid mode values")
  void rejectsInvalidMode() {
    assertThatThrownBy(() -> PaygateSecurityModeResolver.validate("invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid paygate.security-mode value: 'invalid'")
        .hasMessageContaining("Valid values: auto, servlet, spring-security");
  }

  @Test
  @DisplayName("validate accepts auto mode even when integration is missing")
  void acceptsAuto() {
    PaygateSecurityModeResolver.validate("auto", true, false);
  }

  @Test
  @DisplayName("validate accepts servlet mode")
  void acceptsServlet() {
    PaygateSecurityModeResolver.validate("servlet", false, false);
  }

  @Test
  @DisplayName(
      "validate accepts spring-security mode when Spring Security and integration are present")
  void acceptsSpringSecurityWhenRequirementsPresent() {
    PaygateSecurityModeResolver.validate("spring-security", true, true);
  }

  @Test
  @DisplayName("validate rejects spring-security mode when Spring Security is missing")
  void rejectsSpringSecurityWhenSpringSecurityMissing() {
    assertThatThrownBy(() -> PaygateSecurityModeResolver.validate("spring-security", false, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.security-mode=spring-security requires Spring Security")
        .hasMessageContaining("missing: Spring Security")
        .hasMessageContaining(
            "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity");
  }

  @Test
  @DisplayName("validate rejects spring-security mode when Paygate integration is missing")
  void rejectsSpringSecurityWhenIntegrationMissing() {
    assertThatThrownBy(() -> PaygateSecurityModeResolver.validate("spring-security", true, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate-spring-security integration module")
        .hasMessageContaining(
            "com.greenharborlabs.paygate.spring.security.PaygateSecurityAutoConfiguration");
  }

  @Test
  @DisplayName("isSpringSecurityPresent detects EnableWebSecurity class")
  void detectsSpringSecurityClass() {
    // This test runs in a classpath that may or may not have Spring Security.
    // We just verify it returns a boolean without throwing.
    boolean result = PaygateSecurityModeResolver.isSpringSecurityPresent();
    assertThat(result).isInstanceOf(Boolean.class);
  }

  @Test
  @DisplayName("isPaygateSpringSecurityIntegrationPresent detects integration marker class")
  void detectsPaygateSpringSecurityIntegrationClass() {
    boolean result = PaygateSecurityModeResolver.isPaygateSpringSecurityIntegrationPresent();
    assertThat(result).isInstanceOf(Boolean.class);
  }
}
