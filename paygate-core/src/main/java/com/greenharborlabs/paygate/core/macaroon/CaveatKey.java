package com.greenharborlabs.paygate.core.macaroon;

import java.util.Objects;

/**
 * Canonicalizes caveat keys for verifier lookup without changing their signed representation.
 *
 * <p>Only leading and trailing ASCII space (U+0020) and horizontal tab (U+0009) are removed.
 * Internal whitespace, all other Unicode whitespace, separators, and caveat values remain exactly
 * as parsed and signed.
 */
public final class CaveatKey {

  private CaveatKey() {}

  /** Rejects ambiguous or blank keys when creating a new caveat. */
  public static void requireValidConstructedKey(String key) {
    if (key == null || key.isBlank() || key.indexOf('=') >= 0) {
      throw new IllegalArgumentException("key must not be null, blank, or contain '='");
    }
  }

  /**
   * Returns the verifier-lookup form of a parsed caveat key.
   *
   * @param key parsed, signed caveat key
   * @return the same key when it has no edge ASCII space/tab, otherwise its canonical lookup form
   */
  public static String canonicalize(String key) {
    Objects.requireNonNull(key, "key must not be null");
    int first = 0;
    int last = key.length();
    while (first < last && isEdgeWhitespace(key.charAt(first))) {
      first++;
    }
    while (last > first && isEdgeWhitespace(key.charAt(last - 1))) {
      last--;
    }
    return first == 0 && last == key.length() ? key : key.substring(first, last);
  }

  /** Returns whether the key has a removable leading or trailing ASCII space/tab. */
  public static boolean hasEdgePadding(String key) {
    return !canonicalize(key).equals(key);
  }

  private static boolean isEdgeWhitespace(char value) {
    return value == ' ' || value == '\t';
  }
}
