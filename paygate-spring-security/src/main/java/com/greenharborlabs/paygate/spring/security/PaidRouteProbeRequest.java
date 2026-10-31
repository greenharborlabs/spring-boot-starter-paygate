package com.greenharborlabs.paygate.spring.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Builds the synthetic request that the filter-chain guard passes to {@link
 * org.springframework.security.web.SecurityFilterChain#matches(HttpServletRequest)}.
 *
 * <p>The request looks like an application-relative request dispatched to a {@code
 * DispatcherServlet} registered on the default {@code /} mapping, with no context path, which is
 * what Spring Boot registers. Its {@code RequestPath} is parsed and cached up front so that
 * path-pattern based matchers behave as they do at request time. Request attributes are stored; all
 * other request data (headers, parameters, remote address, ...) is absent.
 */
final class PaidRouteProbeRequest {

  private static final HttpServletMapping DEFAULT_SERVLET_MAPPING = new DefaultServletMapping();

  private PaidRouteProbeRequest() {}

  static HttpServletRequest create(String method, String path, DispatcherType dispatcherType) {
    Map<String, Object> attributes = new HashMap<>();
    String description =
        "PaidRouteProbeRequest[" + dispatcherType + " " + method + " " + path + "]";
    var request =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                PaidRouteProbeRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, invoked, args) ->
                    switch (invoked.getName()) {
                      case "getMethod" -> method;
                      case "getRequestURI", "getServletPath" -> path;
                      case "getRequestURL" -> new StringBuffer("http://localhost").append(path);
                      case "getContextPath" -> "";
                      case "getPathInfo", "getQueryString" -> null;
                      case "getDispatcherType" -> dispatcherType;
                      case "getHttpServletMapping" -> DEFAULT_SERVLET_MAPPING;
                      case "getScheme" -> "http";
                      case "getServerName" -> "localhost";
                      case "getServerPort" -> 80;
                      case "getAttribute" -> attributes.get((String) args[0]);
                      case "setAttribute" -> {
                        if (args[1] == null) {
                          attributes.remove((String) args[0]);
                        } else {
                          attributes.put((String) args[0], args[1]);
                        }
                        yield null;
                      }
                      case "removeAttribute" -> attributes.remove((String) args[0]);
                      case "getAttributeNames" ->
                          Collections.enumeration(List.copyOf(attributes.keySet()));
                      case "equals" -> proxy == args[0];
                      case "hashCode" -> System.identityHashCode(proxy);
                      case "toString" -> description;
                      default -> absentValue(invoked.getReturnType());
                    });
    ServletRequestPathUtils.parseAndCache(request);
    return request;
  }

  private static Object absentValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (Enumeration.class.isAssignableFrom(type)) {
      return Collections.emptyEnumeration();
    }
    if (Map.class.isAssignableFrom(type)) {
      return Map.of();
    }
    if (Set.class.isAssignableFrom(type)) {
      return Set.of();
    }
    if (Collection.class.isAssignableFrom(type)) {
      return List.of();
    }
    return null;
  }

  /** The mapping Spring Boot registers for {@code DispatcherServlet}: the default servlet. */
  private static final class DefaultServletMapping implements HttpServletMapping {

    @Override
    public String getMatchValue() {
      return "";
    }

    @Override
    public String getPattern() {
      return "/";
    }

    @Override
    public String getServletName() {
      return "dispatcherServlet";
    }

    @Override
    public MappingMatch getMappingMatch() {
      return MappingMatch.DEFAULT;
    }
  }
}
