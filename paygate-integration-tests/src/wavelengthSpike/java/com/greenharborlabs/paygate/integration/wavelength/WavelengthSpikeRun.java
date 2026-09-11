package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Enforces preflight and current-run acceptance for the live Wavelength spike. */
public final class WavelengthSpikeRun {

  static final String RUN_ID_PROPERTY = "wavelength.spike.run-id";
  static final String RUN_DIRECTORY_PROPERTY = "wavelength.spike.run-directory";
  static final String MANIFEST_PATH_PROPERTY = "wavelength.spike.manifest-path";
  static final String MANIFEST_SHA256_PROPERTY = "wavelength.spike.manifest-sha256";

  static final String EXPECTED_MANIFEST_SHA256 =
      "c10ee0755c9f46b80482029ca9fd5e57fab6ce6e4fdaa699d7eeb2a58829c2d7";
  static final String EXECUTION_SUMMARY_NAME = "execution.properties";

  private static final String LIVE_TEST_CLASS =
      "com.greenharborlabs.paygate.integration.wavelength.WavelengthSpikeIT";
  private static final Set<String> MANDATORY_TESTS =
      Set.of(LIVE_TEST_CLASS + "#currentRunEvidenceIsBound");
  private static final List<String> MANDATORY_GATES = List.of("preflight", "evidence");
  private static final Pattern RUN_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private static final Map<String, String> RUNTIME_ASSET_SHA256 =
      Map.ofEntries(
          Map.entry(
              "wavewalletdk.wasm",
              "868e8a7b56c49dd150e62238ffc26a7654c1e2033c8c889aab80ece88e704662"),
          Map.entry(
              "wavewalletdk.wasm.gz",
              "4090ebaea89c31bbd4e9e4dea844bfafcbdbc13803caed640faddf3417fbf809"),
          Map.entry(
              "wasm_exec.js", "0c949f4996f9a89698e4b5c586de32249c3b69b7baadb64d220073cc04acba14"),
          Map.entry(
              "sqlite-bridge.js",
              "5af9600180007a6c1ce79ce53f1d4bd983c3cabc752761d77b449fe512a17066"),
          Map.entry(
              "sqlite-worker.js",
              "75d368280451880b5e7eac4bbce7d1a2d90b1546734ad6783d9d8712ddda37e9"),
          Map.entry(
              "sqlite3.js", "ceb7e58031eebac151c5a0374bc8bb82dff6221bac55947662011a7cb485bac9"),
          Map.entry(
              "sqlite3.wasm", "4dd52fadf5d76e0abee36e07441baa2b53a760d1591d3ba1200eca88e11e82c6"),
          Map.entry(
              "sqlite3-opfs-async-proxy.js",
              "4ea2bcbd715b0d56089fc871ea241f8c5985d8669d1ddecaab4d56a8da806ce9"));

  private static final List<String> CONFIRMATIONS =
      List.of(
          "WAVELENGTH_SPIKE_RECEIVER_READY",
          "WAVELENGTH_SPIKE_PAYER_READY",
          "WAVELENGTH_SPIKE_PAYER_FUNDED",
          "WAVELENGTH_SPIKE_BROWSER_PROFILE_FRESH",
          "WAVELENGTH_SPIKE_PAYMENT_AUTHORIZED");

  private WavelengthSpikeRun() {}

  /** Entry point used by the uncached Gradle acceptance-finalizer task. */
  public static void main(String[] arguments) {
    if (arguments.length != 3) {
      throw new IllegalStateException("Wavelength spike acceptance context is unavailable");
    }
    validateAcceptance(Path.of(arguments[0]), arguments[1], arguments[2]);
  }

  static void preflight(
      Map<String, String> environment,
      Path manifest,
      Path runDirectory,
      String runId,
      String manifestSha256)
      throws IOException {
    preflight(
        environment,
        manifest,
        runDirectory,
        runId,
        manifestSha256,
        EXPECTED_MANIFEST_SHA256,
        RUNTIME_ASSET_SHA256);
  }

  static void preflight(
      Map<String, String> environment,
      Path manifest,
      Path runDirectory,
      String runId,
      String manifestSha256,
      String expectedManifestSha256,
      Map<String, String> runtimeAssetSha256)
      throws IOException {
    var failures = new ArrayList<String>();

    if (!matches(runId, RUN_ID)) {
      failures.add("fresh run identity");
    }
    if (!isSafeRunDirectory(runDirectory)) {
      failures.add("fresh evidence directory");
    }
    if (!isCurrentManifest(manifest, manifestSha256, expectedManifestSha256)) {
      failures.add("pinned compatibility manifest");
    }
    if (!isTlsEndpoint(environment.get("WAVELENGTH_SPIKE_DAEMON_URL"))) {
      failures.add("daemon TLS endpoint");
    }
    if (!isCredentialSafe(environment.get("WAVELENGTH_SPIKE_CREDENTIAL_PATH"))) {
      failures.add("credential file");
    }
    if (!isSafeDirectory(environment.get("WAVELENGTH_SPIKE_RECEIVER_DATA_DIR"))) {
      failures.add("receiver data directory");
    }
    if (!isSafeDirectory(environment.get("WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR"))) {
      failures.add("fresh browser profile directory");
    }
    if (!runtimeAssetsMatch(environment.get("WAVELENGTH_SPIKE_RUNTIME_DIR"), runtimeAssetSha256)) {
      failures.add("pinned browser runtime assets");
    }
    if (!isExecutableFile(environment.get("WAVELENGTH_SPIKE_RESTART_EXECUTABLE"))) {
      failures.add("isolated daemon restart executable");
    }
    for (var confirmation : CONFIRMATIONS) {
      if (!"confirmed".equals(environment.get(confirmation))) {
        failures.add(confirmation);
      }
    }

    if (!failures.isEmpty()) {
      throw new IllegalStateException(
          "Wavelength preflight failed: " + String.join(", ", failures));
    }

    writeGateEvidence(runDirectory, runId, manifestSha256, "preflight");
  }

  static void writeGateEvidence(Path runDirectory, String runId, String manifestSha256, String gate)
      throws IOException {
    var gateDirectory = runDirectory.resolve(gate);
    Files.createDirectory(gateDirectory);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("run_id", runId);
    evidence.put("manifest_sha256", manifestSha256);
    evidence.put("gate", gate);
    evidence.put("operation", "artifact_write");
    evidence.put("outcome", "passed");
    SanitizedEvidenceWriter.write(gateDirectory, evidence);
  }

  static void validateAcceptance(Path runDirectory, String runId, String manifestSha256) {
    var failures = new ArrayList<String>();
    if (!matches(runId, RUN_ID) || !matches(manifestSha256, SHA_256)) {
      failures.add("invalid current-run identity");
    }

    var results = readExecutionResults(runDirectory, failures);
    if (results.isEmpty()) {
      failures.add("zero discovered tests");
    }
    for (var mandatoryTest : MANDATORY_TESTS) {
      var outcomes = results.getOrDefault(mandatoryTest, List.of());
      if (outcomes.size() != 1) {
        failures.add("missing or filtered mandatory test");
      } else if (!"SUCCESS".equals(outcomes.getFirst())) {
        failures.add("skipped or failed mandatory test");
      }
    }

    for (var gate : MANDATORY_GATES) {
      validateGateEvidence(runDirectory, runId, manifestSha256, gate, failures);
    }

    if (!failures.isEmpty()) {
      throw new IllegalStateException(
          "Wavelength spike acceptance failed: " + String.join(", ", failures));
    }
  }

  static String sha256(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static boolean isCurrentManifest(
      Path manifest, String manifestSha256, String expectedManifestSha256) {
    if (manifest == null
        || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(manifest)
        || !matches(manifestSha256, SHA_256)
        || !manifestSha256.equals(expectedManifestSha256)) {
      return false;
    }
    try {
      return manifestSha256.equals(sha256(manifest));
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isTlsEndpoint(String value) {
    if (value == null) {
      return false;
    }
    try {
      var uri = new URI(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static boolean isCredentialSafe(String value) {
    var path = safePath(value);
    if (path == null) {
      return false;
    }
    try {
      SpikeCredentialFileValidator.validate(path);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isSafeDirectory(String value) {
    var path = safePath(value);
    return path != null
        && !Files.isSymbolicLink(path)
        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        && Files.isReadable(path)
        && Files.isWritable(path);
  }

  private static boolean isSafeRunDirectory(Path path) {
    return path != null
        && !Files.isSymbolicLink(path)
        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        && Files.isWritable(path);
  }

  private static boolean isExecutableFile(String value) {
    var path = safePath(value);
    return path != null
        && !Files.isSymbolicLink(path)
        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        && Files.isExecutable(path);
  }

  private static boolean runtimeAssetsMatch(
      String runtimeDirectory, Map<String, String> expectedAssets) {
    var directory = safePath(runtimeDirectory);
    if (directory == null
        || Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    for (var asset : expectedAssets.entrySet()) {
      var path = directory.resolve(asset.getKey());
      if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      try {
        if (!asset.getValue().equals(sha256(path))) {
          return false;
        }
      } catch (IOException e) {
        return false;
      }
    }
    return true;
  }

  private static Path safePath(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Path.of(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Map<String, List<String>> readExecutionResults(
      Path runDirectory, List<String> failures) {
    var summary = runDirectory.resolve(EXECUTION_SUMMARY_NAME);
    if (!Files.isRegularFile(summary, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(summary)) {
      failures.add("missing execution summary");
      return Map.of();
    }

    var properties = new Properties();
    try (var reader = Files.newBufferedReader(summary, StandardCharsets.UTF_8)) {
      properties.load(reader);
      int count = Integer.parseInt(properties.getProperty("test.count", "-1"));
      if (count < 0 || count > 10_000) {
        throw new IllegalArgumentException();
      }
      var results = new LinkedHashMap<String, List<String>>();
      for (int index = 0; index < count; index++) {
        var id = properties.getProperty("test." + index + ".id");
        var outcome = properties.getProperty("test." + index + ".outcome");
        if (id == null || outcome == null) {
          throw new IllegalArgumentException();
        }
        results.computeIfAbsent(id, ignored -> new ArrayList<>()).add(outcome);
      }
      return results;
    } catch (IOException | IllegalArgumentException e) {
      failures.add("invalid execution summary");
      return Map.of();
    }
  }

  private static void validateGateEvidence(
      Path runDirectory, String runId, String manifestSha256, String gate, List<String> failures) {
    var artifact = runDirectory.resolve(gate).resolve(SanitizedEvidenceWriter.ARTIFACT_NAME);
    if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(artifact)) {
      failures.add("missing mandatory evidence");
      return;
    }
    try {
      var content = Files.readString(artifact, StandardCharsets.UTF_8);
      var expected =
          "{\"schema_version\":1,\"run_id\":\""
              + runId
              + "\",\"manifest_sha256\":\""
              + manifestSha256
              + "\",\"gate\":\""
              + gate
              + "\",\"operation\":\"artifact_write\",\"outcome\":\"passed\"}\n";
      if (!content.equals(expected)) {
        failures.add("stale or incomplete mandatory evidence");
      }
    } catch (IOException e) {
      failures.add("unreadable mandatory evidence");
    }
  }

  private static boolean matches(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches();
  }
}
