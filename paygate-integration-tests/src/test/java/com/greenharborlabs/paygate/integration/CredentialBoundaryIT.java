package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Request-level regressions for fresh and cached L402 credential boundary checks. */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, CredentialBoundaryIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.challenge-binding-secret=credential-boundary-test-secret-at-least-32-bytes",
      "paygate.rate-limit.burst-size=1000"
    })
@DisplayName("Credential boundary enforcement")
class CredentialBoundaryIT {

  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\\\"([^\\\"]+)\\\"");
  private static final String PROTECTED_PATH = "/credential-boundary/protected";

  @LocalServerPort private int port;

  @BeforeEach
  void resetFixture() {
    TestConfig.BoundaryController.INVOCATIONS.set(0);
    TestConfig.ROOT_KEY_STORE.clearFault();
    TestConfig.CREDENTIAL_STORE.resetStoreCalls();
  }

  @Test
  @DisplayName("fresh tampering and cached root-key removal are rejected before the handler")
  void rejectsFreshTamperingAndCachedRootKeyRemovalBeforeHandler() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var credential = obtainCredential(client);
      var tampered = new Credential(tamper(credential.macaroon()), credential.preimage());

      assertThat(send(client, tampered).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(0);

      assertThat(send(client, credential).statusCode()).isEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(1);

      // Deliberately bypass RootKeyStore.revokeRootKey: an out-of-band deletion has no event
      // available to invalidate the credential cache, so the next request must recheck storage.
      TestConfig.ROOT_KEY_STORE.deleteWithoutEvent(tokenId(credential));
      assertThat(send(client, credential).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(1);
    }
  }

  @Test
  @DisplayName("validly signed credentials missing an authoritative boundary are rejected")
  void rejectsValidSignatureCredentialMissingRouteBeforeHandler() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var credential = obtainCredential(client);
      var missingRoute = mintWithoutBoundary(credential, "route");

      // The first request exercises the fresh-validation path with a correctly signed macaroon.
      assertThat(send(client, missingRoute).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(0);

      // Populate the token's cache slot with the authentic variant, then ensure the altered
      // signed variant cannot borrow that cache entry or reach the protected handler.
      assertThat(send(client, credential).statusCode()).isEqualTo(200);
      assertThat(send(client, missingRoute).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(1);
    }
  }

  @Test
  @DisplayName("a transient root-key lookup failure does not poison a valid cached credential")
  void transientRootKeyLookupFailureDoesNotEvictValidCachedCredential() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var credential = obtainCredential(client);
      assertThat(send(client, credential).statusCode()).isEqualTo(200);
      assertThat(TestConfig.CREDENTIAL_STORE.storeCalls()).isEqualTo(1);

      TestConfig.ROOT_KEY_STORE.failNextLookup();
      assertThat(send(client, credential).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(1);
      assertThat(TestConfig.CREDENTIAL_STORE.storeCalls()).isEqualTo(1);

      assertThat(send(client, credential).statusCode()).isEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(2);
      // A retained cache entry permits recovery without a second full validation and store.
      // If the transient failure had evicted it, this request would freshly validate and store.
      assertThat(TestConfig.CREDENTIAL_STORE.storeCalls()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("MPP credentials bind the exact raw query before handler execution")
  void rejectsRawQueryNormalizationBeforeHandler() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var credential = obtainMppCredential(client, "?tag=a%2Bb");

      assertThat(sendPayment(client, "?tag=a+b", credential).statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(0);

      assertThat(sendPayment(client, "?tag=a%2Bb", credential).statusCode()).isEqualTo(200);
      assertThat(TestConfig.BoundaryController.INVOCATIONS).hasValue(1);
    }
  }

  private Credential obtainCredential(HttpClient client) throws Exception {
    var response = send(client, null);
    assertThat(response.statusCode()).isEqualTo(402);
    String header =
        response.headers().allValues("WWW-Authenticate").stream()
            .filter(value -> value.startsWith("L402"))
            .findFirst()
            .orElseThrow();
    var matcher = MACAROON_PATTERN.matcher(header);
    assertThat(matcher.find()).isTrue();
    @SuppressWarnings("unchecked")
    var body = JsonMapper.builder().build().readValue(response.body(), Map.class);
    return new Credential(matcher.group(1), (String) body.get("test_preimage"));
  }

  private HttpResponse<String> send(HttpClient client, Credential credential) throws Exception {
    var request =
        HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + PROTECTED_PATH));
    if (credential != null) {
      request.header(
          "Authorization", "L402 " + credential.macaroon() + ":" + credential.preimage());
    }
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private String obtainMppCredential(HttpClient client, String rawQuery) throws Exception {
    var response = send(client, rawQuery, null);
    assertThat(response.statusCode()).isEqualTo(402);
    Map<String, Object> body = JsonMapper.builder().build().readValue(response.body(), Map.class);
    Map<String, Object> challenge =
        (Map<String, Object>) ((Map<String, Object>) body.get("protocols")).get("Payment");
    String preimage = (String) body.get("test_preimage");
    String credential =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "challenge",
                    challenge,
                    "source",
                    "test-client",
                    "payload",
                    Map.of("preimage", preimage)));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(credential.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> sendPayment(HttpClient client, String rawQuery, String credential)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + PROTECTED_PATH + rawQuery))
            .header("Authorization", "Payment " + credential)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(HttpClient client, String rawQuery, Credential credential)
      throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + PROTECTED_PATH + rawQuery));
    if (credential != null) {
      request.header(
          "Authorization", "L402 " + credential.macaroon() + ":" + credential.preimage());
    }
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] tokenId(Credential credential) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroon()));
    return MacaroonIdentifier.decode(macaroon.identifier()).tokenId();
  }

  private static Credential mintWithoutBoundary(Credential credential, String boundary) {
    Macaroon original =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroon()));
    var caveats = new ArrayList<>(original.caveats());
    assertThat(caveats.removeIf(caveat -> caveat.key().equals(boundary))).isTrue();

    try (var rootKey = TestConfig.ROOT_KEY_STORE.getRootKey(tokenId(credential))) {
      assertThat(rootKey).isNotNull();
      byte[] keyMaterial = rootKey.value();
      try {
        var reminted =
            MacaroonMinter.mint(
                keyMaterial,
                MacaroonIdentifier.decode(original.identifier()),
                original.location(),
                caveats);
        return new Credential(
            Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(reminted)),
            credential.preimage());
      } finally {
        java.util.Arrays.fill(keyMaterial, (byte) 0);
      }
    }
  }

  private static String tamper(String macaroon) {
    byte[] bytes = Base64.getDecoder().decode(macaroon);
    bytes[bytes.length - 1] ^= 1;
    return Base64.getEncoder().encodeToString(bytes);
  }

  private record Credential(String macaroon, String preimage) {}

  @TestConfiguration
  static class TestConfig {
    private static final FaultInjectingRootKeyStore ROOT_KEY_STORE =
        new FaultInjectingRootKeyStore();
    private static final CountingCredentialStore CREDENTIAL_STORE = new CountingCredentialStore();

    @Bean
    RootKeyStore rootKeyStore() {
      return ROOT_KEY_STORE;
    }

    @Bean
    CredentialStore credentialStore() {
      return CREDENTIAL_STORE;
    }

    @RestController
    @RequestMapping("/credential-boundary")
    static class BoundaryController {
      private static final AtomicInteger INVOCATIONS = new AtomicInteger();

      @PaymentRequired(priceSats = 10)
      @GetMapping("/protected")
      Map<String, String> protectedResource() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
      }
    }
  }

  static final class FaultInjectingRootKeyStore implements RootKeyStore {
    private final InMemoryRootKeyStore delegate = new InMemoryRootKeyStore();
    private final AtomicBoolean failNextLookup = new AtomicBoolean();

    @Override
    public GenerationResult generateRootKey() {
      return delegate.generateRootKey();
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      if (failNextLookup.compareAndSet(true, false)) {
        throw new IllegalStateException("deterministic transient root-key-store failure");
      }
      return delegate.getRootKey(keyId);
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
      delegate.revokeRootKey(keyId);
    }

    void deleteWithoutEvent(byte[] keyId) {
      delegate.revokeRootKey(keyId);
    }

    void failNextLookup() {
      failNextLookup.set(true);
    }

    void clearFault() {
      failNextLookup.set(false);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  static final class CountingCredentialStore implements CredentialStore {
    private final CredentialStore delegate =
        new com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore();
    private final AtomicInteger storeCalls = new AtomicInteger();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
      storeCalls.incrementAndGet();
      delegate.store(tokenId, credential, ttlSeconds);
    }

    @Override
    public L402Credential get(String tokenId) {
      return delegate.get(tokenId);
    }

    @Override
    public void revoke(String tokenId) {
      delegate.revoke(tokenId);
    }

    @Override
    public long activeCount() {
      return delegate.activeCount();
    }

    int storeCalls() {
      return storeCalls.get();
    }

    void resetStoreCalls() {
      storeCalls.set(0);
    }
  }
}
