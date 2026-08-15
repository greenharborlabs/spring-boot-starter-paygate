package com.greenharborlabs.paygate.core.macaroon;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that the request client IP matches at least one IP address specified in the {@code
 * client_ip} caveat value (comma-separated).
 *
 * <p>Each value is compared literally. The verifier performs no DNS resolution and does not
 * interpret CIDR or network-range notation. Stateless and thread-safe.
 */
public class ClientIpCaveatVerifier implements CaveatVerifier {

  private final int maxValuesPerCaveat;

  public ClientIpCaveatVerifier(int maxValuesPerCaveat) {
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
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

    // 3. Match request client IP against each allowed literal value.
    for (String ip : ips) {
      if (requestClientIp.equals(ip)) {
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
            .collect(Collectors.toSet());
    Set<String> currentIps =
        Arrays.stream(current.value().split(",", -1)).map(String::trim).collect(Collectors.toSet());
    return previousIps.containsAll(currentIps);
  }
}
