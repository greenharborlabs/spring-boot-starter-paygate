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

  private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 5 + 8192 + 1 + 64;

  private static Macaroon testMacaroon(byte fill) {
    return testMacaroon(fill, null);
  }

  private static Macaroon testMacaroon(byte fill, String location) {
    byte[] tokenId = new byte[32];
    Arrays.fill(tokenId, fill);
    return MacaroonMinter.mint(
        new byte[32], new MacaroonIdentifier(0, new byte[32], tokenId), location, List.of());
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

  @Nested
  @DisplayName("Redacted rendering")
  class RedactedRendering {

    @Test
    @DisplayName("Redacts authorization header, macaroon, and preimage markers")
    void redactsAuthorizationHeaderMacaroonAndPreimageMarkers() {
      String macaroonMarker = "MACAROON-SECRET-7b308d32";
      String preimageMarker = "PREIMAGE-SECRET-2a962713";
      String rawHeader = "L402 " + macaroonMarker + ":" + preimageMarker;
      L402Metadata metadata = new L402Metadata(MAC, List.of(), rawHeader);

      String rendered = metadata.toString();

      assertThat(rendered)
          .isEqualTo(
              "L402Metadata[macaroon=<redacted>, additionalMacaroons=<redacted>, "
                  + "additionalMacaroonCount=0, rawAuthorizationHeader=<redacted>, "
                  + "rawAuthorizationHeaderLength="
                  + rawHeader.length()
                  + "]")
          .doesNotContain(macaroonMarker, preimageMarker, rawHeader);
    }

    @Test
    @DisplayName("Summarizes additional macaroons without rendering bearer material")
    void summarizesAdditionalMacaroonsWithoutRenderingBearerMaterial() {
      String primaryLocationMarker = "PRIMARY-LOCATION-SECRET-1826f271";
      String additionalLocationMarker = "ADDITIONAL-LOCATION-SECRET-cf0369ef";
      Macaroon primary = testMacaroon((byte) 0x04, primaryLocationMarker);
      Macaroon additional = testMacaroon((byte) 0x05, additionalLocationMarker);
      L402Metadata metadata = new L402Metadata(primary, List.of(additional), "credential");

      String rendered = metadata.toString();

      assertThat(rendered)
          .contains("additionalMacaroons=<redacted>", "additionalMacaroonCount=1")
          .doesNotContain(primaryLocationMarker, additionalLocationMarker)
          .doesNotContain(primary.toString(), additional.toString());
    }

    @Test
    @DisplayName("Redacts consistently at credential length boundaries")
    void redactsConsistentlyAtCredentialLengthBoundaries() {
      List<String> headers =
          List.of(
              "",
              "x",
              "BOUNDARY-SECRET-4a5f17d8".repeat(400).substring(0, MAX_AUTHORIZATION_HEADER_LENGTH));

      for (String header : headers) {
        String rendered = new L402Metadata(MAC, List.of(), header).toString();

        assertThat(rendered)
            .contains("rawAuthorizationHeader=<redacted>")
            .contains("rawAuthorizationHeaderLength=" + header.length())
            .doesNotContain("BOUNDARY-SECRET-4a5f17d8")
            .doesNotContain(header.isEmpty() ? "unused-empty-marker" : header);
      }
    }

    @Test
    @DisplayName("Nested rendering omits credential and location markers")
    void nestedRenderingOmitsCredentialAndLocationMarkers() {
      String locationMarker = "NESTED-LOCATION-SECRET-6134c250";
      String credentialMarker = "NESTED-CREDENTIAL-SECRET-a7aef154";
      L402Metadata metadata =
          new L402Metadata(testMacaroon((byte) 0x06, locationMarker), List.of(), credentialMarker);

      String nestedRendered = new RuntimeException("validation context: " + metadata).toString();

      assertThat(nestedRendered)
          .contains("L402Metadata", "<redacted>")
          .doesNotContain(locationMarker, credentialMarker);
    }

    @Test
    @DisplayName("Rendering length is bounded by safe diagnostic fields")
    void renderingLengthIsBoundedBySafeDiagnosticFields() {
      String longMarker = "BOUNDED-SECRET-9fef7ec4".repeat(400);
      L402Metadata metadata =
          new L402Metadata(testMacaroon((byte) 0x07, longMarker), List.of(), longMarker);

      assertThat(metadata.toString()).hasSizeLessThan(256).doesNotContain(longMarker);
    }
  }
}
