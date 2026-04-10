package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("L402HeaderComponents")
class L402HeaderComponentsTest {

  private static final String VALID_PREIMAGE_HEX =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
  private static final String VALID_MACAROON_B64 = "AgJMaHR0cHM6Ly9leGFtcGxl";
  private static final String VALID_L402_HEADER =
      "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX;
  private static final String VALID_LSAT_HEADER =
      "LSAT " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX;

  @Nested
  @DisplayName("extract()")
  class Extract {

    @Test
    @DisplayName("returns components for valid L402 header")
    void validL402Header() {
      Optional<L402HeaderComponents> result = L402HeaderComponents.extract(VALID_L402_HEADER);

      assertThat(result).isPresent();
      assertThat(result.get().scheme()).isEqualTo("L402");
      assertThat(result.get().macaroonBase64()).isEqualTo(VALID_MACAROON_B64);
      assertThat(result.get().preimageHex()).isEqualTo(VALID_PREIMAGE_HEX);
    }

    @Test
    @DisplayName("returns components for valid LSAT header")
    void validLsatHeader() {
      Optional<L402HeaderComponents> result = L402HeaderComponents.extract(VALID_LSAT_HEADER);

      assertThat(result).isPresent();
      assertThat(result.get().scheme()).isEqualTo("LSAT");
    }

    @Test
    @DisplayName("accepts lowercase l402 scheme and normalizes to uppercase")
    void lowercaseL402Scheme() {
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("l402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX);

      assertThat(result).isPresent();
      assertThat(result.get().scheme()).isEqualTo("L402");
    }

    @Test
    @DisplayName("accepts mixed-case lsat scheme and normalizes to uppercase")
    void mixedCaseLsatScheme() {
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("LsAt " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX);

      assertThat(result).isPresent();
      assertThat(result.get().scheme()).isEqualTo("LSAT");
    }

    @Test
    @DisplayName("returns empty for null header")
    void nullHeader() {
      assertThat(L402HeaderComponents.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("returns empty for empty header")
    void emptyHeader() {
      assertThat(L402HeaderComponents.extract("")).isEmpty();
    }

    @Test
    @DisplayName("returns empty for Bearer token")
    void bearerToken() {
      assertThat(L402HeaderComponents.extract("Bearer some-token")).isEmpty();
    }

    @Test
    @DisplayName("returns empty for missing preimage")
    void missingPreimage() {
      assertThat(L402HeaderComponents.extract("L402 " + VALID_MACAROON_B64)).isEmpty();
    }

    @Test
    @DisplayName("returns empty for short preimage (63 hex chars)")
    void shortPreimage() {
      String shortPreimage = VALID_PREIMAGE_HEX.substring(1);
      assertThat(L402HeaderComponents.extract("L402 " + VALID_MACAROON_B64 + ":" + shortPreimage))
          .isEmpty();
    }

    @Test
    @DisplayName("returns empty for long preimage (65 hex chars)")
    void longPreimage() {
      String longPreimage = VALID_PREIMAGE_HEX + "a";
      assertThat(L402HeaderComponents.extract("L402 " + VALID_MACAROON_B64 + ":" + longPreimage))
          .isEmpty();
    }

    @Test
    @DisplayName("returns empty for non-hex preimage")
    void nonHexPreimage() {
      String nonHex = "zzzzzz0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
      assertThat(L402HeaderComponents.extract("L402 " + VALID_MACAROON_B64 + ":" + nonHex))
          .isEmpty();
    }

    @Test
    @DisplayName("rejects oversized macaroon (>8192 characters)")
    void oversizedMacaroon() {
      String oversized = "A".repeat(8193);
      assertThat(L402HeaderComponents.extract("L402 " + oversized + ":" + VALID_PREIMAGE_HEX))
          .isEmpty();
    }

    @Test
    @DisplayName("accepts macaroon at max size (8192 characters)")
    void maxSizeMacaroon() {
      String maxSize = "A".repeat(8192);
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("L402 " + maxSize + ":" + VALID_PREIMAGE_HEX);

      assertThat(result).isPresent();
      assertThat(result.get().macaroonBase64()).hasSize(8192);
    }

    @Test
    @DisplayName("accepts macaroon with base64 padding characters")
    void base64Padding() {
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("L402 YWJj=:" + VALID_PREIMAGE_HEX);
      assertThat(result).isPresent();
      assertThat(result.get().macaroonBase64()).isEqualTo("YWJj=");
    }

    @Test
    @DisplayName("accepts comma-separated multi-token macaroons")
    void multiTokenMacaroons() {
      String multiToken = "token1,token2";
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("L402 " + multiToken + ":" + VALID_PREIMAGE_HEX);
      assertThat(result).isPresent();
      assertThat(result.get().macaroonBase64()).isEqualTo(multiToken);
    }

    @Test
    @DisplayName("returns empty for unknown scheme")
    void unknownScheme() {
      assertThat(
              L402HeaderComponents.extract(
                  "Basic " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX))
          .isEmpty();
    }

    @Test
    @DisplayName("accepts uppercase hex in preimage")
    void uppercaseHexPreimage() {
      String upperPreimage = VALID_PREIMAGE_HEX.toUpperCase();
      Optional<L402HeaderComponents> result =
          L402HeaderComponents.extract("L402 " + VALID_MACAROON_B64 + ":" + upperPreimage);
      assertThat(result).isPresent();
      assertThat(result.get().preimageHex()).isEqualTo(upperPreimage);
    }
  }

  @Nested
  @DisplayName("extractOrThrow()")
  class ExtractOrThrow {

    @Test
    @DisplayName("returns components for valid header")
    void validHeader() {
      L402HeaderComponents result = L402HeaderComponents.extractOrThrow(VALID_L402_HEADER);

      assertThat(result.scheme()).isEqualTo("L402");
      assertThat(result.macaroonBase64()).isEqualTo(VALID_MACAROON_B64);
      assertThat(result.preimageHex()).isEqualTo(VALID_PREIMAGE_HEX);
    }

    @Test
    @DisplayName("throws L402Exception for null header")
    void nullHeader() {
      assertThatThrownBy(() -> L402HeaderComponents.extractOrThrow(null))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
                assertThat(l402Ex.getTokenId()).isNull();
              });
    }

    @Test
    @DisplayName("throws L402Exception for empty header")
    void emptyHeader() {
      assertThatThrownBy(() -> L402HeaderComponents.extractOrThrow(""))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
              });
    }

    @Test
    @DisplayName("throws L402Exception for malformed header")
    void malformedHeader() {
      assertThatThrownBy(() -> L402HeaderComponents.extractOrThrow("Bearer token"))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
              });
    }

    @Test
    @DisplayName("throws L402Exception for oversized macaroon")
    void oversizedMacaroon() {
      String oversized = "A".repeat(8193);
      assertThatThrownBy(
              () ->
                  L402HeaderComponents.extractOrThrow(
                      "L402 " + oversized + ":" + VALID_PREIMAGE_HEX))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
              });
    }
  }

  @Nested
  @DisplayName("isL402Header()")
  class IsL402Header {

    @Test
    @DisplayName("returns true for L402 prefix")
    void l402Prefix() {
      assertThat(L402HeaderComponents.isL402Header("L402 something")).isTrue();
    }

    @Test
    @DisplayName("returns true for LSAT prefix")
    void lsatPrefix() {
      assertThat(L402HeaderComponents.isL402Header("LSAT something")).isTrue();
    }

    @Test
    @DisplayName("returns true for lowercase l402 prefix")
    void lowercaseL402Prefix() {
      assertThat(L402HeaderComponents.isL402Header("l402 something")).isTrue();
    }

    @Test
    @DisplayName("returns false for Bearer prefix")
    void bearerPrefix() {
      assertThat(L402HeaderComponents.isL402Header("Bearer token")).isFalse();
    }

    @Test
    @DisplayName("returns false for null")
    void nullHeader() {
      assertThat(L402HeaderComponents.isL402Header(null)).isFalse();
    }

    @Test
    @DisplayName("returns false for empty string")
    void emptyHeader() {
      assertThat(L402HeaderComponents.isL402Header("")).isFalse();
    }

    @Test
    @DisplayName("returns false for L402 without space")
    void l402NoSpace() {
      assertThat(L402HeaderComponents.isL402Header("L402token")).isFalse();
    }
  }

  @Nested
  @DisplayName("toHeaderValue()")
  class ToHeaderValue {

    @Test
    @DisplayName("reconstructs valid L402 header")
    void reconstructsL402Header() {
      var components = new L402HeaderComponents("L402", VALID_MACAROON_B64, VALID_PREIMAGE_HEX);
      assertThat(components.toHeaderValue()).isEqualTo(VALID_L402_HEADER);
    }

    @Test
    @DisplayName("reconstructs valid LSAT header")
    void reconstructsLsatHeader() {
      var components = new L402HeaderComponents("LSAT", VALID_MACAROON_B64, VALID_PREIMAGE_HEX);
      assertThat(components.toHeaderValue()).isEqualTo(VALID_LSAT_HEADER);
    }
  }

  @Nested
  @DisplayName("round-trip")
  class RoundTrip {

    @Test
    @DisplayName("extract(toHeaderValue()) returns equivalent components")
    void extractRoundTrip() {
      var original = new L402HeaderComponents("L402", VALID_MACAROON_B64, VALID_PREIMAGE_HEX);
      Optional<L402HeaderComponents> roundTripped =
          L402HeaderComponents.extract(original.toHeaderValue());

      assertThat(roundTripped).isPresent();
      assertThat(roundTripped.get()).isEqualTo(original);
    }

    @Test
    @DisplayName("extractOrThrow(toHeaderValue()) returns equivalent components")
    void extractOrThrowRoundTrip() {
      var original = new L402HeaderComponents("LSAT", VALID_MACAROON_B64, VALID_PREIMAGE_HEX);
      L402HeaderComponents roundTripped =
          L402HeaderComponents.extractOrThrow(original.toHeaderValue());

      assertThat(roundTripped).isEqualTo(original);
    }
  }

  @Nested
  @DisplayName("compact constructor validation")
  class CompactConstructor {

    @Test
    @DisplayName("rejects null scheme")
    void nullScheme() {
      assertThatThrownBy(() -> new L402HeaderComponents(null, "abc", "def"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("rejects null macaroonBase64")
    void nullMacaroonBase64() {
      assertThatThrownBy(() -> new L402HeaderComponents("L402", null, "def"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("macaroonBase64");
    }

    @Test
    @DisplayName("rejects null preimageHex")
    void nullPreimageHex() {
      assertThatThrownBy(() -> new L402HeaderComponents("L402", "abc", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("preimageHex");
    }
  }
}
