package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On real Tomcat, any token is accepted as an HTTP method. Sending many distinct (and very long)
 * method tokens with a compressed body to a method-agnostic paid route with a named pricing
 * strategy emits one {@code COMPRESSED_BODY_REJECTED} decision per request; the auto-configured
 * decision reporter must still keep the {@code method} tag of {@code paygate.security.decisions}
 * bounded.
 */
@Tag("integration")
@SpringBootTest(
    classes = DecisionMethodTagCardinalityIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "paygate.enabled=true",
      "paygate.root-key-store=memory",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=1",
      "paygate.rate-limit.requests-per-second=0.001"
    })
class DecisionMethodTagCardinalityIT {

  @LocalServerPort int port;

  @Autowired MeterRegistry meterRegistry;

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(Ctl.class)
  static class App {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    LightningBackend stubLightningBackend() {
      return new LightningBackend() {
        @Override
        public Invoice createInvoice(long amountSats, String memo) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHealthy() {
          return true;
        }
      };
    }

    @Bean("dyn")
    PaygatePricingStrategy dyn() {
      return (request, defaultPrice) -> defaultPrice;
    }
  }

  @RestController
  static class Ctl {
    @PaymentRequired(priceSats = 5, pricingStrategy = "dyn")
    @RequestMapping("/dyn")
    String dyn() {
      return "PAID-DYN";
    }
  }

  @Test
  void arbitraryMethodTokensProduceBoundedDecisionMethodTags() throws Exception {
    for (int i = 0; i < 200; i++) {
      assertThat(sendCompressedBody("X" + i)).isEqualTo(400);
    }
    assertThat(sendCompressedBody("L".repeat(4000))).isEqualTo(400);
    assertThat(sendCompressedBody("PUT")).isEqualTo(400);

    List<Meter> decisionMeters =
        meterRegistry.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("paygate.security.decisions"))
            .toList();
    Set<String> methodTags =
        decisionMeters.stream()
            .map(meter -> meter.getId().getTag("method"))
            .collect(Collectors.toUnmodifiableSet());

    assertThat(methodTags).containsExactlyInAnyOrder("_other", "PUT");
    assertThat(decisionMeters).hasSize(2);
    assertThat(
            meterRegistry
                .get("paygate.security.decisions")
                .tag("method", "_other")
                .counter()
                .count())
        .isEqualTo(201.0);
  }

  private int sendCompressedBody(String method) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      String request =
          method
              + " /dyn HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
              + "Content-Encoding: gzip\r\nContent-Length: 1\r\n\r\nx";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      var buffer = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(buffer);
      String response = buffer.toString(StandardCharsets.ISO_8859_1);
      return response.length() >= 12 ? Integer.parseInt(response.substring(9, 12)) : -1;
    }
  }
}
