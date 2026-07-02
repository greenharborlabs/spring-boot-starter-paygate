package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

/** Tests nested filter-chain discovery for the Spring Security fail-closed guard. */
class PaygateSpringSecurityFilterChainGuardAutoConfigurationTest {

  @Test
  @DisplayName("auto-configuration creates guard bean")
  void autoConfigurationCreatesGuardBean() {
    var configuration = new PaygateSpringSecurityFilterChainGuardAutoConfiguration();

    assertThatCode(
            () ->
                configuration.paygateSpringSecurityFilterChainGuard(
                    filterChainProxyProvider(), new PaygateProperties()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("custom filter-chain acknowledgement skips proxy inspection")
  void acknowledgementSkipsProxyInspection() {
    var provider = filterChainProxyProvider();
    var properties = new PaygateProperties();
    properties.getSpringSecurity().setCustomFilterChainAcknowledged(true);

    guard(provider, properties).afterSingletonsInstantiated();

    verifyNoInteractions(provider);
  }

  @Test
  @DisplayName("empty proxy provider fails closed")
  void emptyProxyProviderFailsClosed() {
    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("direct PaygateAuthenticationFilter in proxy starts successfully")
  void directPaygateFilterStartsSuccessfully() {
    var proxy = filterChainProxy(paygateFilter());

    assertThatCode(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("nested FilterChainProxy containing PaygateAuthenticationFilter starts successfully")
  void nestedFilterChainProxyStartsSuccessfully() {
    var nestedProxy = filterChainProxy(paygateFilter());
    var outerProxy = filterChainProxy(nestedProxy);

    assertThatCode(
            () ->
                guard(filterChainProxyProvider(outerProxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "wrapper filter getFilters containing PaygateAuthenticationFilter starts successfully")
  void wrapperGetFiltersStartsSuccessfully() {
    var proxy = filterChainProxy(new FilterWrapper(List.of(paygateFilter())));

    assertThatCode(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("recursive wrapper filter does not loop forever")
  void recursiveWrapperDoesNotLoopForever() {
    var recursiveFilter = new MutableFilterWrapper();
    recursiveFilter.setFilters(List.of(recursiveFilter));
    var proxy = filterChainProxy(recursiveFilter);

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("wrapper filter with non-filter collection is ignored")
  void wrapperWithNonFilterCollectionIsIgnored() {
    var proxy = filterChainProxy(new NonFilterCollectionWrapper(List.of("not-a-filter")));

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("plain filter without getFilters is ignored")
  void plainFilterWithoutGetFiltersIsIgnored() {
    var proxy = filterChainProxy(new NoNestedFiltersFilter());

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  private static PaygateSpringSecurityFilterChainGuardAutoConfiguration
          .PaygateSpringSecurityFilterChainGuard
      guard(ObjectProvider<FilterChainProxy> filterChainProxies, PaygateProperties properties) {
    return new PaygateSpringSecurityFilterChainGuardAutoConfiguration
        .PaygateSpringSecurityFilterChainGuard(filterChainProxies, properties);
  }

  private static FilterChainProxy filterChainProxy(Filter... filters) {
    return new FilterChainProxy(new TestSecurityFilterChain(List.of(filters)));
  }

  private static PaygateAuthenticationFilter paygateFilter() {
    return new PaygateAuthenticationFilter(
        authentication -> authentication, List.of(), mock(PaygateEndpointRegistry.class));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<FilterChainProxy> filterChainProxyProvider(
      FilterChainProxy... proxies) {
    ObjectProvider<FilterChainProxy> provider = mock(ObjectProvider.class);
    when(provider.stream()).thenReturn(Stream.of(proxies));
    return provider;
  }

  private record TestSecurityFilterChain(List<Filter> filters) implements SecurityFilterChain {

    @Override
    public boolean matches(HttpServletRequest request) {
      return true;
    }

    @Override
    public List<Filter> getFilters() {
      return filters;
    }
  }

  private static class FilterWrapper extends NoNestedFiltersFilter {

    private final Collection<Filter> filters;

    FilterWrapper(Collection<Filter> filters) {
      this.filters = filters;
    }

    public Collection<Filter> getFilters() {
      return filters;
    }
  }

  private static final class MutableFilterWrapper extends NoNestedFiltersFilter {

    private Collection<Filter> filters = List.of();

    void setFilters(Collection<Filter> filters) {
      this.filters = filters;
    }

    public Collection<Filter> getFilters() {
      return filters;
    }
  }

  private static final class NonFilterCollectionWrapper extends NoNestedFiltersFilter {

    private final Collection<?> filters;

    NonFilterCollectionWrapper(Collection<?> filters) {
      this.filters = filters;
    }

    public Collection<?> getFilters() {
      return filters;
    }
  }

  private static class NoNestedFiltersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      chain.doFilter(request, response);
    }
  }
}
