package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the 2026-10-18 security review item I-01: the V2 header accepts exactly the
 * field sequences go-macaroon accepts, an optional location followed by one identifier, so a
 * macaroon has a single accepted binary encoding. Fixture root keys are zero-filled test constants.
 */
class MacaroonSerializerHeaderStrictnessTest {

  private static final byte[] ROOT = new byte[32];
  private static final int FIELD_LOCATION = 1;
  private static final int FIELD_IDENTIFIER = 2;
  private static final int FIELD_SIGNATURE = 6;

  @Test
  @DisplayName("rejects a header with two identifiers instead of using the last one")
  void rejectsDuplicateIdentifier() {
    byte[] decoy = id(0x11);
    byte[] real = id(0x22);
    List<Caveat> caveats = List.of(new Caveat("route", "/a"));
    byte[] data =
        build(
            new byte[][] {decoy, real},
            new int[] {FIELD_IDENTIFIER, FIELD_IDENTIFIER},
            caveats,
            sign(real, caveats));

    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(data))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("out of order");
  }

  @Test
  @DisplayName("rejects a location that follows the identifier")
  void rejectsLocationAfterIdentifier() {
    byte[] real = id(0x33);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] data =
        build(
            new byte[][] {real, "loc-after-id".getBytes(StandardCharsets.UTF_8)},
            new int[] {FIELD_IDENTIFIER, FIELD_LOCATION},
            caveats,
            sign(real, caveats));

    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(data))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("out of order");
  }

  @Test
  @DisplayName("rejects unknown header field types 4, 6 and 99 instead of skipping them")
  void rejectsUnknownHeaderFieldTypes() {
    byte[] real = id(0x33);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] sig = sign(real, caveats);
    for (int unknownType : new int[] {4, FIELD_SIGNATURE, 99}) {
      byte[] data =
          build(
              new byte[][] {real, new byte[] {1, 2, 3}},
              new int[] {FIELD_IDENTIFIER, unknownType},
              caveats,
              sig);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(data))
          .as("header field type %d", unknownType)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported header field type: " + unknownType);
    }
  }

  @Test
  @DisplayName("rejects the review's [identifier, location, signature, 99] header")
  void rejectsReviewHeaderWithTrailingLocationSignatureAndUnknownFields() {
    byte[] real = id(0x33);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] data =
        build(
            new byte[][] {
              real,
              "loc-after-id".getBytes(StandardCharsets.UTF_8),
              new byte[32],
              new byte[] {1, 2, 3}
            },
            new int[] {FIELD_IDENTIFIER, FIELD_LOCATION, FIELD_SIGNATURE, 99},
            caveats,
            sign(real, caveats));

    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(data))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an unknown header field type before the identifier is rejected")
  void rejectsUnknownHeaderFieldBeforeIdentifier() {
    byte[] real = id(0x34);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] data =
        build(
            new byte[][] {new byte[] {9}, real},
            new int[] {0x7F, FIELD_IDENTIFIER},
            caveats,
            sign(real, caveats));

    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(data))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("location then identifier still parses, verifies and re-serializes identically")
  void acceptsCanonicalLocationThenIdentifier() {
    byte[] real = id(0x35);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] data =
        build(
            new byte[][] {"https://example.test".getBytes(StandardCharsets.UTF_8), real},
            new int[] {FIELD_LOCATION, FIELD_IDENTIFIER},
            caveats,
            sign(real, caveats));

    Macaroon parsed = MacaroonSerializer.deserializeV2(data);

    assertThat(parsed.location()).isEqualTo("https://example.test");
    assertThat(parsed.identifier()).isEqualTo(real);
    assertThat(MacaroonSerializer.serializeV2(parsed)).isEqualTo(data);
    MacaroonVerifier.verify(parsed, ROOT, List.of(), L402VerificationContext.builder().build());
  }

  @Test
  @DisplayName("L402Credential.parse rejects a duplicate-identifier encoding as a malformed header")
  void l402CredentialRejectsDuplicateIdentifierEncoding() {
    byte[] decoy = id(0x55);
    byte[] real = id(0x66);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] data =
        build(
            new byte[][] {decoy, real},
            new int[] {FIELD_IDENTIFIER, FIELD_IDENTIFIER},
            caveats,
            sign(real, caveats));
    String header = "L402 " + Base64.getEncoder().encodeToString(data) + ":" + "00".repeat(32);

    assertThatThrownBy(() -> L402Credential.parse(header))
        .isInstanceOfSatisfying(
            L402Exception.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER));
  }

  @Test
  @DisplayName("malformed inputs, including every truncation, are still rejected")
  void stillRejectsMalformedInputs() {
    byte[] real = id(0x77);
    List<Caveat> caveats = List.of(new Caveat("method", "GET"));
    byte[] good =
        build(new byte[][] {real}, new int[] {FIELD_IDENTIFIER}, caveats, sign(real, caveats));
    assertThat(MacaroonSerializer.deserializeV2(good).caveats()).hasSize(1);

    byte[] trailing = Arrays.copyOf(good, good.length + 1);
    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(trailing))
        .isInstanceOf(IllegalArgumentException.class);
    for (int n = 1; n < good.length; n++) {
      byte[] truncated = Arrays.copyOf(good, n);
      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(truncated))
          .as("truncated at " + n)
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] nonMinimalFieldType = {0x02, (byte) 0x82, 0x00, 0x01, 0x00};
    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(nonMinimalFieldType))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] two = Arrays.copyOf(good, good.length * 2);
    System.arraycopy(good, 0, two, good.length, good.length);
    assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(two))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Additional macaroons");
  }

  private static byte[] id(int fill) {
    byte[] identifier = new byte[66];
    Arrays.fill(identifier, (byte) fill);
    identifier[0] = 0;
    identifier[1] = 1;
    return identifier;
  }

  private static void packet(ByteArrayOutputStream out, int type, byte[] data) {
    out.writeBytes(Varint.encode(type));
    out.writeBytes(Varint.encode(data.length));
    out.writeBytes(data);
  }

  private static byte[] sign(byte[] identifier, List<Caveat> caveats) {
    byte[] sig = MacaroonCrypto.hmac(MacaroonCrypto.deriveKey(ROOT), identifier);
    for (Caveat c : caveats) {
      sig = MacaroonCrypto.hmac(sig, c.toString().getBytes(StandardCharsets.UTF_8));
    }
    return sig;
  }

  /** Builds a V2 macaroon from arbitrary header packets, then caveats, then the signature. */
  private static byte[] build(
      byte[][] headerPackets, int[] headerTypes, List<Caveat> caveats, byte[] sig) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x02);
    for (int i = 0; i < headerPackets.length; i++) {
      packet(out, headerTypes[i], headerPackets[i]);
    }
    out.write(0);
    for (Caveat c : caveats) {
      packet(out, FIELD_IDENTIFIER, c.toString().getBytes(StandardCharsets.UTF_8));
      out.write(0);
    }
    out.write(0);
    packet(out, FIELD_SIGNATURE, sig);
    return out.toByteArray();
  }
}
