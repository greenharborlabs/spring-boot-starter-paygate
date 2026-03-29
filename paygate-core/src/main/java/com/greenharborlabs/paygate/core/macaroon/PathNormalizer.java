package com.greenharborlabs.paygate.core.macaroon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Canonical path normalization for the paygate project. Combines security-defensive iterative
 * percent-decoding with RFC 3986 dot-segment resolution.
 *
 * <p>This is the single source of truth for path normalization. All modules should delegate here
 * rather than implementing their own normalization.
 *
 * <p>Zero external dependencies — JDK only.
 */
public final class PathNormalizer {

  private static final Set<String> RESERVED_ENCODED = Set.of("2F", "3F", "23", "3A");
  private static final int MAX_DECODE_ITERATIONS = 3;

  private PathNormalizer() {}

  /**
   * Normalizes a request path through a security-defensive pipeline:
   *
   * <ol>
   *   <li>Strip query string
   *   <li>Prepend {@code /} if missing
   *   <li>Iterative percent-decode with convergence check (max 4 total passes), preserving reserved
   *       delimiters {@code %2F}, {@code %3F}, {@code %23}, {@code %3A}
   *   <li>Collapse consecutive slashes
   *   <li>Resolve dot-segments per RFC 3986 Section 5.2.4
   *   <li>Strip trailing slash unless root
   * </ol>
   */
  public static String normalize(String path) {
    if (path == null || path.isEmpty()) {
      return "/";
    }

    String result = stripQueryString(path);
    result = prependSlashIfMissing(result);
    result = iterativeDecode(result);
    result = collapseSlashes(result);
    result = resolveDotSegments(result);
    result = stripTrailingSlash(result);

    return result;
  }

  /**
   * Single-pass percent-decode preserving reserved path delimiters. Reserved delimiters ({@code
   * %2F}, {@code %3F}, {@code %23}, {@code %3A}) are preserved with uppercased hex digits per RFC
   * 3986 Section 2.1.
   *
   * <p>Invalid hex digits after {@code %} and incomplete sequences pass through unchanged. Plus
   * sign is treated as literal (not space).
   */
  static String percentDecode(String input) {
    int len = input.length();
    var sb = new StringBuilder(len);
    int i = 0;

    while (i < len) {
      char c = input.charAt(i);

      if (c != '%' || i + 2 >= len) {
        sb.append(c);
        i++;
        continue;
      }

      String hex = input.substring(i + 1, i + 3);

      if (!isValidHex(hex)) {
        sb.append(c);
        i++;
        continue;
      }

      if (RESERVED_ENCODED.contains(hex.toUpperCase())) {
        sb.append('%').append(hex.toUpperCase());
      } else {
        sb.append((char) Integer.parseInt(hex, 16));
      }
      i += 3;
    }

    return sb.toString();
  }

  private static String stripQueryString(String path) {
    int idx = path.indexOf('?');
    return idx >= 0 ? path.substring(0, idx) : path;
  }

  private static String prependSlashIfMissing(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }

  /**
   * Iteratively percent-decodes until output converges or the iteration limit is reached. Maximum 4
   * total decode passes (1 initial + up to 3 iterations).
   */
  private static String iterativeDecode(String input) {
    String decoded = percentDecode(input);
    String prev = input;
    int iterations = 0;

    while (!decoded.equals(prev) && iterations < MAX_DECODE_ITERATIONS) {
      prev = decoded;
      decoded = percentDecode(decoded);
      iterations++;
    }

    // Fail-closed: if decoding did not converge within the iteration limit,
    // perform one final pass to decode any residual unreserved percent-encoded
    // characters (e.g., %2e from deeply-nested encoding attacks).
    // Reserved delimiters (%2F, %3F, %23, %3A) remain preserved.
    if (!decoded.equals(prev)) {
      decoded = percentDecode(decoded);
    }

    return decoded;
  }

  private static boolean isValidHex(String hex) {
    if (hex.length() != 2) {
      return false;
    }
    for (int i = 0; i < 2; i++) {
      char c = hex.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  private static String collapseSlashes(String path) {
    var sb = new StringBuilder(path.length());
    boolean prevSlash = false;

    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '/') {
        if (!prevSlash) {
          sb.append(c);
        }
        prevSlash = true;
      } else {
        sb.append(c);
        prevSlash = false;
      }
    }

    return sb.toString();
  }

  private static String resolveDotSegments(String path) {
    String[] parts = path.split("/", -1);
    List<String> output = new ArrayList<>();

    for (String part : parts) {
      switch (part) {
        case "." -> {
          /* skip */
        }
        case ".." -> {
          if (!output.isEmpty()) {
            output.removeLast();
          }
        }
        default -> output.add(part);
      }
    }

    String result = String.join("/", output);
    if (!result.startsWith("/")) {
      result = "/" + result;
    }
    return result;
  }

  private static String stripTrailingSlash(String path) {
    if (path.length() > 1 && path.endsWith("/")) {
      return path.substring(0, path.length() - 1);
    }
    return path;
  }
}
