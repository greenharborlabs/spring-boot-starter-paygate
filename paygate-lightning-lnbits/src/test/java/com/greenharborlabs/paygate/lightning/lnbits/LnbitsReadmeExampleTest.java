package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import java.net.http.HttpClient;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the "Writing Your Own Tests" example from this module's README as written, so the example
 * keeps compiling against the declared {@code mockwebserver3} test dependency and keeps passing the
 * backend's BOLT11 payment-hash and plaintext-HTTP checks.
 */
class LnbitsReadmeExampleTest {

  @Test
  void myCustomTest() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();

      server.enqueue(
          new MockResponse.Builder()
              .code(201)
              .setHeader("Content-Type", "application/json")
              .body(
                  """
                  {"payment_hash": "0001020304050607080900010203040506070809000102030405060708090102",
                   "payment_request": "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"}
                  """)
              .build());

      var config = new LnbitsConfig(server.url("/").toString(), "test-key", 5, 10, true);
      var backend =
          new LnbitsBackend(config, JsonMapper.builder().build(), HttpClient.newHttpClient());

      Invoice invoice = backend.createInvoice(100L, "test");
      assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    }
  }
}
