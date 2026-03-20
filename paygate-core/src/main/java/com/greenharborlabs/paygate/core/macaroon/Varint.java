package com.greenharborlabs.paygate.core.macaroon;

/**
 * Unsigned LEB128 (varint) encoding and decoding, compatible with
 * protobuf varint and Go {@code binary.PutUvarint} / {@code binary.Uvarint}.
 *
 * <p>Used internally for the Macaroon V2 binary format field types and payload lengths.
 */
public final class Varint {

    /** Maximum number of bytes in an unsigned LEB128-encoded 64-bit value. */
    private static final int MAX_VARINT_BYTES = 10;

    private Varint() {
        // utility class
    }

    /**
     * Result of decoding a varint from a byte array.
     *
     * @param value     the decoded unsigned value
     * @param bytesRead number of bytes consumed from the input
     */
    public record DecodeResult(long value, int bytesRead) {}

    /**
     * Encodes a non-negative {@code long} as an unsigned LEB128 byte array.
     *
     * @param value the value to encode (must be non-negative)
     * @return the LEB128-encoded bytes
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Varint encode requires a non-negative value, got: " + value);
        }

        // Fast path: single-byte values (0-127)
        if (value <= 0x7F) {
            return new byte[]{(byte) value};
        }

        // Worst case for a 63-bit unsigned value is 9 bytes
        byte[] buffer = new byte[MAX_VARINT_BYTES];
        int pos = 0;
        long remaining = value;

        while (remaining > 0x7F) {
            buffer[pos++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[pos++] = (byte) remaining;

        byte[] result = new byte[pos];
        System.arraycopy(buffer, 0, result, 0, pos);
        return result;
    }

    /**
     * Decodes an unsigned LEB128 varint from {@code data} starting at {@code offset}.
     *
     * @param data   the byte array containing the varint
     * @param offset the position to start reading from
     * @return a {@link DecodeResult} with the decoded value and number of bytes consumed
     * @throws IllegalArgumentException if input is null, offset is out of bounds,
     *                                  or the varint is truncated
     */
    public static DecodeResult decode(byte[] data, int offset) {
        if (data == null) {
            throw new IllegalArgumentException("Data array must not be null");
        }
        if (offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException(
                    "Offset %d is out of bounds for array of length %d".formatted(offset, data.length));
        }

        long value = 0;
        int shift = 0;
        int pos = offset;

        while (pos < data.length) {
            byte b = data[pos];
            value |= (long) (b & 0x7F) << shift;
            pos++;

            if ((b & 0x80) == 0) {
                return new DecodeResult(value, pos - offset);
            }

            shift += 7;
            if (shift >= 64) {
                throw new IllegalArgumentException("Varint is too long — exceeds 64-bit unsigned range");
            }
        }

        // Reached end of array with continuation bit still set
        throw new IllegalArgumentException("Truncated varint at offset " + offset);
    }
}
