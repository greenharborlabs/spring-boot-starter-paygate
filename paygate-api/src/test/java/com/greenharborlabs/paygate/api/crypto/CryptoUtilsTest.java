package com.greenharborlabs.paygate.api.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CryptoUtils")
class CryptoUtilsTest {

    @Nested
    @DisplayName("constantTimeEquals")
    class ConstantTimeEquals {

        @Test
        @DisplayName("identical arrays return true")
        void identicalArrays() {
            assertThat(CryptoUtils.constantTimeEquals(
                    new byte[]{1, 2, 3}, new byte[]{1, 2, 3})).isTrue();
        }

        @Test
        @DisplayName("identical 32-byte arrays return true")
        void identical32ByteArrays() {
            byte[] a = new byte[32];
            byte[] b = new byte[32];
            for (int i = 0; i < 32; i++) {
                a[i] = (byte) i;
                b[i] = (byte) i;
            }
            assertThat(CryptoUtils.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        @DisplayName("single-bit difference returns false")
        void singleBitDifference() {
            byte[] a = new byte[32];
            byte[] b = new byte[32];
            b[15] = 1; // single bit flip
            assertThat(CryptoUtils.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("different values return false")
        void differentValues() {
            assertThat(CryptoUtils.constantTimeEquals(
                    new byte[]{1, 2, 3}, new byte[]{1, 2, 4})).isFalse();
        }

        @Test
        @DisplayName("empty arrays return true")
        void emptyArrays() {
            assertThat(CryptoUtils.constantTimeEquals(
                    new byte[0], new byte[0])).isTrue();
        }

        @Test
        @DisplayName("different-length arrays return false")
        void differentLengthArrays() {
            assertThat(CryptoUtils.constantTimeEquals(
                    new byte[32], new byte[16])).isFalse();
        }
    }

    @Nested
    @DisplayName("zeroize")
    class Zeroize {

        @Test
        @DisplayName("fills array with zeros")
        void fillsWithZeros() {
            byte[] data = new byte[]{(byte) 0xFF, (byte) 0xFF};
            CryptoUtils.zeroize(data);
            assertThat(data).containsExactly(0, 0);
        }

        @Test
        @DisplayName("null is a no-op")
        void nullIsNoOp() {
            CryptoUtils.zeroize((byte[]) null); // must not throw
        }

        @Test
        @DisplayName("varargs zeroizes all non-null arrays, skipping nulls")
        void varargsZeroizesAllSkippingNulls() {
            byte[] arr1 = new byte[]{1, 2, 3};
            byte[] arr2 = new byte[]{4, 5};
            CryptoUtils.zeroize(arr1, null, arr2);
            assertThat(arr1).containsExactly(0, 0, 0);
            assertThat(arr2).containsExactly(0, 0);
        }

        @Test
        @DisplayName("null varargs array is a no-op")
        void nullVarargsIsNoOp() {
            CryptoUtils.zeroize((byte[][]) null); // must not throw
        }
    }
}
