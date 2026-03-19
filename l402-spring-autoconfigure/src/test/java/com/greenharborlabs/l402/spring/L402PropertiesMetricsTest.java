package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L402PropertiesMetricsTest {

    @Test
    void defaultMaxEndpointCardinality() {
        var props = new L402Properties();
        assertThat(props.getMetrics().getMaxEndpointCardinality()).isEqualTo(100);
    }

    @Test
    void defaultOverflowTagValue() {
        var props = new L402Properties();
        assertThat(props.getMetrics().getOverflowTagValue()).isEqualTo("_other");
    }

    @Test
    void customValuesBindCorrectly() {
        var metrics = new L402Properties.Metrics();
        metrics.setMaxEndpointCardinality(50);
        metrics.setOverflowTagValue("overflow");

        assertThat(metrics.getMaxEndpointCardinality()).isEqualTo(50);
        assertThat(metrics.getOverflowTagValue()).isEqualTo("overflow");
    }

    @Test
    void rejectsZeroCardinality() {
        var metrics = new L402Properties.Metrics();
        assertThatThrownBy(() -> metrics.setMaxEndpointCardinality(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 1");
    }

    @Test
    void rejectsNegativeCardinality() {
        var metrics = new L402Properties.Metrics();
        assertThatThrownBy(() -> metrics.setMaxEndpointCardinality(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 1");
    }
}
