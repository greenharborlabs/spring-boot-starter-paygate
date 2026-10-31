package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression for Paygate switch spellings. Spring binds {@code on} as {@code true} while
 * {@code @ConditionalOnProperty(havingValue = "true")} treats it as off, so {@code
 * paygate.protocols.l402.enabled=on} used to start with zero payment protocols and {@code
 * paygate.enabled=on} silently removed the payment wall. Both now fail startup.
 */
@Tag("integration")
@DisplayName("Paygate switch property spelling")
class SwitchPropertySpellingRegressionTest {

  @ParameterizedTest(name = "paygate.protocols.l402.enabled={0}")
  @ValueSource(strings = {"on", "yes"})
  @DisplayName("a non-boolean L402 switch fails startup instead of starting with zero protocols")
  void nonBooleanL402SwitchFailsStartup(String value) {
    assertThatThrownBy(
            () -> start("--paygate.enabled=true", "--paygate.protocols.l402.enabled=" + value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.protocols.l402.enabled must be exactly 'true' or 'false'");
  }

  @Test
  @DisplayName("a non-boolean master switch fails startup instead of disabling Paygate")
  void nonBooleanMasterSwitchFailsStartup() {
    assertThatThrownBy(() -> start("--paygate.enabled=on"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.enabled must be exactly 'true' or 'false'");
  }

  @Test
  @DisplayName("a correctly spelled switch starts L402 and challenges the paid route")
  void booleanSwitchStartsL402() throws Exception {
    try (var context = start("--paygate.enabled=TRUE", "--paygate.protocols.l402.enabled=true");
        var client = HttpClient.newHttpClient()) {
      assertThat(context.getBeansOfType(PaymentProtocol.class)).hasSize(1);
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/paid")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(402);
      assertThat(response.body()).doesNotContain("PAID-WORK");
    }
  }

  private static ConfigurableApplicationContext start(String... switches) {
    String[] args = new String[switches.length + 3];
    System.arraycopy(switches, 0, args, 0, switches.length);
    args[switches.length] = "--paygate.root-key-store=memory";
    args[switches.length + 1] = "--paygate.protocols.mpp.enabled=false";
    args[switches.length + 2] = "--server.port=0";
    return new SpringApplicationBuilder(App.class).run(args);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(PaidController.class)
  static class App {
    @Bean
    LightningBackend stubLightningBackend() {
      return new LightningBackend() {
        @Override
        public Invoice createInvoice(long amountSats, String memo) {
          byte[] hash = new byte[32];
          new SecureRandom().nextBytes(hash);
          Instant now = Instant.now();
          return new Invoice(
              hash,
              "lnbc1n1pstub",
              amountSats,
              memo,
              InvoiceStatus.PENDING,
              null,
              now,
              now.plus(1, ChronoUnit.HOURS));
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
  }

  @RestController
  static class PaidController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/paid")
    String paid() {
      return "PAID-WORK";
    }
  }
}
