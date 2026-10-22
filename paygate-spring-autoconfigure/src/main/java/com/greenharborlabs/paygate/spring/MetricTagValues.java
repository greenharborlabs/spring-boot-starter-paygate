package com.greenharborlabs.paygate.spring;

import java.util.Set;

/**
 * Bounded tag values shared by paygate meters and the structured logs that mirror them.
 *
 * <p>Request-derived values must never become a meter tag directly: every distinct tag value
 * permanently registers a new meter, so a client-controlled value is an unbounded memory and
 * time-series cardinality sink.
 */
final class MetricTagValues {

  /** Overflow sentinel for values outside a fixed tag vocabulary. */
  static final String OTHER = "_other";

  /** Sentinel the {@code SecurityDecision} contract uses when no request method is known. */
  static final String UNKNOWN = "_unknown";

  /**
   * Standard HTTP methods that keep their own tag value. Matching is exact and case-sensitive
   * because HTTP method tokens are case-sensitive (RFC 9110 section 9.1); {@code "get"} is not
   * {@code GET} and is therefore reported as {@link #OTHER}. {@code CONNECT} is deliberately absent
   * because servlet applications never serve it.
   */
  private static final Set<String> STANDARD_HTTP_METHODS =
      Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

  private MetricTagValues() {}

  /**
   * Maps a request method to a value from a fixed vocabulary of at most ten entries: the eight
   * standard methods, {@link #UNKNOWN} for a missing method, and {@link #OTHER} for anything else.
   *
   * @param method request method as received, possibly {@code null}
   * @return bounded tag value safe for meters and logs
   */
  static String httpMethod(String method) {
    if (method == null || UNKNOWN.equals(method)) {
      return UNKNOWN;
    }
    return STANDARD_HTTP_METHODS.contains(method) ? method : OTHER;
  }
}
