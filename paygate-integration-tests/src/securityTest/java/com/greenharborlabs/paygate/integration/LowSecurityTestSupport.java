package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Shared bounded assertions for low-security integration regressions. */
public final class LowSecurityTestSupport {

  private static final String MARKER_RESOURCE = "low-security/marker-secrets.txt";
  private static final int MAX_CAPTURED_DIAGNOSTICS = 64;
  private static final int MAX_DIAGNOSTIC_LENGTH = 4_096;
  private static final int MAX_THROWABLE_DEPTH = 16;
  private static final List<String> MARKERS = loadMarkers();

  private LowSecurityTestSupport() {}

  /** Returns the non-secret marker corpus used for leakage assertions. */
  public static List<String> markerSecrets() {
    return MARKERS;
  }

  /** Fails when a client-visible value or diagnostic contains any complete marker value. */
  public static void assertContainsNoMarkerSecrets(String boundary, CharSequence value) {
    assertThat(value).as(boundary).isNotNull();
    for (var marker : MARKERS) {
      assertThat(value.toString()).as(boundary).doesNotContain(marker);
    }
  }

  /**
   * Fails when any supplied client-visible value or diagnostic contains a complete marker value.
   */
  public static void assertContainsNoMarkerSecrets(
      String boundary, Iterable<? extends CharSequence> values) {
    assertThat(values).as(boundary).isNotNull();
    for (var value : values) {
      assertContainsNoMarkerSecrets(boundary, value);
    }
  }

  /** Fails unless the supplied protected-handler count remains zero. */
  public static void assertNoProtectedHandlerExecution(LongSupplier count) {
    assertNoSideEffect("protected handler executions", count);
  }

  /** Fails unless the supplied invoice-creation count remains zero. */
  public static void assertNoInvoiceCreation(LongSupplier count) {
    assertNoSideEffect("invoice creations", count);
  }

  /** Fails unless the supplied root-key operation count remains zero. */
  public static void assertNoRootKeyOperation(LongSupplier count) {
    assertNoSideEffect("root-key operations", count);
  }

  /** Fails unless the supplied protocol-formatting count remains zero. */
  public static void assertNoProtocolFormatting(LongSupplier count) {
    assertNoSideEffect("protocol formatting operations", count);
  }

  private static void assertNoSideEffect(String name, LongSupplier count) {
    assertThat(count).as(name + " count supplier").isNotNull();
    assertThat(count.getAsLong()).as(name).isZero();
  }

  /** Creates a bounded capture of diagnostics for marker and untrusted-message assertions. */
  public static DiagnosticsCapture capturedDiagnostics() {
    return new DiagnosticsCapture();
  }

  /**
   * Stores a bounded set of observed diagnostics and checks it for prohibited marker or nested
   * untrusted exception text.
   */
  public static final class DiagnosticsCapture {
    private final List<String> values = new ArrayList<>();

    /** Captures one bounded diagnostic after first rejecting complete marker values. */
    public void record(String boundary, CharSequence value) {
      Objects.requireNonNull(boundary, "boundary must not be null");
      Objects.requireNonNull(value, "diagnostic value must not be null");
      if (values.size() == MAX_CAPTURED_DIAGNOSTICS) {
        throw new AssertionError("too many captured diagnostics; capture must remain bounded");
      }
      if (value.length() > MAX_DIAGNOSTIC_LENGTH) {
        throw new AssertionError("captured diagnostic exceeds the bounded test capture length");
      }
      assertContainsNoMarkerSecrets(boundary, value);
      values.add(value.toString());
    }

    /** Verifies that no complete marker is present in the captured diagnostics. */
    public void assertContainsNoMarkers() {
      assertContainsNoMarkerSecrets("captured diagnostics", values);
    }

    /** Fails when captured output reflects a message from any bounded cause or suppressed chain. */
    public void assertDoesNotContain(Throwable attackerControlled) {
      for (var message : throwableMessages(attackerControlled)) {
        for (var value : values) {
          assertThat(value)
              .as("captured diagnostics must not reflect attacker-controlled exception messages")
              .doesNotContain(message);
        }
      }
    }

    /** Returns an immutable snapshot of the bounded diagnostic capture. */
    public List<String> values() {
      return List.copyOf(values);
    }
  }

  private static List<String> loadMarkers() {
    InputStream resource =
        LowSecurityTestSupport.class.getClassLoader().getResourceAsStream(MARKER_RESOURCE);
    if (resource == null) {
      throw new IllegalStateException("missing marker corpus " + MARKER_RESOURCE);
    }
    try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
      var markers =
          reader.lines().map(String::trim).filter(LowSecurityTestSupport::isMarker).toList();
      if (markers.isEmpty()) {
        throw new IllegalStateException("marker corpus must contain at least one marker");
      }
      return markers;
    } catch (IOException exception) {
      throw new IllegalStateException("could not load marker corpus", exception);
    }
  }

  private static boolean isMarker(String value) {
    return !value.isBlank() && !value.startsWith("#");
  }

  private static List<String> throwableMessages(Throwable throwable) {
    var messages = new ArrayList<String>();
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    collectThrowableMessages(throwable, messages, seen, 0);
    return messages;
  }

  private static void collectThrowableMessages(
      Throwable throwable, List<String> messages, Map<Throwable, Boolean> seen, int depth) {
    if (throwable == null
        || depth == MAX_THROWABLE_DEPTH
        || seen.put(throwable, Boolean.TRUE) != null) {
      return;
    }
    var message = throwable.getMessage();
    if (message != null && !message.isEmpty()) {
      messages.add(message);
    }
    collectThrowableMessages(throwable.getCause(), messages, seen, depth + 1);
    for (var suppressed : throwable.getSuppressed()) {
      collectThrowableMessages(suppressed, messages, seen, depth + 1);
    }
  }
}
