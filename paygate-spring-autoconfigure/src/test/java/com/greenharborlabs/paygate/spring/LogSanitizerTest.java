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
  @DisplayName("strips control characters and DEL")
  void stripsControlCharactersAndDel() {
    assertThat(LogSanitizer.sanitize("line1\nline2\t\u007Fok")).isEqualTo("line1line2ok");
  }

  @Test
  @DisplayName("preserves printable characters")
  void preservesPrintableCharacters() {
    assertThat(LogSanitizer.sanitize("safe-value_123")).isEqualTo("safe-value_123");
  }
}
