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
    void rejectsZeroMaximumPlusOneOverflowAndMalformedDecimalForms() {
      String maximumPlusOne = Long.toString(SecurityBounds.MAX_PRICE_SATS + 1);
      for (String value :
          new String[] {"0", maximumPlusOne, "9223372036854775808", "", " 1", "1 ", "1e2"}) {
        assertThatThrownBy(() -> PaidPriceCaveatVerifier.parse(value))
            .isInstanceOf(MacaroonVerificationException.class)
            .satisfies(
                failure ->
                    assertThat(((MacaroonVerificationException) failure).getReason())
                        .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET));
      }
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

    @Test
    void acceptsCanonicalCaveatOutputOnlyForTheServiceScopedKey() {
      Caveat caveat = new Caveat("catalog_price_sats", "21");

      verifier.verify(caveat, new L402VerificationContext());

      assertThatThrownBy(
              () ->
                  verifier.verify(
                      new Caveat("other_price_sats", "21"), new L402VerificationContext()))
          .isInstanceOf(MacaroonVerificationException.class);
    }
  }
}
