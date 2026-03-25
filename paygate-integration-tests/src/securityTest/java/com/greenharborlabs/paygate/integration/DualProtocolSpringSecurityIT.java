package com.greenharborlabs.paygate.integration;

import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising dual-protocol (L402 + MPP) header ordering
 * through the Spring Security filter chain ({@code PaygateAuthenticationFilter}).
 *
 * <p>Parallel to {@link DualProtocolIntegrationTest} which tests the servlet
 * filter path via the plain example app.
 */
@Tag("integration")
@SpringBootTest(
        classes = SecurityExampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=example-api",
        "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long"
})
@DisplayName("Dual-protocol Spring Security integration (L402 + MPP)")
class DualProtocolSpringSecurityIT {

    private static final Pattern INVOICE_PATTERN = Pattern.compile("invoice=\"([^\"]+)\"");
    private static final String DATA_PATH = "/api/v1/data";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("402 response contains both L402 and Payment WWW-Authenticate headers in order")
    void dualProtocol402HasBothHeaders() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(402);

            List<String> wwwAuthHeaders = response.headers().allValues("WWW-Authenticate");
            assertThat(wwwAuthHeaders).hasSize(2);
            assertThat(wwwAuthHeaders.get(0)).startsWith("L402");
            assertThat(wwwAuthHeaders.get(1)).startsWith("Payment");
        }
    }

    @Test
    @DisplayName("L402 and MPP challenges share the same Lightning invoice")
    @SuppressWarnings("unchecked")
    void dualProtocol402SharesSingleInvoice() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(402);

            // Extract invoice from L402 WWW-Authenticate header
            List<String> wwwAuthHeaders = response.headers().allValues("WWW-Authenticate");
            String l402Header = wwwAuthHeaders.stream()
                    .filter(h -> h.startsWith("L402"))
                    .findFirst().orElseThrow();
            Matcher invoiceMatcher = INVOICE_PATTERN.matcher(l402Header);
            assertThat(invoiceMatcher.find()).as("L402 header should contain invoice").isTrue();
            String l402Invoice = invoiceMatcher.group(1);

            // Extract invoice from MPP Payment challenge via the response body
            Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
            Map<String, Object> protocols = (Map<String, Object>) body.get("protocols");
            assertThat(protocols).containsKey("Payment");
            Map<String, Object> paymentChallenge = (Map<String, Object>) protocols.get("Payment");
            String requestB64 = (String) paymentChallenge.get("request");
            assertThat(requestB64).isNotNull();

            // Decode the base64url-nopad request field to get the charge request JSON
            byte[] requestBytes = Base64.getUrlDecoder().decode(requestB64);
            String requestJson = new String(requestBytes, StandardCharsets.UTF_8);
            Map<String, Object> chargeRequest = MAPPER.readValue(requestJson, Map.class);

            // The charge request contains method details with the bolt11 invoice
            Map<String, Object> methodDetails = (Map<String, Object>) chargeRequest.get("methodDetails");
            assertThat(methodDetails).isNotNull();
            String mppInvoice = (String) methodDetails.get("invoice");
            assertThat(mppInvoice).isNotNull();

            // Both protocols reference the same invoice
            assertThat(l402Invoice).isEqualTo(mppInvoice);

            // The top-level invoice field also matches
            String bodyInvoice = (String) body.get("invoice");
            assertThat(bodyInvoice).isEqualTo(l402Invoice);
        }
    }

    /**
     * Tests L402-only mode through Spring Security by booting the app
     * without an MPP challenge-binding-secret.
     */
    @Nested
    @Tag("integration")
    @SpringBootTest(
            classes = SecurityExampleApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
            "paygate.enabled=true",
            "paygate.test-mode=true",
            "paygate.root-key-store=memory",
            "paygate.service-name=example-api",
            "paygate.protocols.mpp.challenge-binding-secret="
    })
    @DisplayName("L402-only mode (no MPP secret) via Spring Security")
    class L402OnlyModeTest {

        @LocalServerPort
        private int port;

        private String baseUrl() {
            return "http://localhost:" + port;
        }

        @Test
        @DisplayName("Without MPP secret, only L402 WWW-Authenticate header appears")
        void l402OnlyModeWhenNoMppSecret() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + DATA_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(402);

                List<String> wwwAuthHeaders = response.headers().allValues("WWW-Authenticate");
                assertThat(wwwAuthHeaders)
                        .as("Only L402 header should be present when MPP secret is not configured")
                        .hasSize(1);
                assertThat(wwwAuthHeaders.getFirst()).startsWith("L402");

                // Verify no Payment protocol in the body
                @SuppressWarnings("unchecked")
                Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> protocols = (Map<String, Object>) body.get("protocols");
                assertThat(protocols).doesNotContainKey("Payment");
            }
        }
    }
}
