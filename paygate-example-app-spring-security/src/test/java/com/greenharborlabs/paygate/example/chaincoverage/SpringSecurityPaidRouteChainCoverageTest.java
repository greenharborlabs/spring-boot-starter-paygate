package com.greenharborlabs.paygate.example.chaincoverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Regression tests for the Spring Security filter-chain guard on a real embedded server: every
 * registered paid route must be selected into a chain that applies Paygate, the guard must cope
 * with path-pattern chain matchers, and a paid manual route that no chain covers is never served.
 *
 * <p>Lives outside the example application's package so its test applications are not
 * component-scanned into the example application's own test contexts.
 */
@DisplayName("Spring Security paid-route filter-chain coverage")
class SpringSecurityPaidRouteChainCoverageTest {

  private static final String MANUAL_ROUTE_BODY = "PAID-MANUAL-CONTENT";

  private static ConfigurableApplicationContext start(Class<?> app, String securityMode) {
    return new SpringApplicationBuilder(app)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.port=0",
            "--spring.profiles.active=test",
            "--paygate.enabled=true",
            "--paygate.test-mode=true",
            "--paygate.root-key-store=memory",
            "--paygate.security-mode=" + securityMode,
            "--paygate.service-name=chain-coverage",
            "--paygate.protocols.mpp.enabled=false");
  }

  private static ConfigurableApplicationContext start(Class<?> app) {
    return start(app, "spring-security");
  }

  private static HttpResponse<String> get(ConfigurableApplicationContext context, String path)
      throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  @Test
  @DisplayName("WebSecurityCustomizer.ignoring() of a paid path fails startup")
  void ignoringPaidPathFailsStartup() {
    assertThatThrownBy(() -> start(IgnoringApp.class).close())
        .satisfies(
            failure ->
                assertThat(rootCause(failure))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no PaygateAuthenticationFilter"));
  }

  @Test
  @DisplayName("an /api/** chain plus a catch-all chain starts and enforces payment in both")
  void apiChainPlusCatchAllStartsAndEnforcesPayment() throws Exception {
    try (var context = start(ApiPlusCatchAllApp.class)) {
      assertThat(get(context, "/api/paid").statusCode()).isEqualTo(402);
      assertThat(get(context, "/poc/paid").statusCode()).isEqualTo(402);
    }
  }

  @Test
  @DisplayName("a single securityMatcher(\"/**\") chain starts and enforces payment")
  void singleCatchAllPathPatternChainStartsAndEnforcesPayment() throws Exception {
    try (var context = start(CatchAllPathPatternApp.class)) {
      assertThat(get(context, "/internal/report").statusCode()).isEqualTo(402);
      assertThat(get(context, "/report").statusCode()).isEqualTo(402);
    }
  }

  @Test
  @DisplayName("a negated lambda chain that leaves paid routes uncovered fails startup")
  void negatedLambdaChainLeavingPaidRoutesUncoveredFailsStartup() {
    assertThatThrownBy(() -> start(NegatedLambdaApp.class).close())
        .satisfies(
            failure ->
                assertThat(rootCause(failure))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Paid route GET /internal/")
                    .hasMessageContaining("no Spring Security filter chain matches it"));
  }

  @Test
  @DisplayName("a negated regex chain that leaves a paid manual route uncovered fails startup")
  void negatedRegexChainLeavingManualRouteUncoveredFailsStartup() {
    assertThatThrownBy(() -> start(NegatedRegexApp.class).close())
        .satisfies(
            failure ->
                assertThat(rootCause(failure))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Paid route GET /internal/report")
                    .hasMessageContaining("no Spring Security filter chain matches it"));
  }

  @Test
  @DisplayName("a negated path-pattern chain fails startup naming the route, not with an NPE")
  void negatedPathPatternChainFailsStartupNamingRoute() {
    assertThatThrownBy(() -> start(NegatedPathPatternApp.class).close())
        .satisfies(
            failure ->
                assertThat(rootCause(failure))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Paid route GET /internal/report"));
  }

  @Test
  @DisplayName("a paid manual route registered after startup outside every chain is never served")
  void lateManualRouteOutsideEveryChainIsNeverServed() throws Exception {
    try (var context = start(LateRegistrationApp.class)) {
      context
          .getBean(PaygateEndpointRegistry.class)
          .register(new PaygateEndpointConfig("GET", "/internal/report", 10, 3600, "", "", ""));

      var response = get(context, "/internal/report");

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).doesNotContain(MANUAL_ROUTE_BODY);
      assertThat(get(context, "/report").statusCode()).isEqualTo(402);
    }
  }

  @Test
  @DisplayName("servlet mode protects a manual route that a Spring Security chain excludes")
  void servletModeProtectsManualRouteOutsideSecurityChain() throws Exception {
    try (var context = start(ServletModeApp.class, "servlet")) {
      assertThat(get(context, "/internal/report").statusCode()).isEqualTo(402);
    }
  }

  // --- shared wiring --------------------------------------------------------------------------

  static SecurityFilterChain referenceChain(
      HttpSecurity http,
      PaygateAuthenticationFilter paygateFilter,
      PaygateAuthenticationProvider paygateProvider,
      PaygateAuthenticationEntryPoint entryPoint,
      PaygateAuthFailureRateLimitFilter rateLimitFilter)
      throws Exception {
    http.addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class);
    return http.authenticationProvider(paygateProvider)
        .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
        .build();
  }

  @EnableWebSecurity
  static class AuthenticationManagerConfig {
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
        throws Exception {
      return configuration.getAuthenticationManager();
    }
  }

  @RestController
  static class PaidController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/poc/paid")
    Map<String, String> paid() {
      return Map.of("paid", "content");
    }
  }

  @RestController
  static class PaidApiController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/api/paid")
    Map<String, String> paid() {
      return Map.of("paid", "api");
    }
  }

  @RestController
  static class InternalController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/internal/mvc")
    Map<String, String> paid() {
      return Map.of("paid", "mvc");
    }
  }

  /** Router function routes, both registered as paid during startup. */
  static class ManualPaidRoutes {
    @Bean
    RouterFunction<ServerResponse> manualRoutes() {
      return RouterFunctions.route()
          .GET("/internal/report", _ -> ServerResponse.ok().body(MANUAL_ROUTE_BODY))
          .GET("/report", _ -> ServerResponse.ok().body(MANUAL_ROUTE_BODY))
          .build();
    }

    @Bean
    SmartInitializingSingleton registerManualPaidRoutes(PaygateEndpointRegistry registry) {
      return () -> {
        registry.register(
            new PaygateEndpointConfig("GET", "/internal/report", 10, 3600, "", "", ""));
        registry.register(new PaygateEndpointConfig("GET", "/report", 10, 3600, "", "", ""));
      };
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, PaidController.class})
  static class IgnoringApp {
    @Bean
    WebSecurityCustomizer ignorePaidPath() {
      return web -> web.ignoring().requestMatchers("/poc/**");
    }

    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, PaidController.class, PaidApiController.class})
  static class ApiPlusCatchAllApp {
    @Bean
    @Order(1)
    SecurityFilterChain apiChain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher("/api/**");
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultChain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, ManualPaidRoutes.class})
  static class CatchAllPathPatternApp {
    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher("/**");
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, ManualPaidRoutes.class, InternalController.class})
  static class NegatedLambdaApp {
    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher(request -> !request.getRequestURI().startsWith("/internal/"));
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, ManualPaidRoutes.class})
  static class NegatedRegexApp {
    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher(
          new NegatedRequestMatcher(new RegexRequestMatcher("^/internal/.*", null)));
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, ManualPaidRoutes.class})
  static class NegatedPathPatternApp {
    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher(
          new NegatedRequestMatcher(
              PathPatternRequestMatcher.withDefaults().matcher("/internal/**")));
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  /** Only {@code /report} is paid at startup; {@code /internal/report} is registered later. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(AuthenticationManagerConfig.class)
  static class LateRegistrationApp {
    @Bean
    RouterFunction<ServerResponse> manualRoutes() {
      return RouterFunctions.route()
          .GET("/internal/report", _ -> ServerResponse.ok().body(MANUAL_ROUTE_BODY))
          .GET("/report", _ -> ServerResponse.ok().body(MANUAL_ROUTE_BODY))
          .build();
    }

    @Bean
    SmartInitializingSingleton registerCoveredPaidRoute(PaygateEndpointRegistry registry) {
      return () ->
          registry.register(new PaygateEndpointConfig("GET", "/report", 10, 3600, "", "", ""));
    }

    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint entryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.securityMatcher(request -> !request.getRequestURI().startsWith("/internal/"));
      return referenceChain(http, paygateFilter, paygateProvider, entryPoint, rateLimitFilter);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({AuthenticationManagerConfig.class, ManualPaidRoutes.class})
  static class ServletModeApp {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
      http.securityMatcher(request -> !request.getRequestURI().startsWith("/internal/"));
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }
  }
}
