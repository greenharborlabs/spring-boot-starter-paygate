package com.greenharborlabs.paygate.protocol.mpp;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MppCryptoUtilsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Nested
    class ConstantTimeEquals {

        @Test
        void equalArraysReturnTrue() {
            byte[] a = {0x01, 0x02, 0x03, 0x04};
            byte[] b = {0x01, 0x02, 0x03, 0x04};
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        void differentArraysSameLengthReturnFalse() {
            byte[] a = {0x01, 0x02, 0x03, 0x04};
            byte[] b = {0x01, 0x02, 0x03, 0x05};
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        void differentLengthsReturnFalse() {
            byte[] a = {0x01, 0x02, 0x03};
            byte[] b = {0x01, 0x02, 0x03, 0x04};
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        void differentLengthsReversedReturnFalse() {
            byte[] a = {0x01, 0x02, 0x03, 0x04};
            byte[] b = {0x01, 0x02};
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        void bothEmptyReturnTrue() {
            assertThat(MppCryptoUtils.constantTimeEquals(new byte[0], new byte[0])).isTrue();
        }

        @Test
        void oneEmptyOnNotReturnFalse() {
            assertThat(MppCryptoUtils.constantTimeEquals(new byte[0], new byte[]{0x01})).isFalse();
            assertThat(MppCryptoUtils.constantTimeEquals(new byte[]{0x01}, new byte[0])).isFalse();
        }

        @Test
        void allZeroBytesEqualReturnTrue() {
            byte[] a = new byte[32];
            byte[] b = new byte[32];
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        void singleByteDifference() {
            byte[] a = {(byte) 0xFF};
            byte[] b = {(byte) 0xFE};
            assertThat(MppCryptoUtils.constantTimeEquals(a, b)).isFalse();
        }
    }

    @Nested
    class Sha256 {

        @Test
        void knownVector_emptyInput() {
            // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            byte[] hash = MppCryptoUtils.sha256(new byte[0]);
            assertThat(HEX.formatHex(hash))
                    .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        void knownVector_abc() {
            // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
            byte[] hash = MppCryptoUtils.sha256("abc".getBytes(StandardCharsets.UTF_8));
            assertThat(HEX.formatHex(hash))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }

        @Test
        void outputIs32Bytes() {
            byte[] hash = MppCryptoUtils.sha256(new byte[]{0x42});
            assertThat(hash).hasSize(32);
        }
    }
}
