package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for SR-2026-10-18-20: in servlet mode the per-client rate-limit token is consumed
 * before request-body capture and the named pricing strategy run, and each request consumes exactly
 * one admission token on both the challenge and the presented-credential paths.
 *
 * <p>A counting {@link PaygateRateLimiter} replaces the auto-configured limiter so the test
 * measures token consumption directly.
 */
@Tag("integration")
@SpringBootTest(
    classes = RateLimitBeforePricingRegressionTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=ratelimit-order",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.aggregate.burst-size=100000",
      "paygate.rate-limit.aggregate.requests-per-second=100000"
    })
@ActiveProfiles("test")
@DisplayName("Rate limit before body capture and pricing (SR-20)")
class RateLimitBeforePricingRegressionTest {

  private static final Pattern L402_MACAROON = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final String BODY = "x".repeat(8000);

  static final AtomicInteger PRICING_CALLS = new AtomicInteger();
  static final CountingRateLimiter LIMITER = new CountingRateLimiter();

  @LocalServerPort int port;

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(PricedController.class)
  static class App {

    @Bean
    PaygateRateLimiter countingRateLimiter() {
      return LIMITER;
    }

    @Bean("expensivePricing")
    PaygatePricingStrategy expensivePricing() {
      return (request, defaultPrice) -> {
        PRICING_CALLS.incrementAndGet();
        return defaultPrice;
      };
    }
  }

  @RestController
  static class PricedController {
    @PaymentRequired(priceSats = 5, pricingStrategy = "expensivePricing")
    @PostMapping("/priced")
    String priced() {
      return "PAID-PRICED";
    }
  }

  @BeforeEach
  void reset() {
    PRICING_CALLS.set(0);
    LIMITER.reset(Integer.MAX_VALUE);
  }

  @Test
  @DisplayName("the pricing strategy runs only for requests the rate limiter admitted")
  void pricingStrategyRunsOnlyForAdmittedRequests() throws Exception {
    LIMITER.reset(1);
    var statuses = new TreeMap<Integer, Integer>();
    try (var client = HttpClient.newHttpClient()) {
      for (int i = 0; i < 25; i++) {
        statuses.merge(post(client, Map.of()).statusCode(), 1, Integer::sum);
      }
    }

    assertThat(statuses).containsExactlyEntriesOf(new TreeMap<>(Map.of(402, 1, 429, 24)));
    assertThat(PRICING_CALLS).hasValue(1);
    assertThat(LIMITER.attempts()).isEqualTo(25);
  }

  @Test
  @DisplayName("a rate-limited presented credential does not run the pricing strategy")
  void rateLimitedCredentialDoesNotRunPricing() throws Exception {
    LIMITER.reset(0);
    try (var client = HttpClient.newHttpClient()) {
      var response = post(client, Map.of("Authorization", "L402 bogus:00"));

      assertThat(response.statusCode()).isEqualTo(429);
    }
    assertThat(PRICING_CALLS).hasValue(0);
    assertThat(LIMITER.attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("a challenge request consumes exactly one rate-limit token")
  void challengeConsumesOneToken() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      assertThat(post(client, Map.of()).statusCode()).isEqualTo(402);
    }

    assertThat(LIMITER.attempts()).isEqualTo(1);
    assertThat(PRICING_CALLS).hasValue(1);
  }

  @Test
  @DisplayName("a valid presented credential consumes exactly one rate-limit token")
  void validCredentialConsumesOneToken() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String authorization = obtainAuthorization(client);
      LIMITER.reset(Integer.MAX_VALUE);
      PRICING_CALLS.set(0);

      var paid = post(client, Map.of("Authorization", authorization));

      assertThat(paid.statusCode()).isEqualTo(200);
      assertThat(paid.body()).isEqualTo("PAID-PRICED");
    }
    assertThat(LIMITER.attempts()).isEqualTo(1);
    assertThat(PRICING_CALLS).hasValue(1);
  }

  @Test
  @DisplayName("with one token left a valid credential is admitted once and then rate-limited")
  void budgetOfOneAdmitsExactlyOneRequest() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String authorization = obtainAuthorization(client);
      LIMITER.reset(1);

      assertThat(post(client, Map.of("Authorization", authorization)).statusCode()).isEqualTo(200);
      assertThat(post(client, Map.of("Authorization", authorization)).statusCode()).isEqualTo(429);
    }
  }

  private String obtainAuthorization(HttpClient client) throws Exception {
    var challenge = post(client, Map.of());
    assertThat(challenge.statusCode()).isEqualTo(402);
    String header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(value -> value.startsWith("L402"))
            .findFirst()
            .orElseThrow();
    var matcher = L402_MACAROON.matcher(header);
    assertThat(matcher.find()).isTrue();
    Map<?, ?> body = JsonMapper.builder().build().readValue(challenge.body(), Map.class);
    return "L402 " + matcher.group(1) + ":" + body.get("test_preimage");
  }

  private HttpResponse<String> post(HttpClient client, Map<String, String> headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/priced"))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(BODY));
    headers.forEach(builder::header);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Grants a fixed token budget and counts every acquisition attempt. */
  static final class CountingRateLimiter implements PaygateRateLimiter {
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger remaining = new AtomicInteger();

    void reset(int budget) {
      attempts.set(0);
      remaining.set(budget);
    }

    int attempts() {
      return attempts.get();
    }

    @Override
    public boolean tryAcquire(String key) {
      attempts.incrementAndGet();
      return remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0;
    }
  }
}
