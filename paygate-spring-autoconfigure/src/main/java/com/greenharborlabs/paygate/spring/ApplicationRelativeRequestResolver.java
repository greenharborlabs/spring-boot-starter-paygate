package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Resolves the current servlet dispatch to its canonical application-relative path.
 *
 * <p>The canonical path is the path Spring MVC dispatches on: each segment is percent-decoded
 * exactly once and no dot-segment resolution or slash collapsing is applied. Request paths whose
 * once-decoded form is ambiguous are rejected, so the path Paygate authorizes cannot differ from
 * the path a handler receives.
 */
public final class ApplicationRelativeRequestResolver {

  private ApplicationRelativeRequestResolver() {}

  /**
   * Parses the current dispatch without caching parsed state on the request, removes deployment
   * prefixes, validates every segment, and joins the once-decoded segments.
   *
   * <p>The request is rejected when any once-decoded segment:
   *
   * <ul>
   *   <li>is {@code .} or {@code ..};
   *   <li>is empty ({@code //}); a single trailing slash is allowed and removed;
   *   <li>still contains {@code %} (nested percent-encoding);
   *   <li>contains {@code /} (encoded path separator) or {@code \};
   *   <li>carries {@code ;} path parameters, encoded or not.
   * </ul>
   *
   * @param request the current servlet request
   * @return a canonical absolute application-relative path
   * @throws IllegalArgumentException when the request path is ambiguous or its deployment prefixes
   *     are inconsistent
   */
  public static String resolve(HttpServletRequest request) {
    var pathWithinApplication = ServletRequestPathUtils.parse(request).pathWithinApplication();
    var elements = pathWithinApplication.elements();
    var canonicalPath = new StringBuilder(pathWithinApplication.value().length());
    boolean previousWasSeparator = false;
    for (int i = 0; i < elements.size(); i++) {
      PathContainer.Element element = elements.get(i);
      if (element instanceof PathContainer.PathSegment segment) {
        canonicalPath.append(validatedSegment(segment));
        previousWasSeparator = false;
      } else {
        if (previousWasSeparator) {
          throw new IllegalArgumentException("Empty path segment in request path");
        }
        previousWasSeparator = true;
        boolean trailing = i == elements.size() - 1;
        if (!trailing || i == 0) {
          canonicalPath.append(element.value());
        }
      }
    }
    return canonicalPath.isEmpty() ? "/" : canonicalPath.toString();
  }

  private static String validatedSegment(PathContainer.PathSegment segment) {
    String value = segment.valueToMatch();
    if (value.indexOf('/') >= 0) {
      throw new IllegalArgumentException("Encoded path separator in request path");
    }
    if (".".equals(value) || "..".equals(value)) {
      throw new IllegalArgumentException("Dot segment in request path");
    }
    if (value.indexOf('%') >= 0) {
      throw new IllegalArgumentException("Nested percent-encoding in request path");
    }
    if (value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Backslash in request path");
    }
    if (value.indexOf(';') >= 0
        || segment.value().indexOf(';') >= 0
        || !segment.parameters().isEmpty()) {
      throw new IllegalArgumentException("Path parameters in request path");
    }
    return value;
  }
}
