package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ACINQ BOLT11 decoder boundary")
class AcinqBolt11DecoderTest {

  private static final String EXPLICIT_EXPIRY_MAINNET =
      "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh";
  private static final String DEFAULT_EXPIRY_TESTNET =
      "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrsgqdj545axuxtnfemtpwkc45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9k8";
  private static final String EXPECTED_HASH =
      "0001020304050607080900010203040506070809000102030405060708090102";

  private final AcinqBolt11Decoder decoder = new AcinqBolt11Decoder();

  @Test
  @DisplayName("decodes independently specified amount, hash, timestamp, and explicit expiry")
  void decodesExplicitExpiryVector() {
    var decoded = decoder.decode(EXPLICIT_EXPIRY_MAINNET);

    assertThat(decoded.network()).isEqualTo("mainnet");
    assertThat(decoded.amountSats()).isEqualTo(250_000);
    assertThat(decoded.paymentHash()).isEqualTo(HexFormat.of().parseHex(EXPECTED_HASH));
    assertThat(decoded.createdAt()).isEqualTo(Instant.ofEpochSecond(1_496_314_658));
    assertThat(decoded.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_496_314_718));
  }

  @Test
  @DisplayName("uses BOLT11 default expiry and exposes testnet as a family")
  void decodesDefaultExpiryTestnetVector() {
    var decoded = decoder.decode(DEFAULT_EXPIRY_TESTNET);

    assertThat(decoded.network()).isEqualTo("testnet-family");
    assertThat(decoded.amountSats()).isEqualTo(2_000_000);
    assertThat(decoded.expiresAt()).isEqualTo(decoded.createdAt().plusSeconds(3_600));
  }

  @Test
  void rejectsChecksumCorruption() {
    var corrupted =
        EXPLICIT_EXPIRY_MAINNET.substring(0, EXPLICIT_EXPIRY_MAINNET.length() - 1) + "q";

    assertThatThrownBy(() -> decoder.decode(corrupted))
        .isInstanceOf(WavelengthProtocolException.class)
        .hasMessage("Malformed Wavelength invoice");
  }

  @Test
  void rejectsMissingAmount() {
    assertThatThrownBy(
            () ->
                decoder.decode(
                    "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"))
        .isInstanceOf(WavelengthProtocolException.class)
        .hasMessage("Malformed Wavelength invoice amount");
  }

  @Test
  void rejectsNonAsciiAndWhitespace() {
    assertThatThrownBy(() -> decoder.decode(DEFAULT_EXPIRY_TESTNET + "\u2603"))
        .isInstanceOf(WavelengthProtocolException.class);
    assertThatThrownBy(() -> decoder.decode(" " + DEFAULT_EXPIRY_TESTNET))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void rejectsInvoiceLengthOverflowBeforeDecoding() {
    assertThatThrownBy(() -> decoder.decode("a".repeat(4_097)))
        .isInstanceOf(WavelengthProtocolException.class)
        .hasMessage("Malformed Wavelength invoice");
  }

  @Test
  void decodedHashIsDefensivelyCopied() {
    var decoded = decoder.decode(DEFAULT_EXPIRY_TESTNET);
    var first = decoded.paymentHash();
    first[0] ^= (byte) 0xff;

    assertThat(decoded.paymentHash()).isEqualTo(HexFormat.of().parseHex(EXPECTED_HASH));
  }
}
