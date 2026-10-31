package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

  /**
   * Resolves the client address used for rate limiting and holder-added {@code client_ip} caveats.
   *
   * <p>When the direct peer is a trusted proxy, every {@code X-Forwarded-For} field line is joined
   * in order (RFC 9110 section 5.3) and walked right to left past trusted proxies. The first
   * untrusted literal is the client. The walk stops at the first entry that is not an IP literal,
   * such as {@code ip:port}, {@code unknown}, or an obfuscated identifier, and returns the nearest
   * trusted hop instead: the entry to its right, or the direct peer. Entries further left were
   * supplied by a hop that cannot be identified, so they are never returned.
   */
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

    List<String> entries = forwardedForEntries(request);
    String nearestTrustedHop = remoteAddr;
    for (int i = entries.size() - 1; i >= 0; i--) {
      String entry = entries.get(i);
      String normalizedEntry = normalizeIpLiteral(entry);
      if (normalizedEntry == null) {
        return nearestTrustedHop;
      }
      if (!trustedProxyAddresses.contains(normalizedEntry)) {
        return entry;
      }
      nearestTrustedHop = entry;
    }

    // No header, or all entries were trusted proxies
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

  /**
   * Resolves the exact canonical client address suitable for an L402 authorization caveat.
   *
   * <p>Unlike rate-limit identity resolution, this never masks IPv6 addresses or falls back from an
   * incomplete trusted-proxy chain to the proxy peer. All {@code X-Forwarded-For} field lines are
   * joined in order; the joined list must consist only of IP literals, with exactly one untrusted
   * entry in the leftmost position and trusted proxies to its right. Anything else, including
   * repeated field lines that name more than one untrusted address, is ambiguous. An empty result
   * is deliberately an unavailable provenance state and callers must fail closed.
   */
  public Optional<String> resolveBindingAddress(HttpServletRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    String remote = normalizeIpLiteral(request.getRemoteAddr());
    if (remote == null) {
      return Optional.empty();
    }
    if (!trustForwardedHeaders || !trustedProxyAddresses.contains(remote)) {
      return Optional.of(remote);
    }

    List<String> entries = forwardedForEntries(request);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    String client = null;
    for (int index = entries.size() - 1; index >= 0; index--) {
      String canonical = normalizeIpLiteral(entries.get(index));
      if (canonical == null) {
        return Optional.empty();
      }
      if (trustedProxyAddresses.contains(canonical)) {
        continue;
      }
      if (client != null || index != 0) {
        return Optional.empty();
      }
      client = canonical;
    }
    return Optional.ofNullable(client);
  }

  /**
   * Returns every {@code X-Forwarded-For} entry, trimmed, from all field lines in order. Empty
   * entries are preserved so callers treat them as unparseable. Returns an empty list when the
   * header is absent.
   */
  private static List<String> forwardedForEntries(HttpServletRequest request) {
    Enumeration<String> lines = request.getHeaders(X_FORWARDED_FOR);
    if (lines == null) {
      return List.of();
    }
    List<String> entries = new ArrayList<>();
    while (lines.hasMoreElements()) {
      String line = lines.nextElement();
      for (String entry : (line == null ? "" : line).split(",", -1)) {
        entries.add(entry.trim());
      }
    }
    return entries;
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
    if (ip == null || ip.isBlank() || ip.indexOf('%') >= 0) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(ip);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }
}
