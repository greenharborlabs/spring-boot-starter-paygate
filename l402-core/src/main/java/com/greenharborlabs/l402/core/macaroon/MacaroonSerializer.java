package com.greenharborlabs.l402.core.macaroon;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes macaroons in V2 binary format,
 * compatible with Go {@code go-macaroon} library.
 *
 * <p>V2 binary layout:
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
 * <p>Packet structure: {@code fieldType(varint) payloadLen(varint) data[payloadLen]}
 */
public final class MacaroonSerializer {

    private static final int VERSION_BYTE = 0x02;
    private static final int FIELD_EOS = 0;
    private static final int FIELD_LOCATION = 1;
    private static final int FIELD_IDENTIFIER = 2;
    private static final int FIELD_SIGNATURE = 6;

    private MacaroonSerializer() {}

    /**
     * Serializes a macaroon to V2 binary format.
     */
    public static byte[] serializeV2(Macaroon macaroon) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(VERSION_BYTE);

        if (macaroon.location() != null) {
            writePacket(buf, FIELD_LOCATION, macaroon.location().getBytes(StandardCharsets.UTF_8));
        }
        writePacket(buf, FIELD_IDENTIFIER, macaroon.identifier());
        buf.write(FIELD_EOS);

        for (Caveat caveat : macaroon.caveats()) {
            writePacket(buf, FIELD_IDENTIFIER, caveat.toString().getBytes(StandardCharsets.UTF_8));
            buf.write(FIELD_EOS);
        }
        buf.write(FIELD_EOS);

        writePacket(buf, FIELD_SIGNATURE, macaroon.signature());
        return buf.toByteArray();
    }

    /**
     * Deserializes a macaroon from V2 binary format.
     *
     * @throws IllegalArgumentException if the data is malformed
     */
    public static Macaroon deserializeV2(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must not be null or empty");
        }
        if ((data[0] & 0xFF) != VERSION_BYTE) {
            throw new IllegalArgumentException(
                    "Expected V2 version byte 0x02, got 0x%02x".formatted(data[0] & 0xFF));
        }

        int pos = 1;
        String location = null;
        byte[] identifier = null;

        // Parse header section until EOS
        while (pos < data.length) {
            if (data[pos] == FIELD_EOS) {
                pos++;
                break;
            }
            Varint.DecodeResult fieldTypeResult = Varint.decode(data, pos);
            long rawFieldType = fieldTypeResult.value();
            if (rawFieldType < 0 || rawFieldType > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid field type: " + rawFieldType);
            }
            int fieldType = (int) rawFieldType;
            pos += fieldTypeResult.bytesRead();

            Varint.DecodeResult lenResult = Varint.decode(data, pos);
            long rawLen = lenResult.value();
            if (rawLen < 0 || rawLen > data.length) {
                throw new IllegalArgumentException("Invalid packet length: " + rawLen);
            }
            int len = (int) rawLen;
            pos += lenResult.bytesRead();

            if (pos + len > data.length) {
                throw new IllegalArgumentException(
                        "Truncated V2 macaroon: need %d bytes at offset %d but only %d remain"
                                .formatted(len, pos, data.length - pos));
            }
            byte[] payload = new byte[len];
            System.arraycopy(data, pos, payload, 0, len);
            pos += len;

            switch (fieldType) {
                case FIELD_LOCATION -> location = new String(payload, StandardCharsets.UTF_8);
                case FIELD_IDENTIFIER -> identifier = payload;
                default -> { /* skip unknown fields */ }
            }
        }

        if (identifier == null) {
            throw new IllegalArgumentException("Missing identifier in V2 macaroon data");
        }

        // Parse caveats
        List<Caveat> caveats = new ArrayList<>();
        while (pos < data.length) {
            if (data[pos] == FIELD_EOS) {
                pos++;
                break;
            }
            // Each caveat section: identifier packet + EOS
            Varint.DecodeResult fieldTypeResult = Varint.decode(data, pos);
            long rawFieldType = fieldTypeResult.value();
            if (rawFieldType < 0 || rawFieldType > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid field type: " + rawFieldType);
            }
            int fieldType = (int) rawFieldType;
            pos += fieldTypeResult.bytesRead();

            Varint.DecodeResult lenResult = Varint.decode(data, pos);
            long rawLen = lenResult.value();
            if (rawLen < 0 || rawLen > data.length) {
                throw new IllegalArgumentException("Invalid packet length: " + rawLen);
            }
            int len = (int) rawLen;
            pos += lenResult.bytesRead();

            if (pos + len > data.length) {
                throw new IllegalArgumentException(
                        "Truncated V2 macaroon caveat: need %d bytes at offset %d but only %d remain"
                                .formatted(len, pos, data.length - pos));
            }
            byte[] payload = new byte[len];
            System.arraycopy(data, pos, payload, 0, len);
            pos += len;

            if (fieldType == FIELD_IDENTIFIER) {
                String caveatStr = new String(payload, StandardCharsets.UTF_8);
                int eqIdx = caveatStr.indexOf('=');
                if (eqIdx <= 0) {
                    throw new IllegalArgumentException("Malformed caveat (no '=' separator): " + caveatStr);
                }
                caveats.add(new Caveat(caveatStr.substring(0, eqIdx), caveatStr.substring(eqIdx + 1)));
            }

            // EOS after caveat section is mandatory per Go format
            if (pos >= data.length || data[pos] != FIELD_EOS) {
                throw new IllegalArgumentException(
                        "Expected EOS (0x00) after caveat at offset %d".formatted(pos));
            }
            pos++;
        }

        // Parse signature
        if (pos >= data.length) {
            throw new IllegalArgumentException("Missing signature in V2 macaroon data");
        }
        Varint.DecodeResult sigFieldResult = Varint.decode(data, pos);
        long rawSigFieldType = sigFieldResult.value();
        if (rawSigFieldType < 0 || rawSigFieldType > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid field type: " + rawSigFieldType);
        }
        if ((int) rawSigFieldType != FIELD_SIGNATURE) {
            throw new IllegalArgumentException(
                    "Expected signature field type 6, got " + rawSigFieldType);
        }
        pos += sigFieldResult.bytesRead();

        Varint.DecodeResult sigLenResult = Varint.decode(data, pos);
        long rawSigLen = sigLenResult.value();
        if (rawSigLen < 0 || rawSigLen > data.length) {
            throw new IllegalArgumentException("Invalid packet length: " + rawSigLen);
        }
        int sigLen = (int) rawSigLen;
        pos += sigLenResult.bytesRead();

        if (pos + sigLen > data.length) {
            throw new IllegalArgumentException(
                    "Truncated V2 macaroon signature: need %d bytes at offset %d but only %d remain"
                            .formatted(sigLen, pos, data.length - pos));
        }
        byte[] signature = new byte[sigLen];
        System.arraycopy(data, pos, signature, 0, sigLen);

        return new Macaroon(identifier, location, caveats, signature);
    }

    private static void writePacket(ByteArrayOutputStream buf, int fieldType, byte[] data) {
        buf.writeBytes(Varint.encode(fieldType));
        buf.writeBytes(Varint.encode(data.length));
        buf.writeBytes(data);
    }
}
