package com.greenharborlabs.paygate.spring.security;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Fails closed when a Spring Security chain omits or misorders Paygate authentication and
 * authentication-failure rate limiting, or excludes required dispatcher types.
 */
@AutoConfiguration(after = PaygateSecurityAutoConfiguration.class)
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, FilterChainProxy.class})
@Conditional(PaygateSpringSecurityModeCondition.class)
public class PaygateSpringSecurityFilterChainGuardAutoConfiguration {

  @Bean
  SmartInitializingSingleton paygateSpringSecurityFilterChainGuard(
      ObjectProvider<FilterChainProxy> filterChainProxies) {
    return new PaygateSpringSecurityFilterChainGuard(filterChainProxies);
  }

  static final class PaygateSpringSecurityFilterChainGuard implements SmartInitializingSingleton {

    private final ObjectProvider<FilterChainProxy> filterChainProxies;

    PaygateSpringSecurityFilterChainGuard(ObjectProvider<FilterChainProxy> filterChainProxies) {
      this.filterChainProxies = filterChainProxies;
    }

    @Override
    public void afterSingletonsInstantiated() {
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
      validateChain(chain, new IdentityHashMap<>());
    }

    private void validateChain(SecurityFilterChain chain, Map<Object, Boolean> visited) {
      List<Filter> filters = chain.getFilters();
      validateFilterSequence(filters, visited);
      validateErrorDispatcherCoverage(chain);
    }

    private void validateFilterSequence(List<Filter> filters, Map<Object, Boolean> visited) {
      int paygateIndex = directPaygateFilterIndex(filters);
      int rateLimitIndex = directRateLimitFilterIndex(filters);

      if (paygateIndex < 0 && rateLimitIndex < 0) {
        validateWrappedProtection(filters, visited);
        return;
      }

      validateDirectProtection(filters, paygateIndex, rateLimitIndex);
    }

    private void validateWrappedProtection(List<Filter> filters, Map<Object, Boolean> visited) {
      int protectedWrapperIndex = protectedWrapperIndex(filters, visited);
      if (protectedWrapperIndex < 0) {
        throw missingFilter();
      }
      int authorizationIndex = directAuthorizationFilterIndex(filters);
      if (authorizationIndex >= 0 && protectedWrapperIndex > authorizationIndex) {
        throw paygateOrderingFailure();
      }
    }

    private static void validateDirectProtection(
        List<Filter> filters, int paygateIndex, int rateLimitIndex) {
      if (paygateIndex < 0) {
        throw missingFilter();
      }
      if (rateLimitIndex < 0) {
        throw missingRateLimitFilter();
      }
      int authorizationIndex = directAuthorizationFilterIndex(filters);
      if (authorizationIndex >= 0 && paygateIndex > authorizationIndex) {
        throw paygateOrderingFailure();
      }
      if (rateLimitIndex > paygateIndex) {
        throw new IllegalStateException(
            "PaygateAuthFailureRateLimitFilter must run before PaygateAuthenticationFilter in "
                + "every effective Spring Security filter chain.");
      }
    }

    private static IllegalStateException paygateOrderingFailure() {
      return new IllegalStateException(
          "PaygateAuthenticationFilter must run before downstream authorization in every "
              + "effective Spring Security filter chain.");
    }

    private int protectedWrapperIndex(List<Filter> filters, Map<Object, Boolean> visited) {
      for (int index = 0; index < filters.size(); index++) {
        if (validateNestedProtection(filters.get(index), visited)) {
          return index;
        }
      }
      return -1;
    }

    private boolean validateNestedProtection(Filter filter, Map<Object, Boolean> visited) {
      if (visited.put(filter, Boolean.TRUE) != null) {
        return false;
      }
      if (filter instanceof FilterChainProxy nestedProxy) {
        List<SecurityFilterChain> nestedChains = nestedProxy.getFilterChains();
        if (nestedChains.isEmpty()) {
          throw missingFilter();
        }
        for (SecurityFilterChain nestedChain : nestedChains) {
          validateChain(nestedChain, visited);
        }
        return true;
      }
      Collection<Filter> nested = nestedFilters(filter);
      if (nested.isEmpty()) {
        return false;
      }
      validateFilterSequence(List.copyOf(nested), visited);
      return true;
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
      var dispatcher = new DispatcherType[] {DispatcherType.REQUEST};
      HttpServletRequest request = dispatcherProbe(dispatcher);
      for (DispatcherType candidate :
          new DispatcherType[] {
            DispatcherType.REQUEST,
            DispatcherType.ASYNC,
            DispatcherType.FORWARD,
            DispatcherType.ERROR
          }) {
        dispatcher[0] = candidate;
        if (!chain.matches(request)) {
          throw new IllegalStateException(
              "Paygate Spring Security filter-chain dispatcher coverage excludes "
                  + candidate
                  + " dispatches; paid routes must remain enforced on redispatch.");
        }
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
              + "BasicAuthenticationFilter.class). Custom-chain acknowledgement cannot waive "
              + "this minimum protection.");
    }

    private static IllegalStateException missingRateLimitFilter() {
      return new IllegalStateException(
          "Every effective Spring Security filter chain protecting paid routes must include "
              + "PaygateAuthFailureRateLimitFilter before PaygateAuthenticationFilter. Custom-chain "
              + "acknowledgement cannot waive this minimum protection.");
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
