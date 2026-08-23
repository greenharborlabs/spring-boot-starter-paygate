package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.SecurityBounds;

/**
 * Verifies the first-party signed paid-price caveat for one L402 service.
 *
 * <p>Values are canonical positive base-ten satoshi amounts. Repeated values may only decrease (or
 * remain equal), which preserves macaroon attenuation: a later caveat can reduce the covered amount
 * but can never increase it.
 */
public final class PaidPriceCaveatVerifier implements CaveatVerifier {

  private final String key;

  /**
   * Creates a verifier for {@code serviceName + "_price_sats"}.
   *
   * @param serviceName non-blank first-party service name
   */
  public PaidPriceCaveatVerifier(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalArgumentException("serviceName must not be blank");
    }
    this.key = serviceName + "_price_sats";
  }

  @Override
  public String getKey() {
    return key;
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    if (!key.equals(caveat.key())) {
      throw new MacaroonVerificationException("Unexpected paid-price caveat key");
    }
    parse(caveat.value());
  }

  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    return parse(current.value()) <= parse(previous.value());
  }

  /**
   * Parses one canonical price caveat value.
   *
   * @param value caveat text
   * @return bounded amount in satoshis
   * @throws MacaroonVerificationException when the grammar or bounds are invalid
   */
  public static long parse(String value) {
    if (value == null || value.isEmpty() || value.charAt(0) == '+' || value.charAt(0) == '-') {
      throw invalid();
    }
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (character < '0' || character > '9') {
        throw invalid();
      }
    }
    if (value.length() > 1 && value.charAt(0) == '0') {
      throw invalid();
    }
    try {
      long parsed = Long.parseLong(value);
      if (!SecurityBounds.isValidPrice(parsed)) {
        throw invalid();
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw invalid();
    }
  }

  private static MacaroonVerificationException invalid() {
    return new MacaroonVerificationException(
        VerificationFailureReason.CAVEAT_NOT_MET, "Invalid paid-price caveat");
  }
}
