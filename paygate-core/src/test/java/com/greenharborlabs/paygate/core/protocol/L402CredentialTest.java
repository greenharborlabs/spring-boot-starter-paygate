package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("L402Credential.parse")
class L402CredentialTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();

  private byte[] rootKey;
  private byte[] preimageBytes;
  private byte[] paymentHash;
  private byte[] tokenIdBytes;
  private MacaroonIdentifier identifier;
  private Macaroon macaroon;
  private String macaroonBase64;
  private String preimageHex;
  private String tokenIdHex;

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException {
    rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);

    preimageBytes = new byte[32];
    RANDOM.nextBytes(preimageBytes);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

    tokenIdBytes = new byte[32];
    RANDOM.nextBytes(tokenIdBytes);

    identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
    macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    preimageHex = HEX.formatHex(preimageBytes);
    tokenIdHex = HEX.formatHex(tokenIdBytes);
  }

  private String mintAdditionalMacaroonBase64() throws NoSuchAlgorithmException {
    byte[] additionalRootKey = new byte[32];
    RANDOM.nextBytes(additionalRootKey);
    byte[] additionalPaymentHash = MessageDigest.getInstance("SHA-256").digest(additionalRootKey);
    byte[] additionalTokenId = new byte[32];
    RANDOM.nextBytes(additionalTokenId);
    MacaroonIdentifier additionalId =
        new MacaroonIdentifier(0, additionalPaymentHash, additionalTokenId);
    Macaroon additionalMacaroon =
        MacaroonMinter.mint(additionalRootKey, additionalId, "https://example.com", List.of());
    return Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(additionalMacaroon));
  }

  @Nested
  @DisplayName("valid L402 header")
  class ValidL402Header {

    @Test
    @DisplayName("parses L402 prefix with valid base64 macaroon and hex preimage")
    void parsesL402Prefix() {
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
      assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
      assertThat(credential.additionalMacaroons()).isEmpty();
    }

    @Test
    @DisplayName("parses macaroon with correct signature preservation")
    void preservesMacaroonSignature() {
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential.macaroon().signature()).isEqualTo(macaroon.signature());
    }

    @Test
    @DisplayName("parses macaroon with correct location preservation")
    void preservesMacaroonLocation() {
      String header = "L402 " + macaroonBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential.macaroon().location()).isEqualTo("https://example.com");
    }
  }

  @Nested
  @DisplayName("valid LSAT header (backward compatibility)")
  class ValidLsatHeader {

    @Test
    @DisplayName("parses LSAT prefix with valid base64 macaroon and hex preimage")
    void parsesLsatPrefix() {
      String header = "LSAT " + macaroonBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
    }
  }

  @Nested
  @DisplayName("malformed headers")
  class MalformedHeaders {

    @Test
    @DisplayName("throws MALFORMED_HEADER for null header")
    void throwsForNull() {
      assertThatThrownBy(() -> L402Credential.parse((String) null))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for empty string")
    void throwsForEmptyString() {
      assertThatThrownBy(() -> L402Credential.parse(""))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for header without colon separator")
    void throwsForNoColon() {
      String header = "L402 " + macaroonBase64 + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for wrong prefix (Bearer)")
    void throwsForWrongPrefix() {
      String header = "Bearer " + macaroonBase64 + ":" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for preimage with wrong hex length (62 chars)")
    void throwsForShortPreimageHex() {
      String shortHex = preimageHex.substring(0, 62);
      String header = "L402 " + macaroonBase64 + ":" + shortHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for preimage with invalid hex characters")
    void throwsForInvalidHexChars() {
      String invalidHex = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
      String header = "L402 " + macaroonBase64 + ":" + invalidHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for missing macaroon data")
    void throwsForMissingMacaroon() {
      String header = "L402 :" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for invalid base64 macaroon")
    void throwsForInvalidBase64() {
      String header = "L402 !!!invalid-base64!!!:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for macaroon segment containing control characters")
    void throwsForControlCharsInMacaroon() {
      String header = "L402 abc\u0001def:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for macaroon segment containing whitespace")
    void throwsForWhitespaceInMacaroon() {
      String header = "L402 abc def:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for macaroon segment containing CRLF")
    void throwsForCrlfInMacaroon() {
      String header = "L402 abc\r\ndef:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("parses preimage with uppercase hex characters")
    void parsesUppercaseHex() {
      String upperHex = preimageHex.toUpperCase();
      String header = "L402 " + macaroonBase64 + ":" + upperHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
    }

    @Test
    @DisplayName("parses preimage with mixed-case hex characters")
    void parsesMixedCaseHex() {
      // Mix case: uppercase first half, lowercase second half
      String mixedHex =
          preimageHex.substring(0, 32).toUpperCase() + preimageHex.substring(32).toLowerCase();
      String header = "L402 " + macaroonBase64 + ":" + mixedHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
    }
  }

  @Nested
  @DisplayName("multi-token headers")
  class MultiTokenHeaders {

    @Test
    @DisplayName("parses two comma-separated tokens with primary and one additional macaroon")
    void parsesTwoTokens() throws NoSuchAlgorithmException {
      String secondBase64 = mintAdditionalMacaroonBase64();
      String header = "L402 " + macaroonBase64 + "," + secondBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
      assertThat(credential.additionalMacaroons()).hasSize(1);
    }

    @Test
    @DisplayName("parses three comma-separated tokens with primary and two additional macaroons")
    void parsesThreeTokens() throws NoSuchAlgorithmException {
      String secondBase64 = mintAdditionalMacaroonBase64();
      String thirdBase64 = mintAdditionalMacaroonBase64();
      String header =
          "L402 " + macaroonBase64 + "," + secondBase64 + "," + thirdBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
      assertThat(credential.additionalMacaroons()).hasSize(2);
    }

    @Test
    @DisplayName("tokenId is always derived from the first (primary) macaroon")
    void tokenIdFromPrimaryMacaroon() throws NoSuchAlgorithmException {
      String secondBase64 = mintAdditionalMacaroonBase64();
      String header = "L402 " + macaroonBase64 + "," + secondBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      // tokenId must come from the primary macaroon, not the additional one
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for empty token between commas")
    void throwsForEmptyTokenBetweenCommas() {
      String header = "L402 " + macaroonBase64 + ",," + macaroonBase64 + ":" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);

      assertThatThrownBy(() -> L402Credential.parse(header))
          .hasMessageContaining("Empty token in multi-token header");
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for trailing comma (empty last token)")
    void throwsForTrailingComma() {
      String header = "L402 " + macaroonBase64 + ",:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for leading comma (empty first token)")
    void throwsForLeadingComma() {
      String header = "L402 ," + macaroonBase64 + ":" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for malformed base64 in additional token")
    void throwsForMalformedBase64InAdditionalToken() {
      String header = "L402 " + macaroonBase64 + ",!!!bad!!!:" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("multi-token works with LSAT prefix too")
    void multiTokenWithLsatPrefix() throws NoSuchAlgorithmException {
      String secondBase64 = mintAdditionalMacaroonBase64();
      String header = "LSAT " + macaroonBase64 + "," + secondBase64 + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.additionalMacaroons()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("token count limits")
  class TokenCountLimits {

    @Test
    @DisplayName("rejects header with 6 tokens (one over MAX_TOKENS)")
    void rejectsSixTokens() throws NoSuchAlgorithmException {
      StringBuilder tokens = new StringBuilder(macaroonBase64);
      for (int i = 1; i < 6; i++) {
        tokens.append(",").append(mintAdditionalMacaroonBase64());
      }
      String header = "L402 " + tokens + ":" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);

      assertThatThrownBy(() -> L402Credential.parse(header))
          .hasMessageContaining("Too many tokens in header: 6, max: 5");
    }

    @Test
    @DisplayName("accepts header with exactly 5 tokens (at MAX_TOKENS)")
    void acceptsFiveTokens() throws NoSuchAlgorithmException {
      StringBuilder tokens = new StringBuilder(macaroonBase64);
      for (int i = 1; i < 5; i++) {
        tokens.append(",").append(mintAdditionalMacaroonBase64());
      }
      String header = "L402 " + tokens + ":" + preimageHex;

      L402Credential credential = L402Credential.parse(header);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.additionalMacaroons()).hasSize(4);
    }
  }

  @Nested
  @DisplayName("macaroon byte size limits")
  class MacaroonByteSizeLimits {

    @Test
    @DisplayName("rejects macaroon with 4097 decoded bytes")
    void rejects4097Bytes() {
      byte[] oversized = new byte[4097];
      RANDOM.nextBytes(oversized);
      String base64 = Base64.getEncoder().encodeToString(oversized);
      String header = "L402 " + base64 + ":" + preimageHex;

      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);

      assertThatThrownBy(() -> L402Credential.parse(header))
          .hasMessageContaining("Macaroon too large: 4097 bytes, max: 4096");
    }

    @Test
    @DisplayName(
        "accepts macaroon with exactly 4096 decoded bytes (passes size check, may fail deserialization)")
    void accepts4096Bytes() {
      byte[] exactLimit = new byte[4096];
      RANDOM.nextBytes(exactLimit);
      String base64 = Base64.getEncoder().encodeToString(exactLimit);
      String header = "L402 " + base64 + ":" + preimageHex;

      // Size check should pass; deserialization will likely fail since this is random data,
      // but the error should NOT be about size
      assertThatThrownBy(() -> L402Credential.parse(header))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              e -> {
                assertThat(((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
                assertThat(e.getMessage()).doesNotContain("Macaroon too large");
              });
    }
  }

  @Nested
  @DisplayName("parse(L402HeaderComponents)")
  class ParseFromComponents {

    @Test
    @DisplayName("parses valid components into correct credential")
    void parsesValidComponents() {
      var components = new L402HeaderComponents("L402", macaroonBase64, preimageHex);

      L402Credential credential = L402Credential.parse(components);

      assertThat(credential).isNotNull();
      assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
      assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
      assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
      assertThat(credential.additionalMacaroons()).isEmpty();
    }

    @Test
    @DisplayName("throws MALFORMED_HEADER for invalid base64 in macaroonBase64")
    void throwsForInvalidBase64() {
      var components = new L402HeaderComponents("L402", "!!!not+valid+base64!!!", preimageHex);

      assertThatThrownBy(() -> L402Credential.parse(components))
          .isInstanceOf(L402Exception.class)
          .extracting(e -> ((L402Exception) e).getErrorCode())
          .isEqualTo(ErrorCode.MALFORMED_HEADER);
    }
  }
}
