package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CaveatValues")
class CaveatValuesTest {

    @Nested
    @DisplayName("splitBounded")
    class SplitBounded {

        @Test
        @DisplayName("happy path: returns trimmed segments within bounds")
        void happyPath() {
            String[] result = CaveatValues.splitBounded("a,b", 5, "test");
            assertThat(result).containsExactly("a", "b");
        }

        @Test
        @DisplayName("boundary: exactly at limit returns segments")
        void exactlyAtLimit() {
            String[] result = CaveatValues.splitBounded("a,b,c", 3, "test");
            assertThat(result).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("rejection: count exceeds max throws L402Exception")
        void countExceedsMax() {
            assertThatThrownBy(() -> CaveatValues.splitBounded("a,b,c,d", 3, "test"))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402 = (L402Exception) ex;
                        assertThat(l402.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                    })
                    .hasMessageContaining("4")
                    .hasMessageContaining("3")
                    .hasMessageContaining("test");
        }

        @Test
        @DisplayName("rejection: empty segment after trim throws L402Exception")
        void emptySegment() {
            assertThatThrownBy(() -> CaveatValues.splitBounded("a,,b", 5, "test"))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402 = (L402Exception) ex;
                        assertThat(l402.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                    })
                    .hasMessageContaining("Empty");
        }

        @Test
        @DisplayName("rejection: trailing comma produces empty segment")
        void trailingComma() {
            assertThatThrownBy(() -> CaveatValues.splitBounded("a,b,", 5, "test"))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402 = (L402Exception) ex;
                        assertThat(l402.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                    });
        }

        @Test
        @DisplayName("trimming: whitespace around segments is stripped")
        void trimming() {
            String[] result = CaveatValues.splitBounded(" a , b ", 5, "test");
            assertThat(result).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("withinBounds")
    class WithinBounds {

        @Test
        @DisplayName("returns true when segment count equals max")
        void atLimit() {
            assertThat(CaveatValues.withinBounds("a,b,c", 3)).isTrue();
        }

        @Test
        @DisplayName("returns false when segment count exceeds max")
        void exceedsLimit() {
            assertThat(CaveatValues.withinBounds("a,b,c,d", 3)).isFalse();
        }

        @Test
        @DisplayName("returns false when empty segments inflate count beyond max")
        void emptySegmentsInflateCount() {
            // "a,,b" splits to ["a", "", "b"] = 3 segments, max is 2
            assertThat(CaveatValues.withinBounds("a,,b", 2)).isFalse();
        }
    }
}
