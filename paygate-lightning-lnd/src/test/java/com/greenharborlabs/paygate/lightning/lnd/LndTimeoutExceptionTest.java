package com.greenharborlabs.paygate.lightning.lnd;

import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LndTimeoutExceptionTest {

    @Test
    void extendsLightningTimeoutException() {
        var exception = new LndTimeoutException("timeout");
        assertThat(exception).isInstanceOf(LightningTimeoutException.class);
        assertThat(exception).isInstanceOf(LightningException.class);
    }

    @Test
    void messageOnlyConstructor() {
        var exception = new LndTimeoutException("LND timed out");
        assertThat(exception.getMessage()).isEqualTo("LND timed out");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new RuntimeException("gRPC deadline exceeded");
        var exception = new LndTimeoutException("LND timed out", cause);
        assertThat(exception.getMessage()).isEqualTo("LND timed out");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
