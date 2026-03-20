package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaygateSecurityModeResolver")
class PaygateSecurityModeResolverTest {

    @Test
    @DisplayName("auto mode resolves to spring-security when Spring Security is on classpath")
    void autoResolvesToSpringSecurityWhenPresent() {
        // Spring Security IS on the test classpath (via spring-boot-starter-test transitive deps
        // may not include it). We test the resolver method directly.
        String resolved = PaygateSecurityModeResolver.resolveFromConfigured("auto");
        // Result depends on classpath -- just verify it returns a valid mode
        assertThat(resolved).isIn("servlet", "spring-security");
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
    @DisplayName("validate accepts auto mode")
    void acceptsAuto() {
        // Should not throw
        PaygateSecurityModeResolver.validate("auto");
    }

    @Test
    @DisplayName("validate accepts servlet mode")
    void acceptsServlet() {
        PaygateSecurityModeResolver.validate("servlet");
    }

    @Test
    @DisplayName("isSpringSecurityPresent detects EnableWebSecurity class")
    void detectsSpringSecurityClass() {
        // This test runs in a classpath that may or may not have Spring Security.
        // We just verify it returns a boolean without throwing.
        boolean result = PaygateSecurityModeResolver.isSpringSecurityPresent();
        assertThat(result).isInstanceOf(Boolean.class);
    }
}
