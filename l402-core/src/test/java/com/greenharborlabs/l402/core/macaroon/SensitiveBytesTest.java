package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SensitiveBytes")
class SensitiveBytesTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("defensively copies input and zeroes original array")
        void constructorZeroesOriginalArray() {
            byte[] raw = new byte[]{1, 2, 3};
            var sb = new SensitiveBytes(raw);
            assertThat(raw).containsOnly(0);
            assertThat(sb.value()).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("null input throws NullPointerException")
        void nullInputThrowsNpe() {
            assertThatThrownBy(() -> new SensitiveBytes(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("zero-length input throws IllegalArgumentException")
        void emptyInputThrowsIae() {
            assertThatThrownBy(() -> new SensitiveBytes(new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }
    }

    @Nested
    @DisplayName("value()")
    class Value {

        @Test
        @DisplayName("returns independent copy each time")
        void valueReturnsCopy() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            byte[] a = sb.value();
            byte[] b = sb.value();
            assertThat(a).isNotSameAs(b);
            assertThat(a).containsExactly(b);
        }

        @Test
        @DisplayName("mutating returned copy does not affect internal data")
        void mutatingCopyDoesNotAffectInternal() {
            var sb = new SensitiveBytes(new byte[]{10, 20, 30});
            byte[] copy = sb.value();
            copy[0] = 99;
            assertThat(sb.value()).containsExactly(10, 20, 30);
        }

        @Test
        @DisplayName("throws IllegalStateException after destruction")
        void throwsAfterDestruction() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            sb.close();
            assertThatThrownBy(sb::value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Key material has been destroyed");
        }
    }

    @Nested
    @DisplayName("Destruction lifecycle")
    class Destruction {

        @Test
        @DisplayName("close() zeroizes internal data and sets destroyed flag")
        void closeZeroizesInternalData() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            assertThat(sb.value()).containsExactly(1, 2, 3);
            sb.close();
            assertThat(sb.isDestroyed()).isTrue();
            assertThatThrownBy(sb::value).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("destroy() works directly, not just via close()")
        void destroyDirectly() {
            var sb = new SensitiveBytes(new byte[]{4, 5, 6});
            sb.destroy();
            assertThat(sb.isDestroyed()).isTrue();
            assertThatThrownBy(sb::value).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("double close is idempotent")
        void doubleCloseIsIdempotent() {
            var sb = new SensitiveBytes(new byte[]{7, 8, 9});
            sb.close();
            sb.close(); // must not throw
            assertThat(sb.isDestroyed()).isTrue();
        }

        @Test
        @DisplayName("double destroy is idempotent")
        void doubleDestroyIsIdempotent() {
            var sb = new SensitiveBytes(new byte[]{7, 8, 9});
            sb.destroy();
            sb.destroy(); // must not throw
            assertThat(sb.isDestroyed()).isTrue();
        }

        @Test
        @DisplayName("try-with-resources zeroizes on scope exit")
        void tryWithResourcesZeroizes() {
            SensitiveBytes ref;
            try (var sb = new SensitiveBytes(new byte[]{5, 6, 7})) {
                ref = sb;
                assertThat(ref.value()).containsExactly(5, 6, 7);
            }
            assertThat(ref.isDestroyed()).isTrue();
        }
    }

    @Nested
    @DisplayName("equals() and hashCode()")
    class EqualityAndHashCode {

        @Test
        @DisplayName("equal values are equal")
        void equalValues() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{1, 2, 3});
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentValues() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{4, 5, 6});
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different lengths are not equal")
        void differentLengths() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{1, 2});
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("destroyed instance is not equal to anything")
        void destroyedNotEqual() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{1, 2, 3});
            a.close();
            assertThat(a).isNotEqualTo(b);
            assertThat(b).isNotEqualTo(a);
        }

        @Test
        @DisplayName("two destroyed instances are not equal")
        void twoDestroyedNotEqual() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{1, 2, 3});
            a.close();
            b.close();
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("not equal to null or other types")
        void notEqualToNullOrOtherTypes() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            assertThat(sb).isNotEqualTo(null);
            assertThat(sb).isNotEqualTo("not a SensitiveBytes");
        }

        @Test
        @DisplayName("same instance is equal to itself")
        void sameInstanceEquals() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            assertThat(sb).isEqualTo(sb);
        }

        @Test
        @DisplayName("hashCode returns constant 0 after destroy")
        void hashCode_afterDestroy_returnsConstant() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            sb.destroy();
            assertThat(sb.hashCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("hashCode returns same constant after destroy regardless of original content")
        void hashCode_afterDestroy_sameForDifferentContent() {
            var a = new SensitiveBytes(new byte[]{1, 2, 3});
            var b = new SensitiveBytes(new byte[]{99, 98, 97, 96});
            a.destroy();
            b.destroy();
            assertThat(a.hashCode()).isEqualTo(b.hashCode()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("does not leak content, shows length only")
        void toStringDoesNotLeakContent() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3});
            assertThat(sb.toString()).isEqualTo("SensitiveBytes[length=3]");
        }

        @Test
        @DisplayName("works after destruction")
        void toStringAfterDestruction() {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3, 4, 5});
            sb.close();
            assertThat(sb.toString()).isEqualTo("SensitiveBytes[length=5]");
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent destroy from multiple threads does not throw")
        void concurrent_destroy_noException() throws Exception {
            var sb = new SensitiveBytes(new byte[]{1, 2, 3, 4, 5});
            int threadCount = 10;
            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(threadCount);
            var errors = new ConcurrentLinkedQueue<Throwable>();

            for (int i = 0; i < threadCount; i++) {
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        sb.destroy();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            doneLatch.await();
            assertThat(errors).isEmpty();
            assertThat(sb.isDestroyed()).isTrue();
        }

        @Test
        @DisplayName("concurrent close and value produce no unexpected exceptions")
        void concurrentCloseAndValue() throws Exception {
            var sb = new SensitiveBytes(new byte[32]);
            var latch = new CountDownLatch(1);
            var results = new ConcurrentLinkedQueue<Throwable>();
            Thread t = Thread.startVirtualThread(() -> {
                latch.countDown();
                for (int i = 0; i < 1000; i++) {
                    try {
                        sb.value();
                    } catch (IllegalStateException _) {
                        // expected after close
                    } catch (Throwable unexpected) {
                        results.add(unexpected);
                    }
                }
            });
            latch.await();
            sb.close();
            t.join();
            assertThat(results).isEmpty();
        }
    }
}
