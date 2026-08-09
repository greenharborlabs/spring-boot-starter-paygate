package com.greenharborlabs.paygate.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Creates canonical RFC 9530 content digests for request-bound payment challenges.
 *
 * <p>The canonical input preserves the exact UTF-8 method, application path, and raw query. Every
 * variable-width field is length-prefixed, and query presence is represented separately so an
 * absent query differs from an explicit empty query. Callers must provide no more than {@link
 * #MAX_BODY_BYTES} of request body data.
 */
public final class CanonicalRequestDigest {

  /** Maximum request body size accepted for challenge binding. */
  public static final int MAX_BODY_BYTES = 8 * 1024;

  private static final byte[] VERSION =
      "paygate-request-digest-v1".getBytes(StandardCharsets.US_ASCII);

  private CanonicalRequestDigest() {}

  /**
   * Creates the canonical SHA-256 content digest.
   *
   * @param method HTTP method
   * @param applicationPath normalized application path
   * @param queryPresent whether the original request included a query component
   * @param rawQuery exact raw query, required only when {@code queryPresent} is true
   * @param body bounded request body bytes
   * @return an RFC 9530 SHA-256 content digest
   */
  public static String create(
      String method, String applicationPath, boolean queryPresent, String rawQuery, byte[] body) {
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(applicationPath, "applicationPath must not be null");
    Objects.requireNonNull(body, "body must not be null");
    if (queryPresent != (rawQuery != null)) {
      throw new IllegalArgumentException("query presence must match rawQuery nullability");
    }
    if (body.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("request body exceeds " + MAX_BODY_BYTES + " bytes");
    }

    try {
      var digest = MessageDigest.getInstance("SHA-256");
      updateLengthPrefixed(digest, VERSION);
      updateLengthPrefixed(digest, method.getBytes(StandardCharsets.UTF_8));
      updateLengthPrefixed(digest, applicationPath.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) (queryPresent ? 1 : 0));
      if (queryPresent) {
        updateLengthPrefixed(digest, rawQuery.getBytes(StandardCharsets.UTF_8));
      }
      updateLengthPrefixed(digest, body);
      return "sha-256=:" + Base64.getEncoder().encodeToString(digest.digest()) + ":";
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
    int length = value.length;
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
    digest.update(value);
  }
}
