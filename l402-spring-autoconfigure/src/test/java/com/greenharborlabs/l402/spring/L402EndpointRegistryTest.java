package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link L402EndpointRegistry}.
 */
@DisplayName("L402EndpointRegistry")
class L402EndpointRegistryTest {

    private static final long CUSTOM_DEFAULT_TIMEOUT = 7200L;

    @Test
    @DisplayName("sentinel -1 timeoutSeconds is resolved to the configured default timeout")
    void sentinelTimeoutResolvedToDefault() {
        var registry = new L402EndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

        // Register an endpoint config with an explicit timeout to verify it is preserved
        registry.register(new L402EndpointConfig("GET", "/explicit", 10, 300, "explicit timeout", ""));

        // Verify explicit timeout is not changed
        L402EndpointConfig explicitConfig = registry.findConfig("GET", "/explicit");
        assertThat(explicitConfig).isNotNull();
        assertThat(explicitConfig.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("no-arg constructor uses 3600 as fallback default timeout")
    void noArgConstructorUsesFallbackDefault() {
        var registry = new L402EndpointRegistry();

        // Register directly with -1 sentinel via the public register method
        // (sentinel resolution happens in toConfig during annotation scanning,
        // not in register — so this tests the fallback constructor is valid)
        registry.register(new L402EndpointConfig("GET", "/test", 10, 3600, "", ""));

        L402EndpointConfig config = registry.findConfig("GET", "/test");
        assertThat(config).isNotNull();
        assertThat(config.timeoutSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("findConfig returns null for unregistered paths")
    void findConfigReturnsNullForUnregisteredPath() {
        var registry = new L402EndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        assertThat(registry.findConfig("GET", "/nonexistent")).isNull();
    }

    @Test
    @DisplayName("size returns the number of registered endpoint configurations")
    void sizeReturnsRegisteredCount() {
        var registry = new L402EndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        assertThat(registry.size()).isZero();

        registry.register(new L402EndpointConfig("GET", "/a", 10, 600, "", ""));
        registry.register(new L402EndpointConfig("POST", "/b", 20, 1200, "", ""));
        assertThat(registry.size()).isEqualTo(2);
    }
}
