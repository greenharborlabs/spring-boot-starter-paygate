package com.greenharborlabs.paygate.core.util;

/**
 * Minimal JSON string escaper with zero external dependencies. Escapes special characters and all
 * control characters below U+0020 per RFC 8259 Section 7.
 */
public final class JsonEscaper {

  private JsonEscaper() {}

  /**
   * Escapes a string for safe inclusion in a JSON string value. Returns an empty string if {@code
   * value} is {@code null}.
   *
   * @param value the raw string to escape
   * @return the JSON-escaped string, never null
   */
  public static String escape(String value) {
    if (value == null) {
      return "";
    }
    var sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
