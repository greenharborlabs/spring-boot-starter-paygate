package com.greenharborlabs.paygate.integration;

import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying fail-closed capability enforcement in the
 * servlet filter path.
 *
 * <p>A macaroon minted for an endpoint with a capability caveat must be
 * rejected when presented to an endpoint that does not declare a capability.
 * Conversely, a macaroon without capability caveats (unrestricted) must
 * continue to work on endpoints without capability requirements.
 */
@Tag("integration")
@SpringBootTest(
        classes = {ExampleApplication.class, CapabilityEnforcementIT.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=example-api"
})
@DisplayName("Capability enforcement across servlet filter path")
class CapabilityEnforcementIT {

    private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
    private static final String DATA_PATH = "/api/v1/data";
    private static final String SEARCH_PATH = "/api/v1/search";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @TestConfiguration
    static class TestConfig {

        @RestController
        @RequestMapping("/api/v1")
        static class CapabilityTestController {

            @PaymentRequired(priceSats = 10, capability = "search")
            @GetMapping("/search")
            public Map<String, String> search() {
                return Map.of("data", "search results");
            }
        }
    }

    @Test
    @DisplayName("Capability-restricted macaroon is rejected on endpoint without capability")
    void rejectsCapabilityRestrictedMacaroonOnEndpointWithoutCapability() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Get a macaroon from /api/v1/search (has capabilities caveat for "search")
            L402Credential credential = obtainL402Credential(client, SEARCH_PATH);

            // Try to use it on /api/v1/data (no capability declared)
            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
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
            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + SEARCH_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Unrestricted macaroon is accepted on endpoint without capability (no regression)")
    void acceptsUnrestrictedMacaroonOnEndpointWithoutCapability() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Get a macaroon from /api/v1/data (no capabilities caveat)
            L402Credential credential = obtainL402Credential(client, DATA_PATH);

            // Use it on /api/v1/data (same endpoint, no capability)
            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    /**
     * Performs the full L402 test-mode flow: requests the endpoint without auth
     * to get a 402 challenge, extracts the macaroon and test preimage, and
     * returns them as a credential record.
     */
    @SuppressWarnings("unchecked")
    private L402Credential obtainL402Credential(HttpClient client, String path) throws Exception {
        // Step 1: Request without auth to get the 402 challenge
        var challengeRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET().build();
        var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(challengeResponse.statusCode()).isEqualTo(402);

        // Step 2: Extract the macaroon from the L402 WWW-Authenticate header
        List<String> wwwAuthHeaders = challengeResponse.headers().allValues("WWW-Authenticate");
        String l402Header = wwwAuthHeaders.stream()
                .filter(h -> h.startsWith("L402"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No L402 WWW-Authenticate header in 402 response"));
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

    private record L402Credential(String macaroonBase64, String preimageHex) {}
}
