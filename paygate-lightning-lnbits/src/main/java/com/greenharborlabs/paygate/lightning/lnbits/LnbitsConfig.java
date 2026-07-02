package com.greenharborlabs.paygate.lightning.lnbits;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for the LNbits Lightning backend.
 *
 * <p>LNbits connections require HTTPS by default. Plain HTTP is only accepted for loopback,
 * localhost, or known Docker Compose service endpoints when explicitly enabled through the
 * five-argument constructor or {@code paygate.lnbits.allow-plaintext-http}.
 */
public final class LnbitsConfig {

  private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;
  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final Set<String> LOCAL_DOCKER_DEVELOPMENT_HOSTS = Set.of("lnbits");

  private final String baseUrl;
  private final String apiKey;
  private final int requestTimeoutSeconds;
  private final int connectTimeoutSeconds;
  private final boolean allowPlaintextHttp;

  public LnbitsConfig(
      String baseUrl,
      String apiKey,
      int requestTimeoutSeconds,
      int connectTimeoutSeconds,
      boolean allowPlaintextHttp) {
    validateBaseUrl(baseUrl, allowPlaintextHttp);
    requireNonBlankApiKey(apiKey);
    requirePositive("requestTimeoutSeconds", requestTimeoutSeconds);
    requirePositive("connectTimeoutSeconds", connectTimeoutSeconds);
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.requestTimeoutSeconds = requestTimeoutSeconds;
    this.connectTimeoutSeconds = connectTimeoutSeconds;
    this.allowPlaintextHttp = allowPlaintextHttp;
  }

  public LnbitsConfig(
      String baseUrl, String apiKey, int requestTimeoutSeconds, int connectTimeoutSeconds) {
    this(baseUrl, apiKey, requestTimeoutSeconds, connectTimeoutSeconds, false);
  }

  /**
   * Convenience constructor that uses the default request timeout of 5 seconds and the default
   * connect timeout of 10 seconds.
   */
  public LnbitsConfig(String baseUrl, String apiKey) {
    this(baseUrl, apiKey, DEFAULT_REQUEST_TIMEOUT_SECONDS, DEFAULT_CONNECT_TIMEOUT_SECONDS);
  }

  /** Backwards-compatible constructor that uses the default connect timeout of 10 seconds. */
  public LnbitsConfig(String baseUrl, String apiKey, int requestTimeoutSeconds) {
    this(baseUrl, apiKey, requestTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS);
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String apiKey() {
    return apiKey;
  }

  public int requestTimeoutSeconds() {
    return requestTimeoutSeconds;
  }

  public int connectTimeoutSeconds() {
    return connectTimeoutSeconds;
  }

  public boolean allowPlaintextHttp() {
    return allowPlaintextHttp;
  }

  private static void validateBaseUrl(String baseUrl, boolean allowPlaintextHttp) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be null or blank");
    }
    URI parsed = parseBaseUrl(baseUrl);
    String scheme = parsed.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException(
          "baseUrl must have an http or https scheme, but has no scheme: " + baseUrl);
    }
    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    validateSupportedScheme(scheme, normalizedScheme);
    validatePlaintextHttp(baseUrl, parsed, normalizedScheme, allowPlaintextHttp);
  }

  private static URI parseBaseUrl(String baseUrl) {
    try {
      return new URI(baseUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("baseUrl is not a valid URI: " + baseUrl, e);
    }
  }

  private static void validateSupportedScheme(String scheme, String normalizedScheme) {
    if (isSupportedScheme(normalizedScheme)) {
      return;
    }
    throw new IllegalArgumentException(
        "LNbits baseUrl requires HTTPS; unsafe URL scheme '"
            + scheme
            + "' is not supported. Use an https:// URL, or for local/test loopback HTTP set "
            + "paygate.lnbits.allow-plaintext-http=true or pass allowPlaintextHttp=true to the "
            + "constructor.");
  }

  private static boolean isSupportedScheme(String normalizedScheme) {
    return "http".equals(normalizedScheme) || "https".equals(normalizedScheme);
  }

  private static void validatePlaintextHttp(
      String baseUrl, URI parsed, String normalizedScheme, boolean allowPlaintextHttp) {
    if (!"http".equals(normalizedScheme)) {
      return;
    }
    if (allowPlaintextHttp && isLocalHttpTarget(parsed)) {
      return;
    }
    throw new IllegalArgumentException(
        "LNbits baseUrl requires HTTPS; unsafe URL scheme 'http' for "
            + baseUrl
            + ". Plain HTTP is allowed only for local/test loopback URLs with explicit opt-in "
            + "via paygate.lnbits.allow-plaintext-http=true or constructor "
            + "allowPlaintextHttp=true.");
  }

  private static void requireNonBlankApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey must not be null or blank");
    }
  }

  private static void requirePositive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive, got: " + value);
    }
  }

  private static boolean isLocalHttpTarget(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(normalizedHost)
        || normalizedHost.endsWith(".localhost")
        || "::1".equals(normalizedHost)
        || "[::1]".equals(normalizedHost)
        || LOCAL_DOCKER_DEVELOPMENT_HOSTS.contains(normalizedHost)
        || isIpv4Loopback(normalizedHost);
  }

  private static boolean isIpv4Loopback(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4 || !"127".equals(parts[0])) {
      return false;
    }
    for (String part : parts) {
      try {
        int value = Integer.parseInt(part);
        if (value < 0 || value > 255) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LnbitsConfig that)) {
      return false;
    }
    return requestTimeoutSeconds == that.requestTimeoutSeconds
        && connectTimeoutSeconds == that.connectTimeoutSeconds
        && Objects.equals(baseUrl, that.baseUrl)
        && Objects.equals(apiKey, that.apiKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(baseUrl, apiKey, requestTimeoutSeconds, connectTimeoutSeconds);
  }

  @Override
  public String toString() {
    return "LnbitsConfig[baseUrl="
        + baseUrl
        + ", apiKey=***REDACTED***"
        + ", requestTimeoutSeconds="
        + requestTimeoutSeconds
        + ", connectTimeoutSeconds="
        + connectTimeoutSeconds
        + "]";
  }
}
