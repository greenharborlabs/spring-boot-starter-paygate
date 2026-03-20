package com.greenharborlabs.paygate.spring;

import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Resolves the effective L402 security mode from the configured property value
 * and classpath detection.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>{@code auto} (default) + Spring Security on classpath -> {@code spring-security}</li>
 *   <li>{@code auto} + no Spring Security -> {@code servlet}</li>
 *   <li>{@code servlet} -> forced servlet mode regardless of classpath</li>
 *   <li>{@code spring-security} -> forced Spring Security mode (fails if not on classpath)</li>
 * </ul>
 */
final class PaygateSecurityModeResolver {

    static final String PROPERTY_NAME = "paygate.security-mode";
    static final String MODE_AUTO = "auto";
    static final String MODE_SERVLET = "servlet";
    static final String MODE_SPRING_SECURITY = "spring-security";

    private static final Set<String> VALID_MODES = Set.of(MODE_AUTO, MODE_SERVLET, MODE_SPRING_SECURITY);
    private static final String ENABLE_WEB_SECURITY_CLASS =
            "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity";

    private PaygateSecurityModeResolver() {
    }

    /**
     * Reads the configured security mode from the environment and resolves it
     * to an effective mode. Does not perform validation that would cause startup
     * failure -- use {@link #validate} for that.
     */
    static String resolve(Environment environment) {
        String configured = getConfiguredMode(environment);
        return resolveFromConfigured(configured);
    }

    /**
     * Returns the raw configured value (defaulting to "auto" if absent).
     */
    static String getConfiguredMode(Environment environment) {
        return environment.getProperty(PROPERTY_NAME, MODE_AUTO);
    }

    /**
     * Resolves an effective mode from a configured value.
     */
    static String resolveFromConfigured(String configured) {
        return switch (configured) {
            case MODE_AUTO -> isSpringSecurityPresent() ? MODE_SPRING_SECURITY : MODE_SERVLET;
            case MODE_SERVLET -> MODE_SERVLET;
            case MODE_SPRING_SECURITY -> MODE_SPRING_SECURITY;
            default -> configured; // invalid -- will be caught by validate()
        };
    }

    /**
     * Validates the configured mode and throws if invalid or incompatible with
     * the classpath.
     *
     * @throws IllegalStateException on invalid mode or missing Spring Security
     */
    static void validate(String configured) {
        if (!VALID_MODES.contains(configured)) {
            throw new IllegalStateException(
                    "Invalid paygate.security-mode value: '" + configured
                            + "'. Valid values: auto, servlet, spring-security");
        }
        if (MODE_SPRING_SECURITY.equals(configured) && !isSpringSecurityPresent()) {
            throw new IllegalStateException(
                    "paygate.security-mode=spring-security but Spring Security is not on the classpath");
        }
    }

    /**
     * Returns true if Spring Security's {@code EnableWebSecurity} class is loadable.
     */
    static boolean isSpringSecurityPresent() {
        try {
            Class.forName(ENABLE_WEB_SECURITY_CLASS, false,
                    PaygateSecurityModeResolver.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }
}
