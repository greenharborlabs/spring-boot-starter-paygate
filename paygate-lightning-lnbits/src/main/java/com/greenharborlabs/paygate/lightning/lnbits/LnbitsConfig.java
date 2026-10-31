package com.greenharborlabs.paygate.lightning.lnbits;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for the LNbits Lightning backend.
 *
 * <p>LNbits connections require HTTPS by default. Plain HTTP is only accepted for loopback,
 * localhost, or known Docker Compose service endpoints when explicitly enabled through the
 * five-argument constructor or {@code paygate.lnbits.allow-plaintext-http}. A {@code *.localhost}
 * name is accepted only when it resolves and every resolved address is loopback.
 *
 * <p>The API key takes no part in {@link #equals(Object)} or {@link #hashCode()}, and {@link
 * #toString()} and validation messages redact any userinfo carried in the base URL.
 */
public final class LnbitsConfig {

  private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;
  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final Set<String> LOCAL_DOCKER_DEVELOPMENT_HOSTS = Set.of("lnbits");
  private static final String LOCALHOST_SUFFIX = ".localhost";
  private static final HostResolver SYSTEM_RESOLVER = InetAddress::getAllByName;

  /** Resolution seam for {@code *.localhost} plaintext targets. */
  @FunctionalInterface
  interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

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
    this(
        baseUrl,
        apiKey,
        requestTimeoutSeconds,
        connectTimeoutSeconds,
        allowPlaintextHttp,
        SYSTEM_RESOLVER);
  }

  LnbitsConfig(
      String baseUrl,
      String apiKey,
      int requestTimeoutSeconds,
      int connectTimeoutSeconds,
      boolean allowPlaintextHttp,
      HostResolver resolver) {
    Objects.requireNonNull(resolver, "resolver");
    validateBaseUrl(baseUrl, allowPlaintextHttp, resolver);
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

  private static void validateBaseUrl(
      String baseUrl, boolean allowPlaintextHttp, HostResolver resolver) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be null or blank");
    }
    URI parsed = parseBaseUrl(baseUrl);
    String scheme = parsed.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException(
          "baseUrl must have an http or https scheme, but has no scheme: "
              + LnbitsUrlRedaction.redactUserInfo(baseUrl));
    }
    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    validateSupportedScheme(scheme, normalizedScheme);
    validatePlaintextHttp(baseUrl, parsed, normalizedScheme, allowPlaintextHttp, resolver);
  }

  private static URI parseBaseUrl(String baseUrl) {
    try {
      return new URI(baseUrl);
    } catch (URISyntaxException e) {
      // The cause is dropped on purpose: its message echoes the raw input, including userinfo.
      throw new IllegalArgumentException(
          "baseUrl is not a valid URI: "
              + LnbitsUrlRedaction.redactUserInfo(baseUrl)
              + " ("
              + e.getReason()
              + " at index "
              + e.getIndex()
              + ")");
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
      String baseUrl,
      URI parsed,
      String normalizedScheme,
      boolean allowPlaintextHttp,
      HostResolver resolver) {
    if (!"http".equals(normalizedScheme)) {
      return;
    }
    if (allowPlaintextHttp && isLocalHttpTarget(parsed, resolver)) {
      return;
    }
    throw new IllegalArgumentException(
        "LNbits baseUrl requires HTTPS; unsafe URL scheme 'http' for "
            + LnbitsUrlRedaction.redactUserInfo(baseUrl)
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

  private static boolean isLocalHttpTarget(URI uri, HostResolver resolver) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    if ("localhost".equals(normalizedHost)
        || LOCAL_DOCKER_DEVELOPMENT_HOSTS.contains(normalizedHost)
        || isCanonicalIpv4Loopback(normalizedHost)
        || isCanonicalIpv6Loopback(normalizedHost)) {
      return true;
    }
    return normalizedHost.length() > LOCALHOST_SUFFIX.length()
        && normalizedHost.endsWith(LOCALHOST_SUFFIX)
        && resolvesOnlyToLoopback(normalizedHost, resolver);
  }

  /**
   * Accepts a {@code *.localhost} name only when it resolves and every resolved address is
   * loopback. Resolution failure, an empty answer, or any non-loopback address rejects the name.
   */
  private static boolean resolvesOnlyToLoopback(String host, HostResolver resolver) {
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException | SecurityException e) {
      return false;
    }
    if (addresses == null || addresses.length == 0) {
      return false;
    }
    for (InetAddress address : addresses) {
      if (address == null || !address.isLoopbackAddress()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isCanonicalIpv4Loopback(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    int[] octets = new int[4];
    for (int index = 0; index < parts.length; index++) {
      String part = parts[index];
      if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
        return false;
      }
      int value = 0;
      for (int characterIndex = 0; characterIndex < part.length(); characterIndex++) {
        char character = part.charAt(characterIndex);
        if (character < '0' || character > '9') {
          return false;
        }
        value = value * 10 + (character - '0');
      }
      if (value > 255) {
        return false;
      }
      octets[index] = value;
    }
    return octets[0] == 127;
  }

  private static boolean isCanonicalIpv6Loopback(String host) {
    String address = stripIpv6Brackets(host);
    if (!"::1".equals(address)) {
      return false;
    }
    byte[] addressBytes = parseIpv6Address(address);
    if (addressBytes == null) {
      return false;
    }
    for (int index = 0; index < addressBytes.length - 1; index++) {
      if (addressBytes[index] != 0) {
        return false;
      }
    }
    return addressBytes[addressBytes.length - 1] == 1;
  }

  private static String stripIpv6Brackets(String host) {
    if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }

  private static byte[] parseIpv6Address(String address) {
    int compressionIndex = address.indexOf("::");
    if (compressionIndex != address.lastIndexOf("::")) {
      return null;
    }
    String[] leftGroups = splitIpv6Groups(address.substring(0, Math.max(compressionIndex, 0)));
    String[] rightGroups =
        compressionIndex < 0
            ? new String[0]
            : splitIpv6Groups(address.substring(compressionIndex + 2));
    if (compressionIndex < 0
        ? leftGroups.length != 8
        : leftGroups.length + rightGroups.length >= 8) {
      return null;
    }

    byte[] bytes = new byte[16];
    int byteIndex = 0;
    for (String group : leftGroups) {
      if (!writeIpv6Group(group, bytes, byteIndex)) {
        return null;
      }
      byteIndex += 2;
    }
    byteIndex = 16 - rightGroups.length * 2;
    for (String group : rightGroups) {
      if (!writeIpv6Group(group, bytes, byteIndex)) {
        return null;
      }
      byteIndex += 2;
    }
    return bytes;
  }

  private static String[] splitIpv6Groups(String groups) {
    return groups.isEmpty() ? new String[0] : groups.split(":", -1);
  }

  private static boolean writeIpv6Group(String group, byte[] bytes, int byteIndex) {
    if (group.isEmpty() || group.length() > 4) {
      return false;
    }
    int value = 0;
    for (int index = 0; index < group.length(); index++) {
      int digit = Character.digit(group.charAt(index), 16);
      if (digit < 0) {
        return false;
      }
      value = value * 16 + digit;
    }
    bytes[byteIndex] = (byte) (value >>> 8);
    bytes[byteIndex + 1] = (byte) value;
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
    // The API key is deliberately excluded so the secret never feeds equality or hashing.
    return requestTimeoutSeconds == that.requestTimeoutSeconds
        && connectTimeoutSeconds == that.connectTimeoutSeconds
        && Objects.equals(baseUrl, that.baseUrl);
  }

  @Override
  public int hashCode() {
    return Objects.hash(baseUrl, requestTimeoutSeconds, connectTimeoutSeconds);
  }

  @Override
  public String toString() {
    return "LnbitsConfig[baseUrl="
        + LnbitsUrlRedaction.redactUserInfo(baseUrl)
        + ", apiKey=***REDACTED***"
        + ", requestTimeoutSeconds="
        + requestTimeoutSeconds
        + ", connectTimeoutSeconds="
        + connectTimeoutSeconds
        + "]";
  }
}
