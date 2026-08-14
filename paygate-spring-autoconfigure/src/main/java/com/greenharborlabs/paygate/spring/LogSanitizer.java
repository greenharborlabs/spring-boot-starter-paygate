package com.greenharborlabs.paygate.spring;

/** Utility for stripping control characters from untrusted values before logging. */
public final class LogSanitizer {

  private static final int TOKEN_CORRELATION_HEX_LENGTH = 16;

  private LogSanitizer() {}

  public static String sanitize(String value) {
    if (value == null) {
      return "null";
    }
    return value
        .codePoints()
        .filter(LogSanitizer::isSafeLogCodePoint)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  /**
   * Returns a logging-safe correlation prefix for a hex-encoded token identifier.
   *
   * <p>Token identifiers are hex encoded, so sixteen characters represent eight bytes. Limiting the
   * value before it reaches a log or metric prevents credential material from being exposed.
   *
   * @param tokenId an untrusted token identifier
   * @return a sanitized prefix containing at most eight token bytes
   */
  public static String sanitizeTokenId(String tokenId) {
    String sanitized = sanitize(tokenId);
    return sanitized.substring(0, Math.min(sanitized.length(), TOKEN_CORRELATION_HEX_LENGTH));
  }

  private static boolean isSafeLogCodePoint(int codePoint) {
    return codePoint >= 0x20
        && codePoint != 0x7F
        && !(codePoint >= 0x80 && codePoint <= 0x9F)
        && Character.getType(codePoint) != Character.FORMAT;
  }
}
