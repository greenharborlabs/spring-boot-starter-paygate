package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LogSanitizer")
class LogSanitizerTest {

  @Test
  @DisplayName("returns literal null for null input")
  void returnsLiteralNullForNullInput() {
    assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
  }

  @Test
  @DisplayName("strips C0, C1, and DEL control characters")
  void stripsC0C1AndDelControlCharacters() {
    String secretMarker = "CONTROL_SECRET_MARKER";

    String sanitized =
        LogSanitizer.sanitize("line1\nline2\t\u007F\u0085\u009F" + secretMarker + "\u0000ok");

    assertThat(sanitized).isEqualTo("line1line2" + secretMarker + "ok");
  }

  @Test
  @DisplayName("preserves printable characters")
  void preservesPrintableCharacters() {
    assertThat(LogSanitizer.sanitize("safe-value_123")).isEqualTo("safe-value_123");
  }

  @Test
  @DisplayName("strips bidirectional and Unicode format controls from exception text")
  void stripsBidirectionalAndUnicodeFormatControlsFromExceptionText() {
    String exceptionText = "provider\u202Efailed\u2066: timeout\u200B";

    String sanitized = LogSanitizer.sanitize(exceptionText);

    assertThat(sanitized).isEqualTo("providerfailed: timeout");
    assertThat(sanitized).doesNotContain("\u202E", "\u2066", "\u200B");
  }

  @Test
  @DisplayName("uses only an eight-byte sanitized prefix for token correlation")
  void usesOnlyAnEightByteSanitizedPrefixForTokenCorrelation() {
    String secretMarker = "FULL_TOKEN_SECRET_MARKER";
    String fullTokenId = "0011223344556677" + secretMarker;

    String correlation = LogSanitizer.sanitizeTokenId(fullTokenId);

    assertThat(correlation).isEqualTo("0011223344556677");
    assertThat(correlation).doesNotContain(secretMarker, fullTokenId);
  }
}
