package com.greenharborlabs.l402.integration;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.example.ExampleApplication;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the fail-closed security property: when the
 * Lightning backend is unreachable or unhealthy, the server must return
 * 503 Service Unavailable instead of granting access or returning 500.
 *
 * <p>Uses a controllable stub Lightning backend injected via a
 * {@link TestConfiguration} that overrides the auto-configured backend.
 * The backend is stored in a static field because the auto-configuration
 * wraps it with caching/timeout decorators, making {@code @Autowired}
 * injection by concrete type impossible.
 */
@Tag("integration")
@SpringBootTest(
        classes = {ExampleApplication.class, FailClosedIT.FailClosedTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "l402.enabled=true",
        "l402.test-mode=false",
        "l402.root-key-store=memory",
        "l402.service-name=example-api",
        "l402.backend=lnbits",
        "l402.lnbits.url=http://localhost:1",
        "l402.lnbits.api-key=fake-key-for-testing",
        "l402.health-cache.enabled=false"
})
@DisplayName("L402 fail-closed behavior")
class FailClosedIT {

    private static final String DATA_PATH = "/api/v1/data";
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Shared controllable backend instance -- set by the TestConfiguration bean factory. */
    private static volatile ControllableBackend BACKEND;

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @TestConfiguration
    static class FailClosedTestConfig {

        @Bean
        @Primary
        LightningBackend controllableLightningBackend() {
            var backend = new ControllableBackend();
            BACKEND = backend;
            return backend;
        }
    }

    static class ControllableBackend implements LightningBackend {

        private volatile boolean healthy = true;
        private volatile RuntimeException createInvoiceException;

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        void setCreateInvoiceException(RuntimeException exception) {
            this.createInvoiceException = exception;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            if (createInvoiceException != null) {
                throw createInvoiceException;
            }
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc1pstub", 1, null,
                    InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }

    @Nested
    @DisplayName("when Lightning backend reports unhealthy")
    class BackendUnhealthy {

        @BeforeEach
        void setUp() {
            BACKEND.setHealthy(false);
            BACKEND.setCreateInvoiceException(null);
        }

        @Test
        @DisplayName("protected endpoint returns 503 LIGHTNING_UNAVAILABLE")
        @SuppressWarnings("unchecked")
        void protectedEndpointReturns503() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + DATA_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(503);
                Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
                assertThat(body.get("code")).isEqualTo(503);
                assertThat(body.get("error")).isEqualTo("LIGHTNING_UNAVAILABLE");
            }
        }

        @Test
        @DisplayName("unprotected health endpoint remains accessible")
        void healthEndpointStillWorks() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + HEALTH_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("ok");
            }
        }
    }

    @Nested
    @DisplayName("when Lightning backend throws on invoice creation")
    class InvoiceCreationFails {

        @BeforeEach
        void setUp() {
            BACKEND.setHealthy(true);
            BACKEND.setCreateInvoiceException(
                    new RuntimeException("Connection refused: localhost:1"));
        }

        @Test
        @DisplayName("protected endpoint returns 503 LIGHTNING_UNAVAILABLE")
        @SuppressWarnings("unchecked")
        void protectedEndpointReturns503() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + DATA_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(503);
                Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
                assertThat(body.get("code")).isEqualTo(503);
                assertThat(body.get("error")).isEqualTo("LIGHTNING_UNAVAILABLE");
            }
        }

        @Test
        @DisplayName("unprotected health endpoint remains accessible")
        void healthEndpointStillWorks() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + HEALTH_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("ok");
            }
        }
    }

    @Nested
    @DisplayName("baseline: when Lightning backend is healthy")
    class BackendHealthy {

        @BeforeEach
        void setUp() {
            BACKEND.setHealthy(true);
            BACKEND.setCreateInvoiceException(null);
        }

        @Test
        @DisplayName("protected endpoint returns 402 challenge (not 503)")
        void protectedEndpointReturns402() throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + DATA_PATH))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(402);
                String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse(null);
                assertThat(wwwAuth).isNotNull().startsWith("L402");
            }
        }
    }
}
