package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
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
  private final List<RegisteredEndpoint> registrations = new CopyOnWriteArrayList<>();

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
    var method = normalizeMethod(config.httpMethod());
    var pattern = parsePathPattern(config.pathPattern());
    var canonicalPattern = pattern.getPatternString();
    ensureNoConflictingIdentity(method, canonicalPattern, config);
    registrations.add(
        new RegisteredEndpoint(config, method, pattern, null, null, null, Integer.MAX_VALUE));
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
    var resolved = resolve(method, path);
    return resolved == null ? null : resolved.config();
  }

  /**
   * Resolves the payment policy registered for the given HTTP method and request path.
   *
   * <p>An explicit method registration takes precedence over the wildcard method. {@code HEAD}
   * additionally falls back to {@code GET} before the wildcard method. Within each method bucket,
   * exact routes take precedence over patterns and the most specific matching Spring path pattern
   * is selected. An unresolved specificity tie fails closed.
   *
   * @param method the actual HTTP request method
   * @param path the request path
   * @return the resolved endpoint, or {@code null} if the path is not protected
   * @throws IllegalStateException if distinct matching route patterns are equally specific
   */
  public ResolvedEndpoint resolve(String method, String path) {
    var normalizedMethod = normalizeMethod(method);
    var buckets =
        "HEAD".equals(normalizedMethod)
            ? List.of("HEAD", "GET", "*")
            : "*".equals(normalizedMethod) ? List.of("*") : List.of(normalizedMethod, "*");
    var pathContainer = PathContainer.parsePath(path);

    for (String bucket : buckets) {
      var resolved = resolveBucket(bucket, path, pathContainer);
      if (resolved != null) {
        return resolved;
      }
    }
    return null;
  }

  private ResolvedEndpoint resolveBucket(String method, String path, PathContainer pathContainer) {
    var matches = new ArrayList<RegisteredEndpoint>();
    for (RegisteredEndpoint candidate : registrations) {
      if (method.equals(candidate.policyMethod()) && candidate.pattern().matches(pathContainer)) {
        matches.add(candidate);
      }
    }
    if (matches.isEmpty()) {
      return null;
    }

    matches.sort(
        (left, right) ->
            PathPattern.SPECIFICITY_COMPARATOR.compare(left.pattern(), right.pattern()));
    var selected = matches.getFirst();
    if (matches.size() > 1
        && PathPattern.SPECIFICITY_COMPARATOR.compare(selected.pattern(), matches.get(1).pattern())
            == 0) {
      throw new IllegalStateException(
          "Ambiguous endpoint registrations for "
              + method
              + " "
              + selected.pattern().getPatternString()
              + " and "
              + matches.get(1).pattern().getPatternString());
    }
    return selected.config() == null ? null : toResolvedEndpoint(method, selected);
  }

  private ResolvedEndpoint toResolvedEndpoint(String method, RegisteredEndpoint registered) {
    return new ResolvedEndpoint(
        registered.config(),
        registered.pattern().getPatternString(),
        method,
        registered.mappingInfo(),
        registered.handlerMethod(),
        registered.sourceMapping(),
        registered.sourceOrder(),
        registered.pattern());
  }

  /**
   * Resolves a policy using Spring MVC's complete request-mapping matching and comparison rules.
   * This includes request parameters, headers, media types, version, and custom conditions.
   *
   * @param request the inbound servlet request
   * @return the selected endpoint, or {@code null} if no protected mapping matches
   */
  public ResolvedEndpoint resolve(HttpServletRequest request) {
    var matches = new ArrayList<RegisteredEndpoint>();
    for (RegisteredEndpoint candidate : registrations) {
      var mappingInfo = candidate.mappingInfo();
      if (mappingInfo == null) {
        if (candidate.policyMethod().equals(normalizeMethod(request.getMethod()))
            && candidate.pattern().matches(PathContainer.parsePath(request.getRequestURI()))) {
          matches.add(candidate);
        }
      } else if (mappingInfo.getMatchingCondition(request) != null) {
        matches.add(candidate);
      }
    }
    if (matches.isEmpty()) {
      return null;
    }
    matches.sort(
        (left, right) -> {
          if (left.mappingInfo() != null && right.mappingInfo() != null) {
            return left.mappingInfo().compareTo(right.mappingInfo(), request);
          }
          return PathPattern.SPECIFICITY_COMPARATOR.compare(left.pattern(), right.pattern());
        });
    var selected = matches.getFirst();
    if (matches.size() > 1
        && compareForRequest(selected, matches.get(1), request) == 0
        && !java.util.Objects.equals(selected.config(), matches.get(1).config())) {
      throw new IllegalStateException("Ambiguous payment policies for request mapping");
    }
    if (selected.config() == null) {
      return null;
    }
    return toResolvedEndpoint(selected.policyMethod(), selected);
  }

  private static int compareForRequest(
      RegisteredEndpoint left, RegisteredEndpoint right, HttpServletRequest request) {
    if (left.mappingInfo() != null && right.mappingInfo() != null) {
      return left.mappingInfo().compareTo(right.mappingInfo(), request);
    }
    return PathPattern.SPECIFICITY_COMPARATOR.compare(left.pattern(), right.pattern());
  }

  /**
   * Catalogs all handler methods and associates payment policy with those annotated with {@link
   * PaymentRequired}.
   *
   * @param handlerMapping the Spring MVC request mapping handler mapping
   */
  public void scanAnnotatedEndpoints(RequestMappingHandlerMapping handlerMapping) {
    scanAnnotatedEndpoints(handlerMapping, Integer.MAX_VALUE);
  }

  /** Scans a supported MVC mapping source, retaining its complete mapping metadata. */
  public void scanAnnotatedEndpoints(RequestMappingHandlerMapping handlerMapping, int sourceOrder) {
    for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
      HandlerMethod handlerMethod = entry.getValue();
      PaymentRequired paymentRequired = handlerMethod.getMethodAnnotation(PaymentRequired.class);

      RequestMappingInfo mappingInfo = entry.getKey();
      Set<String> patterns = mappingInfo.getDirectPaths();
      if (patterns.isEmpty()) {
        patterns = mappingInfo.getPatternValues();
      }

      Set<org.springframework.web.bind.annotation.RequestMethod> httpMethods =
          mappingInfo.getMethodsCondition().getMethods();

      for (String pattern : patterns) {
        PathPattern parsedPattern = patternFrom(mappingInfo, pattern);
        if (httpMethods.isEmpty()) {
          registerMappedEndpoint(
              paymentRequired == null ? null : toConfig("*", pattern, paymentRequired),
              "*",
              pattern,
              mappingInfo,
              handlerMethod,
              handlerMapping,
              sourceOrder,
              parsedPattern);
        } else {
          for (org.springframework.web.bind.annotation.RequestMethod httpMethod : httpMethods) {
            registerMappedEndpoint(
                paymentRequired == null
                    ? null
                    : toConfig(httpMethod.name(), pattern, paymentRequired),
                httpMethod.name(),
                pattern,
                mappingInfo,
                handlerMethod,
                handlerMapping,
                sourceOrder,
                parsedPattern);
          }
        }
      }
    }
  }

  private void registerMappedEndpoint(
      PaygateEndpointConfig config,
      String policyMethod,
      String routePattern,
      RequestMappingInfo mappingInfo,
      HandlerMethod handlerMethod,
      HandlerMapping sourceMapping,
      int sourceOrder,
      PathPattern parsedPattern) {
    if (config != null) {
      config = normalizeCapabilities(config);
    }
    var pattern = parsedPattern == null ? parsePathPattern(routePattern) : parsedPattern;
    var method = normalizeMethod(policyMethod);
    ensureNoConflictingIdentity(method, pattern.getPatternString(), config);
    registrations.add(
        new RegisteredEndpoint(
            config, method, pattern, mappingInfo, handlerMethod, sourceMapping, sourceOrder));
  }

  private static PathPattern patternFrom(RequestMappingInfo mappingInfo, String pattern) {
    var condition = mappingInfo.getPathPatternsCondition();
    if (condition != null) {
      for (PathPattern parsedPattern : condition.getPatterns()) {
        if (parsedPattern.getPatternString().equals(pattern)) {
          return parsedPattern;
        }
      }
    }
    return null;
  }

  private void ensureNoConflictingIdentity(
      String method, String canonicalPattern, PaygateEndpointConfig config) {
    for (RegisteredEndpoint existing : registrations) {
      if (existing.policyMethod().equals(method)
          && existing.pattern().getPatternString().equals(canonicalPattern)) {
        if (!java.util.Objects.equals(existing.config(), config)) {
          throw new IllegalArgumentException(
              "Duplicate endpoint registration for " + method + " " + canonicalPattern);
        }
        if (existing.mappingInfo() == null) {
          throw new IllegalArgumentException(
              "Duplicate endpoint registration for " + method + " " + canonicalPattern);
        }
      }
    }
  }

  /** Returns an unmodifiable view of all registered endpoint configurations. */
  public Collection<PaygateEndpointConfig> getConfigs() {
    var configs =
        registrations.stream()
            .map(RegisteredEndpoint::config)
            .filter(java.util.Objects::nonNull)
            .toList();
    return Collections.unmodifiableCollection(configs);
  }

  /** Returns the number of registered endpoint configurations. */
  public int size() {
    return registrations.size();
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

  private static String normalizeMethod(String method) {
    return method.toUpperCase(Locale.ROOT);
  }

  static PathPattern parsePathPattern(String pathPattern) {
    return PATTERN_PARSER.parse(pathPattern);
  }

  private record RegisteredEndpoint(
      PaygateEndpointConfig config,
      String policyMethod,
      PathPattern pattern,
      RequestMappingInfo mappingInfo,
      HandlerMethod handlerMethod,
      HandlerMapping sourceMapping,
      int sourceOrder) {}
}
