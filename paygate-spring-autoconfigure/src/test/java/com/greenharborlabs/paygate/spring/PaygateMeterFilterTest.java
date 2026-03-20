package com.greenharborlabs.paygate.spring;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaygateMeterFilter — endpoint tag cardinality capping")
class PaygateMeterFilterTest {

    private static final int DEFAULT_CAP = 100;
    private static final String DEFAULT_OVERFLOW = "_other";

    private PaygateMeterFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PaygateMeterFilter(DEFAULT_CAP, DEFAULT_OVERFLOW);
    }

    @Test
    @DisplayName("allows up to cap distinct endpoints, then replaces with overflow sentinel")
    void cardinalityCapBehavior() {
        // Register meters for 100 distinct endpoints — all should pass through
        for (int i = 0; i < DEFAULT_CAP; i++) {
            Meter.Id input = meterId("paygate.requests", "endpoint", "/api/endpoint-" + i);
            Meter.Id result = filter.map(input);
            assertThat(result.getTag("endpoint")).isEqualTo("/api/endpoint-" + i);
        }

        // The 101st endpoint should get the overflow tag
        Meter.Id overflow = meterId("paygate.requests", "endpoint", "/api/endpoint-100");
        Meter.Id result = filter.map(overflow);
        assertThat(result.getTag("endpoint")).isEqualTo(DEFAULT_OVERFLOW);
    }

    @Test
    @DisplayName("non-L402 meters pass through unmodified")
    void nonL402MetersPassThrough() {
        Meter.Id input = meterId("http.requests", "endpoint", "/api/something");
        Meter.Id result = filter.map(input);

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("L402 meters without endpoint tag pass through unmodified")
    void noEndpointTagPassesThrough() {
        Meter.Id input = meterId("paygate.requests", "result", "challenged");
        Meter.Id result = filter.map(input);

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("already-seen endpoint passes through unchanged on subsequent calls")
    void alreadySeenEndpointPassesThrough() {
        String endpoint = "/api/items/{id}";
        Meter.Id first = meterId("paygate.requests", "endpoint", endpoint);
        Meter.Id second = meterId("paygate.invoices.created", "endpoint", endpoint);

        Meter.Id firstResult = filter.map(first);
        Meter.Id secondResult = filter.map(second);

        assertThat(firstResult.getTag("endpoint")).isEqualTo(endpoint);
        assertThat(secondResult.getTag("endpoint")).isEqualTo(endpoint);
    }

    @Test
    @DisplayName("uses custom overflow tag value when configured")
    void customOverflowTagValue() {
        String customOverflow = "__overflow__";
        var customFilter = new PaygateMeterFilter(2, customOverflow);

        // Fill the cap
        customFilter.map(meterId("paygate.requests", "endpoint", "/a"));
        customFilter.map(meterId("paygate.requests", "endpoint", "/b"));

        // Third should use custom overflow
        Meter.Id result = customFilter.map(meterId("paygate.requests", "endpoint", "/c"));
        assertThat(result.getTag("endpoint")).isEqualTo(customOverflow);
    }

    /**
     * Helper to build a {@link Meter.Id} with the given name and key-value tag pairs.
     */
    private Meter.Id meterId(String name, String... tagPairs) {
        if (tagPairs.length % 2 != 0) {
            throw new IllegalArgumentException("tagPairs must have even length");
        }
        var tags = new java.util.ArrayList<Tag>();
        for (int i = 0; i < tagPairs.length; i += 2) {
            tags.add(Tag.of(tagPairs[i], tagPairs[i + 1]));
        }
        return new Meter.Id(name, Tags.of(tags), null, null, Meter.Type.COUNTER);
    }
}
