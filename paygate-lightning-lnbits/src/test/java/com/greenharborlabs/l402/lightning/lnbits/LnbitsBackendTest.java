package com.greenharborlabs.l402.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.lightning.LightningTimeoutException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LnbitsBackendTest {

    private static final String API_KEY = "test-api-key-abc123";
    private static final HexFormat HEX = HexFormat.of();

    // A valid 32-byte payment hash (hex-encoded = 64 chars)
    private static final String PAYMENT_HASH_HEX =
            "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private static final byte[] PAYMENT_HASH = HEX.parseHex(PAYMENT_HASH_HEX);

    private static final String BOLT11 =
            "lnbc100n1pjexamplefakedatafortesting";

    private MockWebServer server;
    private LnbitsBackend backend;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        objectMapper = new ObjectMapper();
        var config = new LnbitsConfig(
                server.url("/").toString(),
                API_KEY
        );
        backend = new LnbitsBackend(config, objectMapper, HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void createInvoice_postsToPaymentsEndpoint() throws Exception {
        // Given: LNbits returns a successful create-invoice response
        String responseBody = """
                {
                    "payment_hash": "%s",
                    "payment_request": "%s",
                    "checking_id": "some-id"
                }
                """.formatted(PAYMENT_HASH_HEX, BOLT11);

        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        // When
        Invoice invoice = backend.createInvoice(100L, "test memo");

        // Then: correct endpoint and method
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/payments");

        // Then: correct request body
        var requestBody = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(requestBody.get("out").asBoolean()).isFalse();
        assertThat(requestBody.get("amount").asLong()).isEqualTo(100L);
        assertThat(requestBody.get("memo").asText()).isEqualTo("test memo");

        // Then: correct X-Api-Key header
        assertThat(request.getHeader("X-Api-Key")).isEqualTo(API_KEY);

        // Then: response is mapped to a valid Invoice
        assertThat(invoice).isNotNull();
        assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
        assertThat(invoice.bolt11()).isEqualTo(BOLT11);
        assertThat(invoice.amountSats()).isEqualTo(100L);
        assertThat(invoice.memo()).isEqualTo("test memo");
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    void lookupInvoice_getsPaymentByHash() throws Exception {
        // Given: LNbits returns an unpaid invoice
        String responseBody = """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "memo": "lookup memo",
                        "time": 1700000000,
                        "expiry": 3600
                    }
                }
                """.formatted(PAYMENT_HASH_HEX, BOLT11);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        // When
        Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

        // Then: correct endpoint, method, and hash in URL
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/payments/" + PAYMENT_HASH_HEX);

        // Then: correct header
        assertThat(request.getHeader("X-Api-Key")).isEqualTo(API_KEY);

        // Then: unpaid maps to PENDING
        assertThat(invoice).isNotNull();
        assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.amountSats()).isEqualTo(100);
    }

    @Test
    void lookupInvoice_settledInvoice_returnsSettledStatus() throws Exception {
        // Given: LNbits returns a paid invoice with preimage
        String preimageHex =
                "ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00";
        String responseBody = """
                {
                    "paid": true,
                    "preimage": "%s",
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 250000,
                        "memo": "settled memo",
                        "time": 1700000000,
                        "expiry": 3600
                    }
                }
                """.formatted(preimageHex, PAYMENT_HASH_HEX, BOLT11);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        // When
        Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

        // Then
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(invoice.preimage()).isEqualTo(HEX.parseHex(preimageHex));
        assertThat(invoice.amountSats()).isEqualTo(250);
    }

    @Test
    void isHealthy_returnsTrue_when200() throws Exception {
        // Given: wallet endpoint returns 200
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id": "wallet-id", "name": "test", "balance": 1000}
                        """));

        // When / Then
        assertThat(backend.isHealthy()).isTrue();

        // Verify correct endpoint, method, and auth header
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/wallet");
        assertThat(request.getHeader("X-Api-Key")).isEqualTo(API_KEY);
    }

    @Test
    void isHealthy_returnsFalse_whenNon200() {
        // Given: wallet endpoint returns 500
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        // When / Then
        assertThat(backend.isHealthy()).isFalse();
    }

    @Test
    void allRequests_includeApiKeyHeader() throws Exception {
        // Enqueue responses for three different request types
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """.formatted(PAYMENT_HASH_HEX, BOLT11)));

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"paid": false, "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000, "memo": "m", "time": 1700000000, "expiry": 3600}}
                        """.formatted(PAYMENT_HASH_HEX, BOLT11)));

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id": "w", "name": "test", "balance": 0}
                        """));

        // Execute all three request types
        backend.createInvoice(100L, "memo");
        backend.lookupInvoice(PAYMENT_HASH);
        backend.isHealthy();

        // Verify X-Api-Key header on every request
        for (int i = 0; i < 3; i++) {
            RecordedRequest request = server.takeRequest();
            assertThat(request.getHeader("X-Api-Key"))
                    .as("Request #%d (%s %s) must include X-Api-Key",
                            i + 1, request.getMethod(), request.getPath())
                    .isEqualTo(API_KEY);
        }
    }

    @Test
    void createInvoice_throws_when4xxResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized"));

        assertThatThrownBy(() -> backend.createInvoice(100L, "test memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Unauthorized");
    }

    @Test
    void createInvoice_throws_when5xxResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        assertThatThrownBy(() -> backend.createInvoice(100L, "test memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("Internal Server Error");
    }

    @Test
    void lookupInvoice_throws_when4xxResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("Not Found"));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("Not Found");
    }

    @Test
    void lookupInvoice_throws_when5xxResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody("Bad Gateway"));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 502")
                .hasMessageContaining("Bad Gateway");
    }

    // Verify the backend implements the LightningBackend interface
    @Test
    void implementsLightningBackendInterface() {
        assertThat(backend).isInstanceOf(LightningBackend.class);
    }

    // --- Input validation tests ---

    @Test
    void lookupInvoice_throws_whenPaymentHashIsNull() {
        assertThatThrownBy(() -> backend.lookupInvoice(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");

        // No HTTP request should have been made
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void lookupInvoice_throws_whenPaymentHashIsEmpty() {
        assertThatThrownBy(() -> backend.lookupInvoice(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void lookupInvoice_throws_whenPaymentHashTooShort() {
        assertThatThrownBy(() -> backend.lookupInvoice(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void lookupInvoice_throws_whenPaymentHashTooLong() {
        assertThatThrownBy(() -> backend.lookupInvoice(new byte[33]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");

        assertThat(server.getRequestCount()).isZero();
    }

    // --- Malformed response tests ---

    @Test
    void createInvoice_throws_whenPaymentHashMissing() {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"payment_request": "%s"}
                        """.formatted(BOLT11)));

        assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'payment_hash'");
    }

    @Test
    void createInvoice_throws_whenPaymentRequestMissing() {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"payment_hash": "%s"}
                        """.formatted(PAYMENT_HASH_HEX)));

        assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'payment_request'");
    }

    @Test
    void lookupInvoice_throws_whenPaidFieldMissing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"details": {"bolt11": "%s", "amount": 100}}
                        """.formatted(BOLT11)));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'paid'");
    }

    @Test
    void lookupInvoice_throws_whenDetailsMissing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"paid": false}
                        """));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'details'");
    }

    @Test
    void lookupInvoice_throws_whenDetailsBolt11Missing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"paid": false, "details": {"amount": 100}}
                        """));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'details.bolt11'");
    }

    @Test
    void lookupInvoice_throws_whenDetailsAmountMissing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"paid": false, "details": {"bolt11": "%s"}}
                        """.formatted(BOLT11)));

        assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("Missing 'details.amount'");
    }

    // --- Timestamp parsing tests ---

    @Test
    void lookupInvoice_parsesTimestampsFromDetails() throws Exception {
        // Given: LNbits returns a response with known time and expiry values
        long creationEpoch = 1700000000L;
        long expirySeconds = 7200L;
        String responseBody = """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "memo": "timestamp test",
                        "time": %d,
                        "expiry": %d
                    }
                }
                """.formatted(PAYMENT_HASH_HEX, BOLT11, creationEpoch, expirySeconds);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        // When
        Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

        // Then: timestamps are parsed from the response, not generated with Instant.now()
        assertThat(invoice.createdAt()).isEqualTo(Instant.ofEpochSecond(creationEpoch));
        assertThat(invoice.expiresAt()).isEqualTo(Instant.ofEpochSecond(creationEpoch + expirySeconds));
    }

    @Test
    void lookupInvoice_fallsBackToNow_whenTimeAndExpiryMissing() throws Exception {
        // Given: LNbits returns a response without time or expiry fields
        String responseBody = """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "memo": "no timestamps"
                    }
                }
                """.formatted(PAYMENT_HASH_HEX, BOLT11);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        // When
        Instant before = Instant.now();
        Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);
        Instant after = Instant.now();

        // Then: createdAt falls back to approximately now
        assertThat(invoice.createdAt()).isBetween(before, after);
        // Then: expiresAt is createdAt + 1 hour (DEFAULT_INVOICE_EXPIRY)
        assertThat(invoice.expiresAt()).isEqualTo(invoice.createdAt().plus(Duration.ofHours(1)));
    }

    // --- Timeout tests ---

    private LnbitsBackend backendWithShortTimeout() {
        var config = new LnbitsConfig(
                server.url("/").toString(),
                API_KEY,
                1  // 1-second timeout for fast tests
        );
        return new LnbitsBackend(config, objectMapper, HttpClient.newHttpClient());
    }

    @Test
    void createInvoice_throwsLnbitsTimeoutException_onTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        var shortTimeoutBackend = backendWithShortTimeout();

        assertThatThrownBy(() -> shortTimeoutBackend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsTimeoutException.class)
                .isInstanceOf(LightningTimeoutException.class)
                .hasMessageContaining("createInvoice timed out after 1s");
    }

    @Test
    void lookupInvoice_throwsLnbitsTimeoutException_onTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        var shortTimeoutBackend = backendWithShortTimeout();

        assertThatThrownBy(() -> shortTimeoutBackend.lookupInvoice(PAYMENT_HASH))
                .isInstanceOf(LnbitsTimeoutException.class)
                .isInstanceOf(LightningTimeoutException.class)
                .hasMessageContaining("lookupInvoice timed out after 1s");
    }

    @Test
    void isHealthy_returnsFalse_onTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        var shortTimeoutBackend = backendWithShortTimeout();

        assertThat(shortTimeoutBackend.isHealthy()).isFalse();
    }

    @Test
    void createInvoice_nonTimeoutError_stillThrowsLnbitsException() {
        // Verify that non-timeout errors remain as LnbitsException (not LnbitsTimeoutException)
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        var shortTimeoutBackend = backendWithShortTimeout();

        assertThatThrownBy(() -> shortTimeoutBackend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsException.class)
                .isNotInstanceOf(LnbitsTimeoutException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("Internal Server Error");
    }

    @Test
    void createInvoice_truncatesLongErrorBody() {
        String longBody = "X".repeat(250);
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody(longBody));

        assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageEndingWith("...")
                .message()
                .doesNotContain(longBody);
    }

    @Test
    void createInvoice_handlesEmptyErrorBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody(""));

        assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
                .isInstanceOf(LnbitsException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    void backendUsesConfiguredTimeout() {
        var config = new LnbitsConfig(
                server.url("/").toString(),
                API_KEY,
                30
        );
        assertThat(config.requestTimeoutSeconds()).isEqualTo(30);
    }
}
