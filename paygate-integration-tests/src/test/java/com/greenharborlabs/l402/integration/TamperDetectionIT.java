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
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that tampered macaroons are rejected.
 *
 * <p>Obtains a valid macaroon via the test-mode 402 challenge, flips a byte
 * in the serialized macaroon, and verifies the server rejects it. This
 * exercises the HMAC signature verification in the full request pipeline.
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
@DisplayName("L402 tamper detection")
class TamperDetectionIT {

    private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
    private static final String DATA_PATH = "/api/v1/data";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("tampered macaroon is rejected with 4xx status")
    @SuppressWarnings("unchecked")
    void tamperedMacaroonIsRejected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Step 1: Get a valid challenge
            var challengeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(challengeResponse.statusCode()).isEqualTo(402);

            // Step 2: Extract macaroon and preimage
            String wwwAuth = challengeResponse.headers().firstValue("WWW-Authenticate").orElse(null);
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
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
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
            var challengeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(challengeResponse.statusCode()).isEqualTo(402);

            // Step 2: Extract macaroon (valid) but use a bogus preimage
            String wwwAuth = challengeResponse.headers().firstValue("WWW-Authenticate").orElse(null);
            assertThat(wwwAuth).isNotNull();
            Matcher macaroonMatcher = MACAROON_PATTERN.matcher(wwwAuth);
            assertThat(macaroonMatcher.find()).isTrue();
            String macaroonBase64 = macaroonMatcher.group(1);

            // Bogus preimage: 32 bytes of zeros
            String bogusPreimage = "0".repeat(64);

            // Step 3: Present valid macaroon with wrong preimage
            String authHeaderValue = "L402 " + macaroonBase64 + ":" + bogusPreimage;
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Step 4: Verify rejection — preimage hash won't match the payment hash in the identifier
            assertThat(response.statusCode())
                    .as("Wrong preimage should be rejected with 4xx status")
                    .isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("completely invalid Authorization header is rejected with 400")
    @SuppressWarnings("unchecked")
    void invalidAuthorizationHeaderIsRejected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", "L402 not-valid-at-all")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
            assertThat(body.get("error")).isEqualTo("MALFORMED_HEADER");
        }
    }
}
