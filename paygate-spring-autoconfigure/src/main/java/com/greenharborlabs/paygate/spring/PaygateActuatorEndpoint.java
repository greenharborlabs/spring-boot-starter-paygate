package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposing L402 status and configuration.
 *
 * <p>Available at {@code GET /actuator/paygate} when the actuator is on the classpath
 * and the endpoint is exposed.
 *
 * <p><strong>Security warning:</strong> This endpoint exposes sensitive operational
 * data including backend health status, all protected endpoint paths with pricing,
 * active credential counts, and earnings metrics. In production deployments this
 * endpoint should be secured behind authentication (e.g., Spring Security or
 * management port restrictions) to prevent information disclosure.
 */
@Endpoint(id = "paygate")
public class PaygateActuatorEndpoint {

    private final PaygateProperties properties;
    /**
     * The Lightning backend used for health checks. The auto-configuration wraps this
     * with {@link CachingLightningBackendWrapper} by default, so {@code isHealthy()}
     * calls return cached results and do not block on slow Lightning nodes.
     */
    private final LightningBackend lightningBackend;
    private final PaygateEndpointRegistry endpointRegistry;
    private final CredentialStore credentialStore;
    private final PaygateEarningsTracker earningsTracker;

    public PaygateActuatorEndpoint(PaygateProperties properties,
                                 LightningBackend lightningBackend,
                                 PaygateEndpointRegistry endpointRegistry,
                                 CredentialStore credentialStore,
                                 PaygateEarningsTracker earningsTracker) {
        this.properties = properties;
        this.lightningBackend = lightningBackend;
        this.endpointRegistry = endpointRegistry;
        this.credentialStore = credentialStore;
        this.earningsTracker = earningsTracker;
    }

    @ReadOperation
    public Map<String, Object> paygateInfo() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", properties.isEnabled());
        result.put("backend", properties.getBackend());
        boolean healthy;
        try {
            healthy = lightningBackend.isHealthy();
        } catch (Exception _) {
            healthy = false;
        }
        result.put("backendHealthy", healthy);
        result.put("serviceName", properties.getServiceName());
        result.put("protectedEndpoints", buildProtectedEndpoints());
        result.put("credentials", buildCredentials());
        result.put("earnings", buildEarnings());
        return result;
    }

    private List<Map<String, Object>> buildProtectedEndpoints() {
        Collection<PaygateEndpointConfig> configs = endpointRegistry.getConfigs();
        return configs.stream().map(this::toEndpointMap).toList();
    }

    private Map<String, Object> toEndpointMap(PaygateEndpointConfig config) {
        var map = new LinkedHashMap<String, Object>();
        map.put("method", config.httpMethod());
        map.put("path", config.pathPattern());
        map.put("priceSats", config.priceSats());
        map.put("timeoutSeconds", config.timeoutSeconds());
        map.put("description", config.description());
        map.put("pricingStrategy",
                config.pricingStrategy() == null || config.pricingStrategy().isEmpty()
                        ? null
                        : config.pricingStrategy());
        return map;
    }

    private Map<String, Object> buildCredentials() {
        var map = new LinkedHashMap<String, Object>();
        map.put("active", credentialStore.activeCount());
        map.put("maxSize", properties.getCredentialCacheMaxSize());
        return map;
    }

    private Map<String, Object> buildEarnings() {
        var map = new LinkedHashMap<String, Object>();
        map.put("totalInvoicesCreated", earningsTracker.getTotalInvoicesCreated());
        map.put("totalInvoicesSettled", earningsTracker.getTotalInvoicesSettled());
        map.put("totalSatsEarned", earningsTracker.getTotalSatsEarned());
        map.put("note", "In-memory only; resets on application restart");
        return map;
    }
}
