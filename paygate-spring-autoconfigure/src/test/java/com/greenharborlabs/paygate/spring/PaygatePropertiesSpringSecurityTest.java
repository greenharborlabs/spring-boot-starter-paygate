package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link PaygateProperties.SpringSecurity} configuration properties. */
class PaygatePropertiesSpringSecurityTest {

  @Test
  @DisplayName("Spring Security custom filter-chain acknowledgement defaults false")
  void customFilterChainAcknowledgementDefaultsFalse() {
    var springSecurity = new PaygateProperties.SpringSecurity();

    assertThat(springSecurity.isCustomFilterChainAcknowledged()).isFalse();
  }

  @Test
  @DisplayName("Spring Security custom filter-chain acknowledgement accepts explicit true")
  void customFilterChainAcknowledgementAcceptsExplicitTrue() {
    var springSecurity = new PaygateProperties.SpringSecurity();

    springSecurity.setCustomFilterChainAcknowledged(true);

    assertThat(springSecurity.isCustomFilterChainAcknowledged()).isTrue();
  }

  @Test
  @DisplayName("PaygateProperties accepts Spring Security properties")
  void paygatePropertiesAcceptsSpringSecurityProperties() {
    var properties = new PaygateProperties();
    var springSecurity = new PaygateProperties.SpringSecurity();
    springSecurity.setCustomFilterChainAcknowledged(true);

    properties.setSpringSecurity(springSecurity);

    assertThat(properties.getSpringSecurity().isCustomFilterChainAcknowledged()).isTrue();
  }
}
