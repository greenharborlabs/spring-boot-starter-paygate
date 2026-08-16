package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CanonicalRequestDigestTest {

  @Test
  void preservesRawQueryPresenceOrderDuplicatesAndEncoding() {
    byte[] body = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);

    String absent = CanonicalRequestDigest.create("POST", "/café", false, null, body);
    String empty = CanonicalRequestDigest.create("POST", "/café", true, "", body);
    String ordered = CanonicalRequestDigest.create("POST", "/café", true, "a=1&b=2", body);
    String reordered = CanonicalRequestDigest.create("POST", "/café", true, "b=2&a=1", body);
    String encoded = CanonicalRequestDigest.create("POST", "/café", true, "q=%2F", body);
    String literal = CanonicalRequestDigest.create("POST", "/café", true, "q=/", body);

    assertThat(empty).isNotEqualTo(absent);
    assertThat(reordered).isNotEqualTo(ordered);
    assertThat(encoded).isNotEqualTo(literal);
    assertThat(ordered).startsWith("sha-256=:").endsWith(":");
  }

  @Test
  void rejectsInconsistentQueryMetadataAndOversizedBody() {
    assertThatThrownBy(() -> CanonicalRequestDigest.create("GET", "/", false, "q=1", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query presence");
    assertThatThrownBy(
            () ->
                CanonicalRequestDigest.create(
                    "POST", "/", false, null, new byte[CanonicalRequestDigest.MAX_BODY_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds");
  }
}
