package com.greenharborlabs.paygate.core.lightning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LightningTimeoutExceptionTest {

    @Test
    void isALightningException() {
        var ex = new LightningTimeoutException("timeout");
        assertThat(ex).isInstanceOf(LightningException.class);
    }

    @Test
    void isARuntimeException() {
        var ex = new LightningTimeoutException("timeout");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void messageConstructorPreservesMessage() {
        var ex = new LightningTimeoutException("operation timed out after 5s");
        assertThat(ex.getMessage()).isEqualTo("operation timed out after 5s");
    }

    @Test
    void messageAndCauseConstructorPreservesMessageAndCause() {
        var cause = new java.util.concurrent.TimeoutException("deadline exceeded");
        var ex = new LightningTimeoutException("lookup timed out", cause);

        assertThat(ex.getMessage()).isEqualTo("lookup timed out");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
