package com.greenharborlabs.paygate.integration;

import com.greenharborlabs.paygate.example.ExampleApplication;

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
 * Integration test exercising both L402 and MPP dual-protocol flows.
 *
 * <p>Boots the full example application in test mode with both protocols
 * enabled (via the challenge-binding-secret property). Verifies:
 * <ul>
 *   <li>Both WWW-Authenticate headers appear on 402</li>
 *   <li>Both protocols share a single Lightning invoice</li>
 *   <li>Full L402 flow: 402 -> pay -> credential -> 200</li>
 *   <li>Full MPP flow: 402 -> pay -> credential -> 200 + receipt</li>
 * </ul>
 *
 * <p>A nested inner class tests L402-only mode (no MPP secret) for
 * backward compatibility.
 */
@Tag("integration")
@SpringBootTest(
        classes = ExampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=example-api",
        "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long"
})
@DisplayName("Dual-protocol integration (L402 + MPP)")
class DualProtocolIntegrationTest {

    private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
    private static final Pattern INVOICE_PATTERN = Pattern.compile("invoice=\"([^\"]+)\"");
    private static final String DATA_PATH = "/api/v1/data";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("402 response contains both L402 and Payment WWW-Authenticate headers")
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

    @Test
    @DisplayName("Full L402 flow: 402 challenge -> extract macaroon + preimage -> 200")
    @SuppressWarnings("unchecked")
    void fullL402FlowGrantsAccess() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Step 1: Request without auth to get the 402 challenge
            var challengeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(challengeResponse.statusCode()).isEqualTo(402);

            // Step 2: Extract the macaroon from the L402 WWW-Authenticate header
            List<String> wwwAuthHeaders = challengeResponse.headers().allValues("WWW-Authenticate");
            String l402Header = wwwAuthHeaders.stream()
                    .filter(h -> h.startsWith("L402"))
                    .findFirst().orElseThrow();
            Matcher macaroonMatcher = MACAROON_PATTERN.matcher(l402Header);
            assertThat(macaroonMatcher.find()).as("L402 header should contain macaroon").isTrue();
            String macaroonBase64 = macaroonMatcher.group(1);

            // Step 3: Extract the test_preimage from the response body
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

            // L402 success includes credential expiry header
            assertThat(authenticatedResponse.headers().firstValue("X-L402-Credential-Expires"))
                    .isPresent();
        }
    }

    @Test
    @DisplayName("Full MPP flow: 402 challenge -> build credential -> 200 + Payment-Receipt")
    @SuppressWarnings("unchecked")
    void fullMppFlowGrantsAccessWithReceipt() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Step 1: Request without auth to get the 402 challenge
            var challengeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .GET().build();
            var challengeResponse = client.send(challengeRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(challengeResponse.statusCode()).isEqualTo(402);

            // Step 2: Parse the response body to get MPP challenge fields and test_preimage
            Map<String, Object> body = MAPPER.readValue(challengeResponse.body(), Map.class);
            assertThat(body).containsKey("test_preimage");
            String preimageHex = (String) body.get("test_preimage");
            assertThat(preimageHex).isNotNull().hasSize(64);

            Map<String, Object> protocols = (Map<String, Object>) body.get("protocols");
            assertThat(protocols).containsKey("Payment");
            Map<String, Object> paymentChallenge = (Map<String, Object>) protocols.get("Payment");

            // Step 3: Build the MPP credential JSON
            // The credential echoes back all challenge fields under "challenge"
            // and includes the preimage in "payload"
            String credentialJson = MAPPER.writeValueAsString(Map.of(
                    "challenge", paymentChallenge,
                    "source", "test-client",
                    "payload", Map.of("preimage", preimageHex)
            ));

            // Step 4: Base64url-nopad encode the credential
            String encodedCredential = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(credentialJson.getBytes(StandardCharsets.UTF_8));

            // Step 5: Send the authenticated request
            var authenticatedRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + DATA_PATH))
                    .header("Authorization", "Payment " + encodedCredential)
                    .GET().build();
            var authenticatedResponse = client.send(authenticatedRequest, HttpResponse.BodyHandlers.ofString());

            // Step 6: Verify access is granted
            assertThat(authenticatedResponse.statusCode()).isEqualTo(200);
            Map<String, Object> dataBody = MAPPER.readValue(authenticatedResponse.body(), Map.class);
            assertThat(dataBody.get("data")).isEqualTo("premium content");

            // Step 7: Verify Payment-Receipt header is present and decodable
            String receiptHeader = authenticatedResponse.headers()
                    .firstValue("Payment-Receipt").orElse(null);
            assertThat(receiptHeader).as("Payment-Receipt header should be present").isNotNull();

            // Decode the base64url-nopad receipt
            byte[] receiptBytes = Base64.getUrlDecoder().decode(receiptHeader);
            String receiptJson = new String(receiptBytes, StandardCharsets.UTF_8);
            Map<String, Object> receipt = MAPPER.readValue(receiptJson, Map.class);
            assertThat(receipt).containsKey("status");
            assertThat(receipt.get("status")).isEqualTo("success");
            assertThat(receipt).containsKey("method");
            assertThat(receipt.get("method")).isEqualTo("lightning");
            assertThat(receipt).containsKey("amount_sats");
            assertThat(receipt).containsKey("challenge_id");
            assertThat(receipt).containsKey("timestamp");
            assertThat(receipt).containsKey("protocol_scheme");
            assertThat(receipt.get("protocol_scheme")).isEqualTo("Payment");

            // Step 8: Verify Cache-Control: private header (MPP receipt response)
            assertThat(authenticatedResponse.headers().firstValue("Cache-Control"))
                    .hasValue("private");
        }
    }

    /**
     * Tests L402-only mode by booting the app without an MPP challenge-binding-secret.
     * This verifies backward compatibility: only the L402 WWW-Authenticate header appears.
     */
    @Nested
    @Tag("integration")
    @SpringBootTest(
            classes = ExampleApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
            "paygate.enabled=true",
            "paygate.test-mode=true",
            "paygate.root-key-store=memory",
            "paygate.service-name=example-api",
            "paygate.protocols.mpp.challenge-binding-secret="
    })
    @DisplayName("L402-only mode (no MPP secret)")
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
                // L402 does not contribute bodyData to the protocols map,
                // so protocols will be empty (no Payment entry)
                @SuppressWarnings("unchecked")
                Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> protocols = (Map<String, Object>) body.get("protocols");
                assertThat(protocols).doesNotContainKey("Payment");
            }
        }
    }
}
