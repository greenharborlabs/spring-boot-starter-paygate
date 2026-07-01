package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.spring.PaygateProperties;
import com.greenharborlabs.paygate.spring.PaygateSpringSecurityModeCondition;
import jakarta.servlet.Filter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
      if (filterChainProxies.stream().anyMatch(this::containsPaygateAuthenticationFilter)) {
        return;
      }
      throw new IllegalStateException(
          "Paygate servlet enforcement is disabled in Spring Security mode, but no "
              + "PaygateAuthenticationFilter was found in any FilterChainProxy. Add the reference "
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
