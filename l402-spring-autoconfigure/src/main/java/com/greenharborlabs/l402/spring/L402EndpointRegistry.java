package com.greenharborlabs.l402.spring;

import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of L402-protected endpoints. Supports both manual registration via
 * {@link #register(L402EndpointConfig)} and automatic scanning of
 * {@link L402Protected} annotations from Spring MVC handler mappings.
 *
 * <p>Path matching supports both exact paths and Spring path patterns
 * (e.g. {@code /api/items/{id}}).
 */
public class L402EndpointRegistry {

    private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();
    private static final long DEFAULT_TIMEOUT_SECONDS_FALLBACK = 3600;

    private final long defaultTimeoutSeconds;
    private final Map<String, L402EndpointConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, PathPattern> parsedPatterns = new ConcurrentHashMap<>();

    /**
     * Creates a registry that resolves the {@code -1} sentinel timeout to the given default.
     *
     * @param defaultTimeoutSeconds the default credential timeout in seconds
     */
    public L402EndpointRegistry(long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    /**
     * Creates a registry with the built-in default timeout of 3600 seconds.
     */
    public L402EndpointRegistry() {
        this(DEFAULT_TIMEOUT_SECONDS_FALLBACK);
    }

    /**
     * Manually registers a protected endpoint configuration.
     *
     * @param config the endpoint configuration
     */
    public void register(L402EndpointConfig config) {
        String key = toKey(config.httpMethod(), config.pathPattern());
        configs.put(key, config);
        parsedPatterns.put(key, PATTERN_PARSER.parse(config.pathPattern()));
    }

    /**
     * Finds the L402 configuration for the given HTTP method and path.
     * First tries exact key match, then falls back to pattern matching.
     *
     * @param method the HTTP method (e.g. "GET")
     * @param path   the request path (e.g. "/api/protected" or "/api/items/123")
     * @return the endpoint config, or {@code null} if the path is not protected
     */
    public L402EndpointConfig findConfig(String method, String path) {
        // Fast path: exact match
        String exactKey = toKey(method, path);
        L402EndpointConfig exact = configs.get(exactKey);
        if (exact != null) {
            return exact;
        }

        // Slow path: iterate all registered patterns and match
        PathContainer pathContainer = PathContainer.parsePath(path);
        String methodUpper = method.toUpperCase();

        for (Map.Entry<String, PathPattern> entry : parsedPatterns.entrySet()) {
            String key = entry.getKey();
            // Check that the HTTP method portion matches
            if (!key.startsWith(methodUpper + ":") && !key.startsWith("*:")) {
                continue;
            }
            if (entry.getValue().matches(pathContainer)) {
                return configs.get(key);
            }
        }
        return null;
    }

    /**
     * Scans all handler methods annotated with {@link L402Protected} and registers them.
     *
     * @param handlerMapping the Spring MVC request mapping handler mapping
     */
    public void scanAnnotatedEndpoints(RequestMappingHandlerMapping handlerMapping) {
        Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            L402Protected annotation = handlerMethod.getMethodAnnotation(L402Protected.class);
            if (annotation == null) {
                continue;
            }

            RequestMappingInfo mappingInfo = entry.getKey();
            Set<String> patterns = mappingInfo.getDirectPaths();
            if (patterns.isEmpty()) {
                patterns = mappingInfo.getPatternValues();
            }

            Set<org.springframework.web.bind.annotation.RequestMethod> httpMethods =
                    mappingInfo.getMethodsCondition().getMethods();

            for (String pattern : patterns) {
                if (httpMethods.isEmpty()) {
                    // No specific method restriction means all methods
                    register(toConfig("*", pattern, annotation));
                } else {
                    for (org.springframework.web.bind.annotation.RequestMethod httpMethod : httpMethods) {
                        register(toConfig(httpMethod.name(), pattern, annotation));
                    }
                }
            }
        }
    }

    /**
     * Returns an unmodifiable view of all registered endpoint configurations.
     */
    public Collection<L402EndpointConfig> getConfigs() {
        return Collections.unmodifiableCollection(configs.values());
    }

    /**
     * Returns the number of registered endpoint configurations.
     */
    public int size() {
        return configs.size();
    }

    private L402EndpointConfig toConfig(String method, String path, L402Protected annotation) {
        long timeout = annotation.timeoutSeconds() == -1
                ? defaultTimeoutSeconds
                : annotation.timeoutSeconds();
        return new L402EndpointConfig(
                method,
                path,
                annotation.priceSats(),
                timeout,
                annotation.description(),
                annotation.pricingStrategy()
        );
    }

    private static String toKey(String method, String path) {
        return method.toUpperCase() + ":" + path;
    }
}
