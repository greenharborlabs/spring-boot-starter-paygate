package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.SecurityBounds;

/**
 * Immutable, validated price resolved from trusted inputs for one request.
 *
 * @param amountSats price in satoshis within the shared inclusive security bounds
 */
public record TrustedRequestPrice(long amountSats) {

  /** Validates the resolved price before it is shared by validation and challenge creation. */
  public TrustedRequestPrice {
    if (!SecurityBounds.isValidPrice(amountSats)) {
      throw new IllegalArgumentException(
          "amountSats is outside the supported price range: " + amountSats);
    }
  }
}
