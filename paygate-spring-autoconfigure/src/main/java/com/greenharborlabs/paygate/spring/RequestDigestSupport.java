package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.CanonicalRequestDigest;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.SecurityBounds;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    if (request instanceof CachedBodyRequestWrapper wrapped) {
      wrapped.ensureWithinBound(maxBytes);
      return request;
    }
    return new CachedBodyRequestWrapper(request, maxBytes);
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
    if (request instanceof CachedBodyRequestWrapper wrapped) {
      wrapped.ensureWithinBound(maxBytes);
      return wrapped.cachedBodyBytes();
    }
    return readBounded(request.getInputStream(), maxBytes);
  }

  private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
    byte[] buffer = in.readNBytes(maxBytes + 1);
    if (buffer.length > maxBytes) {
      throw new RequestBodyTooLargeException(
          "Request body exceeds " + maxBytes + " bytes for digest binding");
    }
    return buffer;
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

  private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] cachedBody;

    CachedBodyRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
      super(request);
      this.cachedBody = readBounded(request.getInputStream(), maxBytes);
    }

    byte[] cachedBodyBytes() {
      return cachedBody.clone();
    }

    void ensureWithinBound(int maxBytes) {
      if (cachedBody.length > maxBytes) {
        throw new RequestBodyTooLargeException(
            "Request body exceeds " + maxBytes + " bytes for digest binding");
      }
    }

    @Override
    public ServletInputStream getInputStream() {
      return new CachedBodyServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }

  private static final class CachedBodyServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    CachedBodyServletInputStream(byte[] body) {
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      // Synchronous request wrappers in tests/runtime do not use async I/O.
    }
  }
}
