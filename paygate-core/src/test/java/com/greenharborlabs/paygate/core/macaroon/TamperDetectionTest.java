package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName(
    "TamperDetection — every byte position of a serialized macaroon must be integrity-protected")
class TamperDetectionTest {

  private byte[] rootKey;
  private L402VerificationContext context;

  @BeforeEach
  void setUp() {
    SecureRandom random = new SecureRandom();
    rootKey = new byte[32];
    random.nextBytes(rootKey);
    context = L402VerificationContext.builder().serviceName("test-service").build();
  }

  private MacaroonIdentifier randomIdentifier(SecureRandom random) {
    byte[] paymentHash = new byte[32];
    byte[] tokenId = new byte[32];
    random.nextBytes(paymentHash);
    random.nextBytes(tokenId);
    return new MacaroonIdentifier(0, paymentHash, tokenId);
  }

  private CaveatVerifier acceptingVerifier(String key) {
    return new CaveatVerifier() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public void verify(Caveat caveat, L402VerificationContext ctx) {
        // accepts unconditionally
      }
    };
  }

  @Nested
  @DisplayName("baseline: untampered macaroon passes verification")
  class Baseline {

    @Test
    @DisplayName("macaroon without caveats verifies successfully after round-trip")
    void untamperedNoCaveats() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThatCode(() -> MacaroonVerifier.verify(deserialized, rootKey, List.of(), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("macaroon with caveats verifies successfully after round-trip")
    void untamperedWithCaveats() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      List<Caveat> caveats =
          List.of(new Caveat("service", "test-service"), new Caveat("tier", "premium"));
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, caveats);
      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("service"), acceptingVerifier("tier"));

      assertThatCode(() -> MacaroonVerifier.verify(deserialized, rootKey, verifiers, context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("macaroon with location verifies successfully after round-trip")
    void untamperedWithLocation() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, "https://example.com", List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);
      Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

      assertThatCode(() -> MacaroonVerifier.verify(deserialized, rootKey, List.of(), context))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("exhaustive single-byte tampering at every position")
  class SingleByteTampering {

    /**
     * Location-less macaroons are used for exhaustive byte-position tests because the location
     * field is unsigned per the macaroon protocol (it is an advisory hint and does not participate
     * in the HMAC signature chain). All authenticated fields — version discriminator, identifier,
     * caveats, signature, and structural framing bytes — are covered by these tests.
     */
    @Test
    @DisplayName("tampering any byte of a no-caveat macaroon is rejected")
    void everyBytePositionNoCaveats() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      assertAllPositionsTamperDetected(serialized, List.of());
    }

    @Test
    @DisplayName("tampering any byte of a macaroon with caveats is rejected")
    void everyBytePositionWithCaveats() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      List<Caveat> caveats =
          List.of(new Caveat("service", "test-service"), new Caveat("tier", "premium"));
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, caveats);
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("service"), acceptingVerifier("tier"));

      assertAllPositionsTamperDetected(serialized, verifiers);
    }
  }

  @Nested
  @DisplayName("location field is unsigned per macaroon protocol")
  class LocationUnsigned {

    @Test
    @DisplayName(
        "tampering the location does not invalidate the signature — location is advisory only")
    void locationTamperDoesNotAffectSignature() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, "https://example.com", List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      // Tamper a byte in the location content area.
      // V2 layout with location: 0x02 | 0x01 lenVarInt locationBytes... | ...
      // Position 0 = version (0x02), position 1 = field type (0x01),
      // position 2 = length varint, position 3+ = location content.
      // Find first location content byte (after version, field-type, length varints).
      int locationContentStart = 3; // 0x02, 0x01, len(19 < 128 so 1 byte)
      byte[] tampered = serialized.clone();
      tampered[locationContentStart] = (byte) (tampered[locationContentStart] ^ 0xFF);

      Macaroon deserialized = MacaroonSerializer.deserializeV2(tampered);

      // Signature verification still passes — location is not part of the HMAC chain
      assertThatCode(() -> MacaroonVerifier.verify(deserialized, rootKey, List.of(), context))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("targeted tamper regions")
  class TargetedTamperRegions {

    @Test
    @DisplayName("tampering identifier bytes causes verification failure")
    void identifierTampering() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      // Layout without location: 0x02 | 0x02 0x42 <66 id bytes> | 0x00 | 0x00 | 0x06 0x20 <32 sig
      // bytes>
      // Identifier content starts at position 3 and spans 66 bytes
      int identifierStart = 3;
      for (int i = identifierStart; i < identifierStart + 66; i++) {
        byte[] tampered = serialized.clone();
        tampered[i] = (byte) (tampered[i] ^ 0xFF);

        boolean rejected = isTamperRejected(tampered, List.of());
        assertThat(rejected)
            .as("Tampered identifier byte at position %d must be rejected", i)
            .isTrue();
      }
    }

    @Test
    @DisplayName("tampering signature bytes causes verification failure")
    void signatureTampering() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, List.of());
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      // Signature is the last 32 bytes of the serialized form
      int signatureStart = serialized.length - 32;
      for (int i = signatureStart; i < serialized.length; i++) {
        byte[] tampered = serialized.clone();
        tampered[i] = (byte) (tampered[i] ^ 0xFF);

        boolean rejected = isTamperRejected(tampered, List.of());
        assertThat(rejected)
            .as("Tampered signature byte at position %d must be rejected", i)
            .isTrue();
      }
    }

    @Test
    @DisplayName("tampering caveat bytes causes verification failure")
    void caveatTampering() {
      MacaroonIdentifier id = randomIdentifier(new SecureRandom());
      List<Caveat> caveats =
          List.of(new Caveat("service", "test-service"), new Caveat("tier", "premium"));
      Macaroon original = MacaroonMinter.mint(rootKey, id, null, caveats);
      byte[] serialized = MacaroonSerializer.serializeV2(original);

      List<CaveatVerifier> verifiers =
          List.of(acceptingVerifier("service"), acceptingVerifier("tier"));

      // Caveat region is between the header EOS and the final EOS before signature.
      // Layout: 0x02 | 0x02 0x42 <66 id bytes> | 0x00 (header EOS)
      //         | caveat sections... | 0x00 (caveats EOS) | 0x06 0x20 <32 sig>
      int headerEosPos = 1 + 1 + 1 + 66; // version + field_type + len_varint + identifier
      int caveatsStart = headerEosPos + 1; // after header EOS

      // Find the end of caveats section (the EOS before signature field type 0x06)
      int sigFieldPos = serialized.length - 32 - 2; // 0x06 0x20 before 32 sig bytes
      int caveatsEnd = sigFieldPos - 1; // the EOS byte before signature

      for (int i = caveatsStart; i <= caveatsEnd; i++) {
        byte[] tampered = serialized.clone();
        tampered[i] = (byte) (tampered[i] ^ 0xFF);

        boolean rejected = isTamperRejected(tampered, verifiers);
        assertThat(rejected).as("Tampered caveat byte at position %d must be rejected", i).isTrue();
      }
    }
  }

  /**
   * For every byte position in the serialized macaroon, flips that byte (XOR 0xFF), then attempts
   * deserialization and verification. Each tampered version must be rejected — either
   * deserialization throws (malformed data) or verification throws (signature mismatch).
   */
  private void assertAllPositionsTamperDetected(byte[] serialized, List<CaveatVerifier> verifiers) {
    int rejectedCount = 0;

    for (int i = 0; i < serialized.length; i++) {
      byte[] tampered = serialized.clone();
      tampered[i] = (byte) (tampered[i] ^ 0xFF);

      boolean rejected = isTamperRejected(tampered, verifiers);
      assertThat(rejected)
          .as("Tampered byte at position %d (of %d) must be rejected", i, serialized.length)
          .isTrue();
      if (rejected) {
        rejectedCount++;
      }
    }

    assertThat(rejectedCount).isEqualTo(serialized.length);
  }

  /**
   * Returns true if the tampered serialized macaroon is rejected, either at deserialization
   * (IllegalArgumentException) or at verification (MacaroonVerificationException).
   */
  private boolean isTamperRejected(byte[] tampered, List<CaveatVerifier> verifiers) {
    Macaroon deserialized;
    try {
      deserialized = MacaroonSerializer.deserializeV2(tampered);
    } catch (IllegalArgumentException _) {
      return true;
    }

    try {
      MacaroonVerifier.verify(deserialized, rootKey, verifiers, context);
      return false;
    } catch (MacaroonVerificationException _) {
      return true;
    }
  }
}
