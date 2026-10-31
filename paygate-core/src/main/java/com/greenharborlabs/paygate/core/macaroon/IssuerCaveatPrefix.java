package com.greenharborlabs.paygate.core.macaroon;

import java.util.List;
import java.util.Objects;

/**
 * Locates the issuer-signed prefix of an L402 macaroon's first-party caveats.
 *
 * <p>The issuer prefix is every caveat before the first {@code {serviceName}_valid_until} caveat.
 * Built-in issuance emits {@code {serviceName}_valid_until} as its last caveat, and custom issuers
 * must do the same. Macaroon holders can only append caveats, so a caveat in this prefix was signed
 * by the issuer. A caveat after it may have been added by a holder.
 *
 * <p>Use the prefix only to decide whether issuer evidence is present, such as mandatory
 * boundaries, signed paid price or client-address binding. Holder-appended caveats still
 * participate in signature and monotonic-restriction checks and may only narrow what the issuer
 * granted.
 */
public final class IssuerCaveatPrefix {

  private static final String SERVICES_CAVEAT_KEY = "services";
  private static final String ROUTE_CAVEAT_KEY = "route";
  private static final String METHOD_CAVEAT_KEY = "method";

  private IssuerCaveatPrefix() {}

  /**
   * Returns the caveats before the first {@code {serviceName}_valid_until} caveat.
   *
   * <p>Keys are compared in canonical form (see {@link CaveatKey#canonicalize(String)}).
   *
   * @param caveats authenticated caveats in signed order
   * @param serviceName first-party service name
   * @return an unmodifiable copy of the issuer prefix, or an empty list when no {@code
   *     {serviceName}_valid_until} caveat is present, so absent expiry yields no issuer evidence
   */
  public static List<Caveat> of(List<Caveat> caveats, String serviceName) {
    int end = validUntilIndex(caveats, serviceName);
    return end < 0 ? List.of() : List.copyOf(caveats.subList(0, end));
  }

  /**
   * Requires the issuer to have signed the {@code services}, {@code route}, {@code method} and
   * {@code {serviceName}_capabilities} boundaries before the first {@code
   * {serviceName}_valid_until} caveat.
   *
   * @param caveats authenticated caveats in signed order
   * @param serviceName first-party service name
   * @throws MacaroonVerificationException with {@link VerificationFailureReason#CAVEAT_NOT_MET}
   *     when a boundary is absent from the issuer prefix or no expiry caveat is present
   */
  public static void requireBoundaries(List<Caveat> caveats, String serviceName) {
    String capabilitiesKey = serviceName + "_capabilities";
    boolean hasServices = false;
    boolean hasRoute = false;
    boolean hasMethod = false;
    boolean hasCapabilities = false;
    int end = validUntilIndex(caveats, serviceName);
    for (int i = 0; i < end; i++) {
      String canonicalKey = CaveatKey.canonicalize(caveats.get(i).key());
      hasServices |= SERVICES_CAVEAT_KEY.equals(canonicalKey);
      hasRoute |= ROUTE_CAVEAT_KEY.equals(canonicalKey);
      hasMethod |= METHOD_CAVEAT_KEY.equals(canonicalKey);
      hasCapabilities |= capabilitiesKey.equals(canonicalKey);
    }
    // end < 0 (no valid_until) leaves every flag false.
    if (!hasServices || !hasRoute || !hasMethod || !hasCapabilities) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Credential is missing a required issuer boundary caveat");
    }
  }

  private static int validUntilIndex(List<Caveat> caveats, String serviceName) {
    Objects.requireNonNull(caveats, "caveats must not be null");
    Objects.requireNonNull(serviceName, "serviceName must not be null");
    String validUntilKey = serviceName + "_valid_until";
    for (int i = 0; i < caveats.size(); i++) {
      if (validUntilKey.equals(CaveatKey.canonicalize(caveats.get(i).key()))) {
        return i;
      }
    }
    return -1;
  }
}
