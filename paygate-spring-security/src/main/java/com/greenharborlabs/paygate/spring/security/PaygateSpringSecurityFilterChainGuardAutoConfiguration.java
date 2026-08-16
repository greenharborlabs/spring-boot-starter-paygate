package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.spring.PaygateProperties;
import com.greenharborlabs.paygate.spring.PaygateSpringSecurityModeCondition;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Fails closed when Paygate is in Spring Security mode but the effective filter chain does not
 * contain {@link PaygateAuthenticationFilter}.
 */
@AutoConfiguration(after = PaygateSecurityAutoConfiguration.class)
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, FilterChainProxy.class})
@Conditional(PaygateSpringSecurityModeCondition.class)
@EnableConfigurationProperties(PaygateProperties.class)
public class PaygateSpringSecurityFilterChainGuardAutoConfiguration {

  static final String ACKNOWLEDGEMENT_PROPERTY =
      "paygate.spring-security.custom-filter-chain-acknowledged";

  @Bean
  SmartInitializingSingleton paygateSpringSecurityFilterChainGuard(
      ObjectProvider<FilterChainProxy> filterChainProxies, PaygateProperties properties) {
    return new PaygateSpringSecurityFilterChainGuard(filterChainProxies, properties);
  }

  static final class PaygateSpringSecurityFilterChainGuard implements SmartInitializingSingleton {

    private final ObjectProvider<FilterChainProxy> filterChainProxies;
    private final PaygateProperties properties;

    PaygateSpringSecurityFilterChainGuard(
        ObjectProvider<FilterChainProxy> filterChainProxies, PaygateProperties properties) {
      this.filterChainProxies = filterChainProxies;
      this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
      if (properties.getSpringSecurity().isCustomFilterChainAcknowledged()) {
        return;
      }
      var chains =
          filterChainProxies.stream().flatMap(proxy -> proxy.getFilterChains().stream()).toList();
      if (chains.isEmpty()) {
        throw missingFilter();
      }
      for (SecurityFilterChain chain : chains) {
        validateChain(chain);
      }
    }

    private void validateChain(SecurityFilterChain chain) {
      List<Filter> filters = chain.getFilters();
      int paygateIndex = directPaygateFilterIndex(filters);
      if (paygateIndex < 0 && !containsPaygateAuthenticationFilter(filters)) {
        throw missingFilter();
      }
      int authorizationIndex = directAuthorizationFilterIndex(filters);
      if (paygateIndex >= 0 && authorizationIndex >= 0 && paygateIndex > authorizationIndex) {
        throw new IllegalStateException(
            "PaygateAuthenticationFilter must run before downstream authorization in every "
                + "effective Spring Security filter chain.");
      }
      int rateLimitIndex = directRateLimitFilterIndex(filters);
      if (rateLimitIndex >= 0 && paygateIndex >= 0 && rateLimitIndex > paygateIndex) {
        throw new IllegalStateException(
            "PaygateAuthFailureRateLimitFilter must run before PaygateAuthenticationFilter in "
                + "every effective Spring Security filter chain.");
      }
      validateErrorDispatcherCoverage(chain);
    }

    private static int directPaygateFilterIndex(List<Filter> filters) {
      for (int index = 0; index < filters.size(); index++) {
        if (filters.get(index) instanceof PaygateAuthenticationFilter) {
          return index;
        }
      }
      return -1;
    }

    private static int directAuthorizationFilterIndex(List<Filter> filters) {
      for (int index = 0; index < filters.size(); index++) {
        if (filters.get(index) instanceof AuthorizationFilter) {
          return index;
        }
      }
      return -1;
    }

    private static int directRateLimitFilterIndex(List<Filter> filters) {
      for (int index = 0; index < filters.size(); index++) {
        if (filters.get(index) instanceof PaygateAuthFailureRateLimitFilter) {
          return index;
        }
      }
      return -1;
    }

    private void validateErrorDispatcherCoverage(SecurityFilterChain chain) {
      var requestDispatcher = new DispatcherType[] {DispatcherType.REQUEST};
      HttpServletRequest request = dispatcherProbe(requestDispatcher);
      requestDispatcher[0] = DispatcherType.ERROR;
      if (!chain.matches(request)) {
        throw new IllegalStateException(
            "Paygate Spring Security filter-chain dispatcher coverage excludes ERROR dispatches; "
                + "paid routes must remain enforced on redispatch.");
      }
    }

    private static HttpServletRequest dispatcherProbe(DispatcherType[] dispatcher) {
      return (HttpServletRequest)
          Proxy.newProxyInstance(
              HttpServletRequest.class.getClassLoader(),
              new Class<?>[] {HttpServletRequest.class},
              (_, method, _) ->
                  switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getRequestURI" -> "/paid";
                    case "getDispatcherType" -> dispatcher[0];
                    case "getContextPath", "getServletPath" -> "";
                    case "isSecure" -> false;
                    default -> null;
                  });
    }

    private static IllegalStateException missingFilter() {
      return new IllegalStateException(
          "Paygate servlet enforcement is disabled in Spring Security mode, but no "
              + "PaygateAuthenticationFilter was found in every effective FilterChainProxy chain. "
              + "Add the reference "
              + "wiring, for example http.addFilterBefore(paygateFilter, "
              + "BasicAuthenticationFilter.class), or set "
              + ACKNOWLEDGEMENT_PROPERTY
              + "=true to acknowledge responsibility for enforcing Paygate in a custom filter "
              + "chain.");
    }

    private boolean containsPaygateAuthenticationFilter(FilterChainProxy filterChainProxy) {
      return filterChainProxy.getFilterChains().stream()
          .map(SecurityFilterChain::getFilters)
          .flatMap(List::stream)
          .anyMatch(filter -> containsPaygateAuthenticationFilter(filter, new IdentityHashMap<>()));
    }

    private boolean containsPaygateAuthenticationFilter(List<Filter> filters) {
      return filters.stream()
          .anyMatch(filter -> containsPaygateAuthenticationFilter(filter, new IdentityHashMap<>()));
    }

    private boolean containsPaygateAuthenticationFilter(
        Filter filter, Map<Object, Boolean> visited) {
      if (filter instanceof PaygateAuthenticationFilter) {
        return true;
      }
      if (visited.put(filter, Boolean.TRUE) != null) {
        return false;
      }
      if (filter instanceof FilterChainProxy nestedProxy) {
        return containsPaygateAuthenticationFilter(nestedProxy);
      }
      return nestedFilters(filter).stream()
          .anyMatch(nested -> containsPaygateAuthenticationFilter(nested, visited));
    }

    @SuppressWarnings("unchecked")
    private Collection<Filter> nestedFilters(Filter filter) {
      try {
        Method getFilters = filter.getClass().getMethod("getFilters");
        Object filters = getFilters.invoke(filter);
        if (filters instanceof Collection<?> collection
            && collection.stream().allMatch(Filter.class::isInstance)) {
          return (Collection<Filter>) collection;
        }
      } catch (IllegalAccessException
          | InvocationTargetException
          | NoSuchMethodException
          | SecurityException _) {
        return List.of();
      }
      return List.of();
    }
  }
}
