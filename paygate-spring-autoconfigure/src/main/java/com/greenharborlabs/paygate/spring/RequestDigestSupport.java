package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.CanonicalRequestDigest;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.SecurityBounds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;

/** Utilities for bounded request-body capture and canonical request digest generation. */
public final class RequestDigestSupport {

  public static final int MAX_CACHED_BODY_BYTES = CanonicalRequestDigest.MAX_BODY_BYTES;
  public static final String REQUEST_DIGEST_ATTRIBUTE =
      RequestDigestSupport.class.getName() + ".REQUEST_DIGEST";
  private static final String MPP_SCHEME = "Payment";

  private RequestDigestSupport() {}

  public static boolean isMppProtocol(PaymentProtocol protocol) {
    return protocol != null && MPP_SCHEME.equals(protocol.scheme());
  }

  public static HttpServletRequest wrapForDigest(HttpServletRequest request) throws IOException {
    return wrapForDigest(request, MAX_CACHED_BODY_BYTES);
  }

  /**
   * Captures the request body up to {@code maxBytes} so it remains available to downstream handlers
   * after digest calculation.
   */
  public static HttpServletRequest wrapForDigest(HttpServletRequest request, int maxBytes)
      throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    requireValidMaxBytes(maxBytes);
    return BoundedRequestBody.capture(request, maxBytes);
  }

  public static String computeDigest(HttpServletRequest request, String normalizedPath)
      throws IOException {
    return computeDigest(request, normalizedPath, MAX_CACHED_BODY_BYTES);
  }

  /** Creates the canonical request digest using the configured protected-body bound. */
  public static String computeDigest(
      HttpServletRequest request, String normalizedPath, int maxBytes) throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(normalizedPath, "normalizedPath must not be null");
    requireValidMaxBytes(maxBytes);

    return CanonicalRequestDigest.create(
        request.getMethod(),
        normalizedPath,
        request.getQueryString() != null,
        request.getQueryString(),
        extractBodyBytes(request, maxBytes));
  }

  public static void ensureDigestAttribute(HttpServletRequest request, String normalizedPath)
      throws IOException {
    ensureDigestAttribute(request, normalizedPath, MAX_CACHED_BODY_BYTES);
  }

  /** Stores the configured-bound canonical request digest unless one is already present. */
  public static void ensureDigestAttribute(
      HttpServletRequest request, String normalizedPath, int maxBytes) throws IOException {
    if (request.getAttribute(REQUEST_DIGEST_ATTRIBUTE) != null) {
      return;
    }
    request.setAttribute(
        REQUEST_DIGEST_ATTRIBUTE, computeDigest(request, normalizedPath, maxBytes));
  }

  public static String digestAttribute(HttpServletRequest request) {
    Object value = request.getAttribute(REQUEST_DIGEST_ATTRIBUTE);
    return value instanceof String s ? s : null;
  }

  private static byte[] extractBodyBytes(HttpServletRequest request, int maxBytes)
      throws IOException {
    return BoundedRequestBody.capture(request, maxBytes).observedBytes();
  }

  private static void requireValidMaxBytes(int maxBytes) {
    if (!SecurityBounds.isValidRequestBodySize(maxBytes)) {
      throw new IllegalArgumentException(
          "request body maximum must be between "
              + SecurityBounds.MIN_REQUEST_BODY_SIZE_BYTES
              + " and "
              + SecurityBounds.MAX_REQUEST_BODY_SIZE_BYTES
              + ", got: "
              + maxBytes);
    }
  }
}
