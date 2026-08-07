package com.greenharborlabs.paygate.core.macaroon;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies {@code {service}_capabilities} caveats per the L402 spec. The caveat value is a
 * comma-separated list of capability names (e.g., "search,analyze"), or the reserved {@code ~}
 * sentinel representing no capabilities. Verification checks endpoint satisfaction against the
 * effective (final) caveat value.
 *
 * <p>A capability-less endpoint is satisfied only by {@code ~}. A multi-valued endpoint declaration
 * uses any-of (OR) semantics: {@code search,analyze} is satisfied when the final named set overlaps
 * it by containing either name. A final {@code ~} cannot satisfy a named declaration. Repeated
 * caveats may retain or narrow a named set, including narrowing it to {@code ~}, but may never
 * expand it or turn {@code ~} into a named grant. Blank segments, mixed sentinel/name values, and
 * malformed signed ceilings fail verification.
 */
public class CapabilitiesCaveatVerifier implements CaveatVerifier {

  private final String serviceName;
  private final int maxValuesPerCaveat;

  public CapabilitiesCaveatVerifier(String serviceName, int maxValuesPerCaveat) {
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  @Override
  public String getKey() {
    return serviceName + "_capabilities";
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    CapabilitySet effective = parseCapabilities(caveat.value());
    String requested =
        context.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY);
    if (requested == null) {
      if (effective.none()) {
        return;
      }
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Capabilities caveat contains named capabilities but no capability declared by endpoint");
    }

    CapabilitySet requestedSet = parseCapabilities(requested);
    if (requestedSet.none()) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "No-capability sentinel '~' is reserved and cannot be requested by an endpoint");
    }

    boolean hasAllowedCapability =
        !effective.none() && requestedSet.names().stream().anyMatch(effective.names()::contains);
    if (!hasAllowedCapability) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Requested capabilities '" + requested + "' are not allowed by the effective ceiling");
    }
  }

  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    try {
      CapabilitySet previousSet = parseCapabilities(previous.value());
      CapabilitySet currentSet = parseCapabilities(current.value());
      if (previousSet.none()) {
        return currentSet.none();
      }
      return currentSet.none() || previousSet.names().containsAll(currentSet.names());
    } catch (MacaroonVerificationException ignored) {
      return false;
    }
  }

  /**
   * Parses a verified capability ceiling using the same bounded grammar enforced by this verifier.
   *
   * @param value the capability caveat value
   * @return an immutable set of named capabilities, or an empty set for {@code ~}
   * @throws MacaroonVerificationException if the value is malformed or exceeds configured bounds
   */
  public Set<String> parseEffectiveCapabilities(String value) {
    return parseCapabilities(value).names();
  }

  private CapabilitySet parseCapabilities(String value) {
    String[] segments = CaveatValues.splitBounded(value, maxValuesPerCaveat, getKey());
    if (segments.length == 1 && segments[0].equals("~")) {
      return new CapabilitySet(true, Set.of());
    }

    Set<String> result = new HashSet<>();
    for (String segment : segments) {
      if (segment.equals("~")) {
        throw new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_NOT_MET,
            "No-capability sentinel '~' cannot be mixed with named capabilities");
      }
      result.add(segment);
    }
    return new CapabilitySet(false, Set.copyOf(result));
  }

  private record CapabilitySet(boolean none, Set<String> names) {}
}
