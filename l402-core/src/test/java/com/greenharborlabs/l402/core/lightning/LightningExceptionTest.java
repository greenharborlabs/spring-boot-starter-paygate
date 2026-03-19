package com.greenharborlabs.l402.core.lightning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class LightningExceptionTest {

    @Test
    void messageOnlyConstructor() {
        var ex = new LightningException("connection refused");
        assertThat(ex).hasMessage("connection refused");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new java.io.IOException("timeout");
        var ex = new LightningException("backend unavailable", cause);
        assertThat(ex).hasMessage("backend unavailable");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void canBeCaughtAsRuntimeException() {
        Throwable thrown = catchThrowable(() -> {
            throw new LightningException("test");
        });
        assertThat(thrown).isInstanceOf(RuntimeException.class);
    }
}
