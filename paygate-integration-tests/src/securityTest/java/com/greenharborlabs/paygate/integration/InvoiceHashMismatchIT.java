package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.google.protobuf.ByteString;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.lightning.lnbits.LnbitsBackend;
import com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig;
import com.greenharborlabs.paygate.lightning.lnd.LndBackend;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateSecurityFilter;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-16: a real LND or LNbits backend that returns a BOLT11 invoice committing to a different
 * payment hash than the one it reports fails the challenge with 503, never a 402 that would strand
 * the client's payment.
 */
@Tag("integration")
@SpringBootTest(classes = InvoiceHashMismatchIT.TestApplication.class)
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.security-mode=servlet",
      "paygate.root-key-store=memory",
      "paygate.service-name=invoice-hash-test",
      "paygate.health-cache.enabled=false",
      "paygate.protocols.mpp.challenge-binding-secret="
    })
@DisplayName("Invoice payment-hash consistency (I-16)")
class InvoiceHashMismatchIT {

  private static final String PROTECTED_PATH = "/invoice-hash/protected";

  /** BOLT #11 specification example invoice and the payment hash it commits to. */
  private static final String SPEC_BOLT11 =
      "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql";

  private static final String SPEC_HASH_HEX =
      "0001020304050607080900010203040506070809000102030405060708090102";
  private static final String OTHER_HASH_HEX =
      "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

  @Autowired private WebApplicationContext applicationContext;
  @Autowired private PaygateSecurityFilter paygateSecurityFilter;

  private MockMvc mockMvc;
  private HttpServer lnbitsServer;
  private Server lndServer;
  private ManagedChannel lndChannel;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(applicationContext)
            .addFilters(paygateSecurityFilter)
            .build();
  }

  @AfterEach
  void tearDown() {
    SwitchableBackend.target = null;
    if (lnbitsServer != null) {
      lnbitsServer.stop(0);
    }
    if (lndChannel != null) {
      lndChannel.shutdownNow();
    }
    if (lndServer != null) {
      lndServer.shutdownNow();
    }
  }

  @Test
  @DisplayName("LNbits invoice committing to another hash returns 503")
  void lnbitsMismatchReturns503() throws Exception {
    SwitchableBackend.target = startLnbits(OTHER_HASH_HEX);

    assertLightningUnavailable(challenge());
  }

  @Test
  @DisplayName("LNbits invoice committing to the returned hash still returns a 402 challenge")
  void lnbitsMatchReturns402() throws Exception {
    SwitchableBackend.target = startLnbits(SPEC_HASH_HEX);

    assertChallengeIssued(challenge());
  }

  @Test
  @DisplayName("LND invoice committing to another r_hash returns 503")
  void lndMismatchReturns503() throws Exception {
    SwitchableBackend.target = startLnd(OTHER_HASH_HEX);

    assertLightningUnavailable(challenge());
  }

  @Test
  @DisplayName("LND invoice committing to the returned r_hash still returns a 402 challenge")
  void lndMatchReturns402() throws Exception {
    SwitchableBackend.target = startLnd(SPEC_HASH_HEX);

    assertChallengeIssued(challenge());
  }

  private MockHttpServletResponse challenge() throws Exception {
    return mockMvc.perform(get(PROTECTED_PATH)).andReturn().getResponse();
  }

  private static void assertLightningUnavailable(MockHttpServletResponse response)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("LIGHTNING_UNAVAILABLE");
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
  }

  private static void assertChallengeIssued(MockHttpServletResponse response) {
    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeaders("WWW-Authenticate"))
        .anySatisfy(header -> assertThat(header).startsWith("L402").contains(SPEC_BOLT11));
  }

  private LightningBackend startLnbits(String reportedHashHex) throws Exception {
    lnbitsServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    lnbitsServer.createContext(
        "/api/v1/payments",
        exchange -> {
          byte[] body =
              """
              {"payment_hash":"%s","payment_request":"%s"}
              """
                  .formatted(reportedHashHex, SPEC_BOLT11)
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    lnbitsServer.start();
    return new LnbitsBackend(
        new LnbitsConfig(
            "http://localhost:" + lnbitsServer.getAddress().getPort(), "key", 5, 5, true),
        JsonMapper.builder().build(),
        HttpClient.newHttpClient());
  }

  private LightningBackend startLnd(String reportedHashHex) throws Exception {
    byte[] rHash = HexFormat.of().parseHex(reportedHashHex);
    String name = InProcessServerBuilder.generateName();
    lndServer =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new LightningGrpc.LightningImplBase() {
                  @Override
                  public void addInvoice(
                      Lnrpc.Invoice request, StreamObserver<Lnrpc.AddInvoiceResponse> observer) {
                    observer.onNext(
                        Lnrpc.AddInvoiceResponse.newBuilder()
                            .setRHash(ByteString.copyFrom(rHash))
                            .setPaymentRequest(SPEC_BOLT11)
                            .build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    lndChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new LndBackend(lndChannel);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(TestConfiguration.class)
  static class TestApplication {}

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class TestConfiguration {

    @Bean
    SwitchableBackend lightningBackend() {
      return new SwitchableBackend();
    }

    @Bean
    PaygateRateLimiter paygateRateLimiter() {
      return _ -> true;
    }

    @Bean
    InvoiceHashController invoiceHashController() {
      return new InvoiceHashController();
    }
  }

  @RestController
  static class InvoiceHashController {

    @PaymentRequired(priceSats = 1, description = "Invoice hash boundary")
    @GetMapping(PROTECTED_PATH)
    Map<String, String> protectedHandler() {
      return Map.of("status", "unexpected");
    }
  }

  /** Delegates invoice creation to the real backend selected by each test; always healthy. */
  static final class SwitchableBackend implements LightningBackend {
    // Static because auto-configuration wraps LightningBackend beans, hiding the concrete type.
    private static volatile LightningBackend target;

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      return target.createInvoice(amountSats, memo);
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      throw new AssertionError("challenge issuance must not look up invoices");
    }

    @Override
    public boolean isHealthy() {
      return true;
    }
  }
}
