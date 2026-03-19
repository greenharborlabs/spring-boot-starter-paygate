package com.greenharborlabs.l402.integration;

import com.greenharborlabs.l402.example.ExampleApplication;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising the full L402 happy path:
 * unauthenticated request receives a 402 challenge with a macaroon and
 * test-mode preimage, then a subsequent request with a valid L402
 * Authorization header receives 200.
 *
 * <p>Uses the example application in test mode so no real Lightning
 * backend or Docker container is required.
 */
@Tag("integration")
@SpringBootTest(
        classes = ExampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "l402.enabled=true",
        "l402.test-mode=true",
        "l402.root-key-store=memory",
        "l402.service-name=example-api"
})
@DisplayName("L402 happy path (test mode)")
class LnbitsHappyPathIT {

    private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
    private static final String DATA_PATH = "/api/v1/data";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("unauthenticated request to protected endpoint returns 402 with WWW-Authenticate header")
    void unauthenticatedRequestReturns402() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(402);
            String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse(null);
            assertThat(wwwAuth)
                    .isNotNull()
                    .startsWith("L402")
                    .contains("macaroon=")
                    .contains("invoice=");
        }
    }

    @Test
    @DisplayName("health endpoint responds 200 without authentication")
    void healthEndpointIsUnprotected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/api/v1/health"))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("ok");
        }
    }

    @Test
    @DisplayName("full L402 flow: 402 challenge -> extract macaroon + preimage -> 200 with valid credential")
    @SuppressWarnings("unchecked")
    void fullL402FlowGrantsAccess() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Step 1: Request without auth to get the 402 challenge
            var challengeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(challengeResponse.statusCode()).isEqualTo(402);

            // Step 2: Extract the macaroon from the WWW-Authenticate header
            String wwwAuth = challengeResponse.headers().firstValue("WWW-Authenticate").orElse(null);
            assertThat(wwwAuth).isNotNull();
            Matcher macaroonMatcher = MACAROON_PATTERN.matcher(wwwAuth);
            assertThat(macaroonMatcher.find()).as("WWW-Authenticate header should contain macaroon").isTrue();
            String macaroonBase64 = macaroonMatcher.group(1);

            // Step 3: Extract the test_preimage from the response body (only available in test mode)
            Map<String, Object> body = MAPPER.readValue(challengeResponse.body(), Map.class);
            assertThat(body).containsKey("test_preimage");
            String preimageHex = (String) body.get("test_preimage");
            assertThat(preimageHex).isNotNull().hasSize(64);

            // Step 4: Construct the L402 Authorization header and request again
            String authHeaderValue = "L402 " + macaroonBase64 + ":" + preimageHex;
            var authenticatedRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var authenticatedResponse = client.send(authenticatedRequest, HttpResponse.BodyHandlers.ofString());

            // Step 5: Verify access is granted
            assertThat(authenticatedResponse.statusCode()).isEqualTo(200);
            Map<String, Object> dataBody = MAPPER.readValue(authenticatedResponse.body(), Map.class);
            assertThat(dataBody.get("data")).isEqualTo("premium content");

            // Verify the credential expiry header is present
            assertThat(authenticatedResponse.headers().firstValue("X-L402-Credential-Expires"))
                    .isPresent();
        }
    }

    @Test
    @DisplayName("402 response body contains expected JSON fields")
    @SuppressWarnings("unchecked")
    void challengeResponseBodyContainsExpectedFields() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(402);
            Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
            assertThat(body.get("code")).isEqualTo(402);
            assertThat(body.get("price_sats")).isEqualTo(10);
            assertThat(body.get("invoice")).isNotNull();
            assertThat(body.get("test_preimage")).isNotNull();
        }
    }
}
