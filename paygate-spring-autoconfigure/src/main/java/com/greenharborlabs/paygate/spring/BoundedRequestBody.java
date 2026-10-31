package com.greenharborlabs.paygate.spring;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Eagerly captures a bounded request body and replays the exact observed bytes to later consumers.
 *
 * <p>The primitive reads at most {@code maxBytes + 1} directly from the request stream, rejects
 * overflow, and exposes a fresh replay stream for every read so pricing and downstream processing
 * cannot consume one another's input. When the request declares a positive {@code Content-Length}
 * and fewer bytes are observed, capture fails with {@link RequestBodyIncompleteException}: the body
 * was truncated or consumed by another component, so it cannot be priced or bound safely.
 *
 * <p>The observed bytes are recorded on the request, so a later capture of the same request (for
 * example by the Spring Security authentication filter after an earlier Paygate capture filter, or
 * on a later dispatch) reuses them instead of reading an already drained stream.
 *
 * <p>Because capture drains the container's input stream, the container can no longer parse a
 * {@code POST application/x-www-form-urlencoded} body into request parameters. This wrapper
 * therefore exposes those parameters itself, after any query-string parameters, so filters such as
 * {@code HiddenHttpMethodFilter} or {@code CsrfFilter} and the protected handler still see them.
 * Other methods are left to the container and to Spring's {@code FormContentFilter}, which reads
 * the replayed stream.
 */
public final class BoundedRequestBody extends HttpServletRequestWrapper {

  private static final String CAPTURED_BODY_ATTRIBUTE =
      BoundedRequestBody.class.getName() + ".CAPTURED_BODY";
  private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
  private static final byte AMPERSAND = '&';
  private static final byte EQUALS = '=';
  private static final byte PLUS = '+';
  private static final byte PERCENT = '%';

  private final byte[] bytes;
  private final boolean exposesFormParameters;
  private volatile Map<String, String[]> parameters;

  private BoundedRequestBody(
      HttpServletRequest request, byte[] bytes, boolean exposesFormParameters) {
    super(request);
    this.bytes = bytes;
    this.exposesFormParameters = exposesFormParameters;
  }

  /**
   * Captures the body with a strict inclusive byte bound, or reuses the bytes already captured for
   * this request.
   *
   * @param request inbound request
   * @param maxBytes maximum accepted body size
   * @return a replayable request wrapper
   * @throws IOException if observing the original request stream fails
   * @throws RequestBodyTooLargeException if the observed body exceeds the bound
   * @throws RequestBodyIncompleteException if fewer bytes than the declared positive {@code
   *     Content-Length} were observed
   */
  public static BoundedRequestBody capture(HttpServletRequest request, int maxBytes)
      throws IOException {
    Objects.requireNonNull(request, "request");
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (request instanceof BoundedRequestBody existing) {
      requireWithinBound(existing.bytes, maxBytes);
      return existing;
    }
    if (request.getAttribute(CAPTURED_BODY_ATTRIBUTE) instanceof CapturedBody previous) {
      requireWithinBound(previous.bytes(), maxBytes);
      return new BoundedRequestBody(
          request, previous.bytes(), !containsBoundedRequestBody(request));
    }

    byte[] captured = request.getInputStream().readNBytes(maxBytes + 1);
    requireWithinBound(captured, maxBytes);
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > 0 && captured.length < declaredLength) {
      throw new RequestBodyIncompleteException();
    }
    request.setAttribute(CAPTURED_BODY_ATTRIBUTE, new CapturedBody(captured));
    return new BoundedRequestBody(request, captured, true);
  }

  private static void requireWithinBound(byte[] captured, int maxBytes) {
    if (captured.length > maxBytes) {
      throw new RequestBodyTooLargeException("Request body exceeds " + maxBytes + " bytes");
    }
  }

  private static boolean containsBoundedRequestBody(ServletRequest request) {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper wrapper) {
      if (wrapper instanceof BoundedRequestBody) {
        return true;
      }
      current = wrapper.getRequest();
    }
    return false;
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

  @Override
  public String getParameter(String name) {
    String[] values = getParameterMap().get(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    if (!isFormPost()) {
      return super.getParameterMap();
    }
    Map<String, String[]> resolved = parameters;
    if (resolved == null) {
      resolved = mergeFormParameters(super.getParameterMap());
      parameters = resolved;
    }
    return resolved;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.enumeration(getParameterMap().keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    String[] values = getParameterMap().get(name);
    return values == null ? null : values.clone();
  }

  private boolean isFormPost() {
    if (!exposesFormParameters || bytes.length == 0 || !"POST".equals(getMethod())) {
      return false;
    }
    String contentType = getContentType();
    if (contentType == null) {
      return false;
    }
    int separator = contentType.indexOf(';');
    String mediaType = separator >= 0 ? contentType.substring(0, separator) : contentType;
    return FORM_CONTENT_TYPE.equals(mediaType.strip().toLowerCase(Locale.ROOT));
  }

  private Map<String, String[]> mergeFormParameters(Map<String, String[]> queryParameters) {
    Map<String, List<String>> merged = new LinkedHashMap<>();
    queryParameters.forEach((name, values) -> merged.put(name, new ArrayList<>(List.of(values))));
    Charset charset = formCharset();
    int start = 0;
    while (start <= bytes.length) {
      int end = indexOf(AMPERSAND, start, bytes.length);
      if (end > start) {
        int equals = indexOf(EQUALS, start, end);
        String name = decode(start, equals, charset);
        String value = equals < end ? decode(equals + 1, end, charset) : "";
        // Like servlet containers, skip a nameless parameter or one with an invalid escape.
        if (name != null && !name.isEmpty() && value != null) {
          merged.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
      }
      start = end + 1;
    }
    Map<String, String[]> result = new LinkedHashMap<>(merged.size());
    merged.forEach((name, values) -> result.put(name, values.toArray(String[]::new)));
    return Collections.unmodifiableMap(result);
  }

  private int indexOf(byte target, int from, int to) {
    for (int i = from; i < to; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return to;
  }

  /** Percent-decodes {@code bytes[from, to)}; returns {@code null} for an invalid escape. */
  private String decode(int from, int to, Charset charset) {
    var decoded = new ByteArrayOutputStream(to - from);
    for (int i = from; i < to; i++) {
      byte current = bytes[i];
      if (current == PLUS) {
        decoded.write(' ');
      } else if (current == PERCENT) {
        int high = i + 2 < to ? Character.digit(bytes[i + 1], 16) : -1;
        int low = i + 2 < to ? Character.digit(bytes[i + 2], 16) : -1;
        if (high < 0 || low < 0) {
          return null;
        }
        decoded.write((high << 4) + low);
        i += 2;
      } else {
        decoded.write(current);
      }
    }
    return decoded.toString(charset);
  }

  /**
   * Returns the request character encoding, defaulting to ISO-8859-1 as the servlet specification
   * does for form parameters.
   */
  private Charset formCharset() {
    String encoding = getCharacterEncoding();
    if (encoding == null || encoding.isBlank()) {
      return StandardCharsets.ISO_8859_1;
    }
    try {
      return Charset.forName(encoding);
    } catch (IllegalArgumentException unsupported) {
      return StandardCharsets.ISO_8859_1;
    }
  }

  /** Request-scoped holder for the captured bytes; not constructible by application code. */
  private record CapturedBody(byte[] bytes) {}

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
    public int read(byte[] buffer, int offset, int length) {
      return delegate.read(buffer, offset, length);
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
