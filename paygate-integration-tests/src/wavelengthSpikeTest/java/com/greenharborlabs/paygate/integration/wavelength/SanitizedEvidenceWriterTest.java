package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

@DisplayName("Sanitized Wavelength evidence")
class SanitizedEvidenceWriterTest {

  private static final String CANARY = "WALLET_SECRET_CANARY_7f82d4";
  private static final String RUN_ID = "12345678-1234-4abc-8def-1234567890ab";
  private static final String MANIFEST_SHA256 = "a".repeat(64);

  @TempDir Path tempDir;

  @Test
  void writesOnlyValidatedFieldsInStableOrder() throws Exception {
    var candidate = new LinkedHashMap<String, Object>();
    candidate.put("response_bytes", BoundedBodyHandler.MAX_BODY_BYTES);
    candidate.put("outcome", "passed");
    candidate.put("operation", "status");
    candidate.put("gate", "transport");
    candidate.put("manifest_sha256", MANIFEST_SHA256);
    candidate.put("run_id", RUN_ID);
    candidate.put("duration_ms", 12);
    candidate.put("http_status", 200);

    var artifact = SanitizedEvidenceWriter.write(tempDir, candidate);

    assertThat(artifact).isEqualTo(tempDir.resolve(SanitizedEvidenceWriter.ARTIFACT_NAME));
    assertThat(Files.readString(artifact))
        .isEqualTo(
            "{\"schema_version\":1,\"run_id\":\""
                + RUN_ID
                + "\",\"manifest_sha256\":\""
                + MANIFEST_SHA256
                + "\",\"gate\":\"transport\",\"operation\":\"status\","
                + "\"outcome\":\"passed\",\"http_status\":200,\"duration_ms\":12,"
                + "\"response_bytes\":262144}\n");
    try (var entries = Files.list(tempDir)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .containsExactly(SanitizedEvidenceWriter.ARTIFACT_NAME);
    }
  }

  @Test
  void rejectsUnknownFieldsWithoutPublishingAnArtifact() {
    var candidate = validCandidate();
    candidate.put("raw_response", CANARY);

    assertThatThrownBy(() -> SanitizedEvidenceWriter.write(tempDir, candidate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_EVIDENCE_MESSAGE);
    assertDirectoryIsEmpty();
  }

  @Test
  void rejectsNullKeyWithoutPublishingOrReplacingEvidence() throws Exception {
    var candidate = validCandidate();
    candidate.put(null, "passed");

    assertThatThrownBy(() -> SanitizedEvidenceWriter.write(tempDir, candidate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_EVIDENCE_MESSAGE);
    assertDirectoryIsEmpty();

    assertRejectedWithoutReplacing(candidate);
  }

  @Test
  void rejectsArbitraryTextInAnAllowlistedField() {
    var candidate = validCandidate();
    candidate.put("gate", CANARY);

    assertThatThrownBy(() -> SanitizedEvidenceWriter.write(tempDir, candidate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_EVIDENCE_MESSAGE);
    assertDirectoryIsEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"gate", "operation", "outcome"})
  void rejectsNullRequiredEnumFieldsWithoutReplacingEvidence(String field) throws Exception {
    var candidate = validCandidate();
    candidate.put(field, null);

    assertRejectedWithoutReplacing(candidate);
  }

  @ParameterizedTest
  @ValueSource(strings = {"grpc_code", "receive_mode", "asset_category", "browser_event_code"})
  void rejectsNullOptionalEnumFieldsWithoutReplacingEvidence(String field) throws Exception {
    var candidate = validCandidate();
    candidate.put(field, null);

    assertRejectedWithoutReplacing(candidate);
  }

  @Test
  void canaryNeverReachesArtifactsLogsOrAssertionDiagnostics() throws Exception {
    var logEvents = new ListAppender<ILoggingEvent>();
    Logger root =
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    logEvents.start();
    root.addAppender(logEvents);
    Throwable rejection;
    try {
      var candidate = validCandidate();
      candidate.put("browser_event_code", CANARY);
      rejection =
          org.assertj.core.api.Assertions.catchThrowable(
              () -> SanitizedEvidenceWriter.write(tempDir, candidate));
    } finally {
      root.detachAppender(logEvents);
      logEvents.stop();
    }

    assertThat(rejection)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_EVIDENCE_MESSAGE);
    assertSecretAbsent("artifact names and contents", artifactSnapshot());
    assertSecretAbsent(
        "captured logs",
        logEvents.list.stream()
            .map(event -> event.getFormattedMessage() + " " + event.getThrowableProxy())
            .collect(Collectors.joining("\n")));
    assertSecretAbsent("assertion diagnostics", assertionDiagnostics(rejection));
  }

  @Test
  void directoryFailuresExposeNeitherPathNorCandidateValues() {
    var unavailable = tempDir.resolve(CANARY);
    var candidate = validCandidate();

    var failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> SanitizedEvidenceWriter.write(unavailable, candidate));

    assertThat(failure)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_DIRECTORY_MESSAGE);
    assertSecretAbsent("directory failure", assertionDiagnostics(failure));
  }

  private static Map<String, Object> validCandidate() {
    var candidate = new LinkedHashMap<String, Object>();
    candidate.put("run_id", RUN_ID);
    candidate.put("manifest_sha256", MANIFEST_SHA256);
    candidate.put("gate", "evidence");
    candidate.put("operation", "artifact_write");
    candidate.put("outcome", "passed");
    return candidate;
  }

  private void assertRejectedWithoutReplacing(Map<String, Object> candidate) throws Exception {
    var artifact = SanitizedEvidenceWriter.write(tempDir, validCandidate());
    var original = Files.readString(artifact);

    assertThatThrownBy(() -> SanitizedEvidenceWriter.write(tempDir, candidate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SanitizedEvidenceWriter.INVALID_EVIDENCE_MESSAGE);

    assertThat(Files.readString(artifact)).isEqualTo(original);
    try (var entries = Files.list(tempDir)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .containsExactly(SanitizedEvidenceWriter.ARTIFACT_NAME);
    }
  }

  private void assertDirectoryIsEmpty() {
    try (var entries = Files.list(tempDir)) {
      assertThat(entries).isEmpty();
    } catch (Exception e) {
      throw new AssertionError("Could not inspect temporary evidence directory", e);
    }
  }

  private String artifactSnapshot() throws Exception {
    var snapshot = new StringBuilder();
    try (var paths = Files.walk(tempDir)) {
      for (var path : paths.toList()) {
        snapshot.append(path.getFileName()).append('\n');
        if (Files.isRegularFile(path)) {
          snapshot.append(Files.readString(path)).append('\n');
        }
      }
    }
    return snapshot.toString();
  }

  private static String assertionDiagnostics(Throwable rejection) {
    var output = new StringWriter();
    new AssertionError("Sanitized evidence rejection", rejection)
        .printStackTrace(new PrintWriter(output));
    return output.toString();
  }

  private static void assertSecretAbsent(String boundary, String value) {
    if (value.contains(CANARY)) {
      throw new AssertionError(boundary + " contained a forbidden canary value");
    }
  }
}
