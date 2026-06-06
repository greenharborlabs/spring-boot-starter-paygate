package com.greenharborlabs.paygate.spring;

import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Resolves the effective L402 security mode from the configured property value and classpath
 * detection.
 *
 * <p>Resolution rules:
 *
 * <ul>
 *   <li>{@code auto} (default) + Spring Security and Paygate Spring Security integration on
 *       classpath -> {@code spring-security}
 *   <li>{@code auto} + missing Spring Security or Paygate Spring Security integration -> {@code
 *       servlet}
 *   <li>{@code servlet} -> forced servlet mode regardless of classpath
 *   <li>{@code spring-security} -> forced Spring Security mode (fails if Spring Security or the
 *       Paygate Spring Security integration is not on the classpath)
 * </ul>
 */
final class PaygateSecurityModeResolver {

  static final String PROPERTY_NAME = "paygate.security-mode";
  static final String MODE_AUTO = "auto";
  static final String MODE_SERVLET = "servlet";
  static final String MODE_SPRING_SECURITY = "spring-security";

  private static final Set<String> VALID_MODES =
      Set.of(MODE_AUTO, MODE_SERVLET, MODE_SPRING_SECURITY);
  private static final String ENABLE_WEB_SECURITY_CLASS =
      "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity";
  private static final String PAYGATE_SECURITY_AUTO_CONFIGURATION_CLASS =
      "com.greenharborlabs.paygate.spring.security.PaygateSecurityAutoConfiguration";

  private PaygateSecurityModeResolver() {}

  /**
   * Reads the configured security mode from the environment and resolves it to an effective mode.
   * Does not perform validation that would cause startup failure -- use {@link #validate} for that.
   */
  static String resolve(Environment environment) {
    String configured = getConfiguredMode(environment);
    return resolveFromConfigured(configured);
  }

  /** Returns the raw configured value (defaulting to "auto" if absent). */
  static String getConfiguredMode(Environment environment) {
    return environment.getProperty(PROPERTY_NAME, MODE_AUTO);
  }

  /** Resolves an effective mode from a configured value. */
  static String resolveFromConfigured(String configured) {
    return resolveFromConfigured(
        configured, isSpringSecurityPresent(), isPaygateSpringSecurityIntegrationPresent());
  }

  static String resolveFromConfigured(
      String configured, boolean springSecurityPresent, boolean paygateIntegrationPresent) {
    return switch (configured) {
      case MODE_AUTO ->
          springSecurityPresent && paygateIntegrationPresent ? MODE_SPRING_SECURITY : MODE_SERVLET;
      case MODE_SERVLET -> MODE_SERVLET;
      case MODE_SPRING_SECURITY -> MODE_SPRING_SECURITY;
      default -> configured; // invalid -- will be caught by validate()
    };
  }

  /**
   * Validates the configured mode and throws if invalid or incompatible with the classpath.
   *
   * @throws IllegalStateException on invalid mode or missing Spring Security mode requirements
   */
  static void validate(String configured) {
    validate(configured, isSpringSecurityPresent(), isPaygateSpringSecurityIntegrationPresent());
  }

  static void validate(
      String configured, boolean springSecurityPresent, boolean paygateIntegrationPresent) {
    if (!VALID_MODES.contains(configured)) {
      throw new IllegalStateException(
          "Invalid paygate.security-mode value: '"
              + configured
              + "'. Valid values: auto, servlet, spring-security");
    }
    if (MODE_SPRING_SECURITY.equals(configured)
        && (!springSecurityPresent || !paygateIntegrationPresent)) {
      throw new IllegalStateException(
          "paygate.security-mode=spring-security requires Spring Security and the "
              + "paygate-spring-security integration module on the classpath; missing: "
              + missingSpringSecurityRequirements(
                  springSecurityPresent, paygateIntegrationPresent));
    }
  }

  /** Returns true if Spring Security's {@code EnableWebSecurity} class is loadable. */
  static boolean isSpringSecurityPresent() {
    return isClassPresent(ENABLE_WEB_SECURITY_CLASS);
  }

  /** Returns true if Paygate's Spring Security auto-configuration marker class is loadable. */
  static boolean isPaygateSpringSecurityIntegrationPresent() {
    return isClassPresent(PAYGATE_SECURITY_AUTO_CONFIGURATION_CLASS);
  }

  private static boolean isClassPresent(String className) {
    try {
      Class.forName(className, false, PaygateSecurityModeResolver.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException _) {
      return false;
    }
  }

  private static String missingSpringSecurityRequirements(
      boolean springSecurityPresent, boolean paygateIntegrationPresent) {
    if (!springSecurityPresent && !paygateIntegrationPresent) {
      return "Spring Security ("
          + ENABLE_WEB_SECURITY_CLASS
          + ") and paygate-spring-security integration module ("
          + PAYGATE_SECURITY_AUTO_CONFIGURATION_CLASS
          + ")";
    }
    if (!springSecurityPresent) {
      return "Spring Security (" + ENABLE_WEB_SECURITY_CLASS + ")";
    }
    return "paygate-spring-security integration module ("
        + PAYGATE_SECURITY_AUTO_CONFIGURATION_CLASS
        + ")";
  }
}
