package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Macaroon V2 binary format serialization and deserialization.
 *
 * <p>V2 binary layout:
 *
 * <pre>
 *   0x02                          # Version discriminator
 *   [location packet]?            # Optional (fieldType=1)
 *   identifier packet             # Required (fieldType=2)
 *   0x00                          # EOS (end of header section)
 *   (                             # Per caveat:
 *     identifier packet           #   fieldType=2, caveat "key=value" as UTF-8
 *     0x00                        #   EOS
 *   )*
 *   0x00                          # EOS (end of all caveats)
 *   signature packet              # fieldType=6, 32-byte signature
 * </pre>
 *
 * Packet structure: fieldType(varint) + payloadLen(varint) + data[payloadLen] Field type constants:
 * 0=EOS, 1=Location, 2=Identifier, 4=VerificationId, 6=Signature
 */
class MacaroonSerializerTest {

  // Field type constants matching V2 format
  private static final int FIELD_EOS = 0;
  private static final int FIELD_LOCATION = 1;
  private static final int FIELD_IDENTIFIER = 2;
  private static final int FIELD_VERIFICATION_ID = 4;
  private static final int FIELD_SIGNATURE = 6;

  private static final int VERSION_BYTE = 0x02;

  /** Builds a 66-byte identifier filled with a repeating byte value. */
  private static byte[] identifierFilledWith(byte fill) {
    byte[] id = new byte[Macaroon.IDENTIFIER_LENGTH];
    Arrays.fill(id, fill);
    return id;
  }

  /** Builds a 32-byte signature filled with a repeating byte value. */
  private static byte[] signatureFilledWith(byte fill) {
    byte[] sig = new byte[Macaroon.SIGNATURE_LENGTH];
    Arrays.fill(sig, fill);
    return sig;
  }

  /** Builds a 66-byte identifier with sequential byte values (0x00..0x41). */
  private static byte[] sequentialIdentifier() {
    byte[] id = new byte[Macaroon.IDENTIFIER_LENGTH];
    for (int i = 0; i < id.length; i++) {
      id[i] = (byte) (i & 0xFF);
    }
    return id;
  }

  /** Builds a 32-byte signature with sequential byte values starting at 0xE0. */
  private static byte[] sequentialSignature() {
    byte[] sig = new byte[Macaroon.SIGNATURE_LENGTH];
    for (int i = 0; i < sig.length; i++) {
      sig[i] = (byte) ((0xE0 + i) & 0xFF);
    }
    return sig;
  }

  /**
   * Constructs the expected V2 binary encoding for a packet: fieldType(varint) + payloadLen(varint)
   * + data.
   */
  private static byte[] packet(int fieldType, byte[] data) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(Varint.encode(fieldType));
    out.write(Varint.encode(data.length));
    out.write(data);
    return out.toByteArray();
  }

  /** Constructs the full expected V2 byte sequence for a macaroon. */
  private static byte[] buildExpectedV2Bytes(
      String location, byte[] identifier, List<Caveat> caveats, byte[] signature)
      throws IOException {

    var out = new ByteArrayOutputStream();

    // Version byte
    out.write(VERSION_BYTE);

    // Optional location packet (field type 1)
    if (location != null) {
      out.write(packet(FIELD_LOCATION, location.getBytes(StandardCharsets.UTF_8)));
    }

    // Identifier packet (field type 2)
    out.write(packet(FIELD_IDENTIFIER, identifier));

    // EOS — end of header section
    out.write(FIELD_EOS);

    // Caveat sections
    for (Caveat caveat : caveats) {
      byte[] caveatBytes = caveat.toString().getBytes(StandardCharsets.UTF_8);
      out.write(packet(FIELD_IDENTIFIER, caveatBytes));
      out.write(FIELD_EOS);
    }

    // EOS — end of all caveats
    out.write(FIELD_EOS);

    // Signature packet (field type 6)
    out.write(packet(FIELD_SIGNATURE, signature));

    return out.toByteArray();
  }

  /** Constructs V2 bytes whose caveat sections are supplied as their exact wire packets. */
  private static byte[] buildV2BytesWithRawCaveatSections(
      byte[] identifier, List<byte[]> caveatSections, byte[] signature) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(VERSION_BYTE);
    out.write(packet(FIELD_IDENTIFIER, identifier));
    out.write(FIELD_EOS);
    for (byte[] caveatSection : caveatSections) {
      out.write(caveatSection);
      out.write(FIELD_EOS);
    }
    out.write(FIELD_EOS);
    out.write(packet(FIELD_SIGNATURE, signature));
    return out.toByteArray();
  }

  @Nested
  @DisplayName("Serialize V2")
  class SerializeV2 {

    @Test
    @DisplayName("serializes macaroon with no location and no caveats to exact V2 bytes")
    void serializesMinimalMacaroon() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x00);
      byte[] signature = signatureFilledWith((byte) 0xAA);
      var macaroon = new Macaroon(identifier, null, List.of(), signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      byte[] expected = buildExpectedV2Bytes(null, identifier, List.of(), signature);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("serializes macaroon with location — location packet appears before identifier")
    void serializesWithLocation() throws IOException {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      String location = "https://api.example.com";
      var macaroon = new Macaroon(identifier, location, List.of(), signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      byte[] expected = buildExpectedV2Bytes(location, identifier, List.of(), signature);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName(
        "location packet precedes identifier packet in output (ascending field type order)")
    void locationPrecedesIdentifier() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x11);
      byte[] signature = signatureFilledWith((byte) 0x22);
      var macaroon = new Macaroon(identifier, "https://example.com", List.of(), signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      // After version byte (0x02), the next varint field type should be 1 (location)
      assertThat(result[0]).isEqualTo((byte) VERSION_BYTE);
      var firstFieldType = Varint.decode(result, 1);
      assertThat(firstFieldType.value())
          .as("first field after version should be location (type=1)")
          .isEqualTo(FIELD_LOCATION);
    }

    @Test
    @DisplayName("serializes macaroon with one caveat — caveat section present")
    void serializesWithOneCaveat() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x33);
      byte[] signature = signatureFilledWith((byte) 0x44);
      var caveats = List.of(new Caveat("service", "my-api"));
      var macaroon = new Macaroon(identifier, null, caveats, signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      byte[] expected = buildExpectedV2Bytes(null, identifier, caveats, signature);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("serializes macaroon with multiple caveats — ordering preserved")
    void serializesWithMultipleCaveats() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x55);
      byte[] signature = signatureFilledWith((byte) 0x66);
      var caveats =
          List.of(
              new Caveat("service", "my-api"),
              new Caveat("expires", "2026-12-31T23:59:59Z"),
              new Caveat("tier", "premium"));
      var macaroon = new Macaroon(identifier, null, caveats, signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      byte[] expected = buildExpectedV2Bytes(null, identifier, caveats, signature);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("serializes macaroon with location and multiple caveats — full structure")
    void serializesFullMacaroon() throws IOException {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      String location = "https://api.greenharborlabs.com/v1";
      var caveats =
          List.of(
              new Caveat("service", "data-feed"), new Caveat("expires", "2026-06-30T00:00:00Z"));
      var macaroon = new Macaroon(identifier, location, caveats, signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      byte[] expected = buildExpectedV2Bytes(location, identifier, caveats, signature);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("first byte of serialized output is always 0x02 (V2 version)")
    void startsWithVersionByte() {
      byte[] identifier = identifierFilledWith((byte) 0x00);
      byte[] signature = signatureFilledWith((byte) 0x00);
      var macaroon = new Macaroon(identifier, null, List.of(), signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      assertThat(result[0]).isEqualTo((byte) VERSION_BYTE);
    }

    @Test
    @DisplayName("signature packet uses field type 6 and is the last field in the output")
    void signatureIsLastField() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x00);
      byte[] signature = signatureFilledWith((byte) 0xFF);
      var macaroon = new Macaroon(identifier, null, List.of(), signature);

      byte[] result = MacaroonSerializer.serializeV2(macaroon);

      // The last 32 bytes should be the signature data
      byte[] trailingSig =
          Arrays.copyOfRange(result, result.length - Macaroon.SIGNATURE_LENGTH, result.length);
      assertThat(trailingSig).isEqualTo(signature);

      // Just before the signature data: varint(6) varint(32)
      // varint(6) = 0x06 (single byte), varint(32) = 0x20 (single byte)
      int sigPacketStart = result.length - Macaroon.SIGNATURE_LENGTH - 2;
      assertThat(result[sigPacketStart]).isEqualTo((byte) 0x06);
      assertThat(result[sigPacketStart + 1]).isEqualTo((byte) 0x20);
    }
  }

  @Nested
  @DisplayName("Deserialize V2")
  class DeserializeV2 {

    @Test
    @DisplayName("deserializes valid V2 bytes — no location, no caveats")
    void deserializesMinimal() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0xAA);
      byte[] signature = signatureFilledWith((byte) 0xBB);
      byte[] v2Bytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);

      Macaroon result = MacaroonSerializer.deserializeV2(v2Bytes);

      assertThat(result.identifier()).isEqualTo(identifier);
      assertThat(result.location()).isNull();
      assertThat(result.caveats()).isEmpty();
      assertThat(result.signature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("deserializes valid V2 bytes — with location")
    void deserializesWithLocation() throws IOException {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      String location = "https://api.example.com";
      byte[] v2Bytes = buildExpectedV2Bytes(location, identifier, List.of(), signature);

      Macaroon result = MacaroonSerializer.deserializeV2(v2Bytes);

      assertThat(result.identifier()).isEqualTo(identifier);
      assertThat(result.location()).isEqualTo(location);
      assertThat(result.caveats()).isEmpty();
      assertThat(result.signature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("deserializes valid V2 bytes — with one caveat")
    void deserializesWithOneCaveat() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      var caveats = List.of(new Caveat("service", "payments"));
      byte[] v2Bytes = buildExpectedV2Bytes(null, identifier, caveats, signature);

      Macaroon result = MacaroonSerializer.deserializeV2(v2Bytes);

      assertThat(result.identifier()).isEqualTo(identifier);
      assertThat(result.location()).isNull();
      assertThat(result.caveats()).hasSize(1);
      assertThat(result.caveats().getFirst().key()).isEqualTo("service");
      assertThat(result.caveats().getFirst().value()).isEqualTo("payments");
      assertThat(result.signature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("deserializes valid V2 bytes — with multiple caveats preserving order")
    void deserializesWithMultipleCaveats() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x10);
      byte[] signature = signatureFilledWith((byte) 0x20);
      var caveats =
          List.of(
              new Caveat("service", "my-api"),
              new Caveat("expires", "2026-12-31T23:59:59Z"),
              new Caveat("tier", "premium"));
      byte[] v2Bytes = buildExpectedV2Bytes(null, identifier, caveats, signature);

      Macaroon result = MacaroonSerializer.deserializeV2(v2Bytes);

      assertThat(result.caveats()).hasSize(3);
      assertThat(result.caveats().get(0).toString()).isEqualTo("service=my-api");
      assertThat(result.caveats().get(1).toString()).isEqualTo("expires=2026-12-31T23:59:59Z");
      assertThat(result.caveats().get(2).toString()).isEqualTo("tier=premium");
    }

    @Test
    @DisplayName("deserializes valid V2 bytes — with location and caveats")
    void deserializesFullMacaroon() throws IOException {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      String location = "https://api.greenharborlabs.com";
      var caveats =
          List.of(
              new Caveat("service", "data-feed"), new Caveat("expires", "2026-06-30T00:00:00Z"));
      byte[] v2Bytes = buildExpectedV2Bytes(location, identifier, caveats, signature);

      Macaroon result = MacaroonSerializer.deserializeV2(v2Bytes);

      assertThat(result.identifier()).isEqualTo(identifier);
      assertThat(result.location()).isEqualTo(location);
      assertThat(result.caveats()).hasSize(2);
      assertThat(result.caveats().get(0).key()).isEqualTo("service");
      assertThat(result.caveats().get(1).key()).isEqualTo("expires");
      assertThat(result.signature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("rejects bytes with wrong version byte")
    void rejectsWrongVersion() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x00);
      byte[] signature = signatureFilledWith((byte) 0x00);
      byte[] v2Bytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);

      // Corrupt the version byte
      v2Bytes[0] = 0x01;

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(v2Bytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("version");
    }

    @Test
    @DisplayName("rejects empty byte array")
    void rejectsEmptyInput() {
      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(new byte[0]))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects null byte array")
    void rejectsNullInput() {
      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects truncated bytes — missing signature")
    void rejectsTruncatedMissingSignature() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x00);
      byte[] signature = signatureFilledWith((byte) 0x00);
      byte[] v2Bytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);

      // Truncate to remove signature packet entirely
      // Layout: version(1) + identifier packet + EOS(1) + EOS(1) + signature packet
      // We cut off somewhere before the signature
      byte[] truncated = Arrays.copyOf(v2Bytes, v2Bytes.length - Macaroon.SIGNATURE_LENGTH - 2);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(truncated))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects truncated bytes — cut mid-identifier")
    void rejectsTruncatedMidIdentifier() {
      // Just version byte + field type for identifier + partial length
      byte[] truncated = new byte[] {0x02, 0x02, 0x42};

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(truncated))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects header length varint larger than data array")
    void rejectsOversizedHeaderLength() {
      // Build: version(0x02) + fieldType=2(identifier) + length varint encoding 999999
      var buf = new ByteArrayOutputStream();
      buf.write(0x02); // version
      buf.writeBytes(Varint.encode(FIELD_IDENTIFIER)); // field type = 2
      buf.writeBytes(Varint.encode(999_999)); // length far exceeding data size
      byte[] malicious = buf.toByteArray();

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(malicious))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid packet length");
    }

    @Test
    @DisplayName("rejects header length varint with very large value near Long.MAX_VALUE")
    void rejectsVeryLargeHeaderLength() {
      // Encode a value close to Long.MAX_VALUE as the length varint
      var buf = new ByteArrayOutputStream();
      buf.write(0x02); // version
      buf.writeBytes(Varint.encode(FIELD_IDENTIFIER)); // field type = 2
      buf.writeBytes(Varint.encode(Long.MAX_VALUE)); // enormous length
      byte[] malicious = buf.toByteArray();

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(malicious))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid packet length");
    }

    @Test
    @DisplayName("rejects caveat section with oversized length varint")
    void rejectsOversizedCaveatLength() throws IOException {
      // Build a valid header, then inject a caveat with an oversized length
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      byte[] validBytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);

      // validBytes layout: version + id_packet + EOS + EOS + sig_packet
      // We need to insert a malicious caveat section between the two EOS bytes.
      // Find the first EOS after the identifier (end of header section).
      // The second EOS is at the next position (end of caveats section, since there are none).
      // We replace the second EOS + signature with: malicious caveat + EOS + signature.
      var buf = new ByteArrayOutputStream();
      // Copy up to and including first EOS (header section)
      int headerEnd = findSecondEosPosition(validBytes);
      buf.write(validBytes, 0, headerEnd);
      // Inject malicious caveat: fieldType=2, length=999999
      buf.writeBytes(Varint.encode(FIELD_IDENTIFIER));
      buf.writeBytes(Varint.encode(999_999));
      byte[] malicious = buf.toByteArray();

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(malicious))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid packet length");
    }

    @Test
    @DisplayName("rejects signature section with oversized length varint")
    void rejectsOversizedSignatureLength() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      byte[] validBytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);

      // Replace signature packet with one that has an oversized length.
      // Find signature packet start: it's after the two EOS bytes at the end of caveats.
      var buf = new ByteArrayOutputStream();
      int sigPacketStart =
          validBytes.length - Macaroon.SIGNATURE_LENGTH - 2; // -2 for varint(6) + varint(32)
      buf.write(validBytes, 0, sigPacketStart);
      buf.writeBytes(Varint.encode(FIELD_SIGNATURE));
      buf.writeBytes(Varint.encode(999_999));
      byte[] malicious = buf.toByteArray();

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(malicious))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid packet length");
    }

    @Test
    @DisplayName("rejects trailing bytes after signature")
    void rejectsTrailingBytesAfterSignature() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      byte[] validBytes = buildExpectedV2Bytes(null, identifier, List.of(), signature);
      byte[] withTrailingByte = Arrays.copyOf(validBytes, validBytes.length + 1);
      withTrailingByte[withTrailingByte.length - 1] = 0x7F;

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(withTrailingByte))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Trailing bytes after V2 macaroon signature");
    }

    @Test
    @DisplayName("rejects malformed UTF-8 in a first-party caveat payload")
    void rejectsMalformedUtf8Caveat() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      byte[] malformedCaveat = packet(FIELD_IDENTIFIER, new byte[] {'k', '=', (byte) 0xC3, 0x28});
      byte[] serialized =
          buildV2BytesWithRawCaveatSections(identifier, List.of(malformedCaveat), signature);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(serialized))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("UTF-8");
    }

    @Test
    @DisplayName(
        "rejects an unsupported third-party caveat before treating it as a first-party caveat")
    void rejectsThirdPartyCaveat() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      var thirdPartyCaveat = new ByteArrayOutputStream();
      thirdPartyCaveat.write(
          packet(FIELD_IDENTIFIER, "account=alice".getBytes(StandardCharsets.UTF_8)));
      thirdPartyCaveat.write(packet(FIELD_VERIFICATION_ID, new byte[] {0x01, 0x02, 0x03}));
      thirdPartyCaveat.write(
          packet(FIELD_LOCATION, "https://idp.example.com".getBytes(StandardCharsets.UTF_8)));
      byte[] serialized =
          buildV2BytesWithRawCaveatSections(
              identifier, List.of(thirdPartyCaveat.toByteArray()), signature);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(serialized))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("third-party caveats are unsupported");
    }

    @Test
    @DisplayName("rejects a second serialized macaroon instead of accepting unverified delegation")
    void rejectsAdditionalMacaroon() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0x01);
      byte[] signature = signatureFilledWith((byte) 0x02);
      byte[] primary = buildExpectedV2Bytes(null, identifier, List.of(), signature);
      byte[] additional =
          buildExpectedV2Bytes(
              null, identifierFilledWith((byte) 0x03), List.of(), signatureFilledWith((byte) 0x04));
      byte[] serialized = Arrays.copyOf(primary, primary.length + additional.length);
      System.arraycopy(additional, 0, serialized, primary.length, additional.length);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(serialized))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Additional macaroons are unsupported");
    }

    /**
     * Finds the position of the second EOS (0x00) byte that marks the end of the caveats section.
     * This is the byte right before the signature packet in a no-caveats macaroon.
     */
    private int findSecondEosPosition(byte[] data) {
      int pos = 1; // skip version byte
      // Parse header fields until first EOS
      while (pos < data.length && data[pos] != FIELD_EOS) {
        Varint.DecodeResult ft = Varint.decode(data, pos);
        pos += ft.bytesRead();
        Varint.DecodeResult len = Varint.decode(data, pos);
        pos += len.bytesRead() + (int) len.value();
      }
      pos++; // skip first EOS
      return pos; // this is where the second EOS (or caveat data) starts
    }
  }

  @Nested
  @DisplayName("Caveat count limits")
  class CaveatCountLimits {

    private List<Caveat> generateCaveats(int count) {
      List<Caveat> caveats = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        caveats.add(new Caveat("key" + i, "value" + i));
      }
      return caveats;
    }

    @Test
    @DisplayName("deserializes macaroon with MAX_CAVEATS - 1 caveats")
    void acceptsMaxCaveatsMinusOne() {
      byte[] identifier = identifierFilledWith((byte) 0xA1);
      byte[] signature = signatureFilledWith((byte) 0xB2);
      int count = MacaroonSerializer.MAX_CAVEATS - 1;
      List<Caveat> caveats = generateCaveats(count);
      var original = new Macaroon(identifier, null, caveats, signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon result = MacaroonSerializer.deserializeV2(serialized);

      assertThat(result.caveats()).hasSize(count);
      assertThat(result.caveats()).isEqualTo(caveats);
    }

    @Test
    @DisplayName("serializes and deserializes macaroon with exactly MAX_CAVEATS caveats")
    void acceptsExactlyMaxCaveats() {
      byte[] identifier = identifierFilledWith((byte) 0xA1);
      byte[] signature = signatureFilledWith((byte) 0xB2);
      int count = MacaroonSerializer.MAX_CAVEATS;
      List<Caveat> caveats = generateCaveats(count);
      var original = new Macaroon(identifier, null, caveats, signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon result = MacaroonSerializer.deserializeV2(serialized);

      assertThat(result.caveats()).isEqualTo(caveats);
    }

    @Test
    @DisplayName("serialize rejects macaroon with MAX_CAVEATS + 1 caveats")
    void serializeRejectsExceedingMaxCaveats() throws IOException {
      byte[] identifier = identifierFilledWith((byte) 0xA1);
      byte[] signature = signatureFilledWith((byte) 0xB2);
      int count = MacaroonSerializer.MAX_CAVEATS + 1;
      List<Caveat> caveats = generateCaveats(count);
      var original = new Macaroon(identifier, null, caveats, signature);

      assertThatThrownBy(() -> MacaroonSerializer.serializeV2(original))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Too many caveats: %d, max: %d".formatted(count, MacaroonSerializer.MAX_CAVEATS));

      List<byte[]> rawCaveatSections =
          caveats.stream()
              .map(caveat -> caveat.toString().getBytes(StandardCharsets.UTF_8))
              .map(
                  caveatBytes -> {
                    try {
                      return packet(FIELD_IDENTIFIER, caveatBytes);
                    } catch (IOException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .toList();
      byte[] serializedWithOneTooMany =
          buildV2BytesWithRawCaveatSections(identifier, rawCaveatSections, signature);

      assertThatThrownBy(() -> MacaroonSerializer.deserializeV2(serializedWithOneTooMany))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Too many caveats: %d, max: %d".formatted(count, MacaroonSerializer.MAX_CAVEATS));
    }
  }

  @Nested
  @DisplayName("Round-trip")
  class RoundTrip {

    @Test
    @DisplayName("serialize then deserialize returns equivalent macaroon — no location, no caveats")
    void roundTripMinimal() {
      byte[] identifier = identifierFilledWith((byte) 0x77);
      byte[] signature = signatureFilledWith((byte) 0x88);
      var original = new Macaroon(identifier, null, List.of(), signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThat(deserialized.identifier()).isEqualTo(original.identifier());
      assertThat(deserialized.location()).isEqualTo(original.location());
      assertThat(deserialized.caveats()).isEqualTo(original.caveats());
      assertThat(deserialized.signature()).isEqualTo(original.signature());
    }

    @Test
    @DisplayName("serialize then deserialize returns equivalent macaroon — with location")
    void roundTripWithLocation() {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      var original = new Macaroon(identifier, "https://example.com/api", List.of(), signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThat(deserialized.identifier()).isEqualTo(original.identifier());
      assertThat(deserialized.location()).isEqualTo(original.location());
      assertThat(deserialized.caveats()).isEqualTo(original.caveats());
      assertThat(deserialized.signature()).isEqualTo(original.signature());
    }

    @Test
    @DisplayName("serialize then deserialize returns equivalent macaroon — with caveats")
    void roundTripWithCaveats() {
      byte[] identifier = identifierFilledWith((byte) 0xCC);
      byte[] signature = signatureFilledWith((byte) 0xDD);
      var caveats =
          List.of(
              new Caveat("service", "data-feed"),
              new Caveat("expires", "2026-12-31T23:59:59Z"),
              new Caveat("tier", "premium"));
      var original = new Macaroon(identifier, null, caveats, signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThat(deserialized.identifier()).isEqualTo(original.identifier());
      assertThat(deserialized.location()).isEqualTo(original.location());
      assertThat(deserialized.caveats()).isEqualTo(original.caveats());
      assertThat(deserialized.signature()).isEqualTo(original.signature());
    }

    @Test
    @DisplayName("serialize then deserialize returns equivalent macaroon — full macaroon")
    void roundTripFull() {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      String location = "https://api.greenharborlabs.com/v1";
      var caveats =
          List.of(new Caveat("service", "lightning-node"), new Caveat("capabilities", "read"));
      var original = new Macaroon(identifier, location, caveats, signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThat(deserialized.identifier()).isEqualTo(original.identifier());
      assertThat(deserialized.location()).isEqualTo(original.location());
      assertThat(deserialized.caveats()).isEqualTo(original.caveats());
      assertThat(deserialized.signature()).isEqualTo(original.signature());
    }

    @Test
    @DisplayName("round-trip preserves caveat whose value contains '=' characters")
    void roundTripCaveatWithEqualsInValue() {
      byte[] identifier = identifierFilledWith((byte) 0xEE);
      byte[] signature = signatureFilledWith((byte) 0xFF);
      var caveats = List.of(new Caveat("url", "https://example.com?a=b"));
      var original = new Macaroon(identifier, null, caveats, signature);

      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThat(deserialized.caveats()).hasSize(1);
      assertThat(deserialized.caveats().getFirst().key()).isEqualTo("url");
      assertThat(deserialized.caveats().getFirst().value()).isEqualTo("https://example.com?a=b");
    }

    @Test
    @DisplayName("double serialize produces identical bytes")
    void doubleSerializeIsIdempotent() {
      byte[] identifier = sequentialIdentifier();
      byte[] signature = sequentialSignature();
      var macaroon =
          new Macaroon(identifier, "https://example.com", List.of(new Caveat("k", "v")), signature);

      byte[] first = MacaroonSerializer.serializeV2(macaroon);
      byte[] second = MacaroonSerializer.serializeV2(macaroon);

      assertThat(first).isEqualTo(second);
    }
  }
}
