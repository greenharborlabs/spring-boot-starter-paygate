package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live wiring; all operator-specific administration stays in the reviewed external controller. */
final class T7LiveEnvironment {
  static final List<String> REQUIRED =
      List.of(
          "WAVELENGTH_SPIKE_DAEMON_EXECUTABLE",
          "WAVELENGTH_SPIKE_DAEMON_PID_FILE",
          "WAVELENGTH_SPIKE_DAEMON_SHA256",
          "WAVELENGTH_SPIKE_CONTROLLER_SHA256",
          "WAVELENGTH_SPIKE_BROWSER_PORT");
  static final List<String> CONFIRMED =
      List.of(
          "WAVELENGTH_SPIKE_CONTROL_REVIEWED",
          "WAVELENGTH_SPIKE_HISTORY_AUTHORIZED",
          "WAVELENGTH_SPIKE_DEPENDENCY_FAULT_AUTHORIZED");

  private T7LiveEnvironment() {}

  static void preflight(Map<String, String> environment) {
    var missing = new ArrayList<String>();
    for (var key : REQUIRED) {
      if (environment.getOrDefault(key, "").isBlank()) missing.add(key);
    }
    for (var key : CONFIRMED) {
      if (!"confirmed".equals(environment.get(key))) missing.add(key);
    }
    if (!missing.isEmpty())
      throw new IllegalStateException("T7 preflight failed: " + String.join(", ", missing));
    try {
      int port = Integer.parseInt(environment.get("WAVELENGTH_SPIKE_BROWSER_PORT"));
      if (port < 1024 || port > 65535) throw new IllegalArgumentException();
      for (var key :
          List.of("WAVELENGTH_SPIKE_DAEMON_SHA256", "WAVELENGTH_SPIKE_CONTROLLER_SHA256")) {
        if (!environment.get(key).matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      }
      validateEndpoint(environment.get("WAVELENGTH_SPIKE_DAEMON_URL"));
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("T7 endpoint, port or binary identity is invalid");
    }
  }

  private static void validateEndpoint(String url) {
    var endpoint = URI.create(url);
    if (!"https".equals(endpoint.getScheme())
        || !List.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IllegalArgumentException();
    }
  }

  static void execute(
      Map<String, String> environment,
      Path browserDirectory,
      Path runDirectory,
      String runId,
      String manifestSha256) {
    preflight(environment);
    var evidence = new FileEvidence(runDirectory, runId, manifestSha256);
    try (var receiver = new LiveReceiver(environment)) {
      var daemon = new SpikeDaemonControl(environment);
      var control =
          new T7LiveHarness.Control() {
            public void restart() {
              daemon.restart();
            }

            public void faultOn() {
              daemon.faultOn();
            }

            public void faultOff() {
              daemon.faultOff();
            }

            public void close() {
              daemon.close();
            }
          };
      new T7LiveHarness(
              receiver,
              control,
              invoice -> {
                var body = WavelengthWire.jsonMapper().createObjectNode().put("invoice", invoice);
                var input = WavelengthWire.jsonBytes(body);
                try {
                  var result =
                      SpikeCommand.run(
                          List.of("node", browserDirectory.resolve("scripts/live.mjs").toString()),
                          input,
                          Duration.ofSeconds(330));
                  if (!"T7_RECOVERED_ONE_DISPATCH\n".equals(result)) {
                    throw new IllegalStateException("Browser proof is unavailable");
                  }
                } finally {
                  KeyMaterial.zeroize(input);
                }
              },
              evidence,
              Duration.ofSeconds(120))
          .execute();
    } catch (RuntimeException failure) {
      // No raw HTTP, operator path or SDK exception crosses into JUnit reports.
      throw new IllegalStateException(
          "T7 live proof unavailable; inspect sanitized current-run evidence");
    }
  }

  private static final class LiveReceiver implements T7LiveHarness.Receiver, AutoCloseable {
    private final Map<String, String> environment;
    private HttpClient http;
    private WavelengthClient client;
    private WavelengthInvoiceMapper mapper;
    private WavelengthVendorProbe probe;
    private String lookupPath = "not_observed";

    LiveReceiver(Map<String, String> environment) {
      this.environment = environment;
      freshClient();
    }

    public void freshClient() {
      close();
      byte[] credential = null;
      try {
        var path = Path.of(environment.get("WAVELENGTH_SPIKE_CREDENTIAL_PATH"));
        SpikeCredentialFileValidator.validate(path);
        try (var stream = Files.newInputStream(path)) {
          credential = stream.readNBytes(65_537);
        }
        if (credential.length == 0 || credential.length > 65_536)
          throw new IllegalArgumentException();
        http =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        client =
            new WavelengthClient(
                http,
                WavelengthClient.WavelengthClientConfig.defaults(
                    URI.create(environment.get("WAVELENGTH_SPIKE_DAEMON_URL")),
                    HexFormat.of().formatHex(credential)));
        mapper = new WavelengthInvoiceMapper(new AcinqBolt11Decoder(), Clock.systemUTC(), "signet");
        probe = new WavelengthVendorProbe(client, mapper);
      } catch (IOException | RuntimeException failure) {
        close();
        throw new IllegalStateException("Spike receiver initialization failed");
      } finally {
        if (credential != null) KeyMaterial.zeroize(credential);
      }
    }

    public WavelengthWire.StatusResponse status() {
      return client.status();
    }

    public WavelengthInvoiceMapper.MappedReceive receive() {
      return probe.receive(10, "Paygate T7 signet capability");
    }

    public Invoice lookup(byte[] hash) {
      var result = probe.lookupObserved(hash);
      lookupPath = result.path();
      return result.invoice();
    }

    public Map<String, Object> history(byte[] hash) {
      var budget = client.newLookupBudget();
      var cursors = new HashSet<String>();
      String cursor = "";
      int count = 0;
      int rank = 0;
      int pages = 0;
      long age = 0;
      Long fee = null;
      do {
        if (!cursors.add(cursor))
          throw new WavelengthProtocolException("History cursor did not progress");
        var page = client.list(cursor, budget);
        budget.consumePage(page.entries().size());
        pages++;
        for (var entry : page.entries()) {
          count++;
          if (mapper.matchesPaymentHash(entry, hash)) {
            if (rank != 0) throw new WavelengthProtocolException("History target is ambiguous");
            rank = count;
            fee = entry.feeSats();
            age = Math.max(0, Instant.now().getEpochSecond() - entry.createdAtUnix());
          }
        }
        if (!page.hasMore()) break;
        if (!budget.canContinue()) throw new WavelengthLookupExhaustedException();
        cursor = page.nextCursor();
      } while (true);
      budget.ensureActive();
      if (rank == 0) throw new WavelengthInvoiceNotFoundException();
      var observation = new LinkedHashMap<String, Object>();
      observation.put("lookup_path", lookupPath);
      observation.put("history_entries", count);
      observation.put("history_pages", pages);
      observation.put("target_rank", rank);
      observation.put("target_age_seconds", age);
      if (fee != null) observation.put("fee_sats", fee);
      return observation;
    }

    public void close() {
      if (http != null) {
        http.shutdownNow();
        http = null;
      }
      client = null;
      probe = null;
      mapper = null;
    }
  }

  private record FileEvidence(Path directory, String runId, String manifest)
      implements T7LiveHarness.Evidence {
    public void passed(String gate, Map<String, Object> observation) {
      if (!observation.keySet().equals(WavelengthSpikeRun.OBSERVATION_FIELDS.get(gate))) {
        throw new IllegalStateException("T7 mandatory observation is incomplete");
      }
      try {
        var gateDirectory = Files.createDirectory(directory.resolve(gate));
        var details = Files.createDirectory(gateDirectory.resolve("observation"));
        var data = fields(gate, "passed");
        data.putAll(observation);
        SanitizedEvidenceWriter.write(details, data);
        SanitizedEvidenceWriter.write(gateDirectory, fields(gate, "passed"));
      } catch (IOException failure) {
        throw new IllegalStateException("T7 evidence publication failed");
      }
    }

    public void failed(String gate) {
      try {
        var gateDirectory = directory.resolve(gate);
        if (!Files.exists(gateDirectory)) Files.createDirectory(gateDirectory);
        SanitizedEvidenceWriter.write(gateDirectory, fields(gate, "failed"));
      } catch (IOException failure) {
        throw new IllegalStateException("T7 partial evidence publication failed");
      }
    }

    private LinkedHashMap<String, Object> fields(String gate, String outcome) {
      var fields = new LinkedHashMap<String, Object>();
      fields.put("run_id", runId);
      fields.put("manifest_sha256", manifest);
      fields.put("gate", gate);
      fields.put("operation", "artifact_write");
      fields.put("outcome", outcome);
      return fields;
    }
  }
}
