package com.greenharborlabs.paygate.lightning.lnd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LndConfigTest {

    // --- Full canonical constructor tests ---

    @Test
    void validConfig_withAllFields() {
        var config = new LndConfig("localhost", 10009, "/path/tls.cert", "/path/admin.macaroon",
                false, 60, 20, 5, 4_194_304, 5);

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(10009);
        assertThat(config.tlsCertPath()).isEqualTo("/path/tls.cert");
        assertThat(config.macaroonPath()).isEqualTo("/path/admin.macaroon");
        assertThat(config.allowPlaintext()).isFalse();
        assertThat(config.keepAliveTimeSeconds()).isEqualTo(60);
        assertThat(config.keepAliveTimeoutSeconds()).isEqualTo(20);
        assertThat(config.idleTimeoutMinutes()).isEqualTo(5);
        assertThat(config.maxInboundMessageSize()).isEqualTo(4_194_304);
        assertThat(config.rpcDeadlineSeconds()).isEqualTo(5);
    }

    @Test
    void validConfig_plaintextWithNullPaths() {
        var config = new LndConfig("localhost", 10009, null, null,
                true, 30, 10, 3, 2_097_152, 10);

        assertThat(config.tlsCertPath()).isNull();
        assertThat(config.macaroonPath()).isNull();
        assertThat(config.allowPlaintext()).isTrue();
        assertThat(config.rpcDeadlineSeconds()).isEqualTo(10);
    }

    // --- Host validation ---

    @Test
    void shouldRejectNullHost() {
        assertThatThrownBy(() -> new LndConfig(null, 10009, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void shouldRejectBlankHost() {
        assertThatThrownBy(() -> new LndConfig("  ", 10009, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void shouldRejectEmptyHost() {
        assertThatThrownBy(() -> new LndConfig("", 10009, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    // --- Port validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, 65536, 70000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void shouldRejectInvalidPort(int port) {
        assertThatThrownBy(() -> new LndConfig("localhost", port, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 443, 10009, 65535})
    void shouldAcceptValidPort(int port) {
        var config = new LndConfig("localhost", port, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5);
        assertThat(config.port()).isEqualTo(port);
    }

    // --- Plaintext guard ---

    @Test
    void shouldRejectNullTlsCertWhenPlaintextDisabled() {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, null, null,
                false, 60, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tlsCertPath")
                .hasMessageContaining("allowPlaintext");
    }

    @Test
    void shouldAllowNullTlsCertWhenPlaintextEnabled() {
        var config = new LndConfig("localhost", 10009, null, null,
                true, 60, 20, 5, 4_194_304, 5);
        assertThat(config.allowPlaintext()).isTrue();
        assertThat(config.tlsCertPath()).isNull();
    }

    // --- keepAliveTimeSeconds validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveKeepAliveTimeSeconds(int value) {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, "/tls.cert", null,
                false, value, 20, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keepAliveTimeSeconds");
    }

    // --- keepAliveTimeoutSeconds validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveKeepAliveTimeoutSeconds(int value) {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, "/tls.cert", null,
                false, 60, value, 5, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keepAliveTimeoutSeconds");
    }

    // --- idleTimeoutMinutes validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveIdleTimeoutMinutes(int value) {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, "/tls.cert", null,
                false, 60, 20, value, 4_194_304, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeoutMinutes");
    }

    // --- maxInboundMessageSize validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveMaxInboundMessageSize(int value) {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, "/tls.cert", null,
                false, 60, 20, 5, value, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInboundMessageSize");
    }

    // --- rpcDeadlineSeconds validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveRpcDeadlineSeconds(int value) {
        assertThatThrownBy(() -> new LndConfig("localhost", 10009, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rpcDeadlineSeconds");
    }

    @Test
    void shouldAcceptCustomRpcDeadlineSeconds() {
        var config = new LndConfig("localhost", 10009, "/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 30);
        assertThat(config.rpcDeadlineSeconds()).isEqualTo(30);
    }

    // --- withDefaults factory ---

    @Test
    void withDefaults_shouldProvideSensibleDefaults() {
        var config = LndConfig.withDefaults("myhost", 10009, "/tls.cert", "/admin.macaroon");

        assertThat(config.host()).isEqualTo("myhost");
        assertThat(config.port()).isEqualTo(10009);
        assertThat(config.tlsCertPath()).isEqualTo("/tls.cert");
        assertThat(config.macaroonPath()).isEqualTo("/admin.macaroon");
        assertThat(config.allowPlaintext()).isFalse();
        assertThat(config.keepAliveTimeSeconds()).isEqualTo(60);
        assertThat(config.keepAliveTimeoutSeconds()).isEqualTo(20);
        assertThat(config.idleTimeoutMinutes()).isEqualTo(5);
        assertThat(config.maxInboundMessageSize()).isEqualTo(4_194_304);
        assertThat(config.rpcDeadlineSeconds()).isEqualTo(5);
    }

    @Test
    void withDefaults_shouldStillValidate() {
        assertThatThrownBy(() -> LndConfig.withDefaults("", 10009, "/tls.cert", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    // --- plaintextForTesting factory ---

    @Test
    void plaintextForTesting_shouldSetPlaintextAndNullPaths() {
        var config = LndConfig.plaintextForTesting("localhost", 10009);

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(10009);
        assertThat(config.tlsCertPath()).isNull();
        assertThat(config.macaroonPath()).isNull();
        assertThat(config.allowPlaintext()).isTrue();
        assertThat(config.keepAliveTimeSeconds()).isEqualTo(60);
        assertThat(config.keepAliveTimeoutSeconds()).isEqualTo(20);
        assertThat(config.idleTimeoutMinutes()).isEqualTo(5);
        assertThat(config.maxInboundMessageSize()).isEqualTo(4_194_304);
        assertThat(config.rpcDeadlineSeconds()).isEqualTo(5);
    }

    @Test
    void plaintextForTesting_shouldStillValidate() {
        assertThatThrownBy(() -> LndConfig.plaintextForTesting("", 10009))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }
}
