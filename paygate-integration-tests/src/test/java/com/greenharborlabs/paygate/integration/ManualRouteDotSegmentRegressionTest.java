package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Regression for SR-01 (2026-10-18 security review): paid routes registered through {@link
 * PaygateEndpointRegistry#register} and served by a {@code RouterFunction} were reachable unpaid
 * through dot segments, nested encodings and path parameters, because Paygate matched an
 * over-decoded, dot-resolved path while Spring dispatched the once-decoded raw path.
 *
 * <p>Uses real Tomcat and raw sockets; MockMvc bypasses container URI handling.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, ManualRouteDotSegmentRegressionTest.Routes.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=100000",
      "paygate.rate-limit.requests-per-second=100000",
      "paygate.rate-limit.aggregate.burst-size=100000",
      "paygate.rate-limit.aggregate.requests-per-second=100000"
    })
@DisplayName("Manual paid routes on non-MVC handlers reject ambiguous paths (SR-01)")
class ManualRouteDotSegmentRegressionTest {

  private static final String BYPASS_HEADER = "X-Test-Bypass-Paygate-Filter";

  @LocalServerPort private int port;

  @BeforeEach
  void resetCounters() {
    Routes.PAID_INVOCATIONS.set(0);
    Routes.FREE_INVOCATIONS.set(0);
    Routes.MVC_PAID_INVOCATIONS.set(0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/fn-paid/premium/..",
        "/fn-paid/premium/%2e%2e",
        "/fn-paid/premium/%252e%252e",
        "/fn-paid/premium/%25252e%25252e",
        "/fn-paid/premium/..;x=1",
        "/fn-paid/premium/%2e%2e;x=1",
        "/fn-paid/premium/.",
        "/fn-paid/premium/%252e",
        "/fn-paid/premium/x;tier=premium",
        "/fn-paid//premium/x",
        "/fn-paid/premiumall/..",
        "/fn-paid/premiumall/%2e%2e",
        "/fn-paid/premiumall/%252e%252e",
        "/fn-paid/premiumall/%25252e%25252e",
        "/fn-paid/premiumall/..;x=1",
        "/fn-paid/premiumall/a//b",
        "/fn-paid/premiumall/a/%252e%252e/%252e%252e/fn-free/x"
      })
  @DisplayName("rejects with 400 before the paid RouterFunction runs")
  void rejectsAmbiguousPathsBeforePaidHandler(String rawPath) throws Exception {
    var response = RawHttp.get(port, rawPath);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).contains("MALFORMED_URI");
    assertThat(Routes.PAID_INVOCATIONS).hasValue(0);
  }

  @Test
  @DisplayName("challenges the canonical paid route with 402")
  void challengesCanonicalPaidRoute() throws Exception {
    var response = RawHttp.get(port, "/fn-paid/premium/x");

    assertThat(response.status()).isEqualTo(402);
    assertThat(response.header("WWW-Authenticate")).startsWith("L402");
    assertThat(Routes.PAID_INVOCATIONS).hasValue(0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/fn-paid/premium/x/", "/fn-paid/premiumall/a/b/"})
  @DisplayName("still challenges a trailing-slash variant of a paid route with 402")
  void challengesTrailingSlashVariant(String rawPath) throws Exception {
    var response = RawHttp.get(port, rawPath);

    assertThat(response.status()).isEqualTo(402);
    assertThat(Routes.PAID_INVOCATIONS).hasValue(0);
  }

  @Test
  @DisplayName("serves the paid route with a valid credential")
  void servesPaidRouteWithValidCredential() throws Exception {
    var credential = RawHttp.obtainL402Credential(port, "/fn-paid/premium/x");

    var response =
        RawHttp.get(
            port, "/fn-paid/premium/x", Map.of("Authorization", credential.authorization()));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("PAID-FN name=x");
    assertThat(Routes.PAID_INVOCATIONS).hasValue(1);
  }

  @Test
  @DisplayName("serves a free route")
  void servesFreeRoute() throws Exception {
    var response = RawHttp.get(port, "/fn-free/x");

    assertThat(response.status()).isEqualTo(200);
    assertThat(Routes.FREE_INVOCATIONS).hasValue(1);
  }

  @Nested
  @DisplayName("annotated MVC handlers")
  class AnnotatedMvc {

    @Test
    @DisplayName("are still challenged with 402 and served with a valid credential")
    void challengedAndServed() throws Exception {
      assertThat(RawHttp.get(port, "/mvc-paid/x").status()).isEqualTo(402);

      var credential = RawHttp.obtainL402Credential(port, "/mvc-paid/x");
      var paid =
          RawHttp.get(port, "/mvc-paid/x", Map.of("Authorization", credential.authorization()));

      assertThat(paid.status()).isEqualTo(200);
      assertThat(paid.body()).contains("PAID-MVC name=x");
      assertThat(Routes.MVC_PAID_INVOCATIONS).hasValue(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/mvc-paid/..", "/mvc-paid/%252e%252e", "/mvc-paid/x;y=1"})
    @DisplayName("reject ambiguous paths with 400")
    void rejectAmbiguousPaths(String rawPath) throws Exception {
      assertThat(RawHttp.get(port, rawPath).status()).isEqualTo(400);
      assertThat(Routes.MVC_PAID_INVOCATIONS).hasValue(0);
    }
  }

  @Nested
  @DisplayName("when the Paygate filter is bypassed")
  class FilterBypassed {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "/fn-paid/premium/x",
          "/fn-paid/premium/x/",
          "/fn-paid/premium/%252e%252e",
          "/fn-paid/premiumall/..;x=1"
        })
    @DisplayName("the handler interceptor fails closed with 500 for a paid manual route")
    void interceptorFailsClosed(String rawPath) throws Exception {
      var response = RawHttp.get(port, rawPath, Map.of(BYPASS_HEADER, "1"));

      assertThat(response.status()).isEqualTo(500);
      assertThat(Routes.PAID_INVOCATIONS).hasValue(0);
    }

    @Test
    @DisplayName("the handler interceptor allows a free route")
    void interceptorAllowsFreeRoute() throws Exception {
      var response = RawHttp.get(port, "/fn-free/x", Map.of(BYPASS_HEADER, "1"));

      assertThat(response.status()).isEqualTo(200);
      assertThat(Routes.FREE_INVOCATIONS).hasValue(1);
    }
  }

  @TestConfiguration
  static class Routes {
    static final AtomicInteger PAID_INVOCATIONS = new AtomicInteger();
    static final AtomicInteger FREE_INVOCATIONS = new AtomicInteger();
    static final AtomicInteger MVC_PAID_INVOCATIONS = new AtomicInteger();

    @Bean
    Object manualPaidRoutes(PaygateEndpointRegistry registry) {
      registry.register(
          new PaygateEndpointConfig("GET", "/fn-paid/premium/{name}", 10, 3600, "fn", "", ""));
      registry.register(
          new PaygateEndpointConfig("GET", "/fn-paid/premiumall/**", 10, 3600, "fnall", "", ""));
      return new Object();
    }

    @Bean
    RouterFunction<ServerResponse> paidAndFreeRoutes() {
      return RouterFunctions.route()
          .GET(
              "/fn-paid/premium/{name}",
              request -> {
                PAID_INVOCATIONS.incrementAndGet();
                return ServerResponse.ok().body("PAID-FN name=" + request.pathVariable("name"));
              })
          .GET(
              "/fn-paid/premiumall/**",
              request -> {
                PAID_INVOCATIONS.incrementAndGet();
                return ServerResponse.ok().body("PAID-FNALL path=" + request.path());
              })
          .GET(
              "/fn-free/**",
              request -> {
                FREE_INVOCATIONS.incrementAndGet();
                return ServerResponse.ok().body("FREE");
              })
          .build();
    }

    /**
     * Simulates a deployment in which the Paygate servlet filter does not run for a request, for
     * example because of a narrower filter mapping: the request goes straight to MVC.
     */
    @Bean
    FilterRegistrationBean<Filter> paygateFilterBypass(
        ObjectProvider<DispatcherServlet> dispatcherServlet) {
      Filter bypass =
          (request, response, chain) -> {
            if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse
                && httpRequest.getHeader(BYPASS_HEADER) != null) {
              dispatcherServlet.getObject().service(request, response);
              return;
            }
            chain.doFilter(request, response);
          };
      var registration = new FilterRegistrationBean<>(bypass);
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }

    @RestController
    static class AnnotatedPaidController {
      @PaymentRequired(priceSats = 10)
      @GetMapping("/mvc-paid/{name}")
      String paid(@PathVariable("name") String name) {
        MVC_PAID_INVOCATIONS.incrementAndGet();
        return "PAID-MVC name=" + name;
      }
    }
  }
}
