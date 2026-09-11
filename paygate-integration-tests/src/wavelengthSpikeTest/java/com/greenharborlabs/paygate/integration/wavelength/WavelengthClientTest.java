package com.greenharborlabs.paygate.integration.wavelength;

import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.AMOUNT;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.MEMO;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PAYMENT_HASH;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.delayedJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.grpcError;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.json;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.statusJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.swapRecvJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Bounded Wavelength REST client")
class WavelengthClientTest {

  @Test
  void sendsPinnedStatusRequestAndParsesStrictResponse() throws Exception {
    try (var server =
            new WavelengthTestFixtures.FixtureServer(json(200, statusJson(true, "signet")));
        var http = HttpClient.newHttpClient()) {
      var status = client(http, server).status();

      assertThat(status.ready()).isTrue();
      assertThat(status.unlocked()).isTrue();
      assertThat(status.network()).isEqualTo("signet");
      assertThat(server.requests()).hasSize(1);
      assertThat(server.requests().getFirst().path()).isEqualTo("/v1/wallet/status");
      assertThat(server.requests().getFirst().body()).isEqualTo("{}");
      assertThat(server.requests().getFirst().macaroon()).isEqualTo("0102");
    }
  }

  @Test
  void rejectsInvalidRecvInputBeforeSending() throws Exception {
    try (var server = new WavelengthTestFixtures.FixtureServer(json(200, swapRecvJson()));
        var http = HttpClient.newHttpClient()) {
      var client = client(http, server);

      assertThatThrownBy(() -> client.recv(0, MEMO)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.recv(AMOUNT, "x".repeat(257)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(server.calls()).isZero();
    }
  }

  @Test
  void sendsPinnedRecvShapeExactlyOnce() throws Exception {
    try (var server = new WavelengthTestFixtures.FixtureServer(json(200, swapRecvJson()));
        var http = HttpClient.newHttpClient()) {
      var recv = client(http, server).recv(AMOUNT, MEMO);

      assertThat(recv.invoice()).isEqualTo(WavelengthTestFixtures.INVOICE);
      assertThat(server.calls()).isEqualTo(1);
      assertThat(server.requests().getFirst().path()).isEqualTo("/v1/wallet/recv");
      assertThat(server.requests().getFirst().body())
          .isEqualTo("{\"amt_sat\":\"10\",\"memo\":\"synthetic memo\"}");
    }
  }

  @Test
  void neverRetriesRecvAfterIndeterminateTimeout() throws Exception {
    try (var server =
            new WavelengthTestFixtures.FixtureServer(delayedJson(200, swapRecvJson(), 500));
        var http = HttpClient.newHttpClient()) {
      var client = client(http, server, Duration.ofMillis(100), 5, 500);

      assertThatThrownBy(() -> client.recv(AMOUNT, MEMO))
          .isInstanceOf(WavelengthTimeoutException.class)
          .hasMessage("Wavelength Recv outcome is indeterminate");
      Thread.sleep(150);
      assertThat(server.calls()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @MethodSource("classifiedErrors")
  void classifiesHttpAndGrpcErrors(
      int httpStatus, int grpcCode, WavelengthClient.FailureClassification classification)
      throws Exception {
    try (var server =
            new WavelengthTestFixtures.FixtureServer(json(httpStatus, grpcError(grpcCode)));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> client(http, server).status())
          .isInstanceOfSatisfying(
              WavelengthUpstreamException.class,
              failure -> {
                assertThat(failure.classification()).isEqualTo(classification);
                assertThat(failure.httpStatus()).isEqualTo(httpStatus);
                assertThat(failure.grpcCode()).isEqualTo(grpcCode);
                assertThat(failure.getMessage()).doesNotContain("synthetic upstream detail");
              });
    }
  }

  static Stream<Arguments> classifiedErrors() {
    return Stream.of(
        Arguments.of(400, 3, WavelengthClient.FailureClassification.PERMANENT_REQUEST),
        Arguments.of(404, 12, WavelengthClient.FailureClassification.PERMANENT_CONFIGURATION),
        Arguments.of(409, 10, WavelengthClient.FailureClassification.OPERATION_FAILURE),
        Arguments.of(429, 8, WavelengthClient.FailureClassification.TEMPORARY_UNAVAILABLE),
        Arguments.of(503, 14, WavelengthClient.FailureClassification.TEMPORARY_UNAVAILABLE));
  }

  @Test
  void classifiesCredentialFailuresWithoutDaemonMessage() throws Exception {
    try (var server = new WavelengthTestFixtures.FixtureServer(json(401, grpcError(16)));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> client(http, server).status())
          .isInstanceOf(WavelengthAuthenticationException.class)
          .hasMessage("Wavelength authentication failed");
    }
  }

  @Test
  void rejectsMalformedAndOversizedSuccessBodiesAsProtocolFailures() throws Exception {
    try (var malformed = new WavelengthTestFixtures.FixtureServer(json(200, "{}"));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> client(http, malformed).status())
          .isInstanceOf(WavelengthProtocolException.class);
    }
    try (var oversized =
            new WavelengthTestFixtures.FixtureServer(
                json(200, "x".repeat(BoundedBodyHandler.MAX_BODY_BYTES + 1)));
        var http = HttpClient.newHttpClient()) {
      assertThatThrownBy(() -> client(http, oversized).status())
          .isInstanceOf(WavelengthProtocolException.class)
          .hasMessage("Wavelength response exceeded its size limit");
    }
  }

  @Test
  void onlyInspect404WithGrpcNotFoundUsesFallbackSignal() throws Exception {
    try (var valid = new WavelengthTestFixtures.FixtureServer(json(404, grpcError(5)));
        var http = HttpClient.newHttpClient()) {
      var budget = client(http, valid).newLookupBudget();
      assertThatThrownBy(() -> client(http, valid).inspectActivity(PAYMENT_HASH, budget))
          .isInstanceOf(WavelengthInspectNotFoundException.class);
    }
    try (var invalid = new WavelengthTestFixtures.FixtureServer(json(404, grpcError(13)));
        var http = HttpClient.newHttpClient()) {
      var client = client(http, invalid);
      assertThatThrownBy(() -> client.inspectActivity(PAYMENT_HASH, client.newLookupBudget()))
          .isInstanceOfSatisfying(
              WavelengthUpstreamException.class,
              failure ->
                  assertThat(failure.classification())
                      .isEqualTo(WavelengthClient.FailureClassification.PERMANENT_CONFIGURATION));
    }
  }

  @Test
  void lookupBudgetEnforcesSingleOverallDeadline() {
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var budget =
        new WavelengthClient.LookupBudget(nanos::get, WavelengthClient.LOOKUP_DEADLINE, 5, 500);
    nanos.set(Duration.ofSeconds(16).toNanos());

    assertThatThrownBy(() -> budget.nextCallTimeout(Duration.ofSeconds(10)))
        .isInstanceOf(WavelengthTimeoutException.class)
        .hasMessage("Wavelength invoice lookup timed out");
  }

  @Test
  void validatesHardConfigurationBudgets() {
    assertThatThrownBy(
            () ->
                new WavelengthClient.WavelengthClientConfig(
                    java.net.URI.create("https://localhost/"),
                    "0102",
                    Duration.ofSeconds(10),
                    21,
                    500))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WavelengthClient.WavelengthClientConfig(
                    java.net.URI.create("https://localhost/"),
                    "0102",
                    Duration.ofSeconds(10),
                    5,
                    2_001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WavelengthClient client(
      HttpClient http, WavelengthTestFixtures.FixtureServer server) {
    return client(http, server, Duration.ofSeconds(2), 5, 500);
  }

  private static WavelengthClient client(
      HttpClient http,
      WavelengthTestFixtures.FixtureServer server,
      Duration timeout,
      int pages,
      int entries) {
    var config =
        new WavelengthClient.WavelengthClientConfig(server.uri(), "0102", timeout, pages, entries);
    return new WavelengthClient(http, config);
  }
}
