package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * An exception thrown by a paid handler after a successful payment decision belongs to the
 * application. In Spring Security mode it must reach the container's error handling ({@code
 * /error}) as its own exception, not be reported as 503 "Lightning unavailable".
 *
 * <p>Spring Security mode with the reference security chain, real Tomcat.
 */
@Tag("integration")
@SpringBootTest(
    classes = {
      SecurityExampleApplication.class,
      SpringSecurityHandlerExceptionAfterPaymentIT.Handlers.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.challenge-binding-secret=",
      "paygate.rate-limit.burst-size=1000"
    })
@DisplayName("Spring Security mode: a paid handler exception reaches /error unchanged")
class SpringSecurityHandlerExceptionAfterPaymentIT {

  private static final String FAILING_PATH = "/api/v1/handler-failure";
  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @BeforeEach
  void reset() {
    Handlers.ERROR_DISPATCHES.clear();
    Handlers.INVOCATIONS.clear();
  }

  @Test
  @DisplayName("the handler exception is dispatched to /error and the client sees 500, not 503")
  void handlerExceptionReachesErrorHandling() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String authorization = obtainL402Authorization(client);

      var response =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl() + FAILING_PATH))
                  .header("Authorization", authorization)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(Handlers.INVOCATIONS).as("payment succeeded and the handler ran").hasSize(1);
      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).doesNotContain("LIGHTNING_UNAVAILABLE");
      assertThat(Handlers.ERROR_DISPATCHES)
          .singleElement()
          .satisfies(
              dispatch -> {
                assertThat(dispatch.statusCode()).isEqualTo(500);
                assertThat(dispatch.requestUri()).isEqualTo(FAILING_PATH);
                assertThat(dispatch.exception())
                    .as("the application's own exception reaches error handling")
                    .hasRootCauseInstanceOf(PaidHandlerFailure.class);
              });
    }
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @SuppressWarnings("unchecked")
  private String obtainL402Authorization(HttpClient client) throws Exception {
    var challenge =
        client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + FAILING_PATH)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(challenge.statusCode()).isEqualTo(402);
    String l402Header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(header -> header.startsWith("L402"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No L402 WWW-Authenticate header in challenge"));
    Matcher matcher = MACAROON_PATTERN.matcher(l402Header);
    assertThat(matcher.find()).as("L402 challenge must include a macaroon").isTrue();
    Map<String, Object> body = MAPPER.readValue(challenge.body(), Map.class);
    return "L402 " + matcher.group(1) + ":" + body.get("test_preimage");
  }

  /** Thrown by the paid handler after payment has been accepted. */
  static final class PaidHandlerFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PaidHandlerFailure() {
      super("paid handler failed after payment");
    }
  }

  record ErrorDispatch(Integer statusCode, String requestUri, Throwable exception) {}

  @TestConfiguration
  static class Handlers {
    static final List<String> INVOCATIONS = new CopyOnWriteArrayList<>();
    static final List<ErrorDispatch> ERROR_DISPATCHES = new CopyOnWriteArrayList<>();

    /**
     * Permits the container's error page, as Spring Boot recommends for any chain that otherwise
     * requires authentication. Without it the reference chain denies the ERROR dispatch and the
     * client sees the entry point's response instead of the application's error. Paygate's startup
     * guard requires the reference filters in every chain, so this chain carries them too.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain errorPageSecurityFilterChain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      return http.securityMatcher("/error")
          .addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class)
          .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .build();
    }

    @Bean
    FilterRegistrationBean<Filter> errorDispatchRecorder() {
      Filter recorder =
          (request, response, chain) -> {
            ERROR_DISPATCHES.add(
                new ErrorDispatch(
                    (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE),
                    (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                    (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION)));
            chain.doFilter(request, response);
          };
      var registration = new FilterRegistrationBean<>(recorder);
      registration.setDispatcherTypes(DispatcherType.ERROR);
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }

    @RestController
    static class FailingController {

      @PaymentRequired(priceSats = 10)
      @GetMapping(FAILING_PATH)
      String paid() {
        INVOCATIONS.add(FAILING_PATH);
        throw new PaidHandlerFailure();
      }
    }
  }
}
