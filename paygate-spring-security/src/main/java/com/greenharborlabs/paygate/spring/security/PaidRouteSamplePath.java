package com.greenharborlabs.paygate.spring.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.web.util.UriUtils;

/**
 * Derives a concrete, application-relative request path from a Spring path pattern so that the
 * startup guard can ask Spring Security which filter chain would serve a request to that route.
 *
 * <p>Variables ({@code {id}}) and wildcards ({@code *}, {@code ?}) become a literal value; a
 * regex-constrained variable uses the first candidate the regex accepts. Multi-segment wildcards
 * ({@code **}, {@code {*rest}}) become one segment. Literal segments are percent-encoded so that
 * the result is a valid request URI.
 */
final class PaidRouteSamplePath {

  static final String SAMPLE_SEGMENT = "x";
  private static final List<String> VARIABLE_CANDIDATES = List.of("x", "0", "a", "X", "x0", "-");

  private PaidRouteSamplePath() {}

  /**
   * Returns a sample request path that the given route pattern matches.
   *
   * @param pattern a Spring {@code PathPattern} string such as {@code /api/items/{id}}
   * @return an application-relative request path starting with {@code /}
   */
  static String of(String pattern) {
    if (pattern == null || pattern.isEmpty() || "/".equals(pattern)) {
      return "/";
    }
    var segments = (pattern.startsWith("/") ? pattern.substring(1) : pattern).split("/", -1);
    var path = new StringBuilder(pattern.length() + 8);
    for (String segment : segments) {
      path.append('/');
      if (!segment.isEmpty()) {
        path.append(UriUtils.encodePathSegment(sampleSegment(segment), StandardCharsets.UTF_8));
      }
    }
    return path.toString();
  }

  private static String sampleSegment(String segment) {
    if ("**".equals(segment) || (segment.startsWith("{*") && segment.endsWith("}"))) {
      return SAMPLE_SEGMENT;
    }
    var sample = new StringBuilder(segment.length());
    int index = 0;
    while (index < segment.length()) {
      char current = segment.charAt(index);
      if (current == '{') {
        int end = closingBrace(segment, index);
        sample.append(variableSample(segment.substring(index + 1, end)));
        index = end + 1;
      } else if (current == '*' || current == '?') {
        sample.append(SAMPLE_SEGMENT);
        index++;
      } else {
        sample.append(current);
        index++;
      }
    }
    return sample.isEmpty() ? SAMPLE_SEGMENT : sample.toString();
  }

  /** Finds the brace that closes a variable, allowing nested braces in its regex. */
  private static int closingBrace(String segment, int open) {
    int depth = 0;
    for (int index = open; index < segment.length(); index++) {
      char current = segment.charAt(index);
      if (current == '\\') {
        index++;
      } else if (current == '{') {
        depth++;
      } else if (current == '}' && --depth == 0) {
        return index;
      }
    }
    throw new IllegalArgumentException("Unclosed variable in path pattern segment: " + segment);
  }

  private static String variableSample(String variable) {
    int colon = variable.indexOf(':');
    if (colon < 0) {
      return SAMPLE_SEGMENT;
    }
    Pattern constraint;
    try {
      constraint = Pattern.compile(variable.substring(colon + 1));
    } catch (PatternSyntaxException _) {
      return SAMPLE_SEGMENT;
    }
    for (String candidate : VARIABLE_CANDIDATES) {
      if (constraint.matcher(candidate).matches()) {
        return candidate;
      }
    }
    return SAMPLE_SEGMENT;
  }
}
