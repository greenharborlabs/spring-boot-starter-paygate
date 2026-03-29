package com.greenharborlabs.paygate.core.macaroon;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that the request client IP matches at least one IP address specified in the {@code
 * client_ip} caveat value (comma-separated).
 *
 * <p>IPv6 addresses are normalized via {@link InetAddress#ofLiteral(String)} before comparison so
 * that equivalent representations (e.g. {@code ::1} and {@code 0:0:0:0:0:0:0:1}) match correctly.
 * Non-IP strings fall back to exact string comparison. Stateless and thread-safe.
 */
public class ClientIpCaveatVerifier implements CaveatVerifier {

  private final int maxValuesPerCaveat;

  public ClientIpCaveatVerifier(int maxValuesPerCaveat) {
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  @Override
  public String getKey() {
    return "client_ip";
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    // 1. Extract request client IP — fail-closed if absent
    String requestClientIp =
        context.getRequestMetadata().get(VerificationContextKeys.REQUEST_CLIENT_IP);
    if (requestClientIp == null) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET, "Client IP missing from verification context");
    }

    // 2. Split, bounds-check, and trim caveat value
    String[] ips = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat, "client_ip");

    // 3. Match request client IP against each allowed IP (normalized for IPv6)
    String normalizedRequestIp = normalizeIp(requestClientIp);
    for (String ip : ips) {
      if (normalizedRequestIp.equals(normalizeIp(ip))) {
        return;
      }
    }

    // 6. No IP matched — reject
    throw new MacaroonVerificationException(
        VerificationFailureReason.CAVEAT_NOT_MET,
        "Request client IP does not match any allowed IP");
  }

  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    // Reject oversized caveats before expensive subset-containment check
    if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
        || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
      return false;
    }

    Set<String> previousIps =
        Arrays.stream(previous.value().split(",", -1))
            .map(String::trim)
            .map(ClientIpCaveatVerifier::normalizeIp)
            .collect(Collectors.toSet());
    Set<String> currentIps =
        Arrays.stream(current.value().split(",", -1))
            .map(String::trim)
            .map(ClientIpCaveatVerifier::normalizeIp)
            .collect(Collectors.toSet());
    return previousIps.containsAll(currentIps);
  }

  /**
   * Normalizes an IP address string using {@link InetAddress#ofLiteral(String)}. This ensures
   * equivalent IPv6 representations are canonicalized (e.g. {@code ::1} and {@code 0:0:0:0:0:0:0:1}
   * both become {@code 0:0:0:0:0:0:0:1}).
   *
   * <p>Unlike {@link InetAddress#getByName(String)}, {@code ofLiteral()} never performs DNS lookups
   * — it throws {@link IllegalArgumentException} for non-IP strings, which we catch and fall back
   * to the raw input.
   */
  private static String normalizeIp(String ip) {
    try {
      return InetAddress.ofLiteral(ip).getHostAddress();
    } catch (IllegalArgumentException _) {
      return ip;
    }
  }
}
