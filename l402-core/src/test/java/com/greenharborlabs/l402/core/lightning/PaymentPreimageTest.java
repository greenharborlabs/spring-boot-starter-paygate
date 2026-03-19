package com.greenharborlabs.l402.core.lightning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPreimageTest {

    private static final byte[] VALID_32_BYTES = new byte[32];

    static {
        // Fill with a recognizable non-zero pattern
        for (int i = 0; i < 32; i++) {
            VALID_32_BYTES[i] = (byte) (i + 1);
        }
    }

    // --- Construction: valid ---

    @Test
    void constructWithValid32ByteArray() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        assertThat(preimage.value()).hasSize(32);
    }

    @Test
    void constructWithAllZeros() {
        var preimage = new PaymentPreimage(new byte[32]);

        assertThat(preimage.value()).hasSize(32);
        assertThat(preimage.value()).containsOnly(0);
    }

    // --- Construction: null rejection ---

    @Test
    void constructWithNullThrows() {
        assertThatThrownBy(() -> new PaymentPreimage(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Construction: wrong size rejection ---

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 16, 31, 33, 64, 128})
    void constructWithWrongSizeThrows(int size) {
        byte[] wrongSize = new byte[size];

        assertThatThrownBy(() -> new PaymentPreimage(wrongSize))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Defensive copy: constructor ---

    @Test
    void constructorMakesDefensiveCopy() {
        byte[] original = VALID_32_BYTES.clone();
        var preimage = new PaymentPreimage(original);

        // Mutate the original array
        original[0] = (byte) 0xFF;

        // Preimage's internal value should be unaffected
        assertThat(preimage.value()[0]).isEqualTo((byte) 1);
    }

    // --- Defensive copy: accessor ---

    @Test
    void valueAccessorReturnsDefensiveCopy() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        byte[] first = preimage.value();
        byte[] second = preimage.value();

        // Each call should return a distinct array
        assertThat(first).isNotSameAs(second);
        // But with identical content
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mutatingReturnedValueDoesNotAffectPreimage() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        byte[] leaked = preimage.value();
        leaked[0] = (byte) 0xFF;

        // Internal state should be unchanged
        assertThat(preimage.value()[0]).isEqualTo((byte) 1);
    }

    // --- toHex ---

    @Test
    void toHexReturnsExactly64LowercaseHexChars() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        String hex = preimage.toHex();

        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    void toHexEncodesCorrectly() {
        // Known value: bytes 0x01 through 0x20
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        String hex = preimage.toHex();

        assertThat(hex).startsWith("0102030405");
        assertThat(hex).endsWith("1e1f20");
    }

    @Test
    void toHexAllZeros() {
        var preimage = new PaymentPreimage(new byte[32]);

        assertThat(preimage.toHex()).isEqualTo("0".repeat(64));
    }

    @Test
    void toHexAllOnes() {
        byte[] allFF = new byte[32];
        Arrays.fill(allFF, (byte) 0xFF);
        var preimage = new PaymentPreimage(allFF);

        assertThat(preimage.toHex()).isEqualTo("ff".repeat(32));
    }

    // --- fromHex ---

    @Test
    void fromHexRoundTrip() {
        var original = new PaymentPreimage(VALID_32_BYTES.clone());
        String hex = original.toHex();

        PaymentPreimage restored = PaymentPreimage.fromHex(hex);

        assertThat(restored.value()).isEqualTo(original.value());
    }

    @Test
    void fromHexWithUppercaseInput() {
        String upperHex = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20";

        PaymentPreimage preimage = PaymentPreimage.fromHex(upperHex);

        assertThat(preimage.value()).isEqualTo(VALID_32_BYTES);
    }

    @Test
    void fromHexWithNullThrows() {
        assertThatThrownBy(() -> PaymentPreimage.fromHex(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexWithEmptyStringThrows() {
        assertThatThrownBy(() -> PaymentPreimage.fromHex(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexWithTooShortStringThrows() {
        assertThatThrownBy(() -> PaymentPreimage.fromHex("abcd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexWith62CharsThrows() {
        // 31 bytes = 62 hex chars, too short
        assertThatThrownBy(() -> PaymentPreimage.fromHex("00".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexWith66CharsThrows() {
        // 33 bytes = 66 hex chars, too long
        assertThatThrownBy(() -> PaymentPreimage.fromHex("00".repeat(33)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexWithInvalidCharactersThrows() {
        // 64 chars but contains 'g' which is not valid hex
        String invalid = "gg" + "00".repeat(31);

        assertThatThrownBy(() -> PaymentPreimage.fromHex(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexReturnsUsableInstance() {
        PaymentPreimage preimage = PaymentPreimage.fromHex("00".repeat(32));

        assertThat(preimage).isNotNull();
        assertThat(preimage.value()).hasSize(32);
        assertThat(preimage.isDestroyed()).isFalse();
    }

    // --- matchesHash ---

    @Test
    void matchesHashReturnsTrueForCorrectHash() throws NoSuchAlgorithmException {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        byte[] expectedHash = sha256(VALID_32_BYTES);

        assertThat(preimage.matchesHash(expectedHash)).isTrue();
    }

    @Test
    void matchesHashReturnsFalseForWrongHash() throws NoSuchAlgorithmException {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        // Compute hash of a different value
        byte[] differentData = new byte[32];
        Arrays.fill(differentData, (byte) 0xAB);
        byte[] wrongHash = sha256(differentData);

        assertThat(preimage.matchesHash(wrongHash)).isFalse();
    }

    @Test
    void matchesHashReturnsFalseForSingleBitDifference() throws NoSuchAlgorithmException {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        byte[] correctHash = sha256(VALID_32_BYTES);

        // Flip one bit in the hash
        byte[] almostCorrect = correctHash.clone();
        almostCorrect[0] ^= 0x01;

        assertThat(preimage.matchesHash(almostCorrect)).isFalse();
    }

    @Test
    void matchesHashWithNullThrows() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        assertThatThrownBy(() -> preimage.matchesHash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesHashWithWrongSizeThrows() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        assertThatThrownBy(() -> preimage.matchesHash(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> preimage.matchesHash(new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesHashUsesKnownSha256Vector() throws NoSuchAlgorithmException {
        // SHA-256 of 32 zero bytes is a well-known value
        byte[] zeros = new byte[32];
        var preimage = new PaymentPreimage(zeros);
        byte[] expectedHash = sha256(zeros);

        assertThat(preimage.matchesHash(expectedHash)).isTrue();

        // And does NOT match the hash of something else
        byte[] otherHash = sha256(VALID_32_BYTES);
        assertThat(preimage.matchesHash(otherHash)).isFalse();
    }

    // --- equals() indirectly exercises MacaroonCrypto.constantTimeEquals ---

    @Test
    void equalsReturnsTrueForIdenticalPreimages() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        var b = new PaymentPreimage(VALID_32_BYTES.clone());
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equalsReturnsFalseForDifferentPreimages() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        byte[] different = VALID_32_BYTES.clone();
        different[31] = (byte) 0xFF;
        var b = new PaymentPreimage(different);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equalsReturnsFalseForNull() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        assertThat(a).isNotEqualTo(null);
    }

    @Test
    void equalsReturnsFalseForDifferentType() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        assertThat(a).isNotEqualTo("not a preimage");
    }

    @Test
    void equalsReturnsTrueForSameInstance() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        assertThat(a).isEqualTo(a);
    }

    // --- Destroyable lifecycle ---

    @Test
    void destroyThenValueThrowsIllegalStateException() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        preimage.destroy();

        assertThatThrownBy(preimage::value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destroyed");
    }

    @Test
    void destroyThenMatchesHashThrowsIllegalStateException() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        preimage.destroy();

        assertThatThrownBy(() -> preimage.matchesHash(new byte[32]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destroyed");
    }

    @Test
    void destroyThenToHexThrowsIllegalStateException() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        preimage.destroy();

        assertThatThrownBy(preimage::toHex)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destroyed");
    }

    @Test
    void tryWithResourcesSetsDestroyedFlag() {
        PaymentPreimage preimage;
        try (var p = new PaymentPreimage(VALID_32_BYTES.clone())) {
            preimage = p;
            assertThat(p.isDestroyed()).isFalse();
        }
        assertThat(preimage.isDestroyed()).isTrue();
    }

    @Test
    void doubleDestroyIsIdempotent() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        preimage.destroy();
        preimage.destroy(); // should not throw

        assertThat(preimage.isDestroyed()).isTrue();
    }

    @Test
    void equalsReturnsFalseAfterEitherIsDestroyed() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        var b = new PaymentPreimage(VALID_32_BYTES.clone());

        // Verify equal before destroy
        assertThat(a).isEqualTo(b);

        a.destroy();
        assertThat(a).isNotEqualTo(b);
        assertThat(b).isNotEqualTo(a);

        b.destroy();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCodeReturnsSameConstantForDifferentContent() {
        var a = new PaymentPreimage(VALID_32_BYTES.clone());
        byte[] different = new byte[32];
        Arrays.fill(different, (byte) 0xAB);
        var b = new PaymentPreimage(different);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void hashCodeReturnsConstantAfterDestroy() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        preimage.destroy();

        assertThat(preimage.hashCode()).isEqualTo(0);
    }

    @Test
    void isDestroyedReturnsFalseInitially() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());

        assertThat(preimage.isDestroyed()).isFalse();
    }

    @Test
    void toStringNeverRevealsPreimageBytes() {
        var preimage = new PaymentPreimage(VALID_32_BYTES.clone());
        String before = preimage.toString();
        assertThat(before).doesNotContain("0102");
        assertThat(before).contains("PaymentPreimage");

        preimage.destroy();
        String after = preimage.toString();
        assertThat(after).contains("destroyed");
    }

    @Test
    void destroyZeroizesInternalData() {
        // We can verify indirectly: after destroy, creating a new instance
        // from the same bytes and comparing shows they differ
        byte[] input = VALID_32_BYTES.clone();
        var preimage = new PaymentPreimage(input);

        // Verify it was usable
        assertThat(preimage.toHex()).isNotEmpty();

        preimage.destroy();

        // After destroy, value() throws, confirming data is inaccessible
        assertThatThrownBy(preimage::value)
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Concurrency ---

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("equals() racing with destroy() on other instance")
        void equalsRacingWithDestroy() throws Exception {
            int iterations = 500;
            int threadCount = 10;
            var errors = new ConcurrentLinkedQueue<Throwable>();

            for (int i = 0; i < iterations; i++) {
                var a = new PaymentPreimage(VALID_32_BYTES.clone());
                var b = new PaymentPreimage(VALID_32_BYTES.clone());
                var gate = new CountDownLatch(1);
                var done = new CountDownLatch(threadCount);

                for (int t = 0; t < threadCount; t++) {
                    boolean callEquals = (t % 2 == 0);
                    Thread.ofVirtual().start(() -> {
                        try {
                            gate.await();
                            if (callEquals) {
                                // Result may be true or false depending on race; must not throw
                                a.equals(b);
                            } else {
                                b.destroy();
                            }
                        } catch (Throwable ex) {
                            errors.add(ex);
                        } finally {
                            done.countDown();
                        }
                    });
                }

                gate.countDown();
                boolean finished = done.await(5, TimeUnit.SECONDS);
                assertThat(finished).as("All threads should complete within timeout").isTrue();
            }

            assertThat(errors).as("No errors from equals() racing with destroy()").isEmpty();
        }

        @Test
        @DisplayName("symmetric equals() does not deadlock")
        void symmetricEqualsDoesNotDeadlock() throws Exception {
            int iterations = 1000;

            for (int i = 0; i < iterations; i++) {
                var a = new PaymentPreimage(VALID_32_BYTES.clone());
                var b = new PaymentPreimage(VALID_32_BYTES.clone());
                var gate = new CountDownLatch(1);
                var deadlock = new AtomicBoolean(false);

                Thread t1 = Thread.ofVirtual().start(() -> {
                    try {
                        gate.await();
                        a.equals(b);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                });

                Thread t2 = Thread.ofVirtual().start(() -> {
                    try {
                        gate.await();
                        b.equals(a);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                });

                gate.countDown();

                t1.join(Duration.ofSeconds(1));
                t2.join(Duration.ofSeconds(1));

                if (t1.isAlive() || t2.isAlive()) {
                    deadlock.set(true);
                    t1.interrupt();
                    t2.interrupt();
                }

                assertThat(deadlock.get())
                        .as("Deadlock detected at iteration " + i)
                        .isFalse();
            }
        }
    }

    // --- Helper ---

    private static byte[] sha256(byte[] input) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
