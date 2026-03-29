package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CaveatTest {

  @Nested
  @DisplayName("Construction")
  class Construction {

    @Test
    @DisplayName("creates caveat with valid key and value")
    void validKeyAndValue() {
      var caveat = new Caveat("service", "my-api");

      assertThat(caveat.key()).isEqualTo("service");
      assertThat(caveat.value()).isEqualTo("my-api");
    }

    @Test
    @DisplayName("creates caveat with single-character key and value")
    void singleCharKeyAndValue() {
      var caveat = new Caveat("k", "v");

      assertThat(caveat.key()).isEqualTo("k");
      assertThat(caveat.value()).isEqualTo("v");
    }
  }

  @Nested
  @DisplayName("Validation rejects invalid inputs")
  class Validation {

    @Test
    @DisplayName("rejects null key")
    void nullKey() {
      assertThatThrownBy(() -> new Caveat(null, "value"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects empty key")
    void emptyKey() {
      assertThatThrownBy(() -> new Caveat("", "value"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects blank key")
    void blankKey() {
      assertThatThrownBy(() -> new Caveat("   ", "value"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects null value")
    void nullValue() {
      assertThatThrownBy(() -> new Caveat("key", null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects empty value")
    void emptyValue() {
      assertThatThrownBy(() -> new Caveat("key", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects blank value")
    void blankValue() {
      assertThatThrownBy(() -> new Caveat("key", "   "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("equals and hashCode")
  class EqualsAndHashCode {

    @Test
    @DisplayName("equal caveats are equal")
    void equalCaveats() {
      var a = new Caveat("service", "my-api");
      var b = new Caveat("service", "my-api");

      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("different keys are not equal")
    void differentKeys() {
      var a = new Caveat("service", "my-api");
      var b = new Caveat("tier", "my-api");

      assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("different values are not equal")
    void differentValues() {
      var a = new Caveat("service", "my-api");
      var b = new Caveat("service", "other-api");

      assertThat(a).isNotEqualTo(b);
    }
  }

  @Nested
  @DisplayName("toString")
  class ToString {

    @Test
    @DisplayName("returns key=value format")
    void keyEqualsValue() {
      var caveat = new Caveat("service", "my-api");

      assertThat(caveat.toString()).isEqualTo("service=my-api");
    }

    @Test
    @DisplayName("handles value containing equals sign")
    void valueWithEquals() {
      var caveat = new Caveat("condition", "amount=100");

      assertThat(caveat.toString()).isEqualTo("condition=amount=100");
    }
  }
}
