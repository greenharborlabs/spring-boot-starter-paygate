package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the L402 actuator endpoint.
 *
 * <p>Activates only when Spring Boot Actuator is on the classpath and the required L402 beans are
 * present. Separated from {@link PaygateAutoConfiguration} to allow independent conditional
 * activation.
 *
 * <p>Disabled by default. Can be enabled by setting {@code paygate.actuator.enabled=true}.
 */
@AutoConfiguration(after = PaygateAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class PaygateActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(PaygateEndpointRegistry.class)
  @ConditionalOnProperty(name = "paygate.actuator.enabled", havingValue = "true")
  @ConditionalOnAvailableEndpoint(endpoint = PaygateActuatorEndpoint.class)
  PaygateActuatorEndpoint paygateActuatorEndpoint(
      PaygateProperties properties,
      LightningBackend lightningBackend,
      PaygateEndpointRegistry endpointRegistry,
      CredentialStore credentialStore,
      PaygateEarningsTracker earningsTracker) {
    return new PaygateActuatorEndpoint(
        properties, lightningBackend, endpointRegistry, credentialStore, earningsTracker);
  }

  /**
   * Registers a health contributor whenever Actuator and a Lightning backend are available.
   *
   * <p>The contributor deliberately exposes only {@code UP} or {@code DOWN}; backend names and
   * failure details can disclose deployment topology or secrets.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(LightningBackend.class)
  PaygateLightningHealthIndicator paygateLightningHealthIndicator(
      LightningBackend lightningBackend) {
    return new PaygateLightningHealthIndicator(lightningBackend, 5_000);
  }
}
