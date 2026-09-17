package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Wavelength live-run acceptance")
class WavelengthSpikeRunTest {

  private static final String RUN_ID = "12345678-1234-4abc-8def-1234567890ab";
  private static final String OLD_RUN_ID = "87654321-4321-4cba-8fed-ba0987654321";
  private static final String MANIFEST_SHA256 = "a".repeat(64);
  private static final String OLD_MANIFEST_SHA256 = "b".repeat(64);
  private static final String MANDATORY_TEST =
      "com.greenharborlabs.paygate.integration.wavelength.WavelengthSpikeIT"
          + "#currentRunEvidenceIsBound";

  @TempDir Path tempDir;

  @Nested
  @DisplayName("Preflight")
  class Preflight {

    private Path manifest;
    private Path runDirectory;
    private Map<String, String> environment;
    private Map<String, String> runtimeAssets;

    @BeforeEach
    void setUp() throws Exception {
      manifest = Files.writeString(tempDir.resolve("manifest.json"), "pinned manifest");
      runDirectory = Files.createDirectory(tempDir.resolve("run"));
      var credential = Files.writeString(tempDir.resolve("credential"), "temporary credential");
      Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));
      var receiverData = Files.createDirectory(tempDir.resolve("receiver"));
      var browserProfile = Files.createDirectory(tempDir.resolve("browser"));
      var runtime = Files.createDirectory(tempDir.resolve("runtime"));
      var runtimeAsset = Files.writeString(runtime.resolve("runtime.bin"), "runtime asset");
      var restart = Files.writeString(tempDir.resolve("restart"), "#!/bin/sh\nexit 0\n");
      Files.setPosixFilePermissions(restart, PosixFilePermissions.fromString("rwx------"));

      environment = new LinkedHashMap<>();
      environment.put("WAVELENGTH_SPIKE_DAEMON_URL", "https://127.0.0.1:8081");
      environment.put("WAVELENGTH_SPIKE_CREDENTIAL_PATH", credential.toString());
      environment.put("WAVELENGTH_SPIKE_RECEIVER_DATA_DIR", receiverData.toString());
      environment.put("WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR", browserProfile.toString());
      environment.put("WAVELENGTH_SPIKE_RUNTIME_DIR", runtime.toString());
      environment.put("WAVELENGTH_SPIKE_RESTART_EXECUTABLE", restart.toString());
      environment.put("WAVELENGTH_SPIKE_RECEIVER_READY", "confirmed");
      environment.put("WAVELENGTH_SPIKE_PAYER_READY", "confirmed");
      environment.put("WAVELENGTH_SPIKE_PAYER_FUNDED", "confirmed");
      environment.put("WAVELENGTH_SPIKE_BROWSER_PROFILE_FRESH", "confirmed");
      environment.put("WAVELENGTH_SPIKE_PAYMENT_AUTHORIZED", "confirmed");
      runtimeAssets =
          Map.of(runtimeAsset.getFileName().toString(), WavelengthSpikeRun.sha256(runtimeAsset));
    }

    @Test
    void acceptsCompletePrerequisitesAndWritesCurrentPreflightEvidence() throws Exception {
      var digest = WavelengthSpikeRun.sha256(manifest);

      WavelengthSpikeRun.preflight(
          environment, manifest, runDirectory, RUN_ID, digest, digest, runtimeAssets);

      assertThat(Files.readString(runDirectory.resolve("preflight/evidence.json")))
          .contains("\"run_id\":\"" + RUN_ID + "\"")
          .contains("\"manifest_sha256\":\"" + digest + "\"")
          .contains("\"gate\":\"preflight\"");
    }

    @Test
    void missingPrerequisitesFailWithoutExposingValuesOrPaths() {
      var marker = tempDir.resolve("PRIVATE_PATH_MARKER").toString();
      environment.replaceAll((key, value) -> marker);

      var failure =
          org.assertj.core.api.Assertions.catchThrowable(
              () ->
                  WavelengthSpikeRun.preflight(
                      environment,
                      manifest,
                      runDirectory,
                      RUN_ID,
                      MANIFEST_SHA256,
                      MANIFEST_SHA256,
                      runtimeAssets));

      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Wavelength preflight failed")
          .hasMessageContaining("daemon TLS endpoint")
          .hasMessageContaining("credential file")
          .hasMessageContaining("WAVELENGTH_SPIKE_PAYMENT_AUTHORIZED");
      assertThat(failure.getMessage()).doesNotContain(marker).doesNotContain("PRIVATE_PATH_MARKER");
      assertThat(runDirectory.resolve("preflight/evidence.json")).doesNotExist();
    }

    @Test
    void staleManifestIdentityFailsPreflight() throws Exception {
      var digest = WavelengthSpikeRun.sha256(manifest);

      assertThatThrownBy(
              () ->
                  WavelengthSpikeRun.preflight(
                      environment,
                      manifest,
                      runDirectory,
                      RUN_ID,
                      digest,
                      OLD_MANIFEST_SHA256,
                      runtimeAssets))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("pinned compatibility manifest");
    }

    @Test
    void runtimeAssetMismatchFailsPreflight() throws Exception {
      var digest = WavelengthSpikeRun.sha256(manifest);
      runtimeAssets = Map.of("runtime.bin", OLD_MANIFEST_SHA256);

      assertThatThrownBy(
              () ->
                  WavelengthSpikeRun.preflight(
                      environment, manifest, runDirectory, RUN_ID, digest, digest, runtimeAssets))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("pinned browser runtime assets");
    }
  }

  @Nested
  @DisplayName("Current-run validation")
  class CurrentRunValidation {

    private Path runDirectory;

    @BeforeEach
    void setUp() throws Exception {
      runDirectory = Files.createDirectory(tempDir.resolve("run"));
    }

    @Test
    void acceptsExactlyOneSuccessfulMandatoryTestAndCurrentEvidence() throws Exception {
      writeExecution(
          MANDATORY_TEST,
          "SUCCESS",
          MANDATORY_TEST.replace("currentRunEvidenceIsBound", "directSignetCapabilities"),
          "SUCCESS");
      writeEvidence(runDirectory, RUN_ID, MANIFEST_SHA256);

      WavelengthSpikeRun.validateAcceptance(runDirectory, RUN_ID, MANIFEST_SHA256);
    }

    @Test
    void completionMarkersWithoutObservationsCannotPass() throws Exception {
      writeExecution(
          MANDATORY_TEST,
          "SUCCESS",
          MANDATORY_TEST.replace("currentRunEvidenceIsBound", "directSignetCapabilities"),
          "SUCCESS");
      writeEvidence(runDirectory, RUN_ID, MANIFEST_SHA256);
      Files.delete(runDirectory.resolve("payer_recovery/observation/evidence.json"));
      assertAcceptanceFailsWith("missing, stale or incomplete mandatory observation");
    }

    @Test
    void integrityOnlyCannotPassEvenWithAllSyntheticArtifacts() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      writeEvidence(runDirectory, RUN_ID, MANIFEST_SHA256);
      assertAcceptanceFailsWith("missing or filtered mandatory test");
    }

    @Test
    void zeroDiscoveredTestsFailAcceptance() throws Exception {
      writeExecution();

      assertAcceptanceFailsWith("zero discovered tests");
    }

    @Test
    void filteredMandatoryTestFailsEvenWhenAnotherTestPassed() throws Exception {
      writeExecution("example.OtherTest#passes", "SUCCESS");

      assertAcceptanceFailsWith("missing or filtered mandatory test");
    }

    @Test
    void skippedMandatoryTestFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SKIPPED");

      assertAcceptanceFailsWith("skipped or failed mandatory test");
    }

    @Test
    void duplicateMandatoryTestResultFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS", MANDATORY_TEST, "SUCCESS");

      assertAcceptanceFailsWith("missing or filtered mandatory test");
    }

    @Test
    void staleRunEvidenceFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      writeEvidence(runDirectory, OLD_RUN_ID, MANIFEST_SHA256);

      assertAcceptanceFailsWith("stale or incomplete mandatory evidence");
    }

    @Test
    void staleManifestEvidenceFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      writeEvidence(runDirectory, RUN_ID, OLD_MANIFEST_SHA256);

      assertAcceptanceFailsWith("stale or incomplete mandatory evidence");
    }

    @Test
    void missingEvidenceFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      WavelengthSpikeRun.writeGateEvidence(runDirectory, RUN_ID, MANIFEST_SHA256, "preflight");

      assertAcceptanceFailsWith("missing mandatory evidence");
    }

    @Test
    void incompleteEvidenceFailsAcceptance() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      writeEvidence(runDirectory, RUN_ID, MANIFEST_SHA256);
      Files.writeString(
          runDirectory.resolve("evidence/evidence.json"),
          "{\"schema_version\":1}\n",
          StandardCharsets.UTF_8);

      assertAcceptanceFailsWith("stale or incomplete mandatory evidence");
    }

    @Test
    void priorRunArtifactsCannotSatisfyANewRun() throws Exception {
      writeExecution(MANDATORY_TEST, "SUCCESS");
      var priorRun = Files.createDirectory(tempDir.resolve("prior-run"));
      writeEvidence(priorRun, RUN_ID, MANIFEST_SHA256);

      assertAcceptanceFailsWith("missing mandatory evidence");
    }

    private void assertAcceptanceFailsWith(String message) {
      assertThatThrownBy(
              () -> WavelengthSpikeRun.validateAcceptance(runDirectory, RUN_ID, MANIFEST_SHA256))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(message);
    }

    private void writeExecution(String... testAndOutcomePairs) throws Exception {
      var lines = new StringBuilder();
      lines.append("test.count=").append(testAndOutcomePairs.length / 2).append('\n');
      for (int index = 0; index < testAndOutcomePairs.length; index += 2) {
        int testIndex = index / 2;
        lines
            .append("test.")
            .append(testIndex)
            .append(".id=")
            .append(testAndOutcomePairs[index])
            .append('\n');
        lines
            .append("test.")
            .append(testIndex)
            .append(".outcome=")
            .append(testAndOutcomePairs[index + 1])
            .append('\n');
      }
      Files.writeString(
          runDirectory.resolve(WavelengthSpikeRun.EXECUTION_SUMMARY_NAME), lines.toString());
    }
  }

  private static void writeEvidence(Path directory, String runId, String manifestSha256)
      throws Exception {
    for (var gate : WavelengthSpikeRun.MANDATORY_GATES) {
      WavelengthSpikeRun.writeGateEvidence(directory, runId, manifestSha256, gate);
      if (WavelengthSpikeRun.OBSERVATION_FIELDS.containsKey(gate)) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("run_id", runId);
        fields.put("manifest_sha256", manifestSha256);
        fields.put("gate", gate);
        fields.put("operation", "artifact_write");
        fields.put("outcome", "passed");
        for (var field : WavelengthSpikeRun.OBSERVATION_FIELDS.get(gate)) {
          fields.put(
              field,
              switch (field) {
                case "receive_mode" -> "credit_backed";
                case "asset_category" -> "server_credits";
                case "asset_control", "redemption_dependency" -> "server";
                case "exit_evidence" -> "unknown";
                case "readiness" -> "ready";
                case "lookup_path" -> "list";
                case "unpaid_receives" -> 2;
                case "principal_sats" -> 10;
                default -> 1;
              });
        }
        SanitizedEvidenceWriter.write(
            Files.createDirectory(directory.resolve(gate).resolve("observation")), fields);
      }
    }
  }
}
