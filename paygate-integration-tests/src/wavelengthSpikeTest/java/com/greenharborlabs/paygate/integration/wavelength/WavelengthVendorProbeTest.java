package com.greenharborlabs.paygate.integration.wavelength;

import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.AMOUNT;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.MEMO;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.NOW;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.OTHER_HASH_HEX;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PAYMENT_HASH;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PAYMENT_HASH_HEX;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.creditRecvJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.decoded;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.entryJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.grpcError;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.inspectJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.json;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.pageJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.statusJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Wavelength direct vendor probe")
class WavelengthVendorProbeTest {

  @Test
  void receivesAndRecordsActualCreditModeWithoutSensitiveFields(
      @TempDir java.nio.file.Path runDirectory) throws Exception {
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(200, statusJson(true, "signet")), json(200, creditRecvJson()));
        var http = HttpClient.newHttpClient()) {
      var probe = probe(http, server, 5, 500);
      var receive = probe.receive(AMOUNT, MEMO);

      probe.writeObservedReceiveEvidence(
          runDirectory, "123e4567-e89b-12d3-a456-426614174000", "ab".repeat(32), receive);

      var artifact = Files.readString(runDirectory.resolve("receive/evidence.json"));
      assertThat(receive.mode()).isEqualTo(WavelengthInvoiceMapper.ReceiveMode.CREDIT_BACKED);
      assertThat(artifact).contains("\"receive_mode\":\"credit_backed\"");
      assertThat(artifact).contains("\"asset_category\":\"server_credits\"");
      assertThat(artifact).doesNotContain(PAYMENT_HASH_HEX, "synthetic-bolt11");
    }
  }

  @Test
  void healthRequiresReadyAndSignetAndAbsorbsFailure() throws Exception {
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(200, statusJson(false, "signet")),
                json(200, statusJson(true, "testnet")),
                json(503, grpcError(14)));
        var http = HttpClient.newHttpClient()) {
      var probe = probe(http, server, 5, 500);

      assertThat(probe.isHealthy()).isFalse();
      assertThat(probe.isHealthy()).isFalse();
      assertThat(probe.isHealthy()).isFalse();
    }
  }

  @Test
  void directInspectionMapsExactSwapEntryWithoutListing() throws Exception {
    var entry = entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var server = new WavelengthTestFixtures.FixtureServer(json(200, inspectJson(entry)));
        var http = HttpClient.newHttpClient()) {
      var invoice = probe(http, server, 5, 500).lookup(PAYMENT_HASH);

      assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
      assertThat(server.calls()).isEqualTo(1);
      assertThat(server.requests().getFirst().path()).isEqualTo("/v1/wallet/inspect/activity");
      assertThat(server.requests().getFirst().body())
          .isEqualTo("{\"id\":\"" + PAYMENT_HASH_HEX + "\",\"ledger_limit\":100}");
    }
  }

  @Test
  void contractPermittedNotFoundFallsBackToCreditHistory() throws Exception {
    var match = entryJson("credit-op-1", PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(match), false, "")));
        var http = HttpClient.newHttpClient()) {
      var invoice = probe(http, server, 5, 500).lookup(PAYMENT_HASH);

      assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
      assertThat(server.requests())
          .extracting(WavelengthTestFixtures.CapturedRequest::path)
          .containsExactly("/v1/wallet/inspect/activity", "/v1/wallet/list");
      assertThat(server.requests().get(1).body())
          .isEqualTo(
              "{\"view\":\"LIST_VIEW_ACTIVITY\",\"pending_only\":false,\"kinds\":[\"ENTRY_KIND_RECV\"],\"limit\":100,\"cursor\":\"\"}");
    }
  }

  @Test
  void inspectionFailureOtherThanAuthoritativeNotFoundNeverFallsBack() throws Exception {
    try (var server = new WavelengthTestFixtures.FixtureServer(json(503, grpcError(14)));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, server, 5, 500).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthUpstreamException.class);
      assertThat(server.calls()).isEqualTo(1);
    }
  }

  @Test
  void rejectsDuplicateMatchesInsteadOfSelectingFirst() throws Exception {
    var first = entryJson("credit-op-1", PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    var second = entryJson("credit-op-2", PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(first, second), false, "")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, server, 5, 500).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthProtocolException.class)
          .hasMessage("Wavelength lookup returned duplicate matches");
    }
  }

  @Test
  void distinguishesAuthoritativeAbsenceFromPageBudgetExhaustion() throws Exception {
    var other = entryJson("credit-other", OTHER_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var absent =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(other), false, "")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, absent, 1, 100).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthInvoiceNotFoundException.class)
          .hasMessage("Wavelength invoice was not found");
    }
    try (var exhausted =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(other), true, "next")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, exhausted, 1, 100).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthLookupExhaustedException.class)
          .hasMessage("Wavelength invoice lookup budget was exhausted");
    }
  }

  @Test
  void distinguishesAuthoritativeAbsenceFromEntryBudgetExhaustionAtExactBoundary()
      throws Exception {
    var other = entryJson("credit-other", OTHER_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var absent =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(other), false, "")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, absent, 5, 1).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthInvoiceNotFoundException.class);
    }
    try (var exhausted =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, pageJson(List.of(other), true, "next")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, exhausted, 5, 1).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthLookupExhaustedException.class);
    }
  }

  @Test
  void followsOpaqueCursorAcrossConcurrentHistoryInsertions() throws Exception {
    var newestConcurrent =
        entryJson("new-concurrent", OTHER_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    var match = entryJson("credit-op-1", PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)),
                json(200, pageJson(List.of(newestConcurrent), true, "stable-cursor")),
                json(200, pageJson(List.of(match), false, "")));
        var http = HttpClient.newHttpClient()) {
      var invoice = probe(http, server, 2, 200).lookup(PAYMENT_HASH);

      assertThat(invoice.paymentHash()).isEqualTo(PAYMENT_HASH);
      assertThat(server.requests().get(2).body()).contains("\"cursor\":\"stable-cursor\"");
    }
  }

  @Test
  void rejectsRepeatedOrNonProgressingCursors() throws Exception {
    var other = entryJson("credit-other", OTHER_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)),
                json(200, pageJson(List.of(other), true, "cursor-1")),
                json(200, pageJson(List.of(other), true, "cursor-1")));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, server, 3, 300).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthProtocolException.class)
          .hasMessage("Wavelength lookup cursor did not progress");
    }
  }

  @Test
  void rejectsContradictoryPaginationMetadata() throws Exception {
    var contradictory = pageJson(List.of(), false, "").replace("\"total\":0", "\"total\":1");
    try (var server =
            new WavelengthTestFixtures.FixtureServer(
                json(404, grpcError(5)), json(200, contradictory));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> probe(http, server, 5, 500).lookup(PAYMENT_HASH))
          .isInstanceOf(WavelengthProtocolException.class);
    }
  }

  private static WavelengthVendorProbe probe(
      HttpClient http, WavelengthTestFixtures.FixtureServer server, int maxPages, int maxEntries) {
    var config =
        new WavelengthClient.WavelengthClientConfig(
            server.uri(), "0102", Duration.ofSeconds(2), maxPages, maxEntries);
    var client = new WavelengthClient(http, config);
    var mapper =
        new WavelengthInvoiceMapper(
            ignored -> decoded(), Clock.fixed(NOW, ZoneOffset.UTC), "signet");
    return new WavelengthVendorProbe(client, mapper);
  }
}
