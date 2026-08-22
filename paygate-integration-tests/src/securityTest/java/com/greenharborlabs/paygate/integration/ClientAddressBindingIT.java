package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Verifies the opt-in L402 client-address restriction on the Spring Security request path. */
@Tag("integration")
@SpringBootTest(
    classes = SecurityExampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long",
      "paygate.protocols.l402.client-address-binding-enabled=false"
    })
@DisplayName("Client-address binding integration")
class ClientAddressBindingIT {

  private static final String DATA_PATH = "/api/v1/data";
  private static final String BOUND_CLIENT = "198.51.100.7";
  private static final Pattern TEST_PREIMAGE =
      Pattern.compile("\\\"test_preimage\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

  @LocalServerPort private int port;

  @Test
  @DisplayName("default L402 challenges retain bearer compatibility")
  void defaultChallengesContainNoClientAddressCaveat() throws Exception {
    var challenge = requestChallenge(port);

    assertThat(clientAddressCaveatCount(challenge)).isZero();
  }

  @Nested
  @Tag("integration")
  @SpringBootTest(
      classes = SecurityExampleApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long",
        "paygate.protocols.l402.client-address-binding-enabled=true",
        "paygate.trust-forwarded-headers=true",
        "paygate.trusted-proxy-addresses[0]=127.0.0.1"
      })
  @DisplayName("enabled address binding")
  class EnabledBinding {

    @LocalServerPort private int port;

    @Test
    @DisplayName(
        "same proxy-resolved address succeeds while a replay from another address is denied")
    void proxyResolvedAddressBindingBlocksCrossAddressReplay() throws Exception {
      var challenge = requestChallenge(port, BOUND_CLIENT);
      var macaroon = macaroonFrom(challenge);
      String authorization = "L402 " + tokenFrom(challenge) + ":" + testPreimageFrom(challenge);

      assertThat(
              macaroon.caveats().stream()
                  .filter(caveat -> caveat.key().equals("client_ip"))
                  .map(caveat -> caveat.value())
                  .toList())
          .singleElement()
          .isEqualTo(BOUND_CLIENT);

      assertThat(request(port, BOUND_CLIENT, authorization).statusCode()).isEqualTo(200);
      assertThat(request(port, "198.51.100.8", authorization).statusCode()).isEqualTo(402);
    }
  }

  private static HttpResponse<String> requestChallenge(int port) throws Exception {
    return requestChallenge(port, null);
  }

  private static HttpResponse<String> requestChallenge(int port, String forwardedClient)
      throws Exception {
    var response = request(port, forwardedClient, null);
    assertThat(response.statusCode()).isEqualTo(402);
    return response;
  }

  private static HttpResponse<String> request(
      int port, String forwardedClient, String authorization) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + DATA_PATH));
      if (forwardedClient != null) {
        request.header("X-Forwarded-For", forwardedClient);
      }
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static long clientAddressCaveatCount(HttpResponse<String> challenge) {
    return macaroonFrom(challenge).caveats().stream()
        .filter(caveat -> caveat.key().equals("client_ip"))
        .count();
  }

  private static com.greenharborlabs.paygate.core.macaroon.Macaroon macaroonFrom(
      HttpResponse<String> challenge) {
    return MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(tokenFrom(challenge)));
  }

  private static String tokenFrom(HttpResponse<String> challenge) {
    String header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(value -> value.startsWith("L402 "))
            .findFirst()
            .orElseThrow();
    int tokenStart = header.indexOf("token=\"") + "token=\"".length();
    return header.substring(tokenStart, header.indexOf('"', tokenStart));
  }

  private static String testPreimageFrom(HttpResponse<String> challenge) {
    var matcher = TEST_PREIMAGE.matcher(challenge.body());
    assertThat(matcher.find()).as("test-mode challenge preimage").isTrue();
    return matcher.group(1);
  }
}
