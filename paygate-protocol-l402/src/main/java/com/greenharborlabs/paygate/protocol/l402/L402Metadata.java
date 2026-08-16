package com.greenharborlabs.paygate.protocol.l402;

import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import java.util.List;
import java.util.Objects;

/**
 * Parsed L402 credential metadata carrying the primary macaroon, any additional macaroons presented
 * alongside it, and the raw authorization header needed for downstream validation by {@code
 * L402Validator}.
 */
public record L402Metadata(
    Macaroon macaroon, List<Macaroon> additionalMacaroons, String rawAuthorizationHeader)
    implements ProtocolMetadata {

  public L402Metadata {
    Objects.requireNonNull(macaroon, "macaroon must not be null");
    Objects.requireNonNull(additionalMacaroons, "additionalMacaroons must not be null");
    Objects.requireNonNull(rawAuthorizationHeader, "rawAuthorizationHeader must not be null");
    additionalMacaroons = List.copyOf(additionalMacaroons);
  }

  /**
   * Returns a fixed, non-recursive diagnostic summary without rendering bearer credential material.
   */
  @Override
  public String toString() {
    return "L402Metadata[macaroon=<redacted>, additionalMacaroons=<redacted>, "
        + "additionalMacaroonCount="
        + additionalMacaroons.size()
        + ", rawAuthorizationHeader=<redacted>, rawAuthorizationHeaderLength="
        + rawAuthorizationHeader.length()
        + "]";
  }
}
