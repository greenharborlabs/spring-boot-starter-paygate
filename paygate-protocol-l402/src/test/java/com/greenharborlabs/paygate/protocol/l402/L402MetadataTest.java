package com.greenharborlabs.paygate.protocol.l402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class L402MetadataTest {

  private static Macaroon testMacaroon(byte fill) {
    byte[] tokenId = new byte[32];
    Arrays.fill(tokenId, fill);
    return MacaroonMinter.mint(
        new byte[32], new MacaroonIdentifier(0, new byte[32], tokenId), null, List.of());
  }

  private static final Macaroon MAC = testMacaroon((byte) 0x01);

  @Nested
  @DisplayName("Null checks")
  class NullChecks {

    @Test
    @DisplayName("Rejects null macaroon")
    void rejectsNullMacaroon() {
      assertThatThrownBy(() -> new L402Metadata(null, List.of(), "header"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("macaroon must not be null");
    }

    @Test
    @DisplayName("Rejects null additionalMacaroons")
    void rejectsNullAdditionalMacaroons() {
      assertThatThrownBy(() -> new L402Metadata(MAC, null, "header"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("additionalMacaroons must not be null");
    }

    @Test
    @DisplayName("Rejects null rawAuthorizationHeader")
    void rejectsNullRawAuthorizationHeader() {
      assertThatThrownBy(() -> new L402Metadata(MAC, List.of(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("rawAuthorizationHeader must not be null");
    }
  }

  @Nested
  @DisplayName("Defensive copy")
  class DefensiveCopy {

    @Test
    @DisplayName("additionalMacaroons is defensively copied")
    void additionalMacaroonsIsDefensivelyCopied() {
      Macaroon extraMac = testMacaroon((byte) 0x02);
      Macaroon another = testMacaroon((byte) 0x03);

      ArrayList<Macaroon> mutableList = new ArrayList<>(List.of(extraMac));
      L402Metadata metadata = new L402Metadata(MAC, mutableList, "hdr");

      mutableList.add(another);

      assertThat(metadata.additionalMacaroons()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class Accessors {

    @Test
    @DisplayName("Returns correct macaroon and rawAuthorizationHeader")
    void returnsCorrectValues() {
      String rawHeader = "L402 token:preimage";
      L402Metadata metadata = new L402Metadata(MAC, List.of(), rawHeader);

      assertThat(metadata.macaroon()).isSameAs(MAC);
      assertThat(metadata.rawAuthorizationHeader()).isEqualTo("L402 token:preimage");
    }
  }
}
