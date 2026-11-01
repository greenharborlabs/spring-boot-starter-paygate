package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression tests for the LNbits trust boundary (security review 2026-10-18, SR-21 and SR-22):
 * plaintext opt-in host acceptance, redirect and key handling, and response size and deadline
 * bounds.
 */
class LnbitsBoundaryRegressionTest {

  // Not a real credential; marker used to detect forwarding.
  private static final String API_KEY = "boundary-regression-marker-key";
  private static final HexFormat HEX = HexFormat.of();

  private MockWebServer server;
  private MockWebServer redirectTarget;
  private JsonMapper mapper;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    redirectTarget = new MockWebServer();
    redirectTarget.start();
    mapper = JsonMapper.builder().build();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.close();
    redirectTarget.close();
  }

  /** Same HttpClient construction as the LNbits auto-configuration. */
  private LnbitsBackend backend(int requestTimeoutSeconds) {
    var config =
        new LnbitsConfig(server.url("/").toString(), API_KEY, requestTimeoutSeconds, 10, true);
    return new LnbitsBackend(
        config, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  @Test
  void plaintextHttpOptInRejectsLocalhostSubdomainsThatDoNotResolveToLoopback() throws Exception {
    InetAddress publicAddress = InetAddress.ofLiteral("203.0.113.7");
    LnbitsConfig.HostResolver attackerResolver = host -> new InetAddress[] {publicAddress};

    for (String url : List.of("http://attacker.localhost:5000", "http://a.b.c.localhost/")) {
      assertThatThrownBy(() -> new LnbitsConfig(url, API_KEY, 5, 10, true, attackerResolver))
          .as(url)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsafe URL scheme 'http'");
    }

    // The Compose service-name exception is intentionally kept (dev-gated by auto-configuration).
    for (String url : List.of("http://lnbits:5000", "http://LNBITS:5000")) {
      assertThat(new LnbitsConfig(url, API_KEY, 5, 10, true, attackerResolver).allowPlaintextHttp())
          .as(url)
          .isTrue();
    }

    for (String url :
        List.of(
            "http://127.1:5000",
            "http://lnbits.:5000",
            "http://10.0.0.1",
            "http://[::ffff:127.0.0.1]")) {
      assertThatThrownBy(() -> new LnbitsConfig(url, API_KEY, 5, 10, true))
          .as(url)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void redirectsAreNotFollowedAndApiKeyIsNotForwarded() {
    server.enqueue(
        new MockResponse.Builder()
            .code(302)
            .setHeader("Location", redirectTarget.url("/api/v1/payments").toString())
            .build());
    assertThatThrownBy(() -> backend(5).createInvoice(10, "regression"))
        .isInstanceOf(LnbitsException.class)
        .hasMessage("LNbits request failed");
    assertThat(redirectTarget.getRequestCount()).isZero();
  }

  @Test
  void paymentHashIsHexInPathAndKeyOnlyInHeader() throws Exception {
    byte[] hash = new byte[32];
    hash[0] = (byte) 0x2f; // '/'
    hash[1] = (byte) 0x2e; // '.'
    server.enqueue(new MockResponse.Builder().code(404).build());
    assertThatThrownBy(() -> backend(5).lookupInvoice(hash)).isInstanceOf(LnbitsException.class);
    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded.getTarget()).isEqualTo("/api/v1/payments/" + HEX.formatHex(hash));
    assertThat(recorded.getHeaders().get("X-Api-Key")).isEqualTo(API_KEY);
  }

  @Test
  void paidOutgoingPaymentWithNegativeAmountIsRejected() throws Exception {
    byte[] preimage = new byte[32];
    preimage[0] = 5;
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(preimage);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(
                """
                {"paid":true,"preimage":"%s","details":{"payment_hash":"%s","bolt11":"lnbc1x","amount":-10000}}
                """
                    .formatted(HEX.formatHex(preimage), HEX.formatHex(hash)))
            .build());
    assertThatThrownBy(() -> backend(5).lookupInvoice(hash)).isInstanceOf(LnbitsException.class);
  }

  /** A 24 MiB otherwise-valid lookup body is rejected instead of being buffered and parsed. */
  @Test
  void oversizedResponseBodyIsRejectedAsBackendFailure() throws Exception {
    byte[] preimage = new byte[32];
    preimage[0] = 6;
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(preimage);
    String pad = "x".repeat(24 * 1024 * 1024);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(
                "{\"paid\":true,\"preimage\":\"%s\",\"pad\":\"%s\",\"details\":{\"payment_hash\":\"%s\",\"bolt11\":\"lnbc1x\",\"amount\":10000}}"
                    .formatted(HEX.formatHex(preimage), pad, HEX.formatHex(hash)))
            .build());

    assertThatThrownBy(() -> backend(5).lookupInvoice(hash))
        .isInstanceOf(LnbitsException.class)
        .isNotInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits request failed");
  }

  /**
   * Headers arrive immediately and the body trickles for about 6 s against a 2 s request timeout.
   * The overall deadline covers the body, so the call times out near 2 s instead of completing.
   */
  @Test
  void slowBodyTimesOutWithinTheRequestDeadline() throws Exception {
    byte[] preimage = new byte[32];
    preimage[0] = 8;
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(preimage);
    String body =
        "{\"paid\":true,\"preimage\":\"%s\",\"details\":{\"payment_hash\":\"%s\",\"bolt11\":\"lnbc1x\",\"amount\":10000}}"
            .formatted(HEX.formatHex(preimage), HEX.formatHex(hash));
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body(body)
            .throttleBody(body.length() / 6 + 1, 1, TimeUnit.SECONDS)
            .build());
    var backend = backend(2);

    long start = System.nanoTime();
    assertThatThrownBy(() -> backend.lookupInvoice(hash))
        .isInstanceOf(LnbitsTimeoutException.class)
        .hasMessage("LNbits lookupInvoice timed out after 2s");
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(elapsedMillis).isGreaterThanOrEqualTo(1_900).isLessThan(4_000);
  }
}
