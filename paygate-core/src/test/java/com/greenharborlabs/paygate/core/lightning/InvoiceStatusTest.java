package com.greenharborlabs.paygate.core.lightning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceStatusTest {

    @Test
    void hasExactlyFourValues() {
        assertThat(InvoiceStatus.values()).hasSize(4);
    }

    @Test
    void valuesAreInExpectedOrder() {
        assertThat(InvoiceStatus.values()).containsExactly(
                InvoiceStatus.PENDING,
                InvoiceStatus.SETTLED,
                InvoiceStatus.CANCELLED,
                InvoiceStatus.EXPIRED
        );
    }

    @Test
    void valueOfReturnsCorrectEnum() {
        assertThat(InvoiceStatus.valueOf("PENDING")).isEqualTo(InvoiceStatus.PENDING);
        assertThat(InvoiceStatus.valueOf("SETTLED")).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(InvoiceStatus.valueOf("CANCELLED")).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(InvoiceStatus.valueOf("EXPIRED")).isEqualTo(InvoiceStatus.EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "Settled", "UNKNOWN", "", "cancelled"})
    void valueOfThrowsForInvalidNames(String invalid) {
        assertThatThrownBy(() -> InvoiceStatus.valueOf(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordinalValues() {
        assertThat(InvoiceStatus.PENDING.ordinal()).isZero();
        assertThat(InvoiceStatus.SETTLED.ordinal()).isEqualTo(1);
        assertThat(InvoiceStatus.CANCELLED.ordinal()).isEqualTo(2);
        assertThat(InvoiceStatus.EXPIRED.ordinal()).isEqualTo(3);
    }

    @Test
    void nameReturnsExpectedStrings() {
        assertThat(InvoiceStatus.PENDING.name()).isEqualTo("PENDING");
        assertThat(InvoiceStatus.SETTLED.name()).isEqualTo("SETTLED");
        assertThat(InvoiceStatus.CANCELLED.name()).isEqualTo("CANCELLED");
        assertThat(InvoiceStatus.EXPIRED.name()).isEqualTo("EXPIRED");
    }
}
