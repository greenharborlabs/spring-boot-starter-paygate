package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.CanonicalRequestDigest;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.BoundedRequestBody;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.PricingStability;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-Tomcat regression tests for form-encoded request bodies on paid routes.
 *
 * <p>In Spring Security mode, {@code FormContentFilter}, {@code HiddenHttpMethodFilter}, and {@code
 * CsrfFilter} read form bodies through {@code getParameter} before the security chain runs. Paygate
 * must capture the body ahead of them so pricing, the request-body bound, and MPP digest binding
 * see the real bytes, and the protected handler must still receive its form parameters.
 */
@Tag("integration")
@DisplayName("Form body binding on paid routes")
class FormBodyBindingIT {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String FORM = "application/x-www-form-urlencoded";
  private static final String CONSUME_BODY_HEADER = "X-Test-Consume-Body";
  private static final String MPP_SECRET =
      "paygate.protocols.mpp.challenge-binding-secret=form-body-binding-synthetic-secret-0123456789";

  @Nested
  @SpringBootTest(
      classes = NoCsrfApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=form-body",
        "paygate.security-mode=spring-security",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        MPP_SECRET
      })
  @DisplayName("Spring Security mode with FormContentFilter")
  class SpringSecurityMode {

    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private HandlerCalls calls;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      calls.clear();
    }

    @Test
    @DisplayName("form PUT challenge binds the real body; a different body is refused")
    void formPutReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertDifferentBodyRefusedAndSameBodyServed(
            client, port, backend, calls, "PUT", "/v/put-form");
      }
    }

    @Test
    @DisplayName("form POST challenge binds the real body and the handler receives its parameters")
    void formPostReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertDifferentBodyRefusedAndSameBodyServed(
            client, port, backend, calls, "POST", "/v/post-form");
      }
    }

    @Test
    @DisplayName("a chunked form PUT without Content-Length is bound and served")
    void chunkedFormPutIsBound() throws Exception {
      try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
        var challenge = sendChunked(client, port, "PUT", "/v/put-form", null, "units=1");
        assertThat(challenge.statusCode()).isEqualTo(402);
        assertThat(mppChallenge(challenge).get("digest"))
            .isNotEqualTo(emptyBodyDigest("PUT", "/v/put-form"));
        String auth = mppAuthorization(challenge, backend);

        var different = sendChunked(client, port, "PUT", "/v/put-form", auth, "units=1000000");
        assertThat(different.statusCode()).isEqualTo(402);

        var same = sendChunked(client, port, "PUT", "/v/put-form", auth, "units=1");
        assertThat(same.statusCode()).isEqualTo(200);
        assertThat(calls.list()).containsExactly("put-form:1");
      }
    }

    @Test
    @DisplayName("a body-size pricing strategy sees the real form body for L402 and MPP")
    void bodyPricingSeesTheRealFormBody() throws Exception {
      String body = "units=" + "9".repeat(6000);
      try (var client = HttpClient.newHttpClient()) {
        var challenge = send(client, port, "PUT", "/v/form-priced", null, body, Map.of());
        assertThat(challenge.statusCode()).isEqualTo(402);
        assertThat(amount(mppChallenge(challenge))).isEqualTo(String.valueOf(1 + body.length()));
        assertThat(challenge.headers().allValues("WWW-Authenticate"))
            .anySatisfy(
                header ->
                    assertThat(header)
                        .startsWith("L402")
                        .contains("invoice=\"lntb" + (1 + body.length()) + "release"));

        String auth = mppAuthorization(challenge, backend);
        var paid = send(client, port, "PUT", "/v/form-priced", auth, body, Map.of());
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(calls.list()).containsExactly("form-priced:" + body.length());
      }
    }

    @Test
    @DisplayName("an oversized form body is rejected like any other oversized body")
    void oversizedFormBodyIsRejected() throws Exception {
      String huge = "units=" + "7".repeat(20_000);
      try (var client = HttpClient.newHttpClient()) {
        var formChallenge = send(client, port, "PUT", "/v/put-form", null, huge, Map.of());
        assertThat(formChallenge.statusCode()).isEqualTo(400);
        assertThat(formChallenge.body()).contains("REQUEST_BODY_TOO_LARGE");

        var octetStream =
            client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/v/put-form"))
                    .header("Content-Type", "application/octet-stream")
                    .method("PUT", HttpRequest.BodyPublishers.ofString(huge))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(octetStream.statusCode()).isEqualTo(400);
        assertThat(octetStream.body()).contains("REQUEST_BODY_TOO_LARGE");

        var challenge = send(client, port, "PUT", "/v/put-form", null, "units=1", Map.of());
        assertThat(challenge.statusCode()).isEqualTo(402);
        String auth = mppAuthorization(challenge, backend);
        var paidOversized = send(client, port, "PUT", "/v/put-form", auth, huge, Map.of());
        assertThat(paidOversized.statusCode()).isEqualTo(400);
        assertThat(paidOversized.body()).contains("REQUEST_BODY_TOO_LARGE");
        assertThat(calls.list()).isEmpty();
      }
    }

    @Test
    @DisplayName("a body consumed before Paygate can observe it is rejected with 400")
    void bodyConsumedBeforeCaptureIsRejected() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response =
            send(
                client,
                port,
                "POST",
                "/v/post-form",
                null,
                "units=1",
                Map.of(CONSUME_BODY_HEADER, "true"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("REQUEST_BODY_INCOMPLETE");
        assertThat(calls.list()).isEmpty();
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = NoCsrfApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=form-body",
        "paygate.security-mode=spring-security",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        "spring.mvc.hiddenmethod.filter.enabled=true",
        MPP_SECRET
      })
  @DisplayName("Spring Security mode with HiddenHttpMethodFilter")
  class HiddenMethodFilterEnabled {

    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private HandlerCalls calls;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      calls.clear();
    }

    @Test
    @DisplayName("form POST challenge binds the real body; a different body is refused")
    void formPostReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertDifferentBodyRefusedAndSameBodyServed(
            client, port, backend, calls, "POST", "/v/post-form");
      }
    }

    @Test
    @DisplayName("a _method override onto a paid PUT route fails closed instead of binding no body")
    void methodOverrideOntoPaidRouteFailsClosed() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response =
            send(client, port, "POST", "/v/put-form", null, "_method=PUT&units=1", Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("REQUEST_BODY_INCOMPLETE");
        assertThat(calls.list()).isEmpty();
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = CookieCsrfApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=form-body",
        "paygate.security-mode=spring-security",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        MPP_SECRET
      })
  @DisplayName("Spring Security mode with a CSRF token in the form body")
  class CsrfTokenInBody {

    private static final Map<String, String> CSRF_COOKIE =
        Map.of("Cookie", "XSRF-TOKEN=form-body-token");

    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private HandlerCalls calls;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      calls.clear();
    }

    @Test
    @DisplayName("form POST challenge binds the real body; a different body is refused")
    void formPostReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        String body = "_csrf=form-body-token&units=1";
        var challenge = send(client, port, "POST", "/v/post-form", null, body, CSRF_COOKIE);
        assertThat(challenge.statusCode()).isEqualTo(402);
        assertThat(mppChallenge(challenge).get("digest"))
            .isNotEqualTo(emptyBodyDigest("POST", "/v/post-form"));
        String auth = mppAuthorization(challenge, backend);

        var different =
            send(
                client,
                port,
                "POST",
                "/v/post-form",
                auth,
                "_csrf=form-body-token&units=1000000",
                CSRF_COOKIE);
        assertThat(different.statusCode()).isEqualTo(402);
        assertThat(calls.list()).isEmpty();

        var same = send(client, port, "POST", "/v/post-form", auth, body, CSRF_COOKIE);
        assertThat(same.statusCode()).isEqualTo(200);
        assertThat(calls.list()).containsExactly("post-form:1");
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = ServletModeApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=form-body",
        "paygate.security-mode=servlet",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        MPP_SECRET
      })
  @DisplayName("Servlet mode")
  class ServletMode {

    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private HandlerCalls calls;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      calls.clear();
    }

    @Test
    @DisplayName("form PUT challenge binds the real body; a different body is refused")
    void formPutReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertDifferentBodyRefusedAndSameBodyServed(
            client, port, backend, calls, "PUT", "/v/put-form");
      }
    }

    @Test
    @DisplayName("form POST challenge binds the real body and the handler receives its parameters")
    void formPostReplayWithDifferentBodyIsRefused() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertDifferentBodyRefusedAndSameBodyServed(
            client, port, backend, calls, "POST", "/v/post-form");
      }
    }

    @Test
    @DisplayName("a body consumed before Paygate can observe it is rejected with 400")
    void bodyConsumedBeforeCaptureIsRejected() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response =
            send(
                client,
                port,
                "POST",
                "/v/post-form",
                null,
                "units=1",
                Map.of(CONSUME_BODY_HEADER, "true"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("REQUEST_BODY_INCOMPLETE");
        assertThat(calls.list()).isEmpty();
      }
    }
  }

  // ------------------------------------------------------------------------------------------

  private static void assertDifferentBodyRefusedAndSameBodyServed(
      HttpClient client,
      int port,
      DefenseInDepthSecurityIT.BackendControl backend,
      HandlerCalls calls,
      String method,
      String path)
      throws Exception {
    var challenge = send(client, port, method, path, null, "units=1", Map.of());
    assertThat(challenge.statusCode()).isEqualTo(402);
    assertThat(mppChallenge(challenge).get("digest")).isNotEqualTo(emptyBodyDigest(method, path));
    String auth = mppAuthorization(challenge, backend);

    var different = send(client, port, method, path, auth, "units=1000000", Map.of());
    assertThat(different.statusCode()).isEqualTo(402);
    assertThat(calls.list()).isEmpty();

    var same = send(client, port, method, path, auth, "units=1", Map.of());
    assertThat(same.statusCode()).isEqualTo(200);
    assertThat(calls.list()).containsExactly(path.substring("/v/".length()) + ":1");
  }

  private static String emptyBodyDigest(String method, String path) {
    return CanonicalRequestDigest.create(method, path, false, null, new byte[0]);
  }

  private static HttpResponse<String> send(
      HttpClient client,
      int port,
      String method,
      String path,
      String authorization,
      String formBody,
      Map<String, String> headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", FORM);
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    headers.forEach(builder::header);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> sendChunked(
      HttpClient client, int port, String method, String path, String authorization, String body)
      throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .method(
                method,
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .header("Content-Type", FORM);
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> mppChallenge(HttpResponse<String> response) {
    Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
    var protocols = (Map<String, Object>) body.get("protocols");
    var payment = (Map<String, Object>) protocols.get("Payment");
    var challenge = new TreeMap<String, String>();
    payment.forEach((key, value) -> challenge.put(key, String.valueOf(value)));
    return challenge;
  }

  @SuppressWarnings("unchecked")
  private static String amount(Map<String, String> challenge) {
    byte[] json = Base64.getUrlDecoder().decode(challenge.get("request"));
    Map<String, Object> request = MAPPER.readValue(json, Map.class);
    return String.valueOf(request.get("amount"));
  }

  private static String mppAuthorization(
      HttpResponse<String> challengeResponse, DefenseInDepthSecurityIT.BackendControl backend) {
    var credential = new LinkedHashMap<String, Object>();
    credential.put("challenge", new TreeMap<String, Object>(mppChallenge(challengeResponse)));
    credential.put("payload", Map.of("preimage", backend.preimageHex()));
    byte[] json = MAPPER.writeValueAsBytes(credential);
    return "Payment " + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
  }

  // ------------------------------------------------------------------------------------------

  static final class HandlerCalls {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    void add(String call) {
      calls.add(call);
    }

    List<String> list() {
      return List.copyOf(calls);
    }

    void clear() {
      calls.clear();
    }
  }

  @RestController
  static class FormController {
    private final HandlerCalls calls;

    FormController(HandlerCalls calls) {
      this.calls = calls;
    }

    @PaymentRequired(priceSats = 1)
    @PutMapping("/v/put-form")
    Map<String, String> putForm(@RequestParam(name = "units", required = false) String units) {
      calls.add("put-form:" + units);
      return Map.of("units", String.valueOf(units));
    }

    @PaymentRequired(priceSats = 1)
    @PostMapping("/v/post-form")
    Map<String, String> postForm(@RequestParam(name = "units", required = false) String units) {
      calls.add("post-form:" + units);
      return Map.of("units", String.valueOf(units));
    }

    @PaymentRequired(
        priceSats = 1,
        pricingStrategy = "formBodyLengthPricer",
        pricingStability = PricingStability.REQUEST_DEPENDENT)
    @PutMapping("/v/form-priced")
    Map<String, String> formPriced(@RequestParam(name = "units", required = false) String units) {
      calls.add("form-priced:" + (units == null ? "null" : String.valueOf(units.length() + 6)));
      return Map.of("ok", "true");
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SharedConfiguration {
    @Bean
    HandlerCalls handlerCalls() {
      return new HandlerCalls();
    }

    @Bean
    DefenseInDepthSecurityIT.BackendControl backendControl() {
      return new DefenseInDepthSecurityIT.BackendControl();
    }

    @Bean
    LightningBackend lightningBackend(DefenseInDepthSecurityIT.BackendControl control) {
      return new DefenseInDepthSecurityIT.ControlledLightningBackend(control);
    }

    @Bean("formBodyLengthPricer")
    PaygatePricingStrategy formBodyLengthPricer() {
      return (request, defaultPrice) -> {
        if (!(request instanceof BoundedRequestBody body)) {
          throw new IllegalStateException("bounded body required");
        }
        return defaultPrice + body.observedLength();
      };
    }

    @Bean
    FormController formController(HandlerCalls calls) {
      return new FormController(calls);
    }

    /**
     * Simulates a component ordered ahead of Paygate that parses the form body, so Paygate's
     * capture observes fewer bytes than the declared Content-Length.
     */
    @Bean
    FilterRegistrationBean<Filter> earlyBodyConsumer() {
      Filter filter =
          (request, response, chain) -> {
            if (request instanceof HttpServletRequest http
                && http.getHeader(CONSUME_BODY_HEADER) != null) {
              http.getParameter("units");
            }
            chain.doFilter(request, response);
          };
      var registration = new FilterRegistrationBean<>(filter);
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class PaygateChainSupport {
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config)
        throws Exception {
      return config.getAuthenticationManager();
    }

    /** paygate-spring-security is only on this source set's runtime classpath. */
    static HttpSecurity paygate(HttpSecurity http, ApplicationContext context) throws Exception {
      String pkg = "com.greenharborlabs.paygate.spring.security.";
      var paygateFilter =
          (Filter) context.getBean(Class.forName(pkg + "PaygateAuthenticationFilter"));
      var provider =
          (AuthenticationProvider)
              context.getBean(Class.forName(pkg + "PaygateAuthenticationProvider"));
      var entryPoint =
          (AuthenticationEntryPoint)
              context.getBean(Class.forName(pkg + "PaygateAuthenticationEntryPoint"));
      var rateLimitFilter =
          (Filter) context.getBean(Class.forName(pkg + "PaygateAuthFailureRateLimitFilter"));
      return http.addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class)
          .authenticationProvider(provider)
          .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
          .authorizeHttpRequests(
              authorize ->
                  authorize.requestMatchers("/v/**").hasRole("PAYMENT").anyRequest().permitAll())
          .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
          .sessionManagement(
              session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class NoCsrfChain {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApplicationContext context)
        throws Exception {
      return PaygateChainSupport.paygate(http, context)
          .csrf(AbstractHttpConfigurer::disable)
          .build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CookieCsrfChain {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApplicationContext context)
        throws Exception {
      return PaygateChainSupport.paygate(http, context)
          .csrf(
              csrf ->
                  csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                      .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
          .build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class PermitAllChain {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf(AbstractHttpConfigurer::disable)
          .build();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedConfiguration.class, PaygateChainSupport.class, NoCsrfChain.class})
  static class NoCsrfApplication {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedConfiguration.class, PaygateChainSupport.class, CookieCsrfChain.class})
  static class CookieCsrfApplication {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedConfiguration.class, PermitAllChain.class})
  static class ServletModeApplication {}
}
