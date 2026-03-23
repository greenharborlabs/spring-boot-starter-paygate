package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JcsSerializer (RFC 8785)")
class JcsSerializerTest {

    @Nested
    @DisplayName("key sorting")
    class KeySorting {

        @Test
        @DisplayName("sorts keys lexicographically")
        void sortedKeys() {
            var map = new LinkedHashMap<String, Object>();
            map.put("b", "2");
            map.put("a", "1");

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
        }

        @Test
        @DisplayName("sorts keys by Unicode code point order")
        void unicodeCodePointOrder() {
            var map = new LinkedHashMap<String, Object>();
            map.put("z", 3);
            map.put("a", 1);
            map.put("m", 2);

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"a\":1,\"m\":2,\"z\":3}");
        }
    }

    @Nested
    @DisplayName("nested objects")
    class NestedObjects {

        @Test
        @DisplayName("recursively sorts nested map keys")
        void recursivelySortedNestedMaps() {
            var inner = new LinkedHashMap<String, Object>();
            inner.put("y", "val-y");
            inner.put("x", "val-x");

            var outer = new LinkedHashMap<String, Object>();
            outer.put("b", inner);
            outer.put("a", "val-a");

            assertThat(JcsSerializer.serialize(outer))
                    .isEqualTo("{\"a\":\"val-a\",\"b\":{\"x\":\"val-x\",\"y\":\"val-y\"}}");
        }

        @Test
        @DisplayName("handles deeply nested maps")
        void deeplyNestedMaps() {
            var level2 = new LinkedHashMap<String, Object>();
            level2.put("c", "deep");

            var level1 = new LinkedHashMap<String, Object>();
            level1.put("b", level2);

            var root = new LinkedHashMap<String, Object>();
            root.put("a", level1);

            assertThat(JcsSerializer.serialize(root))
                    .isEqualTo("{\"a\":{\"b\":{\"c\":\"deep\"}}}");
        }
    }

    @Nested
    @DisplayName("mixed types")
    class MixedTypes {

        @Test
        @DisplayName("serializes String, Integer, Long, Double, Boolean, and null in a single map")
        void mixedTypesInSingleMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("bool", true);
            map.put("dbl", 3.14);
            map.put("int", 42);
            map.put("lng", 9999999999L);
            map.put("nil", null);
            map.put("str", "hello");

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"bool\":true,\"dbl\":3.14,\"int\":42,\"lng\":9999999999,\"nil\":null,\"str\":\"hello\"}");
        }

        @Test
        @DisplayName("serializes false and zero correctly")
        void falseAndZero() {
            var map = new LinkedHashMap<String, Object>();
            map.put("f", false);
            map.put("z", 0);

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"f\":false,\"z\":0}");
        }
    }

    @Nested
    @DisplayName("list serialization")
    class ListSerialization {

        @Test
        @DisplayName("preserves list insertion order")
        void preservesOrder() {
            var map = Map.<String, Object>of("items", List.of("c", "a", "b"));

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"items\":[\"c\",\"a\",\"b\"]}");
        }

        @Test
        @DisplayName("serializes empty list")
        void emptyList() {
            var map = Map.<String, Object>of("items", List.of());

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"items\":[]}");
        }

        @Test
        @DisplayName("serializes nested list in map")
        void nestedListInMap() {
            var inner = new LinkedHashMap<String, Object>();
            inner.put("id", 1);

            var list = new java.util.ArrayList<>();
            list.add(inner);
            list.add("text");
            list.add(42);
            list.add(true);
            list.add(null);

            var map = new LinkedHashMap<String, Object>();
            map.put("data", list);

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"data\":[{\"id\":1},\"text\",42,true,null]}");
        }

        @Test
        @DisplayName("serializes list with mixed types")
        void listWithMixedTypes() {
            var map = Map.<String, Object>of("mix", List.of(1, "two", true));

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"mix\":[1,\"two\",true]}");
        }
    }

    @Nested
    @DisplayName("string escaping")
    class StringEscaping {

        @Test
        @DisplayName("escapes quotes and backslashes")
        void quotesAndBackslashes() {
            var map = Map.<String, Object>of("val", "say \"hello\" and use \\path");

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"val\":\"say \\\"hello\\\" and use \\\\path\"}");
        }

        @Test
        @DisplayName("escapes newlines, tabs, and other control characters")
        void controlCharacters() {
            var map = new LinkedHashMap<String, Object>();
            map.put("bs", "\b");
            map.put("ff", "\f");
            map.put("lf", "\n");
            map.put("cr", "\r");
            map.put("tab", "\t");

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"bs\":\"\\b\",\"cr\":\"\\r\",\"ff\":\"\\f\",\"lf\":\"\\n\",\"tab\":\"\\t\"}");
        }

        @Test
        @DisplayName("escapes control characters below 0x20 as \\uXXXX")
        void lowControlChars() {
            // 0x01 (SOH) and 0x1F (US) should be escaped as \u0001 and \u001f
            var map = Map.<String, Object>of("ctrl", "\u0001\u001f");

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"ctrl\":\"\\u0001\\u001f\"}");
        }

        @Test
        @DisplayName("does NOT escape forward slash per RFC 8785")
        void forwardSlashNotEscaped() {
            var map = Map.<String, Object>of("url", "https://example.com/path");

            assertThat(JcsSerializer.serialize(map))
                    .isEqualTo("{\"url\":\"https://example.com/path\"}");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null map returns \"null\"")
        void nullMapReturnsNull() {
            assertThat(JcsSerializer.serialize(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("empty map returns \"{}\"")
        void emptyMapReturnsEmptyObject() {
            assertThat(JcsSerializer.serialize(Collections.emptyMap())).isEqualTo("{}");
        }

        @Test
        @DisplayName("single-entry map has no comma")
        void singleEntry() {
            var map = Map.<String, Object>of("only", "one");

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"only\":\"one\"}");
        }
    }

    @Nested
    @DisplayName("number serialization")
    class NumberSerialization {

        @Test
        @DisplayName("integer doubles render without decimal point")
        void integerDoublesNoDecimal() {
            var map = Map.<String, Object>of("val", 42.0);

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"val\":42}");
        }

        @Test
        @DisplayName("non-integer doubles render with decimal")
        void nonIntegerDoubles() {
            var map = Map.<String, Object>of("val", 3.14);

            assertThat(JcsSerializer.serialize(map)).isEqualTo("{\"val\":3.14}");
        }

        @Test
        @DisplayName("NaN throws IllegalArgumentException")
        void nanRejected() {
            var map = Map.<String, Object>of("val", Double.NaN);

            assertThatThrownBy(() -> JcsSerializer.serialize(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("positive Infinity throws IllegalArgumentException")
        void positiveInfinityRejected() {
            var map = Map.<String, Object>of("val", Double.POSITIVE_INFINITY);

            assertThatThrownBy(() -> JcsSerializer.serialize(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("negative Infinity throws IllegalArgumentException")
        void negativeInfinityRejected() {
            var map = Map.<String, Object>of("val", Double.NEGATIVE_INFINITY);

            assertThatThrownBy(() -> JcsSerializer.serialize(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Infinity");
        }

        @Test
        @DisplayName("Float NaN throws IllegalArgumentException")
        void floatNanRejected() {
            var map = Map.<String, Object>of("val", Float.NaN);

            assertThatThrownBy(() -> JcsSerializer.serialize(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NaN");
        }
    }

    @Nested
    @DisplayName("unsupported types")
    class UnsupportedTypes {

        @Test
        @DisplayName("unsupported type throws IllegalArgumentException")
        void unsupportedTypeRejected() {
            var map = Map.<String, Object>of("val", new Object() {
                @Override
                public String toString() {
                    return "custom";
                }
            });

            assertThatThrownBy(() -> JcsSerializer.serialize(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported type");
        }
    }

    @Nested
    @DisplayName("LightningChargeRequest integration")
    class LightningChargeRequestIntegration {

        @Test
        @DisplayName("toJcsMap produces correct canonical JSON with description")
        void chargeRequestWithDescription() {
            var request = new LightningChargeRequest(
                    "100",
                    "BTC",
                    "Access to API",
                    new LightningChargeRequest.MethodDetails("lnbc100...", "abc123", "mainnet")
            );

            String json = JcsSerializer.serialize(request.toJcsMap());

            assertThat(json).isEqualTo(
                    "{\"amount\":\"100\",\"currency\":\"BTC\",\"description\":\"Access to API\","
                    + "\"methodDetails\":{\"invoice\":\"lnbc100...\",\"network\":\"mainnet\",\"paymentHash\":\"abc123\"}}");
        }

        @Test
        @DisplayName("toJcsMap omits description when null and still produces valid canonical JSON")
        void chargeRequestWithoutDescription() {
            var request = new LightningChargeRequest(
                    "50",
                    "BTC",
                    null,
                    new LightningChargeRequest.MethodDetails("lnbc50...", "def456", "testnet")
            );

            String json = JcsSerializer.serialize(request.toJcsMap());

            assertThat(json).isEqualTo(
                    "{\"amount\":\"50\",\"currency\":\"BTC\","
                    + "\"methodDetails\":{\"invoice\":\"lnbc50...\",\"network\":\"testnet\",\"paymentHash\":\"def456\"}}");
        }

        @Test
        @DisplayName("methodDetails keys are sorted: invoice, network, paymentHash")
        void methodDetailsKeySorting() {
            var details = new LightningChargeRequest.MethodDetails("inv", "ph", "net");
            String json = JcsSerializer.serialize(Map.of("d", details.toJcsMap()));

            // Keys within methodDetails should be: invoice, network, paymentHash (lexicographic)
            assertThat(json).isEqualTo("{\"d\":{\"invoice\":\"inv\",\"network\":\"net\",\"paymentHash\":\"ph\"}}");
        }
    }
}
