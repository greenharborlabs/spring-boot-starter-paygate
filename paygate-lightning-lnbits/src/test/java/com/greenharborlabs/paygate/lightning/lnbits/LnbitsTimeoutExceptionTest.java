package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;
import org.junit.jupiter.api.Test;

class LnbitsTimeoutExceptionTest {

    @Test
    void extendsLightningTimeoutException() {
        var ex = new LnbitsTimeoutException("timeout");
        assertThat(ex).isInstanceOf(LightningTimeoutException.class);
        assertThat(ex).isInstanceOf(LightningException.class);
    }

    @Test
    void messageOnlyConstructor() {
        var ex = new LnbitsTimeoutException("request timed out");
        assertThat(ex.getMessage()).isEqualTo("request timed out");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new RuntimeException("underlying");
        var ex = new LnbitsTimeoutException("timed out", cause);
        assertThat(ex.getMessage()).isEqualTo("timed out");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
