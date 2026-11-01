package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LnbitsBackendTest {

  private static final String API_KEY = "test-api-key-abc123";
  private static final HexFormat HEX = HexFormat.of();

  // BOLT #11 specification example: BOLT11 commits to PAYMENT_HASH_HEX.
  private static final String PAYMENT_HASH_HEX =
      "0001020304050607080900010203040506070809000102030405060708090102";
  private static final byte[] PAYMENT_HASH = HEX.parseHex(PAYMENT_HASH_HEX);

  private static final String BOLT11 =
      "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql";

  private static final String OTHER_PAYMENT_HASH_HEX =
      "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

  private MockWebServer server;
  private LnbitsBackend backend;
  private JsonMapper objectMapper;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();

    objectMapper = JsonMapper.builder().build();
    var config = new LnbitsConfig(server.url("/").toString(), API_KEY, 5, 10, true);
    backend = new LnbitsBackend(config, objectMapper, HttpClient.newHttpClient());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.close();
  }

  @Test
  void createInvoice_postsToPaymentsEndpoint() throws Exception {
    // Given: LNbits returns a successful create-invoice response
    String responseBody =
        """
                {
                    "payment_hash": "%s",
                    "payment_request": "%s",
                    "checking_id": "some-id"
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    // When
    Invoice invoice = backend.createInvoice(100L, "test memo");

    // Then: correct endpoint and method
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getTarget()).isEqualTo("/api/v1/payments");

    // Then: correct request body
    var requestBody = objectMapper.readTree(request.getBody().utf8());
    assertThat(requestBody.get("out").asBoolean()).isFalse();
    assertThat(requestBody.get("amount").asLong()).isEqualTo(100L);
    assertThat(requestBody.get("memo").asString()).isEqualTo("test memo");

    // Then: correct X-Api-Key header
    assertThat(request.getHeaders().get("X-Api-Key")).isEqualTo(API_KEY);

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
    String responseBody =
        """
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
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    // When
    Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

    // Then: correct endpoint, method, and hash in URL
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getTarget()).isEqualTo("/api/v1/payments/" + PAYMENT_HASH_HEX);

    // Then: correct header
    assertThat(request.getHeaders().get("X-Api-Key")).isEqualTo(API_KEY);

    // Then: unpaid maps to PENDING
    assertThat(invoice).isNotNull();
    assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    assertThat(invoice.amountSats()).isEqualTo(100);
  }

  @Test
  void lookupInvoice_settledInvoice_returnsSettledStatus() throws Exception {
    // Given: LNbits returns a paid invoice with preimage
    String preimageHex = "ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00";
    byte[] preimage = HEX.parseHex(preimageHex);
    byte[] paymentHash = sha256(preimage);
    String paymentHashHex = HEX.formatHex(paymentHash);
    String responseBody =
        """
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
                """
            .formatted(preimageHex, paymentHashHex, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    // When
    Invoice invoice = backend.lookupInvoice(paymentHash);

    // Then
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.SETTLED);
    assertThat(invoice.preimage()).isEqualTo(preimage);
    assertThat(invoice.amountSats()).isEqualTo(250);
  }

  @Test
  void lookupInvoice_throws_whenSettledResponseOmitsPreimage() {
    String responseBody =
        """
                {
                    "paid": true,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 250000,
                        "memo": "settled without preimage",
                        "time": 1700000000,
                        "expiry": 3600
                    }
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class);
  }

  @Test
  void isHealthy_returnsTrue_when200() throws Exception {
    // Given: wallet endpoint returns 200
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"id": "wallet-id", "name": "test", "balance": 1000}
                        """)
            .build());

    // When / Then
    assertThat(backend.isHealthy()).isTrue();

    // Verify correct endpoint, method, and auth header
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getTarget()).isEqualTo("/api/v1/wallet");
    assertThat(request.getHeaders().get("X-Api-Key")).isEqualTo(API_KEY);
  }

  @Test
  void isHealthy_returnsFalse_whenNon200() {
    // Given: wallet endpoint returns 500
    server.enqueue(new MockResponse.Builder().code(500).body("Internal Server Error").build());

    // When / Then
    assertThat(backend.isHealthy()).isFalse();
  }

  @Test
  void allRequests_includeApiKeyHeader() throws Exception {
    // Enqueue responses for three different request types
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false, "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000, "memo": "m", "time": 1700000000, "expiry": 3600}}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"id": "w", "name": "test", "balance": 0}
                        """)
            .build());

    // Execute all three request types
    backend.createInvoice(100L, "memo");
    backend.lookupInvoice(PAYMENT_HASH);
    backend.isHealthy();

    // Verify X-Api-Key header on every request
    for (int i = 0; i < 3; i++) {
      RecordedRequest request = server.takeRequest();
      assertThat(request.getHeaders().get("X-Api-Key"))
          .as(
              "Request #%d (%s %s) must include X-Api-Key",
              i + 1, request.getMethod(), request.getTarget())
          .isEqualTo(API_KEY);
    }
  }

  @Test
  void createInvoice_throws_when4xxResponse() {
    server.enqueue(new MockResponse.Builder().code(401).body("Unauthorized").build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "test memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain("Unauthorized");
  }

  @Test
  void createInvoice_throws_when5xxResponse() {
    server.enqueue(new MockResponse.Builder().code(500).body("Internal Server Error").build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "test memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain("Internal Server Error");
  }

  @Test
  void lookupInvoice_throws_when4xxResponse() {
    server.enqueue(new MockResponse.Builder().code(404).body("Not Found").build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain("Not Found");
  }

  @Test
  void lookupInvoice_throws_when5xxResponse() {
    server.enqueue(new MockResponse.Builder().code(502).body("Bad Gateway").build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain("Bad Gateway");
  }

  @Test
  void lookupInvoice_rejectsNonIntegralAndOutOfRangeMillisatoshis() {
    for (String amount :
        new String[] {
          "\"1000\"",
          "1000.0",
          "true",
          "null",
          "0",
          "-1000",
          "1001",
          "9223372036854775808",
          Long.toString((SecurityBounds.MAX_PRICE_SATS + 1) * 1000)
        }) {
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .setHeader("Content-Type", "application/json")
              .body(
                  """
                  {"paid":false,"details":{"payment_hash":"%s","bolt11":"%s","amount":%s}}
                  """
                      .formatted(PAYMENT_HASH_HEX, BOLT11, amount))
              .build());

      assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
          .isInstanceOf(LnbitsException.class)
          .hasMessage("LNbits returned invalid backend data");
    }
  }

  @Test
  void lookupInvoice_acceptsMaximumExactlyConvertibleMillisatoshis() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                {"paid":false,"details":{"payment_hash":"%s","bolt11":"%s","amount":%d}}
                """
                    .formatted(PAYMENT_HASH_HEX, BOLT11, SecurityBounds.MAX_PRICE_SATS * 1000))
            .build());

    assertThat(backend.lookupInvoice(PAYMENT_HASH).amountSats())
        .isEqualTo(SecurityBounds.MAX_PRICE_SATS);
  }

  // Verify the backend implements the LightningBackend interface
  @Test
  void implementsLightningBackendInterface() {
    assertThat(backend).isInstanceOf(LightningBackend.class);
  }

  // --- Input validation tests ---

  @Test
  void createInvoice_throws_whenAmountIsZero() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(0L, "memo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountSats");

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void createInvoice_throws_whenAmountIsNegative() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(-1L, "memo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountSats");

    assertThat(server.getRequestCount()).isZero();
  }

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
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_request": "%s"}
                        """
                    .formatted(BOLT11))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void createInvoice_throws_whenPaymentRequestMissing() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void createInvoice_throws_whenBolt11CommitsToDifferentPaymentHash() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(OTHER_PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .isInstanceOf(LightningException.class)
        .hasMessage("LNbits returned invalid backend data")
        .hasNoCause();
  }

  @Test
  void createInvoice_throws_whenBolt11IsNotAValidInvoice() {
    String corrupted = BOLT11.substring(0, BOLT11.length() - 1) + "q";
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX, corrupted))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data")
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain(corrupted);
  }

  @Test
  void createInvoice_throws_whenBolt11IsPlaceholderText() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "lnbc100n1pjexamplefake"}
                        """
                    .formatted(PAYMENT_HASH_HEX))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void createInvoice_throws_whenPaymentHashHasWrongLength() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(HEX.formatHex(new byte[31]), BOLT11))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void lookupInvoice_throws_whenPaidFieldMissing() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"details": {"bolt11": "%s", "amount": 100}}
                        """
                    .formatted(BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void lookupInvoice_throws_whenDetailsMissing() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false}
                        """)
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void lookupInvoice_throws_whenDetailsBolt11Missing() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false, "details": {"payment_hash": "%s", "amount": 100}}
                        """
                    .formatted(PAYMENT_HASH_HEX))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void lookupInvoice_throws_whenDetailsAmountMissing() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false, "details": {"payment_hash": "%s", "bolt11": "%s"}}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  // --- Provider proof validation tests ---

  @Test
  void lookupInvoice_throws_whenResponsePaymentHashHasWrongLength() {
    String shortHashHex = HEX.formatHex(new byte[31]);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false, "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000}}
                        """
                    .formatted(shortHashHex, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class);
  }

  @Test
  void lookupInvoice_throws_whenResponsePaymentHashDoesNotMatchQueriedHash() {
    String differentHashHex = HEX.formatHex(new byte[32]);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": false, "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000}}
                        """
                    .formatted(differentHashHex, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class);
  }

  @Test
  void lookupInvoice_throws_whenSettledPreimageHasWrongLength() {
    String shortPreimageHex = HEX.formatHex(new byte[31]);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": true, "preimage": "%s", "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000}}
                        """
                    .formatted(shortPreimageHex, PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits returned invalid backend data");
  }

  @Test
  void lookupInvoice_throws_whenSettledPreimageDoesNotHashToQueriedPaymentHash() {
    String preimageHex = HEX.formatHex(new byte[32]);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"paid": true, "preimage": "%s", "details": {"payment_hash": "%s", "bolt11": "%s", "amount": 100000}}
                        """
                    .formatted(preimageHex, PAYMENT_HASH_HEX, BOLT11))
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class);
  }

  // --- Timestamp parsing tests ---

  @Test
  void lookupInvoice_parsesTimestampsFromDetails() throws Exception {
    // Given: LNbits returns a response with known time and expiry values
    long creationEpoch = 1700000000L;
    long expirySeconds = 7200L;
    String responseBody =
        """
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
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11, creationEpoch, expirySeconds);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    // When
    Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

    // Then: timestamps are parsed from the response, not generated with Instant.now()
    assertThat(invoice.createdAt()).isEqualTo(Instant.ofEpochSecond(creationEpoch));
    assertThat(invoice.expiresAt()).isEqualTo(Instant.ofEpochSecond(creationEpoch + expirySeconds));
  }

  @Test
  void lookupInvoice_fallsBackToNow_whenTimeAndExpiryMissing() throws Exception {
    // Given: LNbits returns a response without time or expiry fields
    String responseBody =
        """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "memo": "no timestamps"
                    }
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

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
    var config =
        new LnbitsConfig(
            server.url("/").toString(), API_KEY, 1, 10, true // 1-second timeout for fast tests
            );
    return new LnbitsBackend(config, objectMapper, HttpClient.newHttpClient());
  }

  @Test
  void createInvoice_throwsLnbitsTimeoutException_onTimeout() {
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());

    var shortTimeoutBackend = backendWithShortTimeout();

    assertThatThrownBy(() -> shortTimeoutBackend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsTimeoutException.class)
        .isInstanceOf(LightningTimeoutException.class)
        .hasMessageContaining("createInvoice timed out after 1s");
  }

  @Test
  void lookupInvoice_throwsLnbitsTimeoutException_onTimeout() {
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());

    var shortTimeoutBackend = backendWithShortTimeout();

    assertThatThrownBy(() -> shortTimeoutBackend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsTimeoutException.class)
        .isInstanceOf(LightningTimeoutException.class)
        .hasMessageContaining("lookupInvoice timed out after 1s");
  }

  @Test
  void isHealthy_returnsFalse_onTimeout() {
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());

    var shortTimeoutBackend = backendWithShortTimeout();

    assertThat(shortTimeoutBackend.isHealthy()).isFalse();
  }

  // --- Response bounds (SR-22) ---

  private static final int TWO_MIB = 2 * 1024 * 1024;

  private static String paddedJson(String prefixJson, int totalBytes) {
    // prefixJson must end with an open object, e.g. {"a":1, ; padding closes it.
    String head = prefixJson + "\"pad\":\"";
    String tail = "\"}";
    return head + "x".repeat(totalBytes - head.length() - tail.length()) + tail;
  }

  private static String createInvoiceJson() {
    return "{\"payment_hash\":\"%s\",\"payment_request\":\"%s\","
        .formatted(PAYMENT_HASH_HEX, BOLT11);
  }

  @Test
  void createInvoice_rejectsTwoMebibyteResponseBody() {
    server.enqueue(
        new MockResponse.Builder().body(paddedJson(createInvoiceJson(), TWO_MIB)).build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .isNotInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits request failed")
        .rootCause()
        .isInstanceOf(LnbitsBackend.ResponseBodyTooLargeException.class);
  }

  @Test
  void lookupInvoice_rejectsTwoMebibyteResponseBody() {
    String prefix =
        "{\"paid\":false,\"details\":{\"payment_hash\":\"%s\",\"bolt11\":\"%s\",\"amount\":100000},"
            .formatted(PAYMENT_HASH_HEX, BOLT11);
    server.enqueue(new MockResponse.Builder().body(paddedJson(prefix, TWO_MIB)).build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class)
        .isNotInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits request failed");
  }

  @Test
  void isHealthy_returnsFalse_whenResponseBodyExceedsLimit() {
    server.enqueue(new MockResponse.Builder().body(paddedJson("{", TWO_MIB)).build());

    assertThat(backend.isHealthy()).isFalse();
  }

  @Test
  void createInvoice_acceptsResponseBodyExactlyAtLimit() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .body(paddedJson(createInvoiceJson(), LnbitsBackend.MAX_RESPONSE_BODY_BYTES))
            .build());

    Invoice invoice = backend.createInvoice(100L, "memo");

    assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
  }

  @Test
  void createInvoice_rejectsResponseBodyOneByteOverLimit() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .body(paddedJson(createInvoiceJson(), LnbitsBackend.MAX_RESPONSE_BODY_BYTES + 1))
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed");
  }

  @Test
  void createInvoice_slowBodyTimesOutWithinRequestDeadline() {
    String body = paddedJson(createInvoiceJson(), 600);
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .body(body)
            .throttleBody(100, 1, TimeUnit.SECONDS)
            .build());
    var shortTimeoutBackend = backendWithShortTimeout();

    long start = System.nanoTime();
    assertThatThrownBy(() -> shortTimeoutBackend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits createInvoice timed out after 1s");
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(elapsedMillis).isGreaterThanOrEqualTo(900).isLessThan(3_000);
  }

  @Test
  void isHealthy_returnsFalse_whenBodyIsSlowerThanRequestDeadline() {
    String body = paddedJson("{", 600);
    server.enqueue(
        new MockResponse.Builder().body(body).throttleBody(100, 1, TimeUnit.SECONDS).build());
    var shortTimeoutBackend = backendWithShortTimeout();

    long start = System.nanoTime();
    assertThat(shortTimeoutBackend.isHealthy()).isFalse();
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(elapsedMillis).isLessThan(3_000);
  }

  @Test
  void createInvoice_nonTimeoutError_stillThrowsLnbitsException() {
    // Verify that non-timeout errors remain as LnbitsException (not LnbitsTimeoutException)
    server.enqueue(new MockResponse.Builder().code(500).body("Internal Server Error").build());

    var shortTimeoutBackend = backendWithShortTimeout();

    assertThatThrownBy(() -> shortTimeoutBackend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .isNotInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain("Internal Server Error");
  }

  @Test
  void createInvoice_doesNotExposeSecretErrorBody() {
    String secretBody = "lnbits-secret=x-api-key-abc123-super-secret";
    server.enqueue(new MockResponse.Builder().code(500).body(secretBody).build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed")
        .message()
        .doesNotContain(secretBody)
        .doesNotContain("x-api-key-abc123-super-secret");
  }

  @Test
  void createInvoice_handlesEmptyErrorBody() {
    server.enqueue(new MockResponse.Builder().code(502).body("").build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed");
  }

  @Test
  void checkResponseStatusWarning_doesNotFormatResponseBody() throws Exception {
    Path sourcePath =
        Path.of("src/main/java/com/greenharborlabs/paygate/lightning/lnbits/LnbitsBackend.java");
    if (!Files.exists(sourcePath)) {
      sourcePath =
          Path.of(
              "paygate-lightning-lnbits/src/main/java/com/greenharborlabs/paygate/lightning/lnbits/LnbitsBackend.java");
    }

    String source = Files.readString(sourcePath);
    String methodSource =
        source.substring(source.indexOf("private static void checkResponseStatus"));

    assertThat(methodSource)
        .doesNotContain("response.body()")
        .doesNotContain("truncateBody")
        .doesNotContain("sensitive-body-secret");
  }

  @Test
  void backendUsesConfiguredTimeout() {
    var config = new LnbitsConfig(server.url("/").toString(), API_KEY, 30, 10, true);
    assertThat(config.requestTimeoutSeconds()).isEqualTo(30);
  }

  // --- Edge case tests ---

  @Test
  void createInvoice_throws_whenResponseBodyIsNotJson() {
    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body("not json at all")
            .build());

    assertThatThrownBy(() -> backend.createInvoice(100L, "memo"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed");
  }

  @Test
  void lookupInvoice_settledWithoutPreimage_throws() {
    String responseBody =
        """
                {
                    "paid": true,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "time": 1700000000,
                        "expiry": 3600
                    }
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
        .isInstanceOf(LnbitsException.class);
  }

  @Test
  void lookupInvoice_withoutMemoField_returnsNullMemo() throws Exception {
    String responseBody =
        """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "time": 1700000000,
                        "expiry": 3600
                    }
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);

    assertThat(invoice.memo()).isNull();
  }

  @Test
  void lookupInvoice_nonNumericTime_fallsBackToNow() throws Exception {
    String responseBody =
        """
                {
                    "paid": false,
                    "details": {
                        "payment_hash": "%s",
                        "bolt11": "%s",
                        "amount": 100000,
                        "time": "not-a-number",
                        "expiry": 3600
                    }
                }
                """
            .formatted(PAYMENT_HASH_HEX, BOLT11);

    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(responseBody)
            .build());

    Instant before = Instant.now();
    Invoice invoice = backend.lookupInvoice(PAYMENT_HASH);
    Instant after = Instant.now();

    assertThat(invoice.createdAt()).isBetween(before, after);
  }

  @Test
  void createInvoice_trailingSlashUrl_normalizesPath() throws Exception {
    // Construct backend with explicit trailing-slash URL
    String trailingSlashUrl = "http://localhost:" + server.getPort() + "/";
    var trailingSlashConfig = new LnbitsConfig(trailingSlashUrl, API_KEY, 5, 10, true);
    var trailingSlashBackend =
        new LnbitsBackend(trailingSlashConfig, objectMapper, HttpClient.newHttpClient());

    server.enqueue(
        new MockResponse.Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .body(
                """
                        {"payment_hash": "%s", "payment_request": "%s"}
                        """
                    .formatted(PAYMENT_HASH_HEX, BOLT11))
            .build());

    trailingSlashBackend.createInvoice(100L, "memo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget()).isEqualTo("/api/v1/payments");
  }

  @Test
  void isHealthy_returnsFalse_whenConnectionDisconnects() {
    server.enqueue(
        new MockResponse.Builder().onRequestStart(new SocketEffect.CloseSocket()).build());

    assertThat(backend.isHealthy()).isFalse();
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }
}
