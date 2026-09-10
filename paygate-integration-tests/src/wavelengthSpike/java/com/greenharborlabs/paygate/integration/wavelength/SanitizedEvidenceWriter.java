package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Writes only validated, non-secret metadata for a Wavelength spike evidence run. */
final class SanitizedEvidenceWriter {

  static final String ARTIFACT_NAME = "evidence.json";
  static final String INVALID_EVIDENCE_MESSAGE = "Wavelength evidence contains an unsafe field";
  static final String INVALID_DIRECTORY_MESSAGE = "Wavelength evidence directory is unavailable";
  static final String WRITE_FAILURE_MESSAGE = "Wavelength evidence could not be written safely";

  private static final Pattern RUN_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern IDENTIFIER_PREFIX = Pattern.compile("[0-9a-f]{16}");

  private static final Set<String> GATES =
      Set.of(
          "preflight",
          "transport",
          "status",
          "receive",
          "invoice_decode",
          "payer_dispatch",
          "payer_recovery",
          "receiver_settlement",
          "restart_lookup",
          "paygate_authorization",
          "outage",
          "evidence");
  private static final Set<String> OPERATIONS =
      Set.of(
          "status",
          "recv",
          "inspect_activity",
          "list",
          "browser_payment",
          "browser_activity",
          "paygate_request",
          "daemon_restart",
          "artifact_write");
  private static final Set<String> OUTCOMES =
      Set.of(
          "passed",
          "failed",
          "unavailable",
          "not_found",
          "exhausted",
          "indeterminate",
          "not_observed");
  private static final Set<String> GRPC_CODES =
      Set.of(
          "OK",
          "INVALID_ARGUMENT",
          "UNAUTHENTICATED",
          "PERMISSION_DENIED",
          "NOT_FOUND",
          "ABORTED",
          "RESOURCE_EXHAUSTED",
          "UNIMPLEMENTED",
          "UNAVAILABLE",
          "DEADLINE_EXCEEDED",
          "UNKNOWN");
  private static final Set<String> RECEIVE_MODES =
      Set.of("credit_backed", "swap_backed", "unknown", "not_observed");
  private static final Set<String> ASSET_CATEGORIES =
      Set.of("server_credits", "client_claimable_ark", "unknown", "not_observed");
  private static final Set<String> BROWSER_EVENT_CODES =
      Set.of(
          "runtime_ready",
          "payment_required",
          "paying_over_lightning",
          "unlocked",
          "payment_outcome_unknown");

  private static final List<String> REQUIRED_FIELDS =
      List.of("run_id", "manifest_sha256", "gate", "operation", "outcome");
  private static final List<String> FIELD_ORDER =
      List.of(
          "run_id",
          "manifest_sha256",
          "gate",
          "operation",
          "outcome",
          "http_status",
          "grpc_code",
          "duration_ms",
          "response_bytes",
          "identifier_prefix",
          "receive_mode",
          "asset_category",
          "browser_event_code");
  private static final Set<String> ALLOWED_FIELDS = Set.copyOf(FIELD_ORDER);

  private SanitizedEvidenceWriter() {}

  static Path write(Path evidenceDirectory, Map<String, ?> candidate) throws IOException {
    var evidence = validate(candidate);
    validateDirectory(evidenceDirectory);

    Path temporary = null;
    try {
      temporary = Files.createTempFile(evidenceDirectory, ".wavelength-evidence-", ".tmp");
      Files.writeString(
          temporary,
          toJson(evidence),
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.WRITE);
      var artifact = evidenceDirectory.resolve(ARTIFACT_NAME);
      Files.move(
          temporary, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return artifact;
    } catch (AtomicMoveNotSupportedException e) {
      deleteQuietly(temporary);
      throw new IOException(WRITE_FAILURE_MESSAGE);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(temporary);
      throw new IOException(WRITE_FAILURE_MESSAGE);
    }
  }

  private static Map<String, Object> validate(Map<String, ?> candidate) {
    if (candidate == null
        || !candidate.keySet().stream().allMatch(ALLOWED_FIELDS::contains)
        || !candidate.keySet().containsAll(REQUIRED_FIELDS)) {
      throw new IllegalArgumentException(INVALID_EVIDENCE_MESSAGE);
    }

    var validated = new LinkedHashMap<String, Object>();
    for (var field : FIELD_ORDER) {
      if (!candidate.containsKey(field)) {
        continue;
      }
      var value = candidate.get(field);
      if (!isValid(field, value)) {
        throw new IllegalArgumentException(INVALID_EVIDENCE_MESSAGE);
      }
      validated.put(field, normalizeNumber(value));
    }
    return validated;
  }

  private static boolean isValid(String field, Object value) {
    if (value == null) {
      return false;
    }
    return switch (field) {
      case "run_id" -> matches(value, RUN_ID);
      case "manifest_sha256" -> matches(value, SHA_256);
      case "gate" -> GATES.contains(value);
      case "operation" -> OPERATIONS.contains(value);
      case "outcome" -> OUTCOMES.contains(value);
      case "http_status" -> isIntegerInRange(value, 100, 599);
      case "grpc_code" -> GRPC_CODES.contains(value);
      case "duration_ms" -> isIntegerInRange(value, 0, 900_000);
      case "response_bytes" -> isIntegerInRange(value, 0, BoundedBodyHandler.MAX_BODY_BYTES + 1L);
      case "identifier_prefix" -> matches(value, IDENTIFIER_PREFIX);
      case "receive_mode" -> RECEIVE_MODES.contains(value);
      case "asset_category" -> ASSET_CATEGORIES.contains(value);
      case "browser_event_code" -> BROWSER_EVENT_CODES.contains(value);
      default -> false;
    };
  }

  private static boolean matches(Object value, Pattern pattern) {
    return value instanceof String text && pattern.matcher(text).matches();
  }

  private static boolean isIntegerInRange(Object value, long minimum, long maximum) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long)) {
      return false;
    }
    long number = ((Number) value).longValue();
    return number >= minimum && number <= maximum;
  }

  private static Object normalizeNumber(Object value) {
    return value instanceof Number number ? number.longValue() : value;
  }

  private static void validateDirectory(Path evidenceDirectory) {
    if (evidenceDirectory == null
        || Files.isSymbolicLink(evidenceDirectory)
        || !Files.isDirectory(evidenceDirectory, LinkOption.NOFOLLOW_LINKS)
        || !Files.isWritable(evidenceDirectory)) {
      throw new IllegalArgumentException(INVALID_DIRECTORY_MESSAGE);
    }
  }

  private static String toJson(Map<String, Object> evidence) {
    var entries = new ArrayList<String>();
    entries.add("\"schema_version\":1");
    evidence.forEach(
        (field, value) ->
            entries.add(
                quote(field) + ":" + (value instanceof Number ? value : quote((String) value))));
    return "{" + String.join(",", entries) + "}\n";
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static void deleteQuietly(Path temporary) {
    if (temporary == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException ignored) {
      // The original fixed failure remains authoritative and path-free.
    }
  }
}
