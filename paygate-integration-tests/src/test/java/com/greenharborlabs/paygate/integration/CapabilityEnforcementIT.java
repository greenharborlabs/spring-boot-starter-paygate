package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test verifying fail-closed capability enforcement in the servlet filter path.
 *
 * <p>A macaroon minted for an endpoint with a capability caveat must be rejected when presented to
 * an endpoint that does not declare a capability. Credentials issued without named capabilities
 * carry an authenticated {@code ~} ceiling and remain usable only without capability grants.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, CapabilityEnforcementIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api"
    })
@DisplayName("Capability enforcement across servlet filter path")
class CapabilityEnforcementIT {

  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final String SERVICE_NAME = "example-api";
  private static final String DATA_PATH = "/api/v1/data";
  private static final String NAMED_CAPABILITIES_PATH = "/api/v1/named-capabilities";
  private static final String NO_CAPABILITY_PATH = "/api/v1/no-capability";
  private static final String SEARCH_PATH = "/api/v1/search";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @Autowired private L402Validator validator;

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @TestConfiguration
  static class TestConfig {

    @RestController
    @RequestMapping("/api/v1")
    static class CapabilityTestController {

      private static final AtomicInteger NO_CAPABILITY_INVOCATIONS = new AtomicInteger();
      private static final AtomicInteger NAMED_CAPABILITY_INVOCATIONS = new AtomicInteger();
      private static final AtomicInteger SEARCH_INVOCATIONS = new AtomicInteger();

      @PaymentRequired(priceSats = 10, capability = "search,analyze")
      @GetMapping("/named-capabilities")
      public Map<String, String> namedCapabilities() {
        NAMED_CAPABILITY_INVOCATIONS.incrementAndGet();
        return Map.of("data", "named capability content");
      }

      @PaymentRequired(priceSats = 5)
      @GetMapping("/no-capability")
      public Map<String, String> noCapability() {
        NO_CAPABILITY_INVOCATIONS.incrementAndGet();
        return Map.of("data", "authenticated content");
      }

      @PaymentRequired(priceSats = 10, capability = "search")
      @GetMapping("/search")
      public Map<String, String> search() {
        SEARCH_INVOCATIONS.incrementAndGet();
        return Map.of("data", "search results");
      }
    }
  }

  @Test
  @DisplayName("Capability-less credential cannot be attenuated into an admin grant")
  void rejectsAdminAttenuationFromNoCapabilityCredentialWithoutAuthority() throws Exception {
    TestConfig.CapabilityTestController.NO_CAPABILITY_INVOCATIONS.set(0);
    try (var client = HttpClient.newHttpClient()) {
      L402Credential credential = obtainL402Credential(client, NO_CAPABILITY_PATH);

      assertThat(capabilityCaveats(credential.macaroonBase64())).containsExactly("~");

      L402Credential attenuated =
          attenuateCapability(credential, SERVICE_NAME + "_capabilities", "admin");
      var response = sendAuthenticated(client, NO_CAPABILITY_PATH, attenuated);

      assertThat(response.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.CapabilityTestController.NO_CAPABILITY_INVOCATIONS).hasValue(0);
    }
  }

  @Test
  @DisplayName("Named capability ceiling can be narrowed on its bound HTTP route")
  void acceptsNamedSubsetAttenuationWithOnlyFinalEffectiveCapabilities() throws Exception {
    TestConfig.CapabilityTestController.NAMED_CAPABILITY_INVOCATIONS.set(0);
    TestConfig.CapabilityTestController.SEARCH_INVOCATIONS.set(0);
    try (var client = HttpClient.newHttpClient()) {
      L402Credential issued = obtainL402Credential(client, NAMED_CAPABILITIES_PATH);
      assertThat(capabilityCaveats(issued.macaroonBase64())).containsExactly("search,analyze");

      L402Credential narrowed =
          attenuateCapability(issued, SERVICE_NAME + "_capabilities", "search");
      var sameRouteResponse = sendAuthenticated(client, NAMED_CAPABILITIES_PATH, narrowed);

      assertThat(sameRouteResponse.statusCode()).isEqualTo(200);
      assertThat(TestConfig.CapabilityTestController.NAMED_CAPABILITY_INVOCATIONS).hasValue(1);

      var differentRouteResponse = sendAuthenticated(client, SEARCH_PATH, narrowed);
      assertThat(differentRouteResponse.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.CapabilityTestController.SEARCH_INVOCATIONS).hasValue(0);
    }
  }

  @Test
  @DisplayName("Named capability ceiling rejects substitution outside the issued set")
  void rejectsNamedCapabilitySubstitutionWithoutInvokingHandler() throws Exception {
    TestConfig.CapabilityTestController.NAMED_CAPABILITY_INVOCATIONS.set(0);
    try (var client = HttpClient.newHttpClient()) {
      L402Credential issued = obtainL402Credential(client, NAMED_CAPABILITIES_PATH);
      L402Credential substituted =
          attenuateCapability(issued, SERVICE_NAME + "_capabilities", "analyze,admin");

      assertThatThrownBy(
              () ->
                  validator.validate(
                      authorizationHeader(substituted),
                      verificationContext(NAMED_CAPABILITIES_PATH, "analyze")))
          .isInstanceOf(L402Exception.class)
          .hasMessage("Credential attenuation is invalid");

      var response = sendAuthenticated(client, NAMED_CAPABILITIES_PATH, substituted);
      assertThat(response.statusCode()).isNotEqualTo(200);
      assertThat(TestConfig.CapabilityTestController.NAMED_CAPABILITY_INVOCATIONS).hasValue(0);
    }
  }

  @Test
  @DisplayName("Capability-less credential remains usable without effective capabilities")
  void acceptsNoCapabilityCredentialWithoutDerivedCapabilityGrant() throws Exception {
    TestConfig.CapabilityTestController.NO_CAPABILITY_INVOCATIONS.set(0);
    try (var client = HttpClient.newHttpClient()) {
      L402Credential credential = obtainL402Credential(client, NO_CAPABILITY_PATH);

      L402Validator.ValidationResult result =
          validator.validate(
              authorizationHeader(credential), verificationContext(NO_CAPABILITY_PATH, null));
      try {
        assertThat(result.effectiveCapabilities()).isEmpty();
      } finally {
        result.credential().destroy();
      }

      var response = sendAuthenticated(client, NO_CAPABILITY_PATH, credential);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(TestConfig.CapabilityTestController.NO_CAPABILITY_INVOCATIONS).hasValue(1);
    }
  }

  @Test
  @DisplayName("Capability-restricted macaroon is rejected on endpoint without capability")
  void rejectsCapabilityRestrictedMacaroonOnEndpointWithoutCapability() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      // Get a macaroon from /api/v1/search (has capabilities caveat for "search")
      L402Credential credential = obtainL402Credential(client, SEARCH_PATH);

      // Try to use it on /api/v1/data (no capability declared)
      String authHeaderValue =
          "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + DATA_PATH))
              .header("Authorization", authHeaderValue)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode())
          .as("Capability-restricted macaroon must be rejected on endpoint without capability")
          .isNotEqualTo(200);
    }
  }

  @Test
  @DisplayName("Capability-restricted macaroon is accepted on matching endpoint")
  void acceptsCapabilityRestrictedMacaroonOnMatchingEndpoint() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      // Get a macaroon from /api/v1/search (has capabilities caveat for "search")
      L402Credential credential = obtainL402Credential(client, SEARCH_PATH);

      // Use it on /api/v1/search (matching capability)
      String authHeaderValue =
          "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + SEARCH_PATH))
              .header("Authorization", authHeaderValue)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("No-capability macaroon is accepted on endpoint without capability (no regression)")
  void acceptsUnrestrictedMacaroonOnEndpointWithoutCapability() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      // Get a macaroon from /api/v1/data (authenticated no-capability ceiling)
      L402Credential credential = obtainL402Credential(client, DATA_PATH);

      // Use it on /api/v1/data (same endpoint, no capability)
      String authHeaderValue =
          "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl() + DATA_PATH))
              .header("Authorization", authHeaderValue)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  /**
   * Performs the full L402 test-mode flow: requests the endpoint without auth to get a 402
   * challenge, extracts the macaroon and test preimage, and returns them as a credential record.
   */
  @SuppressWarnings("unchecked")
  private L402Credential obtainL402Credential(HttpClient client, String path) throws Exception {
    // Step 1: Request without auth to get the 402 challenge
    var challengeRequest = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
    var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(challengeResponse.statusCode()).isEqualTo(402);

    // Step 2: Extract the macaroon from the L402 WWW-Authenticate header
    List<String> wwwAuthHeaders = challengeResponse.headers().allValues("WWW-Authenticate");
    String l402Header =
        wwwAuthHeaders.stream()
            .filter(h -> h.startsWith("L402"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("No L402 WWW-Authenticate header in 402 response"));
    Matcher macaroonMatcher = MACAROON_PATTERN.matcher(l402Header);
    assertThat(macaroonMatcher.find()).as("L402 header should contain macaroon").isTrue();
    String macaroonBase64 = macaroonMatcher.group(1);

    // Step 3: Extract the test_preimage from the response body
    Map<String, Object> body = MAPPER.readValue(challengeResponse.body(), Map.class);
    assertThat(body).containsKey("test_preimage");
    String preimageHex = (String) body.get("test_preimage");
    assertThat(preimageHex).isNotNull().hasSize(64);

    return new L402Credential(macaroonBase64, preimageHex);
  }

  private HttpResponse<String> sendAuthenticated(
      HttpClient client, String path, L402Credential credential) throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header(
                "Authorization",
                "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex())
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String authorizationHeader(L402Credential credential) {
    return "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
  }

  private L402VerificationContext verificationContext(String route, String capability) {
    var metadata = new LinkedHashMap<String, String>();
    metadata.put(VerificationContextKeys.REQUEST_PATH, route);
    metadata.put(VerificationContextKeys.REQUEST_ROUTE, route);
    metadata.put(VerificationContextKeys.REQUEST_METHOD, "GET");
    if (capability != null) {
      metadata.put(VerificationContextKeys.REQUESTED_CAPABILITY, capability);
    }
    return L402VerificationContext.builder()
        .serviceName(SERVICE_NAME)
        .currentTime(Instant.now())
        .requestMetadata(metadata)
        .build();
  }

  private List<String> capabilityCaveats(String macaroonBase64) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(macaroonBase64));
    return macaroon.caveats().stream()
        .filter(caveat -> caveat.key().equals(SERVICE_NAME + "_capabilities"))
        .map(Caveat::value)
        .toList();
  }

  /** Performs ordinary first-party holder attenuation by extending the signed caveat chain. */
  private L402Credential attenuateCapability(
      L402Credential credential, String caveatKey, String capability) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroonBase64()));
    Caveat attenuation = new Caveat(caveatKey, capability);
    var caveats = new ArrayList<>(macaroon.caveats());
    caveats.add(attenuation);
    byte[] signature =
        MacaroonCrypto.hmac(
            macaroon.signature(), attenuation.toString().getBytes(StandardCharsets.UTF_8));
    Macaroon attenuated =
        new Macaroon(macaroon.identifier(), macaroon.location(), caveats, signature);
    return new L402Credential(
        Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated)),
        credential.preimageHex());
  }

  private record L402Credential(String macaroonBase64, String preimageHex) {}
}
