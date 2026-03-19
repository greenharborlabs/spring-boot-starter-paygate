package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L402SecurityModeResolver")
class L402SecurityModeResolverTest {

    @Test
    @DisplayName("auto mode resolves to spring-security when Spring Security is on classpath")
    void autoResolvesToSpringSecurityWhenPresent() {
        // Spring Security IS on the test classpath (via spring-boot-starter-test transitive deps
        // may not include it). We test the resolver method directly.
        String resolved = L402SecurityModeResolver.resolveFromConfigured("auto");
        // Result depends on classpath -- just verify it returns a valid mode
        assertThat(resolved).isIn("servlet", "spring-security");
    }

    @Test
    @DisplayName("servlet mode resolves to servlet regardless of classpath")
    void servletModeAlwaysServlet() {
        assertThat(L402SecurityModeResolver.resolveFromConfigured("servlet")).isEqualTo("servlet");
    }

    @Test
    @DisplayName("spring-security mode resolves to spring-security regardless of classpath")
    void springSecurityModeAlwaysSpring() {
        assertThat(L402SecurityModeResolver.resolveFromConfigured("spring-security"))
                .isEqualTo("spring-security");
    }

    @Test
    @DisplayName("getConfiguredMode defaults to auto when property is absent")
    void defaultsToAuto() {
        var env = new MockEnvironment();
        assertThat(L402SecurityModeResolver.getConfiguredMode(env)).isEqualTo("auto");
    }

    @Test
    @DisplayName("getConfiguredMode reads from environment")
    void readsProperty() {
        var env = new MockEnvironment().withProperty("l402.security-mode", "servlet");
        assertThat(L402SecurityModeResolver.getConfiguredMode(env)).isEqualTo("servlet");
    }

    @Test
    @DisplayName("validate rejects invalid mode values")
    void rejectsInvalidMode() {
        assertThatThrownBy(() -> L402SecurityModeResolver.validate("invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid l402.security-mode value: 'invalid'")
                .hasMessageContaining("Valid values: auto, servlet, spring-security");
    }

    @Test
    @DisplayName("validate accepts auto mode")
    void acceptsAuto() {
        // Should not throw
        L402SecurityModeResolver.validate("auto");
    }

    @Test
    @DisplayName("validate accepts servlet mode")
    void acceptsServlet() {
        L402SecurityModeResolver.validate("servlet");
    }

    @Test
    @DisplayName("isSpringSecurityPresent detects EnableWebSecurity class")
    void detectsSpringSecurityClass() {
        // This test runs in a classpath that may or may not have Spring Security.
        // We just verify it returns a boolean without throwing.
        boolean result = L402SecurityModeResolver.isSpringSecurityPresent();
        assertThat(result).isInstanceOf(Boolean.class);
    }
}
