package com.greenharborlabs.paygate.integration;

import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Integration test verifying protocol-agnostic capability enforcement
 * through the Spring Security filter chain.
 *
 * <p>Proves that L402 credentials with capability caveats receive both
 * {@code L402_CAPABILITY_*} and {@code PAYGATE_CAPABILITY_*} authorities,
 * and that {@code @PreAuthorize} expressions using either prefix work correctly.
 */
@Tag("integration")
@SpringBootTest(
        classes = {SecurityExampleApplication.class, ProtocolAgnosticCapabilityIT.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=example-api",
        "paygate.protocols.mpp.challenge-binding-secret=test-secret-that-is-at-least-32-bytes-long-for-hmac"
})
@DisplayName("Protocol-agnostic capability enforcement via Spring Security")
class ProtocolAgnosticCapabilityIT {

    private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
    private static final String PAYGATE_CAP_PATH = "/api/v1/cap-test";
    private static final String L402_CAP_PATH = "/api/v1/cap-test-legacy";
    private static final String NO_CAP_PATH = "/api/v1/data";
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
            @PreAuthorize("hasAuthority('PAYGATE_CAPABILITY_search')")
            @GetMapping("/cap-test")
            public Map<String, String> paygateCapSearch() {
                return Map.of("data", "paygate capability search results");
            }

            @PaymentRequired(priceSats = 10, capability = "search")
            @PreAuthorize("hasAuthority('L402_CAPABILITY_search')")
            @GetMapping("/cap-test-legacy")
            public Map<String, String> l402CapSearch() {
                return Map.of("data", "l402 capability search results");
            }
        }
    }

    @Test
    @DisplayName("L402 with search capability gets 200 on PAYGATE_CAPABILITY-protected endpoint")
    void l402WithCapabilityAccessesPaygateCapabilityEndpoint() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            L402Credential credential = obtainL402Credential(client, PAYGATE_CAP_PATH);

            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + PAYGATE_CAP_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode())
                    .as("L402 credential with search capability should access PAYGATE_CAPABILITY_search endpoint")
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("L402 without search capability is rejected on PAYGATE_CAPABILITY-protected endpoint")
    void l402WithoutCapabilityRejectedOnPaygateCapabilityEndpoint() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Obtain a credential from an endpoint without capability requirement
            L402Credential credential = obtainL402Credential(client, NO_CAP_PATH);

            // Try to use it on the capability-protected endpoint
            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + PAYGATE_CAP_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // The credential lacks PAYGATE_CAPABILITY_search, so @PreAuthorize denies access.
            // Spring Security returns 403, but the /error page may trigger the entry point
            // (which returns 401) since SecurityConfig requires authentication on all paths.
            // Either way, the request must not succeed.
            assertThat(response.statusCode())
                    .as("L402 credential without search capability must not access PAYGATE_CAPABILITY_search endpoint")
                    .isNotEqualTo(200);
        }
    }

    @Test
    @DisplayName("L402 with search capability still works with L402_CAPABILITY prefix (backward compat)")
    void l402WithCapabilityAccessesLegacyL402CapabilityEndpoint() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            L402Credential credential = obtainL402Credential(client, L402_CAP_PATH);

            String authHeaderValue = "L402 " + credential.macaroonBase64() + ":" + credential.preimageHex();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + L402_CAP_PATH))
                    .header("Authorization", authHeaderValue)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode())
                    .as("L402 credential with search capability should access L402_CAPABILITY_search endpoint (backward compat)")
                    .isEqualTo(200);
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
