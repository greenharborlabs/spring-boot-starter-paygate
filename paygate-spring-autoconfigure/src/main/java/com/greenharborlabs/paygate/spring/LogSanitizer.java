package com.greenharborlabs.paygate.spring;

/** Utility for stripping control characters from untrusted values before logging. */
public final class LogSanitizer {

  private LogSanitizer() {}

  public static String sanitize(String value) {
    if (value == null) {
      return "null";
    }
    return value
        .codePoints()
        .filter(cp -> cp >= 0x20 && cp != 0x7F)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }
}
