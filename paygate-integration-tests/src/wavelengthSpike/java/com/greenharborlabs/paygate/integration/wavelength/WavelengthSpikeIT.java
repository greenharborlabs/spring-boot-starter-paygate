package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Live Wavelength spike acceptance integrity")
class WavelengthSpikeIT {

  @BeforeAll
  static void runMandatoryPreflight() throws IOException {
    try {
      WavelengthSpikeRun.preflight(
          System.getenv(),
          requiredPath(WavelengthSpikeRun.MANIFEST_PATH_PROPERTY),
          requiredPath(WavelengthSpikeRun.RUN_DIRECTORY_PROPERTY),
          requiredProperty(WavelengthSpikeRun.RUN_ID_PROPERTY),
          requiredProperty(WavelengthSpikeRun.MANIFEST_SHA256_PROPERTY));
      T7LiveEnvironment.preflight(System.getenv());
    } catch (IllegalStateException failure) {
      var directory = requiredPath(WavelengthSpikeRun.RUN_DIRECTORY_PROPERTY).resolve("preflight");
      if (!java.nio.file.Files.exists(directory)) java.nio.file.Files.createDirectory(directory);
      SanitizedEvidenceWriter.write(
          directory,
          java.util.Map.of(
              "run_id",
              requiredProperty(WavelengthSpikeRun.RUN_ID_PROPERTY),
              "manifest_sha256",
              requiredProperty(WavelengthSpikeRun.MANIFEST_SHA256_PROPERTY),
              "gate",
              "preflight",
              "operation",
              "artifact_write",
              "outcome",
              "unavailable"));
      throw failure;
    }
  }

  @Test
  void directSignetCapabilities() {
    T7LiveEnvironment.execute(
        System.getenv(),
        requiredPath("wavelength.spike.browser-directory"),
        requiredPath(WavelengthSpikeRun.RUN_DIRECTORY_PROPERTY),
        requiredProperty(WavelengthSpikeRun.RUN_ID_PROPERTY),
        requiredProperty(WavelengthSpikeRun.MANIFEST_SHA256_PROPERTY));
  }

  @Test
  void currentRunEvidenceIsBound() throws IOException {
    WavelengthSpikeRun.writeGateEvidence(
        requiredPath(WavelengthSpikeRun.RUN_DIRECTORY_PROPERTY),
        requiredProperty(WavelengthSpikeRun.RUN_ID_PROPERTY),
        requiredProperty(WavelengthSpikeRun.MANIFEST_SHA256_PROPERTY),
        "evidence");
  }

  private static Path requiredPath(String name) {
    return Path.of(requiredProperty(name));
  }

  private static String requiredProperty(String name) {
    var value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Wavelength spike run context is unavailable");
    }
    return value;
  }
}
