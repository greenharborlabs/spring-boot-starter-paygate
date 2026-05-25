package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("L402Challenge")
class L402ChallengeTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern HEADER_PATTERN =
      Pattern.compile(
          "L402 version=\"0\", token=\"([^\"]+)\", macaroon=\"([^\"]+)\", invoice=\"([^\"]+)\"");

  private byte[] rootKey;
  private byte[] paymentHash;
  private byte[] tokenId;

  @BeforeEach
  void setUp() {
    rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);
    paymentHash = new byte[32];
    RANDOM.nextBytes(paymentHash);
    tokenId = new byte[32];
    RANDOM.nextBytes(tokenId);
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader serializes full macaroon, not just identifier")
  void headerContainsFullSerializedMacaroon() {
    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    List<Caveat> caveats = List.of(new Caveat("service", "example"));
    Macaroon macaroon = MacaroonMinter.mint(rootKey, id, null, caveats);
    String invoice = "lnbc100n1p0test";

    L402Challenge challenge = new L402Challenge(macaroon, invoice, 100, "test");
    String header = challenge.toWwwAuthenticateHeader();

    Matcher matcher = HEADER_PATTERN.matcher(header);
    assertThat(matcher.matches()).as("Header must match L402 format").isTrue();

    String tokenBase64 = matcher.group(1);
    String macaroonBase64 = matcher.group(2);
    assertThat(tokenBase64)
        .as("token and macaroon params must carry the same value")
        .isEqualTo(macaroonBase64);

    byte[] decoded = Base64.getDecoder().decode(tokenBase64);
    Macaroon deserialized = MacaroonSerializer.deserializeV2(decoded);

    assertThat(deserialized.identifier()).isEqualTo(macaroon.identifier());
    assertThat(deserialized.signature()).isEqualTo(macaroon.signature());
    assertThat(deserialized.caveats()).isEqualTo(macaroon.caveats());

    assertThat(matcher.group(3)).isEqualTo(invoice);
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader round-trips macaroon without caveats")
  void headerRoundTripNoCaveats() {
    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    Macaroon macaroon = MacaroonMinter.mint(rootKey, id, "https://example.com", List.of());
    String invoice = "lnbc200n1p0test";

    L402Challenge challenge = new L402Challenge(macaroon, invoice, 200, "no caveats");
    String header = challenge.toWwwAuthenticateHeader();

    Matcher matcher = HEADER_PATTERN.matcher(header);
    assertThat(matcher.matches()).isTrue();

    assertThat(matcher.group(1))
        .as("token and macaroon params must match")
        .isEqualTo(matcher.group(2));

    byte[] decoded = Base64.getDecoder().decode(matcher.group(1));
    Macaroon deserialized = MacaroonSerializer.deserializeV2(decoded);

    assertThat(deserialized.identifier()).isEqualTo(macaroon.identifier());
    assertThat(deserialized.signature()).isEqualTo(macaroon.signature());
    assertThat(deserialized.location()).isEqualTo(macaroon.location());
    assertThat(deserialized.caveats()).isEmpty();
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing double quote")
  void headerRejectsBolt11WithDoubleQuote() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\"injected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0x22");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing CR")
  void headerRejectsBolt11WithCR() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\rinjected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0xd");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing LF")
  void headerRejectsBolt11WithLF() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\ninjected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0xa");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing NUL (0x00)")
  void headerRejectsBolt11WithNul() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\0injected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0x0");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing TAB (0x09)")
  void headerRejectsBolt11WithTab() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\tinjected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0x9");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing BEL (0x07)")
  void headerRejectsBolt11WithBel() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\u0007injected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0x7");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader rejects bolt11 containing DEL (0x7F)")
  void headerRejectsBolt11WithDel() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0\u007Finjected", 100, "test");

    assertThatThrownBy(challenge::toWwwAuthenticateHeader)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("illegal character")
        .hasMessageContaining("0x7f");
  }

  @Test
  @DisplayName("toWwwAuthenticateHeader accepts valid bolt11 with only printable ASCII")
  void headerAcceptsValidBolt11() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc100n1p0valid", 100, "test");

    // Should not throw
    String header = challenge.toWwwAuthenticateHeader();
    assertThat(header).contains("invoice=\"lnbc100n1p0valid\"");
  }

  @Test
  @DisplayName("toJsonBody produces valid JSON using JsonEscaper")
  void toJsonBodyProducesValidJson() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge =
        new L402Challenge(macaroon, "lnbc500n1p0test", 500, "A \"special\" service");
    String json = challenge.toJsonBody();

    assertThat(json).contains("\"code\":402");
    assertThat(json).contains("\"price_sats\":500");
    assertThat(json).contains("\"invoice\":\"lnbc500n1p0test\"");
    // Description with quotes should be escaped
    assertThat(json).contains("A \\\"special\\\" service");
  }

  @Test
  @DisplayName("toJsonBody handles null description")
  void toJsonBodyHandlesNullDescription() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc1test", 1, null);
    String json = challenge.toJsonBody();

    assertThat(json).contains("\"description\":null");
  }

  @Test
  @DisplayName("toJsonBody escapes special characters in bolt11 invoice")
  void toJsonBodyEscapesBolt11() {
    Macaroon macaroon = mintTestMacaroon();
    L402Challenge challenge = new L402Challenge(macaroon, "lnbc1\"test", 1, "esc test");
    String json = challenge.toJsonBody();

    assertThat(json).contains("invoice\":\"lnbc1\\\"test\"");
  }

  private Macaroon mintTestMacaroon() {
    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    return MacaroonMinter.mint(rootKey, id, null, List.of());
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("rejects null macaroon")
    void rejectsNullMacaroon() {
      assertThatThrownBy(() -> new L402Challenge(null, "lnbc1test", 1, "test"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("macaroon must not be null");
    }

    @Test
    @DisplayName("rejects null bolt11Invoice")
    void rejectsNullBolt11() {
      assertThatThrownBy(() -> new L402Challenge(mintTestMacaroon(), null, 1, "test"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("bolt11Invoice must not be null");
    }

    @Test
    @DisplayName("rejects empty bolt11Invoice")
    void rejectsEmptyBolt11() {
      assertThatThrownBy(() -> new L402Challenge(mintTestMacaroon(), "", 1, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("bolt11Invoice must not be empty");
    }

    @Test
    @DisplayName("rejects priceSats of zero")
    void rejectsZeroPrice() {
      assertThatThrownBy(() -> new L402Challenge(mintTestMacaroon(), "lnbc1test", 0, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("priceSats must be positive");
    }

    @Test
    @DisplayName("rejects negative priceSats")
    void rejectsNegativePrice() {
      assertThatThrownBy(() -> new L402Challenge(mintTestMacaroon(), "lnbc1test", -5, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("priceSats must be positive");
    }

    @Test
    @DisplayName("accepts valid priceSats of one")
    void acceptsMinimumPrice() {
      L402Challenge challenge = new L402Challenge(mintTestMacaroon(), "lnbc1test", 1, "test");
      assertThat(challenge.priceSats()).isEqualTo(1);
    }

    @Test
    @DisplayName("accepts large priceSats")
    void acceptsLargePrice() {
      L402Challenge challenge =
          new L402Challenge(mintTestMacaroon(), "lnbc1test", 99_999_999, "test");
      assertThat(challenge.priceSats()).isEqualTo(99_999_999);
    }

    @Test
    @DisplayName("toString includes price and description")
    void toStringIncludesFields() {
      L402Challenge challenge = new L402Challenge(mintTestMacaroon(), "lnbc1test", 42, "premium");
      String s = challenge.toString();

      assertThat(s).startsWith("L402Challenge[");
      assertThat(s).contains("priceSats=42");
      assertThat(s).contains("description=premium");
    }

    @Test
    @DisplayName("toString uses null for description when null")
    void toStringNullDescription() {
      L402Challenge challenge = new L402Challenge(mintTestMacaroon(), "lnbc1test", 1, null);
      assertThat(challenge.toString()).contains("description=null");
    }
  }

  @Nested
  @DisplayName("sanitizeBolt11ForHeader")
  class SanitizeBolt11 {

    @Test
    @DisplayName("returns empty string for null input")
    void returnsEmptyForNull() {
      assertThat(L402Challenge.sanitizeBolt11ForHeader(null)).isEmpty();
    }

    @Test
    @DisplayName("returns valid bolt11 unchanged")
    void returnsValidBolt11Unchanged() {
      String invoice = "lnbc100n1p0validinvoice";
      assertThat(L402Challenge.sanitizeBolt11ForHeader(invoice)).isEqualTo(invoice);
    }

    @Test
    @DisplayName("rejects bolt11 with trailing newline")
    void rejectsTrailingNewline() {
      assertThatThrownBy(() -> L402Challenge.sanitizeBolt11ForHeader("lnbc1\n"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("0xa");
    }

    @Test
    @DisplayName("rejects bolt11 with embedded double quote")
    void rejectsEmbeddedQuote() {
      assertThatThrownBy(() -> L402Challenge.sanitizeBolt11ForHeader("lnbc\""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("0x22");
    }

    @Test
    @DisplayName("rejects bolt11 with DEL character")
    void rejectsDel() {
      assertThatThrownBy(() -> L402Challenge.sanitizeBolt11ForHeader("lnbc\u007F"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("0x7f");
    }
  }

  @Test
  @DisplayName("serialized macaroon is longer than just the 66-byte identifier")
  void serializedMacaroonIsLongerThanIdentifier() {
    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    Macaroon macaroon = MacaroonMinter.mint(rootKey, id, null, List.of());

    L402Challenge challenge = new L402Challenge(macaroon, "lnbc1test", 1, "size check");
    String header = challenge.toWwwAuthenticateHeader();

    Matcher matcher = HEADER_PATTERN.matcher(header);
    assertThat(matcher.matches()).isTrue();

    assertThat(matcher.group(1))
        .as("token and macaroon params must match")
        .isEqualTo(matcher.group(2));

    byte[] decoded = Base64.getDecoder().decode(matcher.group(1));
    // Full V2 serialization includes version byte, field tags, lengths, signature
    // so it must be longer than the raw 66-byte identifier
    assertThat(decoded.length).isGreaterThan(66);
  }
}
