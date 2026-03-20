package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConstantTimeEquals — XOR-accumulation comparison must never short-circuit")
class ConstantTimeTest {

    private static final int ARRAY_LENGTH = 32;

    @Nested
    @DisplayName("equal arrays")
    class EqualArrays {

        @Test
        @DisplayName("returns true for identical 32-byte arrays")
        void identicalArrays() {
            byte[] a = new byte[ARRAY_LENGTH];
            new SecureRandom().nextBytes(a);
            byte[] b = a.clone();

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        @DisplayName("returns true for two all-zero arrays")
        void allZeroArrays() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[ARRAY_LENGTH];

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        @DisplayName("returns true for two all-0xFF arrays")
        void allOnesArrays() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[ARRAY_LENGTH];
            Arrays.fill(a, (byte) 0xFF);
            Arrays.fill(b, (byte) 0xFF);

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isTrue();
        }
    }

    @Nested
    @DisplayName("single-position differences — proves no short-circuit")
    class SinglePositionDifference {

        @Test
        @DisplayName("returns false when arrays differ at every individual position (0 through 31)")
        void differAtEachPosition() {
            byte[] base = new byte[ARRAY_LENGTH];
            new SecureRandom().nextBytes(base);

            for (int pos = 0; pos < ARRAY_LENGTH; pos++) {
                byte[] modified = base.clone();
                modified[pos] = (byte) (modified[pos] ^ 0xFF);

                assertThat(MacaroonCrypto.constantTimeEquals(base, modified))
                        .as("Arrays differing only at position %d must return false", pos)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("returns false when only the first byte differs")
        void differAtFirstByte() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[ARRAY_LENGTH];
            b[0] = 0x01;

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when only the last byte differs")
        void differAtLastByte() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[ARRAY_LENGTH];
            b[ARRAY_LENGTH - 1] = 0x01;

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when only the middle byte differs")
        void differAtMiddleByte() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[ARRAY_LENGTH];
            b[ARRAY_LENGTH / 2] = 0x01;

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }
    }

    @Nested
    @DisplayName("different-length arrays")
    class DifferentLengths {

        @Test
        @DisplayName("returns false when first array is shorter")
        void firstShorter() {
            byte[] a = new byte[16];
            byte[] b = new byte[ARRAY_LENGTH];

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when second array is shorter")
        void secondShorter() {
            byte[] a = new byte[ARRAY_LENGTH];
            byte[] b = new byte[16];

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when one array is empty")
        void oneEmpty() {
            byte[] a = new byte[0];
            byte[] b = new byte[ARRAY_LENGTH];

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns true for two empty arrays")
        void bothEmpty() {
            byte[] a = new byte[0];
            byte[] b = new byte[0];

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isTrue();
        }
    }

    @Nested
    @DisplayName("all-zero vs all-0xFF of same length")
    class ZeroVsOnes {

        @Test
        @DisplayName("returns false for all-zero vs all-0xFF arrays of same length")
        void allZeroVsAllOnes() {
            byte[] zeros = new byte[ARRAY_LENGTH];
            byte[] ones = new byte[ARRAY_LENGTH];
            Arrays.fill(ones, (byte) 0xFF);

            assertThat(MacaroonCrypto.constantTimeEquals(zeros, ones)).isFalse();
        }
    }

    @Nested
    @DisplayName("minimal bit differences")
    class MinimalBitDifference {

        @Test
        @DisplayName("detects a single-bit difference at each position")
        void singleBitFlipAtEachPosition() {
            byte[] base = new byte[ARRAY_LENGTH];
            new SecureRandom().nextBytes(base);

            for (int pos = 0; pos < ARRAY_LENGTH; pos++) {
                byte[] modified = base.clone();
                // Flip the least significant bit at this position
                modified[pos] = (byte) (modified[pos] ^ 0x01);

                assertThat(MacaroonCrypto.constantTimeEquals(base, modified))
                        .as("Single-bit difference at position %d must return false", pos)
                        .isFalse();
            }
        }
    }
}
