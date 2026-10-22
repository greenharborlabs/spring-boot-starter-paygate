package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for SR-04 and SR-17 (2026-10-18 security review): the MPP request digest bound an
 * over-decoded, dot-resolved path with {@code ;} parameters stripped, so a credential paid for one
 * path was accepted for a different handler-visible path or {@code @MatrixVariable} value.
 *
 * <p>Servlet mode, real Tomcat, raw sockets.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, MppRequestPathBindingRegressionTest.Handlers.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.security-mode=servlet",
      "paygate.protocols.mpp.challenge-binding-secret=mpp-path-binding-fixture-at-least-32-bytes",
      "paygate.rate-limit.burst-size=1000"
    })
@DisplayName("MPP credentials bind the dispatched path (SR-04, SR-17)")
class MppRequestPathBindingRegressionTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @BeforeEach
  void reset() {
    Handlers.SERVED.clear();
  }

  @Test
  @DisplayName("the credential works on its exact path and not on another path")
  void credentialIsBoundToItsPath() throws Exception {
    String credential = obtainMppCredential("/mpp/files/public/report");

    var exact = send("/mpp/files/public/report", credential);
    assertThat(exact.status()).isEqualTo(200);

    var other = send("/mpp/files/private/report", credential);
    assertThat(other.status()).isNotEqualTo(200);
    assertThat(Handlers.SERVED).containsExactly("files:public");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/mpp/files/private/%252e%252e/public/report",
        "/mpp/files/private/%25252e%25252e/public/report",
        "/mpp/files/private/%2e%2e/public/report",
        "/mpp/files/private/../public/report"
      })
  @DisplayName("a dot-segment variant of the paid path is rejected with 400")
  void dotSegmentVariantIsRejected(String rawPath) throws Exception {
    String credential = obtainMppCredential("/mpp/files/public/report");

    var response = send(rawPath, credential);

    assertThat(response.status()).isEqualTo(400);
    assertThat(Handlers.SERVED).isEmpty();
  }

  @Test
  @DisplayName("a matrix-parameter variant of the paid path is rejected with 400")
  void matrixParameterVariantIsRejected() throws Exception {
    String credential = obtainMppCredential("/mpp/matrix/item");

    var plain = send("/mpp/matrix/item", credential);
    assertThat(plain.status()).isEqualTo(200);

    var premium = send("/mpp/matrix/item;tier=premium", credential);
    assertThat(premium.status()).isEqualTo(400);
    assertThat(RawHttp.get(port, "/mpp/matrix/item;tier=basic").status()).isEqualTo(400);
    assertThat(Handlers.SERVED).containsExactly("matrix:null");
  }

  @SuppressWarnings("unchecked")
  private String obtainMppCredential(String rawPath) throws Exception {
    var challenge = RawHttp.get(port, rawPath);
    assertThat(challenge.status()).isEqualTo(402);
    Map<String, Object> body = MAPPER.readValue(challenge.body(), Map.class);
    Map<String, Object> payment =
        (Map<String, Object>) ((Map<String, Object>) body.get("protocols")).get("Payment");
    String json =
        MAPPER.writeValueAsString(
            Map.of(
                "challenge",
                payment,
                "source",
                "test-client",
                "payload",
                Map.of("preimage", body.get("test_preimage"))));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private RawHttp.Response send(String rawPath, String credential) throws Exception {
    return RawHttp.get(port, rawPath, Map.of("Authorization", "Payment " + credential));
  }

  @TestConfiguration
  static class Handlers {
    static final List<String> SERVED = new CopyOnWriteArrayList<>();

    @RestController
    @RequestMapping("/mpp")
    static class MppController {

      @PaymentRequired(priceSats = 10)
      @GetMapping("/files/{*rest}")
      Map<String, String> file(@PathVariable("rest") String rest) {
        String trimmed = rest.startsWith("/") ? rest.substring(1) : rest;
        int slash = trimmed.indexOf('/');
        String zone = slash < 0 ? trimmed : trimmed.substring(0, slash);
        SERVED.add("files:" + zone);
        return Map.of("zone", zone);
      }

      @PaymentRequired(priceSats = 1)
      @GetMapping("/matrix/{item}")
      Map<String, String> matrix(
          @PathVariable("item") String item,
          @MatrixVariable(name = "tier", pathVar = "item", required = false) String tier) {
        SERVED.add("matrix:" + tier);
        return Map.of("tier", String.valueOf(tier));
      }
    }
  }
}
