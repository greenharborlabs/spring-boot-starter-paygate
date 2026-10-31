package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.example.ExampleApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet mode on real Tomcat with {@code 127.0.0.1} as the trusted proxy and client-address
 * binding disabled. A delegator restricts a paid credential with a holder-added {@code client_ip=X}
 * caveat. Another client sends {@code X-Forwarded-For: X} and the simulated trusted proxy appends
 * its peer as a non-literal entry or as a separate field line; the caveat must not be satisfied.
 */
@Tag("integration")
@SpringBootTest(
    classes = ExampleApplication.class,
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
      "paygate.trust-forwarded-headers=true",
      "paygate.trusted-proxy-addresses[0]=127.0.0.1"
    })
@DisplayName("Forwarded client address and delegated client_ip caveats")
class ForwardedClientIpCaveatIT {

  private static final Pattern MACAROON = Pattern.compile("macaroon=\\\"([^\\\"]+)\\\"");
  private static final String DATA_PATH = "/api/v1/data";
  private static final String DELEGATED = "203.0.113.5";
  private static final String OTHER = "198.51.100.7";

  @LocalServerPort private int port;

  private Credential delegated;

  @BeforeEach
  void delegateToClientAddress() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var challenge = send(client, List.of(DELEGATED), null);
      assertThat(challenge.statusCode()).isEqualTo(402);
      String www =
          challenge.headers().allValues("WWW-Authenticate").stream()
              .filter(v -> v.startsWith("L402"))
              .findFirst()
              .orElseThrow();
      var m = MACAROON.matcher(www);
      assertThat(m.find()).isTrue();
      @SuppressWarnings("unchecked")
      var body = JsonMapper.builder().build().readValue(challenge.body(), Map.class);
      var paid = new Credential(m.group(1), (String) body.get("test_preimage"));
      delegated = attenuate(paid, "client_ip", DELEGATED);
    }
  }

  @ParameterizedTest(name = "XFF \"{0}\" -> {1}")
  @DisplayName("a well-formed chain resolves the delegated or the other client")
  @CsvSource(
      delimiter = '|',
      value = {DELEGATED + "|200", DELEGATED + ", " + OTHER + "|402"})
  void wellFormedChainResolvesNearestUntrustedClient(String xff, int expectedStatus)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      assertThat(send(client, List.of(xff), delegated).statusCode()).isEqualTo(expectedStatus);
    }
  }

  @ParameterizedTest(name = "appended \"{0}\"")
  @ValueSource(strings = {OTHER + ":51234", "unknown"})
  @DisplayName("an unparseable proxy-appended entry does not satisfy the delegated caveat")
  void unparseableProxyAppendedEntryDoesNotSatisfyCaveat(String appended) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      assertThat(send(client, List.of(DELEGATED + ", " + appended), delegated).statusCode())
          .isEqualTo(402);
    }
  }

  @ParameterizedTest(name = "second line \"{0}\"")
  @ValueSource(strings = {OTHER, OTHER + ":51234"})
  @DisplayName("a client-supplied first field line does not satisfy the delegated caveat")
  void clientSuppliedFirstLineDoesNotSatisfyCaveat(String proxyLine) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      assertThat(send(client, List.of(DELEGATED, proxyLine), delegated).statusCode())
          .isEqualTo(402);
    }
  }

  /** Holder-side attenuation: extends the HMAC chain with one first-party caveat (no root key). */
  private static Credential attenuate(Credential credential, String key, String value) {
    Macaroon original =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroon()));
    var caveat = new Caveat(key, value);
    var caveats = new ArrayList<>(original.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(
            original.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    var attenuated = new Macaroon(original.identifier(), original.location(), caveats, signature);
    return new Credential(
        Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated)),
        credential.preimage());
  }

  private HttpResponse<String> send(
      HttpClient client, List<String> forwardedForLines, Credential credential) throws Exception {
    var builder = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + DATA_PATH));
    for (String line : forwardedForLines) {
      builder.header("X-Forwarded-For", line);
    }
    if (credential != null) {
      builder.header(
          "Authorization", "L402 " + credential.macaroon() + ":" + credential.preimage());
    }
    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private record Credential(String macaroon, String preimage) {}
}
