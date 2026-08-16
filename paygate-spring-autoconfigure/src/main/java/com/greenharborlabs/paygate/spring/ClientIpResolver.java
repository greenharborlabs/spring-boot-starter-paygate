package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the client IP address from an {@link HttpServletRequest}, optionally inspecting the
 * {@code X-Forwarded-For} header when behind trusted proxies.
 */
public class ClientIpResolver {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";
  private static final int DEFAULT_IPV6_PREFIX_LENGTH = 64;

  private final boolean trustForwardedHeaders;
  private final Set<String> trustedProxyAddresses;
  private final int ipv6PrefixLength;

  public ClientIpResolver(boolean trustForwardedHeaders, List<String> trustedProxyAddresses) {
    this(trustForwardedHeaders, trustedProxyAddresses, DEFAULT_IPV6_PREFIX_LENGTH);
  }

  /**
   * Creates a resolver with the IPv6 prefix length used to group rate-limit identities.
   *
   * @param trustForwardedHeaders whether forwarded headers may be considered for trusted peers
   * @param trustedProxyAddresses literal addresses of proxies permitted to supply forwarded headers
   * @param ipv6PrefixLength number of leading IPv6 bits retained in rate-limit identities
   */
  public ClientIpResolver(
      boolean trustForwardedHeaders, List<String> trustedProxyAddresses, int ipv6PrefixLength) {
    Objects.requireNonNull(trustedProxyAddresses, "trustedProxyAddresses must not be null");
    if (ipv6PrefixLength < 0 || ipv6PrefixLength > 128) {
      throw new IllegalArgumentException("ipv6PrefixLength must be between 0 and 128");
    }
    this.trustForwardedHeaders = trustForwardedHeaders;
    this.ipv6PrefixLength = ipv6PrefixLength;
    this.trustedProxyAddresses =
        Set.copyOf(
            trustedProxyAddresses.stream()
                .map(ClientIpResolver::normalizeIpLiteral)
                .filter(Objects::nonNull)
                .toList());
  }

  public String resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();

    if (!trustForwardedHeaders) {
      return remoteAddr;
    }

    // Only trust XFF if the direct caller is a known trusted proxy
    String normalizedRemoteAddr = normalizeIp(remoteAddr);
    if (!trustedProxyAddresses.contains(normalizedRemoteAddr)) {
      return remoteAddr;
    }

    String xff = request.getHeader(X_FORWARDED_FOR);
    if (xff == null || xff.isBlank()) {
      return remoteAddr;
    }

    String[] entries = xff.split(",");

    // Walk right-to-left, skipping trusted proxies
    for (int i = entries.length - 1; i >= 0; i--) {
      String entry = entries[i].trim();
      String normalizedEntry = normalizeIpLiteral(entry);
      if (normalizedEntry == null) {
        continue;
      }
      if (!trustedProxyAddresses.contains(normalizedEntry)) {
        return entry;
      }
    }

    // All entries were trusted proxies
    return remoteAddr;
  }

  /**
   * Resolves the client and returns its canonical identity for rate limiting.
   *
   * <p>IPv4 identities are exact canonical literals. IPv6 identities retain only the configured
   * leading bits, with the remaining address bytes zeroed. The mask is applied only after trusted
   * proxy resolution has selected the effective client.
   */
  public String resolveRateLimitIdentity(HttpServletRequest request) {
    String clientIp = resolve(request);
    InetAddress address = parseIpLiteral(clientIp);
    if (address == null) {
      // Preserve the resolver's fail-closed behavior for malformed servlet remote addresses:
      // they cannot become trusted forwarding peers and no header-derived value is used.
      return clientIp;
    }

    byte[] addressBytes = address.getAddress();
    if (addressBytes.length == 4) {
      return address.getHostAddress();
    }

    applyIpv6PrefixMask(addressBytes, ipv6PrefixLength);
    try {
      return InetAddress.getByAddress(addressBytes).getHostAddress();
    } catch (java.net.UnknownHostException e) {
      throw new IllegalStateException("Unable to construct masked IPv6 rate-limit identity", e);
    }
  }

  private static void applyIpv6PrefixMask(byte[] addressBytes, int prefixLength) {
    int fullBytes = prefixLength / Byte.SIZE;
    int remainingBits = prefixLength % Byte.SIZE;
    if (fullBytes < addressBytes.length && remainingBits != 0) {
      addressBytes[fullBytes] &= (byte) (0xFF << (Byte.SIZE - remainingBits));
      fullBytes++;
    }
    for (int i = fullBytes; i < addressBytes.length; i++) {
      addressBytes[i] = 0;
    }
  }

  private static String normalizeIp(String ip) {
    String normalized = normalizeIpLiteral(ip);
    return normalized == null ? ip : normalized;
  }

  private static String normalizeIpLiteral(String ip) {
    InetAddress address = parseIpLiteral(ip);
    return address == null ? null : address.getHostAddress();
  }

  private static InetAddress parseIpLiteral(String ip) {
    try {
      return InetAddress.ofLiteral(ip);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }
}
