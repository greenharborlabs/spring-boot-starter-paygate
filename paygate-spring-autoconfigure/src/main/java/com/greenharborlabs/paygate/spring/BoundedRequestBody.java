package com.greenharborlabs.paygate.spring;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Eagerly captures a bounded request body and replays the exact observed bytes to later consumers.
 *
 * <p>The primitive deliberately ignores client-declared lengths. It reads at most {@code maxBytes +
 * 1} directly from the request stream, rejects overflow, and exposes a fresh replay stream for
 * every read so pricing and downstream processing cannot consume one another's input.
 */
public final class BoundedRequestBody extends HttpServletRequestWrapper {

  private final byte[] bytes;

  private BoundedRequestBody(HttpServletRequest request, byte[] bytes) {
    super(request);
    this.bytes = bytes;
  }

  /**
   * Captures the body with a strict inclusive byte bound.
   *
   * @param request inbound request
   * @param maxBytes maximum accepted body size
   * @return a replayable request wrapper
   * @throws IOException if observing the original request stream fails
   * @throws RequestBodyTooLargeException if the observed body exceeds the bound
   */
  public static BoundedRequestBody capture(HttpServletRequest request, int maxBytes)
      throws IOException {
    Objects.requireNonNull(request, "request");
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    byte[] captured = request.getInputStream().readNBytes(maxBytes + 1);
    if (captured.length > maxBytes) {
      throw new RequestBodyTooLargeException("Request body exceeds " + maxBytes + " bytes");
    }
    return new BoundedRequestBody(request, captured);
  }

  /** Returns the exact number of server-observed bytes. */
  public int observedLength() {
    return bytes.length;
  }

  /** Returns whether the request has no encoding or explicitly uses the identity encoding. */
  public boolean hasIdentityContentEncoding() {
    String contentEncoding = getHeader("Content-Encoding");
    return contentEncoding == null
        || contentEncoding.isBlank()
        || "identity".equalsIgnoreCase(contentEncoding);
  }

  /** Returns a defensive copy of the observed bytes. */
  public byte[] observedBytes() {
    return bytes.clone();
  }

  /** Returns a fresh synchronous replay stream. */
  @Override
  public ServletInputStream getInputStream() {
    return new ReplayServletInputStream(bytes);
  }

  /** Returns a reader over a fresh replay stream using UTF-8. */
  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }

  private static final class ReplayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    ReplayServletInputStream(byte[] bytes) {
      this.delegate = new ByteArrayInputStream(bytes);
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
      // This wrapper is synchronous; async servlet input is not supported here.
    }
  }
}
