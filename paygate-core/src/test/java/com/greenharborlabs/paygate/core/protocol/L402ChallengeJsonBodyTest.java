package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class L402ChallengeJsonBodyTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Macaroon createTestMacaroon() {
    byte[] rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);
    byte[] paymentHash = new byte[32];
    RANDOM.nextBytes(paymentHash);
    byte[] tokenId = new byte[32];
    RANDOM.nextBytes(tokenId);
    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    return MacaroonMinter.mint(rootKey, id, null, List.of());
  }

  @Test
  void toJsonBodyContainsRequiredFields() {
    Macaroon macaroon = createTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1test", 100, "test description");

    String json = challenge.toJsonBody();

    assertThat(json).contains("\"code\":402");
    assertThat(json).contains("\"message\":\"Payment required\"");
    assertThat(json).contains("\"price_sats\":100");
    assertThat(json).contains("\"description\":\"test description\"");
    assertThat(json).contains("\"invoice\":\"lnbc100n1test\"");
  }

  @Test
  void toJsonBodyWithNullDescription() {
    Macaroon macaroon = createTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1test", 100, null);

    String json = challenge.toJsonBody();

    assertThat(json).contains("\"description\":null");
  }

  @Test
  void toJsonBodyEscapesSpecialCharsInDescription() {
    Macaroon macaroon = createTestMacaroon();
    L402Challenge challenge =
        new L402Challenge(macaroon, "lnbc100n1test", 100, "say \"hello\"\nnewline");

    String json = challenge.toJsonBody();

    assertThat(json).contains("say \\\"hello\\\"\\nnewline");
  }

  @Test
  void toStringContainsPriceAndDescription() {
    Macaroon macaroon = createTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1test", 42, "my desc");

    String result = challenge.toString();

    assertThat(result).contains("priceSats=42");
    assertThat(result).contains("description=my desc");
  }

  @Test
  void rejectsEmptyInvoice() {
    Macaroon macaroon = createTestMacaroon();
    assertThatThrownBy(() -> new L402Challenge(macaroon, "", 100, "desc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be empty");
  }

  @Test
  void rejectsNonPositivePrice() {
    Macaroon macaroon = createTestMacaroon();
    assertThatThrownBy(() -> new L402Challenge(macaroon, "lnbc100n1test", 0, "desc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  void toJsonBodyEscapesControlCharsInDescription() {
    Macaroon macaroon = createTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1test", 50, "ctrl\u0001char");

    String json = challenge.toJsonBody();

    assertThat(json).contains("ctrl\\u0001char");
    assertThat(json).contains("\"price_sats\":50");
  }
}
