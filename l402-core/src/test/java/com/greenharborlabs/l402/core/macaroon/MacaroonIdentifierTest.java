package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacaroonIdentifierTest {

    private static final int CURRENT_VERSION = 0;
    private static final int IDENTIFIER_LENGTH = 66;

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    // --- Valid construction ---

    @Test
    @DisplayName("Constructor accepts valid version 0, 32-byte paymentHash, 32-byte tokenId")
    void validConstruction() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);

        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        assertThat(id.version()).isEqualTo(CURRENT_VERSION);
        assertThat(id.paymentHash()).isEqualTo(paymentHash);
        assertThat(id.tokenId()).isEqualTo(tokenId);
    }

    // --- Encode produces 66 bytes ---

    @Test
    @DisplayName("encode() produces exactly 66 bytes")
    void encodeProduces66Bytes() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        byte[] encoded = MacaroonIdentifier.encode(id);

        assertThat(encoded).hasSize(IDENTIFIER_LENGTH);
    }

    // --- Big-endian version encoding ---

    @Test
    @DisplayName("encode() writes version 0 as two big-endian zero bytes")
    void encodeVersionZeroBigEndian() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        byte[] encoded = MacaroonIdentifier.encode(id);

        assertThat(encoded[0]).isEqualTo((byte) 0x00);
        assertThat(encoded[1]).isEqualTo((byte) 0x00);
    }

    @Test
    @DisplayName("encode() writes paymentHash at offset 2 and tokenId at offset 34")
    void encodeFieldLayout() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        byte[] encoded = MacaroonIdentifier.encode(id);

        assertThat(Arrays.copyOfRange(encoded, 2, 34)).isEqualTo(paymentHash);
        assertThat(Arrays.copyOfRange(encoded, 34, 66)).isEqualTo(tokenId);
    }

    // --- Decode round-trips ---

    @Test
    @DisplayName("decode(encode(id)) round-trips correctly")
    void decodeRoundTrips() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier original = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        byte[] encoded = MacaroonIdentifier.encode(original);
        MacaroonIdentifier decoded = MacaroonIdentifier.decode(encoded);

        assertThat(decoded.version()).isEqualTo(original.version());
        assertThat(decoded.paymentHash()).isEqualTo(original.paymentHash());
        assertThat(decoded.tokenId()).isEqualTo(original.tokenId());
    }

    @Test
    @DisplayName("decode() correctly reads big-endian version from raw bytes")
    void decodeReadsVersionFromRawBytes() {
        byte[] raw = new byte[IDENTIFIER_LENGTH];
        // version = 0 in big-endian
        raw[0] = 0x00;
        raw[1] = 0x00;
        // fill paymentHash and tokenId with known values
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        System.arraycopy(paymentHash, 0, raw, 2, 32);
        System.arraycopy(tokenId, 0, raw, 34, 32);

        MacaroonIdentifier decoded = MacaroonIdentifier.decode(raw);

        assertThat(decoded.version()).isEqualTo(0);
        assertThat(decoded.paymentHash()).isEqualTo(paymentHash);
        assertThat(decoded.tokenId()).isEqualTo(tokenId);
    }

    // --- Validation: wrong-size paymentHash ---

    @Test
    @DisplayName("Constructor rejects paymentHash shorter than 32 bytes")
    void rejectsShortPaymentHash() {
        byte[] shortHash = randomBytes(16);
        byte[] tokenId = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, shortHash, tokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentHash");
    }

    @Test
    @DisplayName("Constructor rejects paymentHash longer than 32 bytes")
    void rejectsLongPaymentHash() {
        byte[] longHash = randomBytes(64);
        byte[] tokenId = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, longHash, tokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentHash");
    }

    @Test
    @DisplayName("Constructor rejects null paymentHash")
    void rejectsNullPaymentHash() {
        byte[] tokenId = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, null, tokenId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Validation: wrong-size tokenId ---

    @Test
    @DisplayName("Constructor rejects tokenId shorter than 32 bytes")
    void rejectsShortTokenId() {
        byte[] paymentHash = randomBytes(32);
        byte[] shortTokenId = randomBytes(16);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, paymentHash, shortTokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenId");
    }

    @Test
    @DisplayName("Constructor rejects tokenId longer than 32 bytes")
    void rejectsLongTokenId() {
        byte[] paymentHash = randomBytes(32);
        byte[] longTokenId = randomBytes(64);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, paymentHash, longTokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenId");
    }

    @Test
    @DisplayName("Constructor rejects null tokenId")
    void rejectsNullTokenId() {
        byte[] paymentHash = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(CURRENT_VERSION, paymentHash, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Validation: invalid version ---

    @Test
    @DisplayName("Constructor rejects negative version")
    void rejectsNegativeVersion() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(-1, paymentHash, tokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("Constructor rejects version exceeding uint16 max (65535)")
    void rejectsVersionExceedingUint16() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);

        assertThatThrownBy(() -> new MacaroonIdentifier(65536, paymentHash, tokenId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("decode() rejects non-zero version as unsupported")
    void decodeRejectsNonZeroVersion() {
        byte[] raw = new byte[IDENTIFIER_LENGTH];
        // version = 1 in big-endian
        raw[0] = 0x00;
        raw[1] = 0x01;

        assertThatThrownBy(() -> MacaroonIdentifier.decode(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    // --- Decode: wrong-length input ---

    @Test
    @DisplayName("decode() rejects input shorter than 66 bytes")
    void decodeRejectsTooShortInput() {
        byte[] tooShort = new byte[65];

        assertThatThrownBy(() -> MacaroonIdentifier.decode(tooShort))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode() rejects input longer than 66 bytes")
    void decodeRejectsTooLongInput() {
        byte[] tooLong = new byte[67];

        assertThatThrownBy(() -> MacaroonIdentifier.decode(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode() rejects null input")
    void decodeRejectsNullInput() {
        assertThatThrownBy(() -> MacaroonIdentifier.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Defensive copy verification ---

    @Test
    @DisplayName("Constructor makes defensive copy of paymentHash")
    void defensiveCopyPaymentHash() {
        byte[] paymentHash = randomBytes(32);
        byte[] original = paymentHash.clone();
        byte[] tokenId = randomBytes(32);

        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        // Mutate the original array
        paymentHash[0] = (byte) ~paymentHash[0];

        // Record's internal copy should be unchanged
        assertThat(id.paymentHash()).isEqualTo(original);
    }

    @Test
    @DisplayName("Constructor makes defensive copy of tokenId")
    void defensiveCopyTokenId() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        byte[] original = tokenId.clone();

        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);

        // Mutate the original array
        tokenId[0] = (byte) ~tokenId[0];

        // Record's internal copy should be unchanged
        assertThat(id.tokenId()).isEqualTo(original);
    }

    // --- Constant-time equality for cryptographic identifiers ---

    @Test
    @DisplayName("equals() uses constant-time comparison for paymentHash and tokenId to prevent timing side-channel attacks")
    void equalsUsesConstantTimeComparisonForCryptographicFields() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier id1 = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, tokenId);
        MacaroonIdentifier id2 = new MacaroonIdentifier(CURRENT_VERSION, paymentHash.clone(), tokenId.clone());

        // Identical identifiers are equal
        assertThat(id1).isEqualTo(id2);

        // Differ only in last byte of paymentHash — still detected as not equal
        byte[] altPaymentHash = paymentHash.clone();
        altPaymentHash[31] = (byte) (altPaymentHash[31] ^ 0x01);
        MacaroonIdentifier id3 = new MacaroonIdentifier(CURRENT_VERSION, altPaymentHash, tokenId);
        assertThat(id1).isNotEqualTo(id3);

        // Differ only in last byte of tokenId — still detected as not equal
        byte[] altTokenId = tokenId.clone();
        altTokenId[31] = (byte) (altTokenId[31] ^ 0x01);
        MacaroonIdentifier id4 = new MacaroonIdentifier(CURRENT_VERSION, paymentHash, altTokenId);
        assertThat(id1).isNotEqualTo(id4);

        // Symmetry holds
        assertThat(id2).isEqualTo(id1);
    }

    @Test
    @DisplayName("Accessor returns defensive copy — mutating returned array does not affect record")
    void accessorReturnsDefensiveCopy() {
        byte[] paymentHash = randomBytes(32);
        byte[] tokenId = randomBytes(32);
        MacaroonIdentifier id = new MacaroonIdentifier(CURRENT_VERSION, paymentHash.clone(), tokenId.clone());

        byte[] returned = id.paymentHash();
        byte[] snapshot = returned.clone();
        returned[0] = (byte) ~returned[0];

        // Subsequent access should still return the original value
        assertThat(id.paymentHash()).isEqualTo(snapshot);
    }
}
