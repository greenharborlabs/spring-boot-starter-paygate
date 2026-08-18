package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.SecurityBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PaidPriceCaveatVerifier")
class PaidPriceCaveatVerifierTest {

  private final PaidPriceCaveatVerifier verifier = new PaidPriceCaveatVerifier("catalog");

  @Test
  void exposesTheServiceScopedKey() {
    assertThat(verifier.getKey()).isEqualTo("catalog_price_sats");
  }

  @Nested
  class Grammar {

    @Test
    void acceptsTheInclusiveBounds() {
      assertThat(PaidPriceCaveatVerifier.parse("1")).isEqualTo(1L);
      assertThat(PaidPriceCaveatVerifier.parse(Long.toString(SecurityBounds.MAX_PRICE_SATS)))
          .isEqualTo(SecurityBounds.MAX_PRICE_SATS);
    }

    @Test
    void rejectsNonCanonicalAndOutOfRangeValues() {
      for (String value :
          new String[] {
            "0", "01", "+1", "-1", "1.0", "x", "2100000000000001", "999999999999999999999999"
          }) {
        assertThatThrownBy(() -> PaidPriceCaveatVerifier.parse(value))
            .isInstanceOf(MacaroonVerificationException.class);
      }
    }
  }

  @Nested
  class Attenuation {

    @Test
    void acceptsEqualOrLowerRepeatedValues() {
      assertThat(
              verifier.isMoreRestrictive(
                  new Caveat("catalog_price_sats", "100"), new Caveat("catalog_price_sats", "100")))
          .isTrue();
      assertThat(
              verifier.isMoreRestrictive(
                  new Caveat("catalog_price_sats", "100"), new Caveat("catalog_price_sats", "99")))
          .isTrue();
    }

    @Test
    void rejectsAnIncreasingRepeatedValue() {
      assertThat(
              verifier.isMoreRestrictive(
                  new Caveat("catalog_price_sats", "99"), new Caveat("catalog_price_sats", "100")))
          .isFalse();
    }
  }
}
