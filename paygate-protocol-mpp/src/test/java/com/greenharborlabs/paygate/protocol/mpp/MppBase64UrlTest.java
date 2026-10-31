package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MppBase64UrlTest {

  @ParameterizedTest
  @ValueSource(strings = {"", "YQ", "YWI", "YWJj", "-_-_"})
  void decodesCanonicalUnpaddedValues(String encoded) {
    assertThat(MppBase64Url.decodeCanonical(encoded)).isNotNull();
  }

  @Test
  void decodesToTheEncodedBytes() {
    assertThat(MppBase64Url.decodeCanonical("YWI"))
        .isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "YQ==", // padded
        "YWI=", // padded
        "YR", // nonzero unused trailing bits, decodes like "YQ"
        "YWJ", // nonzero unused trailing bits, decodes like "YWI"
        "a+b/", // standard alphabet
        "YWJjZ", // impossible length
        "YW I"
      })
  void rejectsNoncanonicalValues(String encoded) {
    assertThatThrownBy(() -> MppBase64Url.decodeCanonical(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> MppBase64Url.decodeCanonical(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
