package com.greenharborlabs.paygate.core.macaroon;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Binary identifier embedded in an L402 macaroon.
 * Layout: [version: 2-byte big-endian uint16][paymentHash: 32 bytes][tokenId: 32 bytes] = 66 bytes.
 */
public record MacaroonIdentifier(int version, byte[] paymentHash, byte[] tokenId) {

    private static final int VERSION_BYTES = 2;
    private static final int HASH_BYTES = 32;
    private static final int IDENTIFIER_LENGTH = VERSION_BYTES + HASH_BYTES + HASH_BYTES; // 66

    /**
     * Compact constructor — validates all fields and makes defensive copies of byte arrays.
     */
    public MacaroonIdentifier {
        if (version < 0 || version > 65535) {
            throw new IllegalArgumentException("version must be in range [0, 65535], got: " + version);
        }
        if (paymentHash == null) {
            throw new IllegalArgumentException("paymentHash must not be null");
        }
        if (paymentHash.length != HASH_BYTES) {
            throw new IllegalArgumentException("paymentHash must be exactly 32 bytes, got: " + paymentHash.length);
        }
        if (tokenId == null) {
            throw new IllegalArgumentException("tokenId must not be null");
        }
        if (tokenId.length != HASH_BYTES) {
            throw new IllegalArgumentException("tokenId must be exactly 32 bytes, got: " + tokenId.length);
        }
        paymentHash = paymentHash.clone();
        tokenId = tokenId.clone();
    }

    @Override
    public byte[] paymentHash() {
        return paymentHash.clone();
    }

    @Override
    public byte[] tokenId() {
        return tokenId.clone();
    }

    /**
     * Serializes the identifier into a 66-byte array.
     */
    public static byte[] encode(MacaroonIdentifier identifier) {
        ByteBuffer buf = ByteBuffer.allocate(IDENTIFIER_LENGTH);
        buf.putShort((short) identifier.version());
        buf.put(identifier.paymentHash());
        buf.put(identifier.tokenId());
        return buf.array();
    }

    /**
     * Deserializes a 66-byte array into a {@link MacaroonIdentifier}.
     * Only version 0 is currently supported.
     */
    public static MacaroonIdentifier decode(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (data.length != IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    "Identifier must be exactly " + IDENTIFIER_LENGTH + " bytes, got: " + data.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        int version = Short.toUnsignedInt(buf.getShort());
        if (version != 0) {
            throw new IllegalArgumentException("Unsupported identifier version: " + version);
        }
        byte[] paymentHash = new byte[HASH_BYTES];
        buf.get(paymentHash);
        byte[] tokenId = new byte[HASH_BYTES];
        buf.get(tokenId);
        return new MacaroonIdentifier(version, paymentHash, tokenId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MacaroonIdentifier other
                && version == other.version
                && MacaroonCrypto.constantTimeEquals(paymentHash, other.paymentHash)
                && MacaroonCrypto.constantTimeEquals(tokenId, other.tokenId);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(version);
        result = 31 * result + Arrays.hashCode(paymentHash);
        result = 31 * result + Arrays.hashCode(tokenId);
        return result;
    }

    @Override
    public String toString() {
        return "MacaroonIdentifier[version=" + version + "]";
    }
}
