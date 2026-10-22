package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.example.ExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression for SR-03 (2026-10-18 security review): a holder-added {@code path} caveat was
 * evaluated against an over-decoded, dot-resolved path while Spring MVC dispatched the once-decoded
 * raw path, so a delegatee restricted to a public zone could reach a private-zone remainder.
 *
 * <p>Servlet mode, real Tomcat, raw sockets.
 */
@Tag("integration")
@SpringBootTest(
    classes = {ExampleApplication.class, DelegatedPathCaveatDecodeRegressionTest.Files.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=1000"
    })
@DisplayName("Delegated path caveat uses the dispatched path (SR-03)")
class DelegatedPathCaveatDecodeRegressionTest {

  @LocalServerPort private int port;

  @BeforeEach
  void reset() {
    Files.INVOCATIONS.set(0);
  }

  @Test
  @DisplayName("the delegated credential works inside its zone and is denied outside it")
  void delegatedCredentialIsScopedToItsZone() throws Exception {
    var delegated = delegated("/caveat/files/public/report", "/caveat/files/public/**");

    var allowed = send("/caveat/files/public/report", delegated);
    assertThat(allowed.status()).isEqualTo(200);
    assertThat(allowed.body()).contains("\"zone\":\"public\"");

    var denied = send("/caveat/files/private/report", delegated);
    assertThat(denied.status()).isEqualTo(402);
    assertThat(Files.INVOCATIONS).hasValue(1);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/caveat/files/private/%252e%252e/public/report",
        "/caveat/files/private/%25252e%25252e/public/report",
        "/caveat/files/private/%2e%2e/public/report",
        "/caveat/files/private/../public/report",
        "/caveat/files/private/x/..;jsessionid=1/../public/report",
        "/caveat/files/public/report;zone=private"
      })
  @DisplayName("a zone-escaping path is rejected with 400 before the handler runs")
  void zoneEscapingPathIsRejected(String rawPath) throws Exception {
    var delegated = delegated("/caveat/files/public/report", "/caveat/files/public/**");

    var response = send(rawPath, delegated);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).doesNotContain("\"zone\":\"private\"");
    assertThat(Files.INVOCATIONS).hasValue(0);
  }

  @Test
  @DisplayName("a prefix-selector handler cannot be steered to another id")
  void prefixSelectorCannotBeSteered() throws Exception {
    var delegated = delegated("/caveat/reports/123/view", "/caveat/reports/123/**");

    assertThat(send("/caveat/reports/123/view", delegated).status()).isEqualTo(200);
    assertThat(send("/caveat/reports/999/view", delegated).status()).isEqualTo(402);
    var steered = send("/caveat/reports/999/%252e%252e/123/view", delegated);

    assertThat(steered.status()).isEqualTo(400);
    assertThat(steered.body()).doesNotContain("\"id\":\"999\"");
    assertThat(Files.INVOCATIONS).hasValue(1);
  }

  private RawHttp.Response send(String rawPath, RawHttp.L402Credential credential)
      throws Exception {
    return RawHttp.get(port, rawPath, Map.of("Authorization", credential.authorization()));
  }

  private RawHttp.L402Credential delegated(String paidPath, String pathCaveat) throws Exception {
    return attenuate(RawHttp.obtainL402Credential(port, paidPath), "path", pathCaveat);
  }

  /** Holder-side attenuation: extends the HMAC chain with one first-party caveat (no root key). */
  private static RawHttp.L402Credential attenuate(
      RawHttp.L402Credential credential, String key, String value) {
    Macaroon original =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(credential.macaroon()));
    var caveat = new Caveat(key, value);
    var caveats = new ArrayList<>(original.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(
            original.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    var attenuated = new Macaroon(original.identifier(), original.location(), caveats, signature);
    return new RawHttp.L402Credential(
        Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated)),
        credential.preimage());
  }

  @TestConfiguration
  static class Files {
    static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @RestController
    @RequestMapping("/caveat")
    static class FilesController {

      /** Zone-dispatching handler: the first remainder segment selects the zone. */
      @PaymentRequired(priceSats = 10)
      @GetMapping("/files/{*rest}")
      Map<String, String> file(@PathVariable("rest") String rest) {
        INVOCATIONS.incrementAndGet();
        String trimmed = rest.startsWith("/") ? rest.substring(1) : rest;
        int slash = trimmed.indexOf('/');
        return Map.of("zone", slash < 0 ? trimmed : trimmed.substring(0, slash), "rest", rest);
      }

      @PaymentRequired(priceSats = 10)
      @GetMapping("/reports/{id}/{*tail}")
      Map<String, String> report(@PathVariable("id") String id, @PathVariable("tail") String tail) {
        INVOCATIONS.incrementAndGet();
        return Map.of("id", id, "tail", tail);
      }
    }
  }
}
