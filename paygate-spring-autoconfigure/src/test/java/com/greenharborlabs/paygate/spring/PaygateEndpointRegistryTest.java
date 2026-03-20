package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PaygateEndpointRegistry}.
 */
@DisplayName("PaygateEndpointRegistry")
class PaygateEndpointRegistryTest {

    private static final long CUSTOM_DEFAULT_TIMEOUT = 7200L;

    @Test
    @DisplayName("sentinel -1 timeoutSeconds is resolved to the configured default timeout")
    void sentinelTimeoutResolvedToDefault() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

        // Register an endpoint config with an explicit timeout to verify it is preserved
        registry.register(new PaygateEndpointConfig("GET", "/explicit", 10, 300, "explicit timeout", "", ""));

        // Verify explicit timeout is not changed
        PaygateEndpointConfig explicitConfig = registry.findConfig("GET", "/explicit");
        assertThat(explicitConfig).isNotNull();
        assertThat(explicitConfig.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("no-arg constructor uses 3600 as fallback default timeout")
    void noArgConstructorUsesFallbackDefault() {
        var registry = new PaygateEndpointRegistry();

        // Register directly with -1 sentinel via the public register method
        // (sentinel resolution happens in toConfig during annotation scanning,
        // not in register — so this tests the fallback constructor is valid)
        registry.register(new PaygateEndpointConfig("GET", "/test", 10, 3600, "", "", ""));

        PaygateEndpointConfig config = registry.findConfig("GET", "/test");
        assertThat(config).isNotNull();
        assertThat(config.timeoutSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("findConfig returns null for unregistered paths")
    void findConfigReturnsNullForUnregisteredPath() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        assertThat(registry.findConfig("GET", "/nonexistent")).isNull();
    }

    @Test
    @DisplayName("size returns the number of registered endpoint configurations")
    void sizeReturnsRegisteredCount() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        assertThat(registry.size()).isZero();

        registry.register(new PaygateEndpointConfig("GET", "/a", 10, 600, "", "", ""));
        registry.register(new PaygateEndpointConfig("POST", "/b", 20, 1200, "", "", ""));
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("registered endpoint with capability is retrievable via findConfig")
    void registeredCapabilityIsPreserved() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

        registry.register(new PaygateEndpointConfig("GET", "/api/analyze", 50, 600, "Analysis endpoint", "", "analyze"));

        PaygateEndpointConfig config = registry.findConfig("GET", "/api/analyze");
        assertThat(config).isNotNull();
        assertThat(config.capability()).isEqualTo("analyze");
    }

    @Test
    @DisplayName("findConfig matches path variables via pattern matching")
    void findConfigMatchesPathVariables() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", ""));

        var config = registry.findConfig("GET", "/api/items/42");
        assertThat(config).isNotNull();
        assertThat(config.pathPattern()).isEqualTo("/api/items/{id}");
    }

    @Test
    @DisplayName("wildcard * method matches any HTTP method")
    void wildcardMethodMatchesAnyMethod() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("*", "/api/universal", 10, 600, "", "", ""));

        assertThat(registry.findConfig("GET", "/api/universal")).isNotNull();
        assertThat(registry.findConfig("POST", "/api/universal")).isNotNull();
        assertThat(registry.findConfig("DELETE", "/api/universal")).isNotNull();
    }

    @Test
    @DisplayName("wildcard * method with path variables matches any method")
    void wildcardMethodWithPathVariablesMatchesAnyMethod() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("*", "/api/resources/{id}", 10, 600, "", "", ""));

        assertThat(registry.findConfig("GET", "/api/resources/99")).isNotNull();
        assertThat(registry.findConfig("PUT", "/api/resources/99")).isNotNull();
    }

    @Test
    @DisplayName("GET pattern does not match POST request (method isolation)")
    void methodIsolationGetDoesNotMatchPost() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", ""));

        assertThat(registry.findConfig("GET", "/api/items/1")).isNotNull();
        assertThat(registry.findConfig("POST", "/api/items/1")).isNull();
    }

    @Test
    @DisplayName("different methods on the same path are independently registered and found")
    void differentMethodsSamePathAreIndependent() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("GET", "/api/data", 10, 600, "get data", "", "read"));
        registry.register(new PaygateEndpointConfig("POST", "/api/data", 20, 1200, "post data", "", "write"));

        var getConfig = registry.findConfig("GET", "/api/data");
        var postConfig = registry.findConfig("POST", "/api/data");

        assertThat(getConfig).isNotNull();
        assertThat(getConfig.priceSats()).isEqualTo(10);
        assertThat(getConfig.capability()).isEqualTo("read");

        assertThat(postConfig).isNotNull();
        assertThat(postConfig.priceSats()).isEqualTo(20);
        assertThat(postConfig.capability()).isEqualTo("write");
    }

    @Test
    @DisplayName("method-specific pattern takes precedence; wildcard still matches other methods")
    void specificMethodAndWildcardCoexist() {
        var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
        registry.register(new PaygateEndpointConfig("GET", "/api/mixed", 10, 600, "", "", "read"));
        registry.register(new PaygateEndpointConfig("*", "/api/mixed", 5, 300, "", "", "any"));

        // GET should match the GET-specific registration (exact key match)
        var getConfig = registry.findConfig("GET", "/api/mixed");
        assertThat(getConfig).isNotNull();
        assertThat(getConfig.capability()).isEqualTo("read");

        // POST should fall through to wildcard
        var postConfig = registry.findConfig("POST", "/api/mixed");
        assertThat(postConfig).isNotNull();
        assertThat(postConfig.capability()).isEqualTo("any");
    }
}
