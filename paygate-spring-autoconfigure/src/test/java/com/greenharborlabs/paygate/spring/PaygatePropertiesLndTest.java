package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PaygateProperties.Lnd} configuration property binding,
 * focusing on the keepalive, idle timeout, and max inbound message size fields.
 */
class PaygatePropertiesLndTest {

    @Test
    @DisplayName("Lnd defaults: existing fields unchanged")
    void existingDefaults() {
        var lnd = new PaygateProperties.Lnd();

        assertThat(lnd.getHost()).isEqualTo("localhost");
        assertThat(lnd.getPort()).isEqualTo(10009);
        assertThat(lnd.getTlsCertPath()).isNull();
        assertThat(lnd.getMacaroonPath()).isNull();
        assertThat(lnd.isAllowPlaintext()).isFalse();
    }

    @Test
    @DisplayName("Lnd defaults: keepAliveTimeSeconds is 60")
    void keepAliveTimeSecondsDefault() {
        var lnd = new PaygateProperties.Lnd();
        assertThat(lnd.getKeepAliveTimeSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("Lnd defaults: keepAliveTimeoutSeconds is 20")
    void keepAliveTimeoutSecondsDefault() {
        var lnd = new PaygateProperties.Lnd();
        assertThat(lnd.getKeepAliveTimeoutSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("Lnd defaults: idleTimeoutMinutes is 5")
    void idleTimeoutMinutesDefault() {
        var lnd = new PaygateProperties.Lnd();
        assertThat(lnd.getIdleTimeoutMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Lnd defaults: maxInboundMessageSize is 4MB")
    void maxInboundMessageSizeDefault() {
        var lnd = new PaygateProperties.Lnd();
        assertThat(lnd.getMaxInboundMessageSize()).isEqualTo(4_194_304);
    }

    @Test
    @DisplayName("Lnd defaults: rpcDeadlineSeconds is null (uses global)")
    void rpcDeadlineSecondsDefaultNull() {
        var lnd = new PaygateProperties.Lnd();
        assertThat(lnd.getRpcDeadlineSeconds()).isNull();
    }

    @Test
    @DisplayName("Lnd rejects zero rpcDeadlineSeconds")
    void rpcDeadlineSecondsRejectsZero() {
        var lnd = new PaygateProperties.Lnd();
        assertThatThrownBy(() -> lnd.setRpcDeadlineSeconds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rpc-deadline-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnd rejects negative rpcDeadlineSeconds")
    void rpcDeadlineSecondsRejectsNegative() {
        var lnd = new PaygateProperties.Lnd();
        assertThatThrownBy(() -> lnd.setRpcDeadlineSeconds(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rpc-deadline-seconds must be > 0");
    }

    @Test
    @DisplayName("Lnd accepts null rpcDeadlineSeconds")
    void rpcDeadlineSecondsAcceptsNull() {
        var lnd = new PaygateProperties.Lnd();
        lnd.setRpcDeadlineSeconds(null);
        assertThat(lnd.getRpcDeadlineSeconds()).isNull();
    }

    @Test
    @DisplayName("Lnd setters update all new fields")
    void settersWork() {
        var lnd = new PaygateProperties.Lnd();

        lnd.setKeepAliveTimeSeconds(120);
        lnd.setKeepAliveTimeoutSeconds(30);
        lnd.setIdleTimeoutMinutes(10);
        lnd.setMaxInboundMessageSize(8_388_608);
        lnd.setRpcDeadlineSeconds(15);

        assertThat(lnd.getKeepAliveTimeSeconds()).isEqualTo(120);
        assertThat(lnd.getKeepAliveTimeoutSeconds()).isEqualTo(30);
        assertThat(lnd.getIdleTimeoutMinutes()).isEqualTo(10);
        assertThat(lnd.getMaxInboundMessageSize()).isEqualTo(8_388_608);
        assertThat(lnd.getRpcDeadlineSeconds()).isEqualTo(15);
    }
}
