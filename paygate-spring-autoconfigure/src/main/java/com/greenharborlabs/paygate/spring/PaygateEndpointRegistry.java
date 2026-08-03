package com.greenharborlabs.paygate.spring;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Registry of payment-protected endpoints. Supports both manual registration via {@link
 * #register(PaygateEndpointConfig)} and automatic scanning of {@link PaymentRequired} annotations
 * from Spring MVC handler mappings.
 *
 * <p>Path matching supports both exact paths and Spring path patterns (e.g. {@code
 * /api/items/{id}}).
 */
public class PaygateEndpointRegistry {

  private static final System.Logger log =
      System.getLogger(PaygateEndpointRegistry.class.getName());
  private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();
  private static final long DEFAULT_TIMEOUT_SECONDS_FALLBACK = 3600;
  private static final int DEFAULT_MAX_VALUES_PER_CAVEAT = 50;
  private static final String NO_CAPABILITY_SENTINEL = "~";

  private final long defaultTimeoutSeconds;
  private final int maxValuesPerCaveat;
  private final Map<String, PaygateEndpointConfig> configs = new ConcurrentHashMap<>();
  private final Map<String, Map<String, PathPattern>> patternsByMethod = new ConcurrentHashMap<>();

  /**
   * Creates a registry that resolves the {@code -1} sentinel timeout to the given default.
   *
   * @param defaultTimeoutSeconds the default credential timeout in seconds
   */
  public PaygateEndpointRegistry(long defaultTimeoutSeconds) {
    this(defaultTimeoutSeconds, DEFAULT_MAX_VALUES_PER_CAVEAT);
  }

  /**
   * Creates a registry with the given timeout default and capability-list bound.
   *
   * @param defaultTimeoutSeconds the default credential timeout in seconds
   * @param maxValuesPerCaveat the maximum number of capability values in one declaration
   */
  public PaygateEndpointRegistry(long defaultTimeoutSeconds, int maxValuesPerCaveat) {
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
    this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  /** Creates a registry with the built-in default timeout of 3600 seconds. */
  public PaygateEndpointRegistry() {
    this(DEFAULT_TIMEOUT_SECONDS_FALLBACK, DEFAULT_MAX_VALUES_PER_CAVEAT);
  }

  /**
   * Manually registers a protected endpoint configuration.
   *
   * @param config the endpoint configuration
   */
  public void register(PaygateEndpointConfig config) {
    config = normalizeCapabilities(config);
    var key = toKey(config.httpMethod(), config.pathPattern());
    configs.put(key, config);
    patternsByMethod
        .computeIfAbsent(config.httpMethod().toUpperCase(), _ -> new ConcurrentHashMap<>())
        .put(key, PATTERN_PARSER.parse(config.pathPattern()));
  }

  private PaygateEndpointConfig normalizeCapabilities(PaygateEndpointConfig config) {
    String declaration = config.capability();
    String normalized;
    if (declaration == null || declaration.isBlank()) {
      normalized = "";
    } else {
      int splitLimit =
          maxValuesPerCaveat == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxValuesPerCaveat + 1;
      String[] segments = declaration.split(",", splitLimit);
      if (segments.length > maxValuesPerCaveat) {
        throw new IllegalArgumentException(
            "Capability declaration has "
                + segments.length
                + " values, maximum allowed is "
                + maxValuesPerCaveat);
      }

      var capabilities = new LinkedHashSet<String>();
      for (String segment : segments) {
        String capability = segment.trim();
        if (capability.isEmpty()) {
          throw new IllegalArgumentException("Capability declaration contains a blank segment");
        }
        if (NO_CAPABILITY_SENTINEL.equals(capability)) {
          throw new IllegalArgumentException(
              "Capability '~' is reserved for the internal no-capability state");
        }
        capabilities.add(capability);
      }
      normalized = String.join(",", capabilities);
    }

    if (normalized.equals(declaration)) {
      return config;
    }
    return new PaygateEndpointConfig(
        config.httpMethod(),
        config.pathPattern(),
        config.priceSats(),
        config.timeoutSeconds(),
        config.description(),
        config.pricingStrategy(),
        normalized);
  }

  /**
   * Finds the L402 configuration for the given HTTP method and path. First tries exact key match,
   * then falls back to pattern matching.
   *
   * @param method the HTTP method (e.g. "GET")
   * @param path the request path (e.g. "/api/protected" or "/api/items/123")
   * @return the endpoint config, or {@code null} if the path is not protected
   */
  public PaygateEndpointConfig findConfig(String method, String path) {
    // Fast path: exact match
    String exactKey = toKey(method, path);
    PaygateEndpointConfig exact = configs.get(exactKey);
    if (exact != null) {
      return exact;
    }

    // Slow path: only iterate patterns for the matching method + wildcard "*"
    var pathContainer = PathContainer.parsePath(path);
    var methodUpper = method.toUpperCase();

    // Check method-specific patterns first
    var methodPatterns = patternsByMethod.get(methodUpper);
    if (methodPatterns != null) {
      for (var entry : methodPatterns.entrySet()) {
        if (entry.getValue().matches(pathContainer)) {
          return configs.get(entry.getKey());
        }
      }
    }

    // Then check wildcard "*" patterns
    var wildcardPatterns = patternsByMethod.get("*");
    if (wildcardPatterns != null) {
      for (var entry : wildcardPatterns.entrySet()) {
        if (entry.getValue().matches(pathContainer)) {
          return configs.get(entry.getKey());
        }
      }
    }

    return null;
  }

  /**
   * Scans all handler methods annotated with {@link PaymentRequired} and registers them.
   *
   * @param handlerMapping the Spring MVC request mapping handler mapping
   */
  public void scanAnnotatedEndpoints(RequestMappingHandlerMapping handlerMapping) {
    Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methods.entrySet()) {
      HandlerMethod handlerMethod = entry.getValue();
      PaymentRequired paymentRequired = handlerMethod.getMethodAnnotation(PaymentRequired.class);

      if (paymentRequired == null) {
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
          register(toConfig("*", pattern, paymentRequired));
        } else {
          for (org.springframework.web.bind.annotation.RequestMethod httpMethod : httpMethods) {
            register(toConfig(httpMethod.name(), pattern, paymentRequired));
          }
        }
      }
    }
  }

  /** Returns an unmodifiable view of all registered endpoint configurations. */
  public Collection<PaygateEndpointConfig> getConfigs() {
    return Collections.unmodifiableCollection(configs.values());
  }

  /** Returns the number of registered endpoint configurations. */
  public int size() {
    return configs.size();
  }

  private PaygateEndpointConfig toConfig(String method, String path, PaymentRequired annotation) {
    long timeout =
        annotation.timeoutSeconds() == -1 ? defaultTimeoutSeconds : annotation.timeoutSeconds();
    return new PaygateEndpointConfig(
        method,
        path,
        annotation.priceSats(),
        timeout,
        annotation.description(),
        annotation.pricingStrategy(),
        annotation.capability());
  }

  private static String toKey(String method, String path) {
    return method.toUpperCase() + ":" + path;
  }
}
