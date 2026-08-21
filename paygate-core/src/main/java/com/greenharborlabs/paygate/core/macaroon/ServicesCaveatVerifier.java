package com.greenharborlabs.paygate.core.macaroon;

import java.util.Set;

public class ServicesCaveatVerifier implements CaveatVerifier {

  private final int maxValuesPerCaveat;

  public ServicesCaveatVerifier(int maxValuesPerCaveat) {
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  @Override
  public String getKey() {
    return "services";
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    String serviceName = context.getServiceName();
    if (serviceName == null) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET, "Service name is null in verification context");
    }

    if (parseGrants(caveat.value()).contains(serviceName)) {
      return;
    }

    throw new MacaroonVerificationException(
        VerificationFailureReason.CAVEAT_NOT_MET,
        "Service '" + serviceName + "' not found in caveat services list");
  }

  /**
   * Returns {@code true} if the current services are a subset of the previous services. An
   * escalation (adding services not in the previous set) returns {@code false}.
   */
  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    try {
      return parseGrants(previous.value()).containsAll(parseGrants(current.value()));
    } catch (MacaroonVerificationException _) {
      return false;
    }
  }

  private Set<String> parseGrants(String serviceList) {
    String[] entries = CaveatValues.splitBounded(serviceList, maxValuesPerCaveat, getKey());
    Set<String> grants = new java.util.HashSet<>(entries.length);
    for (String entry : entries) {
      int separator = entry.indexOf(':');
      String serviceName = separator < 0 ? entry : entry.substring(0, separator);
      String tier = separator < 0 ? null : entry.substring(separator + 1);
      if (serviceName.isEmpty()
          || serviceName.isBlank()
          || separator >= 0 && (entry.indexOf(':', separator + 1) >= 0 || !"0".equals(tier))) {
        throw new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_INVALID, "Invalid services caveat grant");
      }
      grants.add(serviceName);
    }
    return Set.copyOf(grants);
  }
}
