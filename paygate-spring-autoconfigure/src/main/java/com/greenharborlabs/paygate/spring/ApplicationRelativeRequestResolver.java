package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.ServletRequestPathUtils;

/** Resolves the current servlet dispatch to its normalized application-relative path. */
public final class ApplicationRelativeRequestResolver {

  private ApplicationRelativeRequestResolver() {}

  /**
   * Parses the current dispatch without caching parsed state on the request, removes deployment
   * prefixes, and normalizes the resulting application-relative path.
   *
   * @param request the current servlet request
   * @return a normalized absolute application-relative path
   * @throws IllegalArgumentException when the request path and deployment prefixes are inconsistent
   */
  public static String resolve(HttpServletRequest request) {
    var pathWithinApplication = ServletRequestPathUtils.parse(request).pathWithinApplication();
    var decodedPath = new StringBuilder(pathWithinApplication.value().length());
    for (PathContainer.Element element : pathWithinApplication.elements()) {
      if (element instanceof PathContainer.PathSegment segment) {
        String value = segment.valueToMatch();
        if (value.indexOf('/') >= 0) {
          throw new IllegalArgumentException("Encoded path separator in request path");
        }
        decodedPath.append(value);
      } else {
        decodedPath.append(element.value());
      }
    }
    return PathNormalizer.normalize(decodedPath.toString());
  }
}
