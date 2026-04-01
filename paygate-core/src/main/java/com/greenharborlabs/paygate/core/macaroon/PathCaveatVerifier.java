package com.greenharborlabs.paygate.core.macaroon;

/**
 * Verifies that the request path matches at least one glob pattern specified in the {@code path}
 * caveat value (comma-separated).
 *
 * <p>Stateless and thread-safe.
 */
public class PathCaveatVerifier implements CaveatVerifier {

  private final int maxValuesPerCaveat;

  public PathCaveatVerifier(int maxValuesPerCaveat) {
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  @Override
  public String getKey() {
    return "path";
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    // 1. Extract request path — fail-closed if absent
    String requestPath = context.getRequestMetadata().get(VerificationContextKeys.REQUEST_PATH);
    if (requestPath == null) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Request path missing from verification context");
    }

    // 2. Split, bounds-check, and trim caveat value
    String[] patterns = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat, "path");

    // 3. Validate each pattern and pre-normalize
    String[] normalizedPatterns = new String[patterns.length];
    for (int i = 0; i < patterns.length; i++) {
      String trimmed = patterns[i];
      try {
        PathGlobMatcher.validatePattern(trimmed);
      } catch (IllegalArgumentException e) {
        throw new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_NOT_MET, "Invalid path pattern: " + e.getMessage());
      }
      normalizedPatterns[i] = PathGlobMatcher.normalizePath(trimmed);
    }

    // 5a. Reject dot-dot path segments — prevents traversal bypass when
    //     server normalization differs from our own.
    if (containsDotDotSegment(requestPath)) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Request path contains dot-dot traversal segment");
    }

    // 6. Reject encoded path separators, encoded dot-dot traversal, and double-encoding —
    //    prevents traversal attacks via %2F, %5C, %2E%2E, and %25 sequences.
    if (containsEncodedPathTraversalOrDoubleEncoding(requestPath)) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Request path contains encoded traversal marker or double-encoding");
    }

    // 7. Normalize request path once
    String normalizedPath = PathGlobMatcher.normalizePath(requestPath);

    // 8. Match against each pre-normalized pattern — return on first match
    for (String normalizedPattern : normalizedPatterns) {
      if (PathGlobMatcher.matchNormalized(normalizedPattern, normalizedPath)) {
        return;
      }
    }

    // 9. No pattern matched — reject
    throw new MacaroonVerificationException(
        VerificationFailureReason.CAVEAT_NOT_MET,
        "Request path does not match any allowed path pattern");
  }

  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    // Reject oversized caveats before expensive subset-containment check
    if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
        || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
      return false;
    }

    String[] previousNormalized = trimAndNormalize(previous.value().split(",", -1));
    String[] currentNormalized = trimAndNormalize(current.value().split(",", -1));

    for (String cp : currentNormalized) {
      boolean contained = false;
      for (String pp : previousNormalized) {
        if (PathGlobMatcher.isContainedInNormalized(pp, cp)) {
          contained = true;
          break;
        }
      }
      if (!contained) {
        return false;
      }
    }
    return true;
  }

  private static String[] trimAndNormalize(String[] raw) {
    String[] result = new String[raw.length];
    for (int i = 0; i < raw.length; i++) {
      result[i] = PathGlobMatcher.normalizePath(raw[i].trim());
    }
    return result;
  }

  /**
   * Returns true if the path contains a percent-encoded path separator (%2F/%2f for forward slash,
   * %5C/%5c for backslash), encoded dot-dot traversal marker (%2E%2E in any case), or a
   * double-encoding indicator (%25). Case-insensitive on hex digits.
   */
  private static boolean containsEncodedPathTraversalOrDoubleEncoding(String path) {
    int len = path.length();
    for (int i = 0; i <= len - 3; i++) {
      if (path.charAt(i) == '%') {
        char h1 = path.charAt(i + 1);
        char h2 = path.charAt(i + 2);
        // %2F / %2f — encoded forward slash
        if (h1 == '2' && (h2 == 'F' || h2 == 'f')) {
          return true;
        }
        // %5C / %5c — encoded backslash
        if (h1 == '5' && (h2 == 'C' || h2 == 'c')) {
          return true;
        }
        // %25 — double-encoding indicator (percent-encoded percent sign)
        if (h1 == '2' && h2 == '5') {
          return true;
        }

        // %2E%2E / %2e%2e / mixed-case — encoded dot-dot traversal marker
        if (h1 == '2' && (h2 == 'E' || h2 == 'e') && i <= len - 6 && path.charAt(i + 3) == '%') {
          char h3 = path.charAt(i + 4);
          char h4 = path.charAt(i + 5);
          if (h3 == '2' && (h4 == 'E' || h4 == 'e')) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true if the path contains a {@code ..} path segment, indicating directory traversal.
   * Checks for {@code /..} at any position and {@code ../} at the start of the path.
   */
  private static boolean containsDotDotSegment(String path) {
    // Check for bare ".." (no slashes) — after normalization this resolves to root
    if ("..".equals(path)) {
      return true;
    }
    // Check for "../" at the very start (relative path traversal)
    if (path.startsWith("../")) {
      return true;
    }
    // Check for "/.." anywhere in the path — covers /.. at end and /../ in middle
    return path.contains("/..");
  }
}
