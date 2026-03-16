package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link L402Properties.Lnbits} configuration property validation.
 */
class L402PropertiesLnbitsTest {

    @Test
    @DisplayName("Lnbits rejects zero requestTimeoutSeconds")
    void requestTimeoutSecondsRejectsZero() {
        var lnbits = new L402Properties.Lnbits();
        assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnbits rejects negative requestTimeoutSeconds")
    void requestTimeoutSecondsRejectsNegative() {
        var lnbits = new L402Properties.Lnbits();
        assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnbits accepts null requestTimeoutSeconds")
    void requestTimeoutSecondsAcceptsNull() {
        var lnbits = new L402Properties.Lnbits();
        lnbits.setRequestTimeoutSeconds(null);
        assertThat(lnbits.getRequestTimeoutSeconds()).isNull();
    }

    @Test
    @DisplayName("Lnbits accepts positive requestTimeoutSeconds")
    void requestTimeoutSecondsAcceptsPositive() {
        var lnbits = new L402Properties.Lnbits();
        lnbits.setRequestTimeoutSeconds(10);
        assertThat(lnbits.getRequestTimeoutSeconds()).isEqualTo(10);
    }
}
