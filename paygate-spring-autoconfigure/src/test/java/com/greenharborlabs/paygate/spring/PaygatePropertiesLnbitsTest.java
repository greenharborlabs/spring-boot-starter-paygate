package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PaygateProperties.Lnbits} configuration property validation.
 */
class PaygatePropertiesLnbitsTest {

    @Test
    @DisplayName("Lnbits rejects zero requestTimeoutSeconds")
    void requestTimeoutSecondsRejectsZero() {
        var lnbits = new PaygateProperties.Lnbits();
        assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnbits rejects negative requestTimeoutSeconds")
    void requestTimeoutSecondsRejectsNegative() {
        var lnbits = new PaygateProperties.Lnbits();
        assertThatThrownBy(() -> lnbits.setRequestTimeoutSeconds(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnbits accepts null requestTimeoutSeconds")
    void requestTimeoutSecondsAcceptsNull() {
        var lnbits = new PaygateProperties.Lnbits();
        lnbits.setRequestTimeoutSeconds(null);
        assertThat(lnbits.getRequestTimeoutSeconds()).isNull();
    }

    @Test
    @DisplayName("Lnbits accepts positive requestTimeoutSeconds")
    void requestTimeoutSecondsAcceptsPositive() {
        var lnbits = new PaygateProperties.Lnbits();
        lnbits.setRequestTimeoutSeconds(10);
        assertThat(lnbits.getRequestTimeoutSeconds()).isEqualTo(10);
    }
}
