package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Macaroon object contract")
class MacaroonTest {

  private static final String ORDINARY_LOCATION = "https://pay.example.com/l402";
  private static final String ADVERSARIAL_LOCATION =
      "https://admin:location-secret@example.com/pay?token=query-secret\n"
          + "level=ERROR|forged=true";
  private static final String SAFE_DIAGNOSTIC = "Macaroon[identifierLength=66, caveatCount=1]";

  private static final byte[] IDENTIFIER = filledBytes(Macaroon.IDENTIFIER_LENGTH, (byte) 'I');
  private static final byte[] SIGNATURE = filledBytes(Macaroon.SIGNATURE_LENGTH, (byte) 'S');
  private static final List<Caveat> CAVEATS = List.of(new Caveat("route", "/private-secret"));

  @Nested
  @DisplayName("Diagnostic rendering")
  class DiagnosticRendering {

    @Test
    @DisplayName("renders only structural metadata for a null location")
    void rendersOnlyStructuralMetadataForNullLocation() {
      var macaroon = macaroonWithLocation(null);

      assertThat(macaroon.toString()).isEqualTo(SAFE_DIAGNOSTIC);
    }

    @Test
    @DisplayName("omits an ordinary unsigned location")
    void omitsOrdinaryUnsignedLocation() {
      var macaroon = macaroonWithLocation(ORDINARY_LOCATION);

      assertThat(macaroon.toString())
          .isEqualTo(SAFE_DIAGNOSTIC)
          .doesNotContain(ORDINARY_LOCATION, "pay.example.com");
    }

    @Test
    @DisplayName("omits adversarial location and all credential values")
    void omitsAdversarialLocationAndCredentialValues() {
      var macaroon = macaroonWithLocation(ADVERSARIAL_LOCATION);

      assertThat(macaroon.toString())
          .isEqualTo(SAFE_DIAGNOSTIC)
          .doesNotContain(
              ADVERSARIAL_LOCATION,
              "admin:location-secret",
              "query-secret",
              "level=ERROR",
              "forged=true",
              "IIII",
              "SSSS",
              "route",
              "/private-secret");
    }

    @Test
    @DisplayName("location variants render the same deterministic structural shape")
    void locationVariantsRenderTheSameStructuralShape() {
      var withoutLocation = macaroonWithLocation(null);
      var withAdversarialLocation = macaroonWithLocation(ADVERSARIAL_LOCATION);

      assertThat(withoutLocation.toString()).isEqualTo(withAdversarialLocation.toString());
    }
  }

  @Nested
  @DisplayName("Location contract")
  class LocationContract {

    @Test
    @DisplayName("preserves null and non-null locations through access and V2 round trip")
    void preservesLocationsThroughAccessAndV2RoundTrip() {
      var withoutLocation = macaroonWithLocation(null);
      var withOrdinaryLocation = macaroonWithLocation(ORDINARY_LOCATION);
      var withAdversarialLocation = macaroonWithLocation(ADVERSARIAL_LOCATION);

      assertThat(withoutLocation.location()).isNull();
      assertThat(withOrdinaryLocation.location()).isEqualTo(ORDINARY_LOCATION);
      assertThat(withAdversarialLocation.location()).isEqualTo(ADVERSARIAL_LOCATION);

      assertThat(roundTrip(withoutLocation)).isEqualTo(withoutLocation);
      assertThat(roundTrip(withOrdinaryLocation)).isEqualTo(withOrdinaryLocation);
      assertThat(roundTrip(withAdversarialLocation)).isEqualTo(withAdversarialLocation);
    }

    @Test
    @DisplayName("location-only variants remain unequal with location-sensitive hash codes")
    void locationOnlyVariantsRemainUnequal() {
      var withoutLocation = macaroonWithLocation(null);
      var withAdversarialLocation = macaroonWithLocation(ADVERSARIAL_LOCATION);
      var equivalentAdversarialLocation = macaroonWithLocation(ADVERSARIAL_LOCATION);

      assertThat(withoutLocation).isNotEqualTo(withAdversarialLocation);
      assertThat(withoutLocation.hashCode()).isNotEqualTo(withAdversarialLocation.hashCode());
      assertThat(withAdversarialLocation)
          .isEqualTo(equivalentAdversarialLocation)
          .hasSameHashCodeAs(equivalentAdversarialLocation);
    }
  }

  private static Macaroon macaroonWithLocation(String location) {
    return new Macaroon(IDENTIFIER, location, CAVEATS, SIGNATURE);
  }

  private static Macaroon roundTrip(Macaroon macaroon) {
    return MacaroonSerializer.deserializeV2(MacaroonSerializer.serializeV2(macaroon));
  }

  private static byte[] filledBytes(int length, byte value) {
    var bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
