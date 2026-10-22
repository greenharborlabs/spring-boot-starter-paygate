package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for SR-01/SR-03 (2026-10-18 security review) in Spring Security mode. The application
 * relaxes {@link StrictHttpFirewall} and leaves paid routes to Paygate ({@code permitAll}), so the
 * only control is Paygate itself. Ambiguous paths must be rejected rather than reach paid handlers.
 *
 * <p>Uses real Tomcat and raw sockets; MockMvc bypasses container URI handling.
 */
@Tag("integration")
@SpringBootTest(
    classes = RelaxedFirewallPathIdentityIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=relaxed-firewall",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=100000",
      "paygate.rate-limit.requests-per-second=100000",
      "paygate.rate-limit.aggregate.burst-size=100000",
      "paygate.rate-limit.aggregate.requests-per-second=100000"
    })
@DisplayName("Spring Security mode with a relaxed firewall rejects ambiguous paths")
class RelaxedFirewallPathIdentityIT {

  private static final Pattern L402_MACAROON = Pattern.compile("macaroon=\"([^\"]+)\"");

  @LocalServerPort private int port;

  @BeforeEach
  void reset() {
    App.PAID_INVOCATIONS.set(0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/fn/premium/%2e%2e",
        "/fn/premium/%252e%252e",
        "/fn/premium/%25252e%25252e",
        "/fn/premium/%2e%2e;x=1",
        "/fn/premium/%252e",
        "/fn/premium/x;tier=premium",
        "/fn/premiumall/a/%252e%252e/%252e%252e/free",
        "/mvc/files/private/%252e%252e/public/report"
      })
  @DisplayName("rejects with Paygate's 400 before any paid handler runs")
  void rejectsAmbiguousPaths(String rawPath) throws Exception {
    var response = get(rawPath, null);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.raw()).contains("MALFORMED_URI");
    assertThat(App.PAID_INVOCATIONS).hasValue(0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/fn/premium/..", "/fn/premium/.", "/fn/premium/..;x=1"})
  @DisplayName("literal dot segments are still rejected with 400")
  void rejectsLiteralDotSegments(String rawPath) throws Exception {
    assertThat(get(rawPath, null).status()).isEqualTo(400);
    assertThat(App.PAID_INVOCATIONS).hasValue(0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/fn/premium/x", "/fn/premium/x/", "/mvc/files/public/report"})
  @DisplayName("challenges paid routes with 402")
  void challengesPaidRoutes(String rawPath) throws Exception {
    assertThat(get(rawPath, null).status()).isEqualTo(402);
    assertThat(App.PAID_INVOCATIONS).hasValue(0);
  }

  @Test
  @DisplayName("serves a paid RouterFunction route with a valid credential")
  void servesPaidRouterFunctionWithCredential() throws Exception {
    String authorization = obtainL402Authorization("/fn/premium/x");

    var response = get("/fn/premium/x", authorization);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.raw()).contains("PAID-FN name=x");
    assertThat(App.PAID_INVOCATIONS).hasValue(1);
  }

  @Test
  @DisplayName("serves a paid annotated route with a valid credential")
  void servesPaidAnnotatedRouteWithCredential() throws Exception {
    String authorization = obtainL402Authorization("/mvc/files/public/report");

    var response = get("/mvc/files/public/report", authorization);

    assertThat(response.status()).isEqualTo(200);
    assertThat(App.PAID_INVOCATIONS).hasValue(1);
  }

  private record RawResponse(int status, String raw) {}

  private RawResponse get(String rawPath, String authorization) throws IOException {
    var request = new StringBuilder("GET ").append(rawPath).append(" HTTP/1.1\r\n");
    request.append("Host: localhost\r\nConnection: close\r\n");
    if (authorization != null) {
      request.append("Authorization: ").append(authorization).append("\r\n");
    }
    request.append("\r\n");
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      var buffer = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(buffer);
      String raw = buffer.toString(StandardCharsets.ISO_8859_1);
      return new RawResponse(Integer.parseInt(raw.substring(9, 12)), raw);
    }
  }

  @SuppressWarnings("unchecked")
  private String obtainL402Authorization(String rawPath) throws IOException {
    var challenge = get(rawPath, null);
    assertThat(challenge.status()).isEqualTo(402);
    var macaroon = L402_MACAROON.matcher(challenge.raw());
    assertThat(macaroon.find()).isTrue();
    String json = challenge.raw().substring(challenge.raw().indexOf('{'));
    json = json.substring(0, json.lastIndexOf('}') + 1);
    Map<String, Object> body = JsonMapper.builder().build().readValue(json, Map.class);
    return "L402 " + macaroon.group(1) + ":" + body.get("test_preimage");
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableWebSecurity
  @Import(App.PaidController.class)
  static class App {
    static final AtomicInteger PAID_INVOCATIONS = new AtomicInteger();

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
        throws Exception {
      return configuration.getAuthenticationManager();
    }

    @Bean
    WebSecurityCustomizer relaxedFirewall() {
      var firewall = new StrictHttpFirewall();
      firewall.setAllowUrlEncodedPercent(true);
      firewall.setAllowUrlEncodedPeriod(true);
      firewall.setAllowSemicolon(true);
      firewall.setAllowUrlEncodedDoubleSlash(true);
      return web -> web.httpFirewall(firewall);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint paygateEntryPoint,
        ObjectProvider<PaygateAuthFailureRateLimitFilter> rateLimitFilter)
        throws Exception {
      rateLimitFilter.ifAvailable(
          filter -> http.addFilterBefore(filter, BasicAuthenticationFilter.class));
      return http.authenticationProvider(paygateProvider)
          .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
          // Paid routes are left to Paygate; the application adds no role requirement.
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .exceptionHandling(ex -> ex.authenticationEntryPoint(paygateEntryPoint))
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .build();
    }

    @Bean
    Object manualPaidRoutes(PaygateEndpointRegistry registry) {
      registry.register(
          new PaygateEndpointConfig("GET", "/fn/premium/{name}", 10, 3600, "fn", "", ""));
      registry.register(
          new PaygateEndpointConfig("GET", "/fn/premiumall/**", 10, 3600, "fnall", "", ""));
      return new Object();
    }

    @Bean
    RouterFunction<ServerResponse> paidRoutes() {
      return RouterFunctions.route()
          .GET(
              "/fn/premium/{name}",
              request -> {
                PAID_INVOCATIONS.incrementAndGet();
                return ServerResponse.ok().body("PAID-FN name=" + request.pathVariable("name"));
              })
          .GET(
              "/fn/premiumall/**",
              request -> {
                PAID_INVOCATIONS.incrementAndGet();
                return ServerResponse.ok().body("PAID-FNALL");
              })
          .build();
    }

    @RestController
    static class PaidController {
      @PaymentRequired(priceSats = 10)
      @GetMapping("/mvc/files/{*rest}")
      String file(@PathVariable("rest") String rest) {
        PAID_INVOCATIONS.incrementAndGet();
        return "PAID-MVC rest=" + rest;
      }
    }
  }
}
