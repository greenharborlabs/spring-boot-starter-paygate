package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code paygate.lightning.*} configuration properties and the timeout fallback logic
 * between global and backend-specific settings.
 */
@DisplayName("PaygateProperties Lightning timeout")
class PaygatePropertiesLightningTimeoutTest {

  // ── Lightning inner class ──

  @Test
  @DisplayName("Lightning.timeoutSeconds defaults to 5")
  void lightningTimeoutDefaultIs5() {
    var lightning = new PaygateProperties.Lightning();
    assertThat(lightning.getTimeoutSeconds()).isEqualTo(5);
  }

  @Test
  @DisplayName("Lightning.timeoutSeconds can be changed")
  void lightningTimeoutCanBeSet() {
    var lightning = new PaygateProperties.Lightning();
    lightning.setTimeoutSeconds(30);
    assertThat(lightning.getTimeoutSeconds()).isEqualTo(30);
  }

  @Test
  @DisplayName("Lightning.timeoutSeconds rejects zero")
  void lightningTimeoutRejectsZero() {
    var lightning = new PaygateProperties.Lightning();
    assertThatThrownBy(() -> lightning.setTimeoutSeconds(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be > 0");
  }

  @Test
  @DisplayName("Lightning.timeoutSeconds rejects negative values")
  void lightningTimeoutRejectsNegative() {
    var lightning = new PaygateProperties.Lightning();
    assertThatThrownBy(() -> lightning.setTimeoutSeconds(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be > 0");
  }

  // ── PaygateProperties top-level accessor ──

  @Test
  @DisplayName("PaygateProperties exposes Lightning with default")
  void propertiesExposesLightning() {
    var props = new PaygateProperties();
    assertThat(props.getLightning()).isNotNull();
    assertThat(props.getLightning().getTimeoutSeconds()).isEqualTo(5);
  }

  // ── Lnbits.requestTimeoutSeconds ──

  @Test
  @DisplayName("Lnbits.requestTimeoutSeconds defaults to null")
  void lnbitsRequestTimeoutDefaultNull() {
    var lnbits = new PaygateProperties.Lnbits();
    assertThat(lnbits.getRequestTimeoutSeconds()).isNull();
  }

  @Test
  @DisplayName("Lnbits.requestTimeoutSeconds can be set")
  void lnbitsRequestTimeoutCanBeSet() {
    var lnbits = new PaygateProperties.Lnbits();
    lnbits.setRequestTimeoutSeconds(15);
    assertThat(lnbits.getRequestTimeoutSeconds()).isEqualTo(15);
  }

  // ── Lnd.rpcDeadlineSeconds ──

  @Test
  @DisplayName("Lnd.rpcDeadlineSeconds defaults to null")
  void lndRpcDeadlineDefaultNull() {
    var lnd = new PaygateProperties.Lnd();
    assertThat(lnd.getRpcDeadlineSeconds()).isNull();
  }

  @Test
  @DisplayName("Lnd.rpcDeadlineSeconds can be set")
  void lndRpcDeadlineCanBeSet() {
    var lnd = new PaygateProperties.Lnd();
    lnd.setRpcDeadlineSeconds(20);
    assertThat(lnd.getRpcDeadlineSeconds()).isEqualTo(20);
  }

  // ── Fallback resolution logic (unit-level) ──

  @Test
  @DisplayName("LND uses global timeout when rpcDeadlineSeconds is null")
  void lndFallsBackToGlobalTimeout() {
    var props = new PaygateProperties();
    props.getLightning().setTimeoutSeconds(12);
    // rpcDeadlineSeconds is null by default

    int resolved =
        props.getLnd().getRpcDeadlineSeconds() != null
            ? props.getLnd().getRpcDeadlineSeconds()
            : props.getLightning().getTimeoutSeconds();

    assertThat(resolved).isEqualTo(12);
  }

  @Test
  @DisplayName("LND uses backend-specific override when rpcDeadlineSeconds is set")
  void lndUsesBackendSpecificOverride() {
    var props = new PaygateProperties();
    props.getLightning().setTimeoutSeconds(12);
    props.getLnd().setRpcDeadlineSeconds(25);

    int resolved =
        props.getLnd().getRpcDeadlineSeconds() != null
            ? props.getLnd().getRpcDeadlineSeconds()
            : props.getLightning().getTimeoutSeconds();

    assertThat(resolved).isEqualTo(25);
  }

  @Test
  @DisplayName("LNbits uses global timeout when requestTimeoutSeconds is null")
  void lnbitsFallsBackToGlobalTimeout() {
    var props = new PaygateProperties();
    props.getLightning().setTimeoutSeconds(8);

    int resolved =
        props.getLnbits().getRequestTimeoutSeconds() != null
            ? props.getLnbits().getRequestTimeoutSeconds()
            : props.getLightning().getTimeoutSeconds();

    assertThat(resolved).isEqualTo(8);
  }

  @Test
  @DisplayName("LNbits uses backend-specific override when requestTimeoutSeconds is set")
  void lnbitsUsesBackendSpecificOverride() {
    var props = new PaygateProperties();
    props.getLightning().setTimeoutSeconds(8);
    props.getLnbits().setRequestTimeoutSeconds(30);

    int resolved =
        props.getLnbits().getRequestTimeoutSeconds() != null
            ? props.getLnbits().getRequestTimeoutSeconds()
            : props.getLightning().getTimeoutSeconds();

    assertThat(resolved).isEqualTo(30);
  }
}
