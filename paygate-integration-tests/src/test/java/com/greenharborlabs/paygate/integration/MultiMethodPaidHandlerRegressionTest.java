package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for paid handlers that map several HTTP methods. Each mapped method resolves and
 * enforces its own payment policy instead of failing with 500. With the (non-default)
 * HiddenHttpMethodFilter enabled, a credential authorized for the wire method still cannot run the
 * handler under an overridden method.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, MultiMethodPaidHandlerRegressionTest.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=1000",
      "spring.mvc.hiddenmethod.filter.enabled=true"
    })
@DisplayName("Paid handlers mapping several HTTP methods")
class MultiMethodPaidHandlerRegressionTest {

  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\\\"([^\\\"]+)\\\"");
  private static final String MULTI_PATH = "/multi-method/records/1";
  private static final String SPLIT_PATH = "/multi-method/records/2";

  @LocalServerPort private int port;

  @BeforeEach
  void resetCounters() {
    TestConfig.RecordsController.MULTI_POST.set(0);
    TestConfig.RecordsController.MULTI_DELETE.set(0);
    TestConfig.RecordsController.SPLIT_DELETE.set(0);
  }

  @Test
  @DisplayName("each mapped method challenges independently without credentials")
  void eachMappedMethodChallengesWithoutCredentials() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var post = send(client, "POST", MULTI_PATH, "", null);
      var delete = send(client, "DELETE", MULTI_PATH, "", null);

      assertThat(post.statusCode()).isEqualTo(402);
      assertThat(delete.statusCode()).isEqualTo(402);
      assertThat(TestConfig.RecordsController.MULTI_POST.get()).isZero();
      assertThat(TestConfig.RecordsController.MULTI_DELETE.get()).isZero();
    }
  }

  @Test
  @DisplayName("a credential paid for one mapped method serves only that method")
  void credentialServesOnlyItsMappedMethod() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String auth = payFor(client, "POST", MULTI_PATH);

      var post = send(client, "POST", MULTI_PATH, "", auth);
      assertThat(post.statusCode()).isEqualTo(200);
      assertThat(post.body()).contains("\"executedMethod\":\"POST\"");

      var delete = send(client, "DELETE", MULTI_PATH, "", auth);
      assertThat(delete.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.RecordsController.MULTI_POST.get()).isEqualTo(1);
      assertThat(TestConfig.RecordsController.MULTI_DELETE.get()).isZero();
    }
  }

  @Test
  @DisplayName("a POST credential cannot run the same multi-method handler as DELETE via _method")
  void postCredentialCannotOverrideToDeleteOnSameHandler() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String auth = payFor(client, "POST", MULTI_PATH);

      var overridden = send(client, "POST", MULTI_PATH, "_method=DELETE", auth);

      assertThat(overridden.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.RecordsController.MULTI_DELETE.get()).isZero();
    }
  }

  @Test
  @DisplayName("a POST credential cannot reach a separate DELETE handler via _method")
  void postCredentialCannotReachSeparateDeleteHandler() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String auth = payFor(client, "POST", SPLIT_PATH);

      var plain = send(client, "POST", SPLIT_PATH, "", auth);
      assertThat(plain.statusCode()).isEqualTo(200);

      var overridden = send(client, "POST", SPLIT_PATH, "_method=DELETE", auth);
      assertThat(overridden.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.RecordsController.SPLIT_DELETE.get()).isZero();
    }
  }

  private String payFor(HttpClient client, String method, String path) throws Exception {
    var challenge = send(client, method, path, "", null);
    assertThat(challenge.statusCode()).isEqualTo(402);
    String header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(v -> v.startsWith("L402"))
            .findFirst()
            .orElseThrow();
    var matcher = MACAROON_PATTERN.matcher(header);
    assertThat(matcher.find()).isTrue();
    var body = JsonMapper.builder().build().readValue(challenge.body(), Map.class);
    return "L402 " + matcher.group(1) + ":" + body.get("test_preimage");
  }

  private HttpResponse<String> send(
      HttpClient client, String method, String path, String form, String auth) throws Exception {
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .method(method, HttpRequest.BodyPublishers.ofString(form));
    if (auth != null) {
      builder.header("Authorization", auth);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration
  static class TestConfig {
    @RestController
    static class RecordsController {
      static final AtomicInteger MULTI_POST = new AtomicInteger();
      static final AtomicInteger MULTI_DELETE = new AtomicInteger();
      static final AtomicInteger SPLIT_DELETE = new AtomicInteger();

      @PaymentRequired(priceSats = 10)
      @RequestMapping(
          path = MULTI_PATH,
          method = {RequestMethod.POST, RequestMethod.DELETE})
      Map<String, String> mutate(HttpServletRequest request) {
        if ("DELETE".equals(request.getMethod())) {
          MULTI_DELETE.incrementAndGet();
        } else {
          MULTI_POST.incrementAndGet();
        }
        return Map.of("executedMethod", request.getMethod());
      }

      @PaymentRequired(priceSats = 10)
      @RequestMapping(path = SPLIT_PATH, method = RequestMethod.POST)
      Map<String, String> update() {
        return Map.of("executedMethod", "POST");
      }

      @PaymentRequired(priceSats = 10)
      @RequestMapping(path = SPLIT_PATH, method = RequestMethod.DELETE)
      Map<String, String> delete() {
        SPLIT_DELETE.incrementAndGet();
        return Map.of("executedMethod", "DELETE");
      }
    }
  }
}
