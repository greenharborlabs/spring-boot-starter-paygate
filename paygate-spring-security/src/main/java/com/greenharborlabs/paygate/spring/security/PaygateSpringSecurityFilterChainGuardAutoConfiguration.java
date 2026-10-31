package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateSpringSecurityModeCondition;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Fails closed when a Spring Security chain omits or misorders Paygate authentication and
 * authentication-failure rate limiting, or when a registered paid route is not selected into such a
 * chain for every required dispatcher type.
 *
 * <p>Runs after {@link PaygateAutoConfiguration} so that the endpoint registry already holds the
 * annotated routes and the manual routes registered during startup.
 */
@AutoConfiguration(after = {PaygateAutoConfiguration.class, PaygateSecurityAutoConfiguration.class})
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, FilterChainProxy.class})
@Conditional(PaygateSpringSecurityModeCondition.class)
public class PaygateSpringSecurityFilterChainGuardAutoConfiguration {

  @Bean
  SmartInitializingSingleton paygateSpringSecurityFilterChainGuard(
      ObjectProvider<FilterChainProxy> filterChainProxies,
      ObjectProvider<PaygateEndpointRegistry> endpointRegistries) {
    return new PaygateSpringSecurityFilterChainGuard(
        filterChainProxies, endpointRegistries::getIfAvailable);
  }

  static final class PaygateSpringSecurityFilterChainGuard implements SmartInitializingSingleton {

    /** Dispatcher types on which a paid route must remain enforced. */
    private static final List<DispatcherType> REQUIRED_DISPATCHER_TYPES =
        List.of(
            DispatcherType.REQUEST,
            DispatcherType.ASYNC,
            DispatcherType.FORWARD,
            DispatcherType.ERROR);

    private final ObjectProvider<FilterChainProxy> filterChainProxies;
    private final Supplier<PaygateEndpointRegistry> endpointRegistry;

    PaygateSpringSecurityFilterChainGuard(
        ObjectProvider<FilterChainProxy> filterChainProxies,
        Supplier<PaygateEndpointRegistry> endpointRegistry) {
      this.filterChainProxies = filterChainProxies;
      this.endpointRegistry = endpointRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
      var chains =
          filterChainProxies.stream().flatMap(proxy -> proxy.getFilterChains().stream()).toList();
      if (chains.isEmpty()) {
        throw missingFilter();
      }
      for (SecurityFilterChain chain : chains) {
        validateChain(chain, new IdentityHashMap<>());
      }
      validatePaidRouteCoverage(chains);
    }

    private void validateChain(SecurityFilterChain chain, Map<Object, Boolean> visited) {
      validateFilterSequence(chain.getFilters(), visited);
    }

    /**
     * For every registered paid route and required dispatcher type, the first chain that matches a
     * sample request for the route, which is the chain {@link FilterChainProxy} would select, must
     * exist and must pass the request through {@link PaygateAuthenticationFilter}. By this point
     * every chain has already passed the rate-limit, Paygate, and authorization order checks.
     */
    private void validatePaidRouteCoverage(List<SecurityFilterChain> chains) {
      PaygateEndpointRegistry registry = endpointRegistry.get();
      if (registry == null) {
        return;
      }
      for (PaygateEndpointConfig config : registry.getConfigs()) {
        var route = PaidRoute.of(config);
        for (DispatcherType dispatcherType : REQUIRED_DISPATCHER_TYPES) {
          HttpServletRequest probe =
              PaidRouteProbeRequest.create(route.method(), route.samplePath(), dispatcherType);
          SecurityFilterChain chain = firstMatchingChain(chains, probe, route, dispatcherType);
          if (chain == null) {
            throw uncoveredRoute(
                route, dispatcherType, "no Spring Security filter chain matches it");
          }
          if (!reachesPaygateFilter(
              chain.getFilters(), probe, route, dispatcherType, new IdentityHashMap<>())) {
            throw uncoveredRoute(
                route,
                dispatcherType,
                "the first matching filter chain does not apply PaygateAuthenticationFilter to it");
          }
        }
      }
    }

    private static SecurityFilterChain firstMatchingChain(
        List<SecurityFilterChain> chains,
        HttpServletRequest probe,
        PaidRoute route,
        DispatcherType dispatcherType) {
      for (int index = 0; index < chains.size(); index++) {
        SecurityFilterChain chain = chains.get(index);
        boolean matches;
        try {
          matches = chain.matches(probe);
        } catch (RuntimeException e) {
          throw new IllegalStateException(
              "Spring Security filter chain "
                  + describe(chain, index)
                  + " failed while matching paid route "
                  + route
                  + " on "
                  + dispatcherType
                  + " dispatches ("
                  + e.getClass().getSimpleName()
                  + "). Paygate cannot verify that this route is protected, so startup fails "
                  + "closed.",
              e);
        }
        if (matches) {
          return chain;
        }
      }
      return null;
    }

    /**
     * Returns whether the filters selected for the probe include a Paygate authentication filter,
     * following a nested {@link FilterChainProxy} through its own first matching chain.
     */
    private boolean reachesPaygateFilter(
        List<Filter> filters,
        HttpServletRequest probe,
        PaidRoute route,
        DispatcherType dispatcherType,
        Map<Object, Boolean> visited) {
      if (directPaygateFilterIndex(filters) >= 0) {
        return true;
      }
      for (Filter filter : filters) {
        if (visited.put(filter, Boolean.TRUE) != null) {
          continue;
        }
        List<Filter> selected;
        if (filter instanceof FilterChainProxy nestedProxy) {
          SecurityFilterChain nested =
              firstMatchingChain(nestedProxy.getFilterChains(), probe, route, dispatcherType);
          selected = nested == null ? List.of() : nested.getFilters();
        } else {
          selected = List.copyOf(nestedFilters(filter));
        }
        if (!selected.isEmpty()
            && reachesPaygateFilter(selected, probe, route, dispatcherType, visited)) {
          return true;
        }
      }
      return false;
    }

    private static String describe(SecurityFilterChain chain, int index) {
      if (chain instanceof DefaultSecurityFilterChain defaultChain) {
        return "#" + index + " (matcher " + defaultChain.getRequestMatcher() + ")";
      }
      return "#" + index + " (" + chain.getClass().getName() + ")";
    }

    private static IllegalStateException uncoveredRoute(
        PaidRoute route, DispatcherType dispatcherType, String reason) {
      return new IllegalStateException(
          "Paid route "
              + route
              + " is not protected by Spring Security on "
              + dispatcherType
              + " dispatches: "
              + reason
              + ". Requests that no chain protects bypass Paygate. Every paid route must be "
              + "matched first by a chain containing PaygateAuthFailureRateLimitFilter and "
              + "PaygateAuthenticationFilter, with dispatcher coverage for REQUEST, ASYNC, "
              + "FORWARD, and ERROR. Custom-chain acknowledgement cannot waive this minimum "
              + "protection.");
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
    private static Collection<Filter> nestedFilters(Filter filter) {
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

    /** A registered paid route and the concrete request used to probe chain selection. */
    private record PaidRoute(
        String policyMethod, String pattern, String method, String samplePath) {

      static PaidRoute of(PaygateEndpointConfig config) {
        String policyMethod = config.httpMethod().toUpperCase(Locale.ROOT);
        return new PaidRoute(
            policyMethod,
            config.pathPattern(),
            "*".equals(policyMethod) ? "GET" : policyMethod,
            PaidRouteSamplePath.of(config.pathPattern()));
      }

      @Override
      public String toString() {
        return policyMethod + " " + pattern + " (sample request " + method + " " + samplePath + ")";
      }
    }
  }
}
