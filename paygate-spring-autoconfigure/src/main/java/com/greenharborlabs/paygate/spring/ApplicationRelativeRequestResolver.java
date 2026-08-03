package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import jakarta.servlet.http.HttpServletRequest;
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
    String path = ServletRequestPathUtils.parse(request).pathWithinApplication().value();
    return PathNormalizer.normalize(path);
  }
}
