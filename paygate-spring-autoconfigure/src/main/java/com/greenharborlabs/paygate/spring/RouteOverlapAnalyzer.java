package com.greenharborlabs.paygate.spring;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Conservative bounded classifier for intersections between Spring-style route patterns. */
public final class RouteOverlapAnalyzer {

  /** Classification of a method and route-language intersection. */
  public enum Classification {
    EXACT,
    PROVEN_OVERLAP,
    POSSIBLE_OVERLAP
  }

  /** A sanitized overlap finding containing only registered route metadata. */
  public record Finding(
      String method,
      String paidPattern,
      String unprotectedPattern,
      Classification classification) {}

  private RouteOverlapAnalyzer() {}

  /** Returns a conservative finding when the two Spring mappings can address a common request. */
  public static @Nullable Finding analyze(
      String paidMethod, String paidPattern, String unprotectedMethod, String unprotectedPattern) {
    String method = intersectMethod(paidMethod, unprotectedMethod);
    if (method == null) return null;
    String paid = normalizePattern(paidPattern);
    String unprotected = normalizePattern(unprotectedPattern);
    if (paid.equals(unprotected)) {
      return new Finding(method, paid, unprotected, Classification.EXACT);
    }
    var paidSegments = segments(paid);
    var freeSegments = segments(unprotected);
    boolean uncertain = false;
    int paidIndex = 0;
    int freeIndex = 0;
    while (paidIndex < paidSegments.size() && freeIndex < freeSegments.size()) {
      String left = paidSegments.get(paidIndex);
      String right = freeSegments.get(freeIndex);
      if (catchAll(left) || catchAll(right)) {
        return new Finding(method, paid, unprotected, Classification.POSSIBLE_OVERLAP);
      }
      if (literal(left) && literal(right) && !left.equals(right)) return null;
      uncertain |= !literal(left) || !literal(right);
      paidIndex++;
      freeIndex++;
    }
    if (paidIndex != paidSegments.size() || freeIndex != freeSegments.size()) return null;
    return new Finding(
        method,
        paid,
        unprotected,
        uncertain ? Classification.POSSIBLE_OVERLAP : Classification.PROVEN_OVERLAP);
  }

  private static @Nullable String intersectMethod(String first, String second) {
    String left = first.toUpperCase(Locale.ROOT);
    String right = second.toUpperCase(Locale.ROOT);
    if (left.equals("*") || left.equals(right)) return right;
    if (right.equals("*")) return left;
    if ((left.equals("HEAD") && right.equals("GET"))
        || (left.equals("GET") && right.equals("HEAD"))) return "HEAD";
    return null;
  }

  private static String normalizePattern(String pattern) {
    String normalized = pattern == null || pattern.isBlank() ? "/" : pattern.trim();
    while (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.length() > 256 ? normalized.substring(0, 256) : normalized;
  }

  private static List<String> segments(String pattern) {
    return List.of(pattern.substring(1).split("/", -1));
  }

  private static boolean literal(String segment) {
    return !segment.contains("{")
        && !segment.contains("}")
        && !segment.contains("*")
        && !segment.contains("?");
  }

  private static boolean catchAll(String segment) {
    return segment.equals("**") || segment.startsWith("{*");
  }
}
