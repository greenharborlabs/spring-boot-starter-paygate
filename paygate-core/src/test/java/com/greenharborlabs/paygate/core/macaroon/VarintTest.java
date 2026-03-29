package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VarintTest {

  @Nested
  @DisplayName("Encode")
  class Encode {

    @Test
    @DisplayName("encodes 0 as single byte [0x00]")
    void encodesZero() {
      byte[] result = Varint.encode(0);

      assertThat(result).containsExactly(0x00);
    }

    @Test
    @DisplayName("encodes 1 as single byte [0x01]")
    void encodesOne() {
      byte[] result = Varint.encode(1);

      assertThat(result).containsExactly(0x01);
    }

    @Test
    @DisplayName("encodes 127 as single byte [0x7F] — max single-byte value")
    void encodes127() {
      byte[] result = Varint.encode(127);

      assertThat(result).containsExactly(0x7F);
    }

    @Test
    @DisplayName("encodes 128 as two bytes [0x80, 0x01] — first multi-byte value")
    void encodes128() {
      byte[] result = Varint.encode(128);

      assertThat(result).containsExactly((byte) 0x80, 0x01);
    }

    @Test
    @DisplayName("encodes 300 as two bytes [0xAC, 0x02]")
    void encodes300() {
      byte[] result = Varint.encode(300);

      assertThat(result).containsExactly((byte) 0xAC, 0x02);
    }

    @Test
    @DisplayName("encodes 16384 as three bytes [0x80, 0x80, 0x01]")
    void encodes16384() {
      byte[] result = Varint.encode(16384);

      assertThat(result).containsExactly((byte) 0x80, (byte) 0x80, 0x01);
    }

    @Test
    @DisplayName("encodes large value requiring many bytes")
    void encodesLargeValue() {
      // 2^63 - 1 (Long.MAX_VALUE) should produce 9 bytes in unsigned LEB128
      byte[] result = Varint.encode(Long.MAX_VALUE);

      assertThat(result).hasSize(9);
      // All bytes except the last should have continuation bit set
      for (int i = 0; i < result.length - 1; i++) {
        assertThat(result[i] & 0x80)
            .as("byte %d should have continuation bit set", i)
            .isEqualTo(0x80);
      }
      // Last byte should NOT have continuation bit set
      assertThat(result[result.length - 1] & 0x80).isZero();
    }

    @Test
    @DisplayName("rejects negative value")
    void rejectsNegative() {
      assertThatThrownBy(() -> Varint.encode(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects Long.MIN_VALUE")
    void rejectsLongMinValue() {
      assertThatThrownBy(() -> Varint.encode(Long.MIN_VALUE))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Decode")
  class Decode {

    @Test
    @DisplayName("decodes [0x00] as 0")
    void decodesZero() {
      var result = Varint.decode(new byte[] {0x00}, 0);

      assertThat(result.value()).isZero();
      assertThat(result.bytesRead()).isEqualTo(1);
    }

    @Test
    @DisplayName("decodes [0x01] as 1")
    void decodesOne() {
      var result = Varint.decode(new byte[] {0x01}, 0);

      assertThat(result.value()).isEqualTo(1);
      assertThat(result.bytesRead()).isEqualTo(1);
    }

    @Test
    @DisplayName("decodes [0x7F] as 127")
    void decodes127() {
      var result = Varint.decode(new byte[] {0x7F}, 0);

      assertThat(result.value()).isEqualTo(127);
      assertThat(result.bytesRead()).isEqualTo(1);
    }

    @Test
    @DisplayName("decodes [0x80, 0x01] as 128")
    void decodes128() {
      var result = Varint.decode(new byte[] {(byte) 0x80, 0x01}, 0);

      assertThat(result.value()).isEqualTo(128);
      assertThat(result.bytesRead()).isEqualTo(2);
    }

    @Test
    @DisplayName("decodes [0xAC, 0x02] as 300")
    void decodes300() {
      var result = Varint.decode(new byte[] {(byte) 0xAC, 0x02}, 0);

      assertThat(result.value()).isEqualTo(300);
      assertThat(result.bytesRead()).isEqualTo(2);
    }

    @Test
    @DisplayName("decodes [0x80, 0x80, 0x01] as 16384")
    void decodes16384() {
      var result = Varint.decode(new byte[] {(byte) 0x80, (byte) 0x80, 0x01}, 0);

      assertThat(result.value()).isEqualTo(16384);
      assertThat(result.bytesRead()).isEqualTo(3);
    }

    @Test
    @DisplayName("decodes from non-zero offset")
    void decodesFromOffset() {
      // Prefix bytes [0xFF, 0xFF] then varint for 300: [0xAC, 0x02]
      byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xAC, 0x02};

      var result = Varint.decode(data, 2);

      assertThat(result.value()).isEqualTo(300);
      assertThat(result.bytesRead()).isEqualTo(2);
    }

    @Test
    @DisplayName("ignores trailing bytes after varint")
    void ignoresTrailingBytes() {
      // Varint for 1: [0x01], then trailing garbage
      byte[] data = {0x01, (byte) 0xFF, (byte) 0xFF};

      var result = Varint.decode(data, 0);

      assertThat(result.value()).isEqualTo(1);
      assertThat(result.bytesRead()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Round-trip encode then decode")
  class RoundTrip {

    @ParameterizedTest(name = "round-trips value {0}")
    @CsvSource({
      "0",
      "1",
      "127",
      "128",
      "255",
      "256",
      "300",
      "16383",
      "16384",
      "65535",
      "2097152",
      "268435455",
      "2147483647",
      "4294967295",
      "9223372036854775807"
    })
    @DisplayName("encode then decode returns original value")
    void roundTrip(long original) {
      byte[] encoded = Varint.encode(original);
      var decoded = Varint.decode(encoded, 0);

      assertThat(decoded.value()).isEqualTo(original);
      assertThat(decoded.bytesRead()).isEqualTo(encoded.length);
    }
  }

  @Nested
  @DisplayName("Decode error handling")
  class DecodeErrors {

    @Test
    @DisplayName("rejects null data array")
    void rejectsNullData() {
      assertThatThrownBy(() -> Varint.decode(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects negative offset")
    void rejectsNegativeOffset() {
      assertThatThrownBy(() -> Varint.decode(new byte[] {0x01}, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects offset beyond array bounds")
    void rejectsOffsetBeyondBounds() {
      assertThatThrownBy(() -> Varint.decode(new byte[] {0x01}, 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects empty data array")
    void rejectsEmptyData() {
      assertThatThrownBy(() -> Varint.decode(new byte[] {}, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects truncated varint — continuation bit set but no more bytes")
    void rejectsTruncatedVarint() {
      // 0x80 has continuation bit set, but there is no following byte
      assertThatThrownBy(() -> Varint.decode(new byte[] {(byte) 0x80}, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
