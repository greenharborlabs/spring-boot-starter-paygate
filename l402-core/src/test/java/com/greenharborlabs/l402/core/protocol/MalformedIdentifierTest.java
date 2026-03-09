package com.greenharborlabs.l402.core.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that macaroons with identifiers that are not exactly 66 bytes
 * are rejected as MALFORMED_HEADER during credential parsing.
 * Covers T071.
 */
@DisplayName("MalformedIdentifier")
class MalformedIdentifierTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Builds a minimal V2 binary macaroon with a given identifier length,
     * bypassing the Macaroon constructor's 66-byte enforcement.
     *
     * V2 layout:
     *   0x02                        version byte
     *   field=2 len=N data[N]       identifier packet
     *   0x00                        EOS (end of header)
     *   0x00                        EOS (end of caveats, no caveats)
     *   field=6 len=32 data[32]     signature packet
     */
    private static byte[] buildRawMacaroonBytes(int identifierLength) {
        byte[] identifier = new byte[identifierLength];
        RANDOM.nextBytes(identifier);

        byte[] signature = new byte[32];
        RANDOM.nextBytes(signature);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Version byte
        buf.write(0x02);
        // Identifier packet: fieldType=2, then length, then data
        writeVarint(buf, 2);
        writeVarint(buf, identifierLength);
        buf.writeBytes(identifier);
        // EOS end of header
        buf.write(0x00);
        // EOS end of caveats (no caveats)
        buf.write(0x00);
        // Signature packet: fieldType=6, length=32, data
        writeVarint(buf, 6);
        writeVarint(buf, 32);
        buf.writeBytes(signature);

        return buf.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream buf, long value) {
        while (value > 0x7F) {
            buf.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.write((int) value);
    }

    private String buildHeaderWithIdentifierLength(int identifierLength) {
        byte[] macaroonBytes = buildRawMacaroonBytes(identifierLength);
        String macaroonBase64 = Base64.getEncoder().encodeToString(macaroonBytes);

        byte[] preimage = new byte[32];
        RANDOM.nextBytes(preimage);
        String preimageHex = HEX.formatHex(preimage);

        return "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    @ParameterizedTest(name = "identifier of {0} bytes is rejected as MALFORMED_HEADER")
    @ValueSource(ints = {10, 32, 65, 67, 100, 0, 1})
    @DisplayName("non-66-byte identifier throws MALFORMED_HEADER")
    void nonStandardIdentifierLengthIsRejected(int identifierLength) {
        String header = buildHeaderWithIdentifierLength(identifierLength);

        assertThatThrownBy(() -> L402Credential.parse(header))
                .isInstanceOf(L402Exception.class)
                .satisfies(e -> assertThat(((L402Exception) e).getErrorCode())
                        .isEqualTo(ErrorCode.MALFORMED_HEADER));
    }

    @Test
    @DisplayName("66-byte identifier is accepted without MALFORMED_HEADER")
    void correctIdentifierLengthIsAccepted() {
        // Build a proper 66-byte identifier with valid structure:
        // [version:2 bytes BE=0][paymentHash:32][tokenId:32]
        byte[] paymentHash = new byte[32];
        RANDOM.nextBytes(paymentHash);
        byte[] tokenId = new byte[32];
        RANDOM.nextBytes(tokenId);

        byte[] identifier = new byte[66];
        // version 0 in big-endian
        identifier[0] = 0;
        identifier[1] = 0;
        System.arraycopy(paymentHash, 0, identifier, 2, 32);
        System.arraycopy(tokenId, 0, identifier, 34, 32);

        byte[] signature = new byte[32];
        RANDOM.nextBytes(signature);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x02);
        writeVarint(buf, 2);
        writeVarint(buf, 66);
        buf.writeBytes(identifier);
        buf.write(0x00);
        buf.write(0x00);
        writeVarint(buf, 6);
        writeVarint(buf, 32);
        buf.writeBytes(signature);

        String macaroonBase64 = Base64.getEncoder().encodeToString(buf.toByteArray());
        byte[] preimage = new byte[32];
        RANDOM.nextBytes(preimage);
        String preimageHex = HEX.formatHex(preimage);

        String header = "L402 " + macaroonBase64 + ":" + preimageHex;

        // Should NOT throw MALFORMED_HEADER — parse should succeed
        // (it may fail on signature verification later, but parse itself should work)
        L402Credential credential = L402Credential.parse(header);
        assertThat(credential).isNotNull();
        assertThat(credential.tokenId()).isEqualTo(HEX.formatHex(tokenId));
    }
}
