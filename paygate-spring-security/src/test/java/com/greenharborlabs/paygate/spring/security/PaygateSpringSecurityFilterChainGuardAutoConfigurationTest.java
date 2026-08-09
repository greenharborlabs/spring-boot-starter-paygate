package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Tests nested filter-chain discovery for the Spring Security fail-closed guard. */
class PaygateSpringSecurityFilterChainGuardAutoConfigurationTest {

  private final WebApplicationContextRunner springSecurityContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PaygateAutoConfiguration.class,
                  WebMvcAutoConfiguration.class,
                  PaygateSecurityAutoConfiguration.class,
                  PaygateSpringSecurityFilterChainGuardAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.security-mode=spring-security",
              "paygate.spring-security.custom-filter-chain-acknowledged=true")
          .withBean(
              SecurityModeSpringSecurityTest.StubLightningBackend.class,
              SecurityModeSpringSecurityTest.StubLightningBackend::new)
          .withBean(AuthenticationManager.class, () -> authentication -> authentication);

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
  @DisplayName("every effective security chain must contain the Paygate filter")
  void multipleEffectiveChainsRequirePaygateFilterInEachChain() {
    var paidFilter = paygateFilter();
    var paidChain = new TestSecurityFilterChain(requestMatcher("/paid"), List.of(paidFilter));
    var otherChain =
        new TestSecurityFilterChain(requestMatcher("/other"), List.of(new NoNestedFiltersFilter()));
    var proxy = new FilterChainProxy(List.of(paidChain, otherChain));

    assertThat(paidChain.matches(request("/paid"))).isTrue();
    assertThat(otherChain.matches(request("/paid"))).isFalse();
    assertThat(otherChain.matches(request("/other"))).isTrue();
    assertThat(paidChain.matches(request("/other"))).isFalse();

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName(
      "paid permitAll chain without Paygate filter fails even when another chain has the filter")
  void permitAllDoesNotWaivePaymentForPaidChain() {
    AuthorizationManager<HttpServletRequest> permitAll = (_, _) -> new AuthorizationDecision(true);
    var permitAllAuthorizationFilter = new AuthorizationFilter(permitAll);
    var paidFilter = paygateFilter();
    var permitAllChain =
        new TestSecurityFilterChain(
            requestMatcher("/permit-all"), List.of(permitAllAuthorizationFilter));
    var paidChain = new TestSecurityFilterChain(requestMatcher("/paid"), List.of(paidFilter));
    var proxy = new FilterChainProxy(List.of(permitAllChain, paidChain));

    assertThat(permitAllChain.matches(request("/permit-all"))).isTrue();
    assertThat(paidChain.matches(request("/permit-all"))).isFalse();
    assertThat(paidChain.matches(request("/paid"))).isTrue();

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("a paid chain with authorization but no Paygate filter fails independently")
  void authorizationFilterDoesNotCountAsPaygateFilter() {
    AuthorizationManager<HttpServletRequest> permitAll = (_, _) -> new AuthorizationDecision(true);
    var proxy = filterChainProxy(new AuthorizationFilter(permitAll));

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("Paygate filter must run before downstream authorization in a permitAll chain")
  void paygateFilterMustPrecedePermitAllAuthorizationFilter() {
    AuthorizationManager<HttpServletRequest> permitAll = (_, _) -> new AuthorizationDecision(true);
    var authorizationFilter = new AuthorizationFilter(permitAll);
    var proxy = filterChainProxy(authorizationFilter, paygateFilter());

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PaygateAuthenticationFilter");
  }

  @Test
  @DisplayName("a Paygate filter that excludes ERROR dispatches fails closed at startup")
  void errorDispatcherExclusionFailsClosed() {
    var paygateFilter = paygateFilter();
    var paygateChainReached = new AtomicBoolean();
    Filter downstreamPaygateChainFilter =
        (request, response, chain) -> paygateChainReached.set(true);
    var proxy =
        new FilterChainProxy(
            new TestSecurityFilterChain(
                request -> request.getDispatcherType() != DispatcherType.ERROR,
                List.of(paygateFilter, downstreamPaygateChainFilter)));
    var errorRequest = request("/paid");
    errorRequest.setDispatcherType(DispatcherType.ERROR);

    assertThatCode(() -> proxy.doFilter(errorRequest, mock(), mock())).doesNotThrowAnyException();
    assertThat(paygateChainReached).isFalse();

    assertThatThrownBy(
            () ->
                guard(filterChainProxyProvider(proxy), new PaygateProperties())
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dispatcher");
  }

  @Test
  @DisplayName(
      "Spring Security Paygate filter is disabled for direct servlet-container registration")
  void paygateAuthenticationFilterContainerRegistrationIsDisabled() {
    springSecurityContextRunner.run(
        context -> {
          assertThatCode(() -> context.getBean("paygateAuthenticationFilterDisabledRegistration"))
              .doesNotThrowAnyException();
          var registration =
              context.getBean(
                  "paygateAuthenticationFilterDisabledRegistration", FilterRegistrationBean.class);
          var paygateFilter = context.getBean(PaygateAuthenticationFilter.class);
          assertThat(registration.isEnabled()).isFalse();
          assertThat(registration.getFilter()).isSameAs(paygateFilter);
        });
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

  private static MockHttpServletRequest request(String path) {
    return new MockHttpServletRequest("GET", path);
  }

  private static RequestMatcher requestMatcher(String path) {
    return request -> path.equals(request.getRequestURI());
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

  private record TestSecurityFilterChain(RequestMatcher requestMatcher, List<Filter> filters)
      implements SecurityFilterChain {

    TestSecurityFilterChain(List<Filter> filters) {
      this(request -> true, filters);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
      return requestMatcher.matches(request);
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
