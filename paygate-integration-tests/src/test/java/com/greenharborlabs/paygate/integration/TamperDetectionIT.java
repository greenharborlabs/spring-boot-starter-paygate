package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test verifying that tampered macaroons are rejected.
 *
 * <p>Obtains a valid macaroon via the test-mode 402 challenge, flips a byte in the serialized
 * macaroon, and verifies the server rejects it. This exercises the HMAC signature verification in
 * the full request pipeline.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, TamperDetectionIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.rate-limit.burst-size=1000"
    })
@DisplayName("L402 tamper detection")
class TamperDetectionIT {

  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final HexFormat HEX = HexFormat.of();
  private static final String SERVICE_NAME = "example-api";
  private static final String DATA_PATH = "/api/v1/data";
  private static final String ITEM_ROUTE_PATTERN = "/security-boundary/items/{itemId}";
  private static final String FIRST_ITEM_PATH = "/security-boundary/items/fixture-alpha";
  private static final String SECOND_ITEM_PATH = "/security-boundary/items/fixture-beta";
  private static final String DIFFERENT_ROUTE = "/security-boundary/expensive";
  private static final String ADMIN_PATH = "/security-boundary/admin";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @Autowired private RootKeyStore rootKeyStore;

  @BeforeEach
  void resetHandlerInvocations() {
    TestConfig.SecurityBoundaryController.INVOCATIONS.set(0);
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @TestConfiguration
  static class TestConfig {

    @RestController
    @RequestMapping("/security-boundary")
    static class SecurityBoundaryController {

      private static final AtomicInteger INVOCATIONS = new AtomicInteger();

      @PaymentRequired(priceSats = 10, capability = "search,analyze")
      @GetMapping("/items/{itemId}")
      Map<String, String> getItem() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
      }

      @PaymentRequired(priceSats = 10, capability = "search,analyze")
      @PostMapping("/items/{itemId}")
      Map<String, String> postItem() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
      }

      @PaymentRequired(priceSats = 10, capability = "search,analyze")
      @GetMapping("/expensive")
      Map<String, String> expensive() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
      }

      @PaymentRequired(priceSats = 10, capability = "admin")
      @GetMapping("/admin")
      Map<String, String> admin() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
      }
    }
  }

  @Test
  @DisplayName("credential is confined to its canonical route and actual HTTP method")
  void credentialIsConfinedToCanonicalRouteAndActualMethod() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      L402Credential credential = obtainCredential(client, FIRST_ITEM_PATH);

      assertThat(sendAuthenticated(client, SECOND_ITEM_PATH, "GET", credential).statusCode())
          .isEqualTo(200);
      assertThat(sendAuthenticated(client, DIFFERENT_ROUTE, "GET", credential).statusCode())
          .isNotEqualTo(200);
      assertThat(sendAuthenticated(client, FIRST_ITEM_PATH, "POST", credential).statusCode())
          .isNotEqualTo(200);
      assertThat(TestConfig.SecurityBoundaryController.INVOCATIONS).hasValue(1);
    }
  }

  @Test
  @DisplayName("capability attenuation narrows but never expands the issuance ceiling")
  void capabilityAttenuationCannotExpandIssuanceCeiling() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      L402Credential issued = obtainCredential(client, FIRST_ITEM_PATH);
      L402Credential narrowed = attenuateCapability(issued, "search");
      L402Credential escalated = attenuateCapability(issued, "search,admin");

      assertThat(sendAuthenticated(client, FIRST_ITEM_PATH, "GET", narrowed).statusCode())
          .isEqualTo(200);
      assertThat(sendAuthenticated(client, FIRST_ITEM_PATH, "GET", escalated).statusCode())
          .isNotEqualTo(200);

      L402Credential noCapability = obtainCredential(client, DATA_PATH);
      L402Credential noCapabilityEscalation = attenuateCapability(noCapability, "admin");
      assertThat(sendAuthenticated(client, DATA_PATH, "GET", noCapability).statusCode())
          .isEqualTo(200);
      assertThat(sendAuthenticated(client, DATA_PATH, "GET", noCapabilityEscalation).statusCode())
          .isNotEqualTo(200);
      assertThat(TestConfig.SecurityBoundaryController.INVOCATIONS).hasValue(1);
    }
  }

  @Test
  @DisplayName("signed legacy credentials missing any request boundary fail closed")
  void credentialsMissingRequiredBoundariesFailClosed() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      for (String omittedBoundary : List.of("route", "method", "capability")) {
        L402Credential credential = mintCredentialMissing(omittedBoundary);

        assertThat(sendAuthenticated(client, FIRST_ITEM_PATH, "GET", credential).statusCode())
            .as("credential missing %s must be rejected", omittedBoundary)
            .isNotEqualTo(200);
      }
      assertThat(TestConfig.SecurityBoundaryController.INVOCATIONS).hasValue(0);
    }
  }

  @Test
  @DisplayName("authentic legacy v0 holder-appended boundaries are rejected before handlers")
  void legacyVersionZeroHolderAttenuationFailsClosed() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      L402Credential appendedAdmin =
          mintLegacyCredential(
              List.of(new Caveat("services", SERVICE_NAME + ":0"), validUntilCaveat()),
              List.of(
                  new Caveat("route", "/security-boundary/admin"),
                  new Caveat("method", "GET"),
                  new Caveat(SERVICE_NAME + "_capabilities", "admin")));
      L402Credential repeatedSearch =
          mintLegacyCredential(
              List.of(
                  new Caveat("services", SERVICE_NAME + ":0"),
                  new Caveat(SERVICE_NAME + "_capabilities", "search"),
                  validUntilCaveat()),
              List.of(
                  new Caveat("route", "/security-boundary/expensive"),
                  new Caveat("method", "GET"),
                  new Caveat(SERVICE_NAME + "_capabilities", "search")));
      L402Credential zeroCaveat =
          mintLegacyCredential(
              List.of(),
              List.of(
                  new Caveat("route", "/security-boundary/expensive"),
                  new Caveat("method", "GET"),
                  new Caveat(SERVICE_NAME + "_capabilities", "search")));

      assertThat(satisfiesOldPresenceOnlyDecision(appendedAdmin, ADMIN_PATH, "admin")).isTrue();
      assertThat(satisfiesOldPresenceOnlyDecision(repeatedSearch, DIFFERENT_ROUTE, "search"))
          .isTrue();
      assertThat(satisfiesOldPresenceOnlyDecision(zeroCaveat, DIFFERENT_ROUTE, "search")).isTrue();

      for (var attack :
          List.of(
              Map.entry(ADMIN_PATH, appendedAdmin),
              Map.entry(DIFFERENT_ROUTE, repeatedSearch),
              Map.entry(DIFFERENT_ROUTE, zeroCaveat))) {
        var response = sendAuthenticated(client, attack.getKey(), "GET", attack.getValue());
        assertThat(response.statusCode())
            .as("legacy credential must be rejected")
            .isNotEqualTo(200);
        boolean containsMacaroon = response.body().contains(attack.getValue().macaroonBase64());
        boolean containsPreimage = response.body().contains(attack.getValue().preimageHex());
        assertCredentialMaterialNotDisclosed(containsMacaroon, containsPreimage);
      }
      assertThat(TestConfig.SecurityBoundaryController.INVOCATIONS).hasValue(0);
    }
  }

  @Test
  @DisplayName("non-disclosure assertion diagnostics never reveal credential material")
  void nonDisclosureAssertionDiagnosticsAreSecretSafe() {
    String macaroonMarker = "macaroon-secret-marker";
    String preimageMarker = "preimage-secret-marker";
    String secretBearingBody =
        "response-body-secret-marker:" + macaroonMarker + ":" + preimageMarker;

    AssertionError failure = null;
    try {
      assertCredentialMaterialNotDisclosed(
          secretBearingBody.contains(macaroonMarker), secretBearingBody.contains(preimageMarker));
    } catch (AssertionError assertionError) {
      failure = assertionError;
    }

    assertThat(failure != null)
        .as("the diagnostic fixture must trigger the non-disclosure assertion")
        .isTrue();
    String diagnostic = failure.getMessage();
    assertThat(diagnostic.contains(secretBearingBody))
        .as("assertion diagnostic must not contain the response body")
        .isFalse();
    assertThat(diagnostic.contains(macaroonMarker))
        .as("assertion diagnostic must not contain the macaroon value")
        .isFalse();
    assertThat(diagnostic.contains(preimageMarker))
        .as("assertion diagnostic must not contain the preimage value")
        .isFalse();
  }

  static void assertCredentialMaterialNotDisclosed(
      boolean containsMacaroon, boolean containsPreimage) {
    assertThat(containsMacaroon)
        .as("response must not disclose macaroon credential material")
        .isFalse();
    assertThat(containsPreimage)
        .as("response must not disclose payment preimage material")
        .isFalse();
  }

  @Test
  @DisplayName("tampered macaroon is rejected with 4xx status")
  @SuppressWarnings("unchecked")
  void tamperedMacaroonIsRejected() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      // Step 1: Get a valid challenge
      var challengeRequest =
          HttpRequest.newBuilder().uri(URI.create(baseUrl() + DATA_PATH)).GET().build();
      var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(challengeResponse.statusCode()).isEqualTo(402);

      // Step 2: Extract macaroon and preimage
      String wwwAuth = findL402Header(challengeResponse);
      assertThat(wwwAuth).isNotNull();
      Matcher macaroonMatcher = MACAROON_PATTERN.matcher(wwwAuth);
      assertThat(macaroonMatcher.find()).isTrue();
      String macaroonBase64 = macaroonMatcher.group(1);

      Map<String, Object> body = MAPPER.readValue(challengeResponse.body(), Map.class);
      String preimageHex = (String) body.get("test_preimage");
      assertThat(preimageHex).isNotNull();

      // Step 3: Tamper with the macaroon — flip a byte near the end (signature area)
      byte[] macaroonBytes = Base64.getDecoder().decode(macaroonBase64);
      assertThat(macaroonBytes.length).isGreaterThan(10);
      int tamperIndex = macaroonBytes.length - 5;
      macaroonBytes[tamperIndex] = (byte) (macaroonBytes[tamperIndex] ^ 0xFF);
      String tamperedMacaroonBase64 = Base64.getEncoder().encodeToString(macaroonBytes);

      // Step 4: Present the tampered credential
      String authHeaderValue = "L402 " + tamperedMacaroonBase64 + ":" + preimageHex;
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + DATA_PATH))
              .header("Authorization", authHeaderValue)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      // Step 5: Verify rejection — could be 400 (deserialization fails) or 401 (signature mismatch)
      assertThat(response.statusCode())
          .as("Tampered macaroon should be rejected with 4xx status")
          .isBetween(400, 499);
    }
  }

  @Test
  @DisplayName("wrong preimage is rejected with 4xx status")
  @SuppressWarnings("unchecked")
  void wrongPreimageIsRejected() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      // Step 1: Get a valid challenge
      var challengeRequest =
          HttpRequest.newBuilder().uri(URI.create(baseUrl() + DATA_PATH)).GET().build();
      var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(challengeResponse.statusCode()).isEqualTo(402);

      // Step 2: Extract macaroon (valid) but use a bogus preimage
      String wwwAuth = findL402Header(challengeResponse);
      assertThat(wwwAuth).isNotNull();
      Matcher macaroonMatcher = MACAROON_PATTERN.matcher(wwwAuth);
      assertThat(macaroonMatcher.find()).isTrue();
      String macaroonBase64 = macaroonMatcher.group(1);

      // Bogus preimage: 32 bytes of zeros
      String bogusPreimage = "0".repeat(64);

      // Step 3: Present valid macaroon with wrong preimage
      String authHeaderValue = "L402 " + macaroonBase64 + ":" + bogusPreimage;
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + DATA_PATH))
              .header("Authorization", authHeaderValue)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      // Step 4: Verify rejection — preimage hash won't match the payment hash in the identifier
      assertThat(response.statusCode())
          .as("Wrong preimage should be rejected with 4xx status")
          .isBetween(400, 499);
    }
  }

  @SuppressWarnings("unchecked")
  private L402Credential obtainCredential(HttpClient client, String path) throws Exception {
    var challengeRequest = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
    var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(challengeResponse.statusCode()).isEqualTo(402);

    String l402Header = findL402Header(challengeResponse);
    assertThat(l402Header).isNotNull();
    Matcher matcher = MACAROON_PATTERN.matcher(l402Header);
    assertThat(matcher.find()).isTrue();

    Map<String, Object> body = MAPPER.readValue(challengeResponse.body(), Map.class);
    String preimageHex = (String) body.get("test_preimage");
    assertThat(preimageHex).isNotNull().hasSize(64);
    return new L402Credential(matcher.group(1), preimageHex);
  }

  private HttpResponse<String> sendAuthenticated(
      HttpClient client, String path, String method, L402Credential credential) throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", authorizationHeader(credential))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private L402Credential attenuateCapability(L402Credential credential, String capability) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroonBase64()));
    Caveat attenuation = new Caveat(SERVICE_NAME + "_capabilities", capability);
    var caveats = new ArrayList<>(macaroon.caveats());
    caveats.add(attenuation);
    byte[] signature =
        MacaroonCrypto.hmac(
            macaroon.signature(), attenuation.toString().getBytes(StandardCharsets.UTF_8));
    try {
      var attenuated = new Macaroon(macaroon.identifier(), macaroon.location(), caveats, signature);
      return new L402Credential(
          Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated)),
          credential.preimageHex());
    } finally {
      MacaroonCrypto.zeroize(signature);
    }
  }

  private L402Credential mintCredentialMissing(String omittedBoundary) throws Exception {
    byte[] preimage = new byte[32];
    java.util.Arrays.fill(preimage, (byte) 0x5A);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    try (var generated = rootKeyStore.generateRootKey()) {
      byte[] rootKey = generated.rootKey().value();
      try {
        var caveats = new ArrayList<Caveat>();
        caveats.add(new Caveat("services", SERVICE_NAME + ":0"));
        if (!omittedBoundary.equals("route")) {
          caveats.add(new Caveat("route", ITEM_ROUTE_PATTERN));
        }
        if (!omittedBoundary.equals("method")) {
          caveats.add(new Caveat("method", "GET"));
        }
        if (!omittedBoundary.equals("capability")) {
          caveats.add(new Caveat(SERVICE_NAME + "_capabilities", "search,analyze"));
        }
        caveats.add(
            new Caveat(
                SERVICE_NAME + "_valid_until",
                String.valueOf(Instant.now().plusSeconds(300).getEpochSecond())));
        var identifier = new MacaroonIdentifier(1, paymentHash, generated.tokenId());
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);
        return new L402Credential(
            Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon)),
            HEX.formatHex(preimage));
      } finally {
        MacaroonCrypto.zeroize(rootKey);
      }
    } finally {
      MacaroonCrypto.zeroize(paymentHash);
      MacaroonCrypto.zeroize(preimage);
    }
  }

  private L402Credential mintLegacyCredential(
      List<Caveat> issuerCaveats, List<Caveat> holderCaveats) throws Exception {
    byte[] preimage = new byte[32];
    java.util.Arrays.fill(preimage, (byte) 0x6B);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    try (var generated = rootKeyStore.generateRootKey()) {
      byte[] rootKey = generated.rootKey().value();
      try {
        Macaroon macaroon =
            MacaroonMinter.mint(
                rootKey,
                new MacaroonIdentifier(0, paymentHash, generated.tokenId()),
                null,
                issuerCaveats);
        for (Caveat caveat : holderCaveats) {
          macaroon = attenuate(macaroon, caveat);
        }
        return new L402Credential(
            Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon)),
            HEX.formatHex(preimage));
      } finally {
        MacaroonCrypto.zeroize(rootKey);
      }
    } finally {
      MacaroonCrypto.zeroize(paymentHash);
      MacaroonCrypto.zeroize(preimage);
    }
  }

  private static Caveat validUntilCaveat() {
    return new Caveat(
        SERVICE_NAME + "_valid_until",
        String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
  }

  private static Macaroon attenuate(Macaroon macaroon, Caveat caveat) {
    var caveats = new ArrayList<>(macaroon.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(
            macaroon.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    try {
      return new Macaroon(macaroon.identifier(), macaroon.location(), caveats, signature);
    } finally {
      MacaroonCrypto.zeroize(signature);
    }
  }

  private static boolean satisfiesOldPresenceOnlyDecision(
      L402Credential credential, String routePattern, String requestedCapability) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroonBase64()));
    boolean route =
        macaroon.caveats().stream()
            .anyMatch(c -> c.key().equals("route") && c.value().equals(routePattern));
    boolean method =
        macaroon.caveats().stream()
            .anyMatch(c -> c.key().equals("method") && c.value().equals("GET"));
    boolean capability =
        macaroon.caveats().stream()
            .filter(c -> c.key().equals(SERVICE_NAME + "_capabilities"))
            .anyMatch(c -> List.of(c.value().split(",")).contains(requestedCapability));
    return route && method && capability;
  }

  private static String authorizationHeader(L402Credential credential) {
    return "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
  }

  private static String findL402Header(HttpResponse<?> response) {
    return response.headers().allValues("WWW-Authenticate").stream()
        .filter(h -> h.startsWith("L402"))
        .findFirst()
        .orElse(null);
  }

  @Test
  @DisplayName("completely invalid Authorization header is rejected with 400")
  @SuppressWarnings("unchecked")
  void invalidAuthorizationHeaderIsRejected() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + DATA_PATH))
              .header("Authorization", "L402 not-valid-at-all")
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(400);
      Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
      assertThat(body.get("error")).isEqualTo("MALFORMED_HEADER");
    }
  }

  private record L402Credential(String macaroonBase64, String preimageHex) {}
}
