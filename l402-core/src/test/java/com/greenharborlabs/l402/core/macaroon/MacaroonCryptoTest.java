package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacaroonCryptoTest {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final byte[] GENERATOR_KEY = "macaroons-key-generator".getBytes(StandardCharsets.UTF_8);

    /**
     * Independently computes HMAC-SHA256 using javax.crypto for test verification.
     */
    private static byte[] referenceHmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("HMAC-SHA256 unavailable in test JDK", e);
        }
    }

    @Nested
    @DisplayName("deriveKey")
    class DeriveKey {

        @Test
        @DisplayName("returns 32-byte derived key")
        void returns32Bytes() {
            byte[] rootKey = "test-root-key".getBytes(StandardCharsets.UTF_8);

            byte[] derived = MacaroonCrypto.deriveKey(rootKey);

            assertThat(derived).hasSize(32);
        }

        @Test
        @DisplayName("matches independently computed HMAC-SHA256 with generator key")
        void matchesReferenceHmac() {
            byte[] rootKey = "my-secret-root-key-1234".getBytes(StandardCharsets.UTF_8);

            byte[] derived = MacaroonCrypto.deriveKey(rootKey);
            byte[] expected = referenceHmac(GENERATOR_KEY, rootKey);

            assertThat(derived).isEqualTo(expected);
        }

        @Test
        @DisplayName("produces different output for different root keys")
        void differentRootKeysProduceDifferentOutput() {
            byte[] rootKeyA = "root-key-alpha".getBytes(StandardCharsets.UTF_8);
            byte[] rootKeyB = "root-key-bravo".getBytes(StandardCharsets.UTF_8);

            byte[] derivedA = MacaroonCrypto.deriveKey(rootKeyA);
            byte[] derivedB = MacaroonCrypto.deriveKey(rootKeyB);

            assertThat(derivedA).isNotEqualTo(derivedB);
        }

        @Test
        @DisplayName("is deterministic for the same root key")
        void deterministic() {
            byte[] rootKey = "deterministic-test".getBytes(StandardCharsets.UTF_8);

            byte[] first = MacaroonCrypto.deriveKey(rootKey);
            byte[] second = MacaroonCrypto.deriveKey(rootKey);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("handles 32-byte binary root key")
        void handlesBinaryRootKey() {
            byte[] rootKey = new byte[32];
            Arrays.fill(rootKey, (byte) 0xAB);

            byte[] derived = MacaroonCrypto.deriveKey(rootKey);
            byte[] expected = referenceHmac(GENERATOR_KEY, rootKey);

            assertThat(derived).hasSize(32);
            assertThat(derived).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("hmac")
    class Hmac {

        @Test
        @DisplayName("returns 32-byte HMAC-SHA256")
        void returns32Bytes() {
            byte[] key = new byte[32];
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

            byte[] result = MacaroonCrypto.hmac(key, data);

            assertThat(result).hasSize(32);
        }

        @Test
        @DisplayName("matches reference HMAC-SHA256 computation")
        void matchesReference() {
            byte[] key = "some-key-value-here".getBytes(StandardCharsets.UTF_8);
            byte[] data = "some-data-value-here".getBytes(StandardCharsets.UTF_8);

            byte[] result = MacaroonCrypto.hmac(key, data);
            byte[] expected = referenceHmac(key, data);

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("produces different output for different data")
        void differentDataProducesDifferentOutput() {
            byte[] key = "shared-key".getBytes(StandardCharsets.UTF_8);
            byte[] dataA = "data-alpha".getBytes(StandardCharsets.UTF_8);
            byte[] dataB = "data-bravo".getBytes(StandardCharsets.UTF_8);

            byte[] resultA = MacaroonCrypto.hmac(key, dataA);
            byte[] resultB = MacaroonCrypto.hmac(key, dataB);

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        @DisplayName("produces different output for different keys")
        void differentKeysProduceDifferentOutput() {
            byte[] keyA = "key-alpha".getBytes(StandardCharsets.UTF_8);
            byte[] keyB = "key-bravo".getBytes(StandardCharsets.UTF_8);
            byte[] data = "same-data".getBytes(StandardCharsets.UTF_8);

            byte[] resultA = MacaroonCrypto.hmac(keyA, data);
            byte[] resultB = MacaroonCrypto.hmac(keyB, data);

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        @DisplayName("handles empty data")
        void handlesEmptyData() {
            byte[] key = "non-empty-key".getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[0];

            byte[] result = MacaroonCrypto.hmac(key, data);
            byte[] expected = referenceHmac(key, data);

            assertThat(result).hasSize(32);
            assertThat(result).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("constantTimeEquals")
    class ConstantTimeEquals {

        @Test
        @DisplayName("returns true for identical arrays")
        void identicalArrays() {
            byte[] a = {0x01, 0x02, 0x03, 0x04};
            byte[] b = {0x01, 0x02, 0x03, 0x04};

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isTrue();
        }

        @Test
        @DisplayName("returns true for two empty arrays")
        void emptyArrays() {
            assertThat(MacaroonCrypto.constantTimeEquals(new byte[0], new byte[0])).isTrue();
        }

        @Test
        @DisplayName("returns false when content differs by one bit")
        void differsByOneBit() {
            byte[] a = {0x01, 0x02, 0x03, 0x04};
            byte[] b = {0x01, 0x02, 0x03, 0x05};

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when content differs in first byte")
        void differsInFirstByte() {
            byte[] a = {0x00, 0x02, 0x03};
            byte[] b = {(byte) 0xFF, 0x02, 0x03};

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false for different lengths")
        void differentLengths() {
            byte[] a = {0x01, 0x02, 0x03};
            byte[] b = {0x01, 0x02};

            assertThat(MacaroonCrypto.constantTimeEquals(a, b)).isFalse();
        }

        @Test
        @DisplayName("returns false when first is empty and second is not")
        void emptyVsNonEmpty() {
            assertThat(MacaroonCrypto.constantTimeEquals(new byte[0], new byte[]{0x01})).isFalse();
        }

        @Test
        @DisplayName("returns true for 32-byte HMAC outputs that are equal")
        void equalHmacOutputs() {
            byte[] key = "test-key".getBytes(StandardCharsets.UTF_8);
            byte[] data = "test-data".getBytes(StandardCharsets.UTF_8);
            byte[] hmac1 = referenceHmac(key, data);
            byte[] hmac2 = referenceHmac(key, data);

            assertThat(MacaroonCrypto.constantTimeEquals(hmac1, hmac2)).isTrue();
        }

        @Test
        @DisplayName("returns false for 32-byte HMAC outputs that differ")
        void differentHmacOutputs() {
            byte[] key = "test-key".getBytes(StandardCharsets.UTF_8);
            byte[] hmac1 = referenceHmac(key, "data-one".getBytes(StandardCharsets.UTF_8));
            byte[] hmac2 = referenceHmac(key, "data-two".getBytes(StandardCharsets.UTF_8));

            assertThat(MacaroonCrypto.constantTimeEquals(hmac1, hmac2)).isFalse();
        }

        @Test
        @DisplayName("returns true for same reference array")
        void sameReference() {
            byte[] a = {0x01, 0x02, 0x03};

            assertThat(MacaroonCrypto.constantTimeEquals(a, a)).isTrue();
        }

        @Test
        @DisplayName("returns false for different lengths without early return (no length leak)")
        void differentLengthsNoTimingLeak() {
            byte[] shorter = {0x01, 0x02, 0x03};
            byte[] longer = {0x01, 0x02, 0x03, 0x04, 0x05};

            // Both orderings must return false
            assertThat(MacaroonCrypto.constantTimeEquals(shorter, longer)).isFalse();
            assertThat(MacaroonCrypto.constantTimeEquals(longer, shorter)).isFalse();

            // Empty vs non-empty
            assertThat(MacaroonCrypto.constantTimeEquals(new byte[0], new byte[]{0x01})).isFalse();
            assertThat(MacaroonCrypto.constantTimeEquals(new byte[]{0x01}, new byte[0])).isFalse();
        }

        @Test
        @DisplayName("returns false for all-zero vs all-one arrays of same length")
        void allZeroVsAllOne() {
            byte[] zeros = new byte[32];
            byte[] ones = new byte[32];
            Arrays.fill(ones, (byte) 0xFF);

            assertThat(MacaroonCrypto.constantTimeEquals(zeros, ones)).isFalse();
        }
    }

    @Nested
    @DisplayName("bindForRequest")
    class BindForRequest {

        @Test
        @DisplayName("produces 32-byte output")
        void produces32Bytes() {
            byte[] rootSig = new byte[32];
            byte[] dischargeSig = new byte[32];
            Arrays.fill(rootSig, (byte) 0x11);
            Arrays.fill(dischargeSig, (byte) 0x22);

            byte[] bound = MacaroonCrypto.bindForRequest(rootSig, dischargeSig);

            assertThat(bound).hasSize(32);
        }

        @Test
        @DisplayName("is deterministic for the same inputs")
        void deterministic() {
            byte[] rootSig = "root-signature-value".getBytes(StandardCharsets.UTF_8);
            byte[] dischargeSig = "discharge-signature-value".getBytes(StandardCharsets.UTF_8);

            byte[] first = MacaroonCrypto.bindForRequest(rootSig, dischargeSig);
            byte[] second = MacaroonCrypto.bindForRequest(rootSig, dischargeSig);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("produces different output for different root signatures")
        void differentRootSigs() {
            byte[] rootSigA = new byte[32];
            byte[] rootSigB = new byte[32];
            Arrays.fill(rootSigA, (byte) 0xAA);
            Arrays.fill(rootSigB, (byte) 0xBB);
            byte[] dischargeSig = new byte[32];
            Arrays.fill(dischargeSig, (byte) 0xCC);

            byte[] boundA = MacaroonCrypto.bindForRequest(rootSigA, dischargeSig);
            byte[] boundB = MacaroonCrypto.bindForRequest(rootSigB, dischargeSig);

            assertThat(boundA).isNotEqualTo(boundB);
        }

        @Test
        @DisplayName("produces different output for different discharge signatures")
        void differentDischargeSigs() {
            byte[] rootSig = new byte[32];
            Arrays.fill(rootSig, (byte) 0xAA);
            byte[] dischargeSigA = new byte[32];
            byte[] dischargeSigB = new byte[32];
            Arrays.fill(dischargeSigA, (byte) 0xBB);
            Arrays.fill(dischargeSigB, (byte) 0xCC);

            byte[] boundA = MacaroonCrypto.bindForRequest(rootSig, dischargeSigA);
            byte[] boundB = MacaroonCrypto.bindForRequest(rootSig, dischargeSigB);

            assertThat(boundA).isNotEqualTo(boundB);
        }

        @Test
        @DisplayName("is not symmetric — swapping rootSig and dischargeSig changes output")
        void notSymmetric() {
            byte[] sigA = new byte[32];
            byte[] sigB = new byte[32];
            Arrays.fill(sigA, (byte) 0x11);
            Arrays.fill(sigB, (byte) 0x22);

            byte[] forward = MacaroonCrypto.bindForRequest(sigA, sigB);
            byte[] reversed = MacaroonCrypto.bindForRequest(sigB, sigA);

            assertThat(forward).isNotEqualTo(reversed);
        }
    }

    @Nested
    @DisplayName("SensitiveBytes overloads")
    class SensitiveBytesOverloads {

        @Test
        @DisplayName("deriveKey(SensitiveBytes) matches deriveKey(byte[])")
        void deriveKeyWithSensitiveBytesMatchesByteArray() {
            byte[] rootKey = new byte[32];
            new SecureRandom().nextBytes(rootKey);
            byte[] expected = MacaroonCrypto.deriveKey(rootKey.clone());
            try (var sb = new SensitiveBytes(rootKey.clone())) {
                try (var result = MacaroonCrypto.deriveKey(sb)) {
                    assertThat(result.value()).isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("hmac(SensitiveBytes, byte[]) matches hmac(byte[], byte[])")
        void hmacWithSensitiveBytesMatchesByteArray() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            byte[] data = "test-data".getBytes(StandardCharsets.UTF_8);
            byte[] expected = MacaroonCrypto.hmac(key.clone(), data);
            try (var sb = new SensitiveBytes(key.clone())) {
                byte[] result = MacaroonCrypto.hmac(sb, data);
                assertThat(result).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("deriveKey(SensitiveBytes) does not destroy the source — source remains usable")
        void deriveKeyDoesNotDestroySource() {
            byte[] rootKey = new byte[32];
            new SecureRandom().nextBytes(rootKey);
            byte[] snapshot = rootKey.clone();
            try (var sb = new SensitiveBytes(rootKey.clone())) {
                try (var ignored = MacaroonCrypto.deriveKey(sb)) {
                    // Source must still be usable after the call
                    assertThat(sb.value()).isEqualTo(snapshot);
                }
            }
        }

        @Test
        @DisplayName("hmac(SensitiveBytes, byte[]) does not destroy the source — source remains usable")
        void hmacDoesNotDestroySource() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            byte[] snapshot = key.clone();
            byte[] data = "test-data".getBytes(StandardCharsets.UTF_8);
            try (var sb = new SensitiveBytes(key.clone())) {
                MacaroonCrypto.hmac(sb, data);
                // Source must still be usable after the call
                assertThat(sb.value()).isEqualTo(snapshot);
            }
        }

        @Test
        @DisplayName("deriveKey(SensitiveBytes) throws ISE when input is destroyed")
        void deriveKeyThrowsWhenDestroyed() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            sb.close();
            assertThatThrownBy(() -> MacaroonCrypto.deriveKey(sb))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("hmac(SensitiveBytes, byte[]) throws ISE when key is destroyed")
        void hmacThrowsWhenKeyDestroyed() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            sb.close();
            assertThatThrownBy(() -> MacaroonCrypto.hmac(sb, new byte[]{4, 5}))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("zeroize")
    class Zeroize {

        @Test
        @DisplayName("delegates to KeyMaterial.zeroize")
        void zeroizeDelegatesToKeyMaterial() {
            byte[] data = {1, 2, 3};
            MacaroonCrypto.zeroize(data);
            assertThat(data).containsOnly((byte) 0);
        }

        @Test
        @DisplayName("handles null without throwing")
        void zeroizeHandlesNull() {
            MacaroonCrypto.zeroize(null);
            // no exception expected
        }
    }
}
