package com.greenharborlabs.paygate.core.macaroon;

/**
 * Verifies that a request's canonical registered route exactly matches its {@code route} caveat.
 */
public class RouteCaveatVerifier implements CaveatVerifier {

  private final int maxValuesPerCaveat;

  public RouteCaveatVerifier(int maxValuesPerCaveat) {
    if (maxValuesPerCaveat < 1) {
      throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
    }
    this.maxValuesPerCaveat = maxValuesPerCaveat;
  }

  @Override
  public String getKey() {
    return "route";
  }

  @Override
  public void verify(Caveat caveat, L402VerificationContext context) {
    String requestRoute = context.getRequestMetadata().get(VerificationContextKeys.REQUEST_ROUTE);
    if (requestRoute == null || requestRoute.isBlank()) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Request route missing from verification context");
    }

    CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat, "route");
    if (!requestRoute.equals(caveat.value())) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Request route does not match credential route");
    }
  }

  @Override
  public boolean isMoreRestrictive(Caveat previous, Caveat current) {
    return CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
        && CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)
        && previous.value().equals(current.value());
  }
}
