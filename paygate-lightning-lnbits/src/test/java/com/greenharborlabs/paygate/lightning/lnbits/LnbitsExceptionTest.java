package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.LightningException;
import org.junit.jupiter.api.Test;

class LnbitsExceptionTest {

    @Test
    void extendsLightningException() {
        var ex = new LnbitsException("x");
        assertThat(ex).isInstanceOf(LightningException.class);
    }

    @Test
    void messageOnlyConstructor() {
        var ex = new LnbitsException("api failed");
        assertThat(ex.getMessage()).isEqualTo("api failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new RuntimeException("underlying");
        var ex = new LnbitsException("api failed", cause);
        assertThat(ex.getMessage()).isEqualTo("api failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
