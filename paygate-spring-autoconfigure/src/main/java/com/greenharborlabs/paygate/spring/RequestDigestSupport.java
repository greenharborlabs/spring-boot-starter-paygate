package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.PaymentProtocol;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/** Utilities for bounded request-body capture and canonical request digest generation. */
public final class RequestDigestSupport {

  public static final int MAX_CACHED_BODY_BYTES = 8 * 1024;
  public static final String REQUEST_DIGEST_ATTRIBUTE =
      RequestDigestSupport.class.getName() + ".REQUEST_DIGEST";
  private static final String MPP_SCHEME = "Payment";

  private RequestDigestSupport() {}

  public static boolean isMppProtocol(PaymentProtocol protocol) {
    return protocol != null && MPP_SCHEME.equals(protocol.scheme());
  }

  public static HttpServletRequest wrapForDigest(HttpServletRequest request) throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    if (request instanceof CachedBodyRequestWrapper) {
      return request;
    }
    return new CachedBodyRequestWrapper(request, MAX_CACHED_BODY_BYTES);
  }

  public static String computeDigest(HttpServletRequest request, String normalizedPath)
      throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(normalizedPath, "normalizedPath must not be null");

    byte[] bodyBytes = extractBodyBytes(request, MAX_CACHED_BODY_BYTES);
    byte[] methodBytes = request.getMethod().getBytes(StandardCharsets.UTF_8);
    byte[] pathBytes = normalizedPath.getBytes(StandardCharsets.UTF_8);

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(methodBytes);
      digest.update((byte) 0);
      digest.update(pathBytes);
      digest.update((byte) 0);
      digest.update(bodyBytes);
      String b64 = Base64.getEncoder().encodeToString(digest.digest());
      return "sha-256=:" + b64 + ":";
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  public static void ensureDigestAttribute(HttpServletRequest request, String normalizedPath)
      throws IOException {
    if (request.getAttribute(REQUEST_DIGEST_ATTRIBUTE) != null) {
      return;
    }
    request.setAttribute(REQUEST_DIGEST_ATTRIBUTE, computeDigest(request, normalizedPath));
  }

  public static String digestAttribute(HttpServletRequest request) {
    Object value = request.getAttribute(REQUEST_DIGEST_ATTRIBUTE);
    return value instanceof String s ? s : null;
  }

  private static byte[] extractBodyBytes(HttpServletRequest request, int maxBytes)
      throws IOException {
    if (request instanceof CachedBodyRequestWrapper wrapped) {
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

  private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] cachedBody;

    CachedBodyRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
      super(request);
      this.cachedBody = readBounded(request.getInputStream(), maxBytes);
    }

    byte[] cachedBodyBytes() {
      return cachedBody.clone();
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
