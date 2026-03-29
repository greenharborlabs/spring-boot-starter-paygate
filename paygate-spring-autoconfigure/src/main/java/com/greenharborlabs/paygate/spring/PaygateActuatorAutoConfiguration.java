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
 * <p>Can be disabled by setting {@code paygate.actuator.enabled=false}.
 */
@AutoConfiguration(after = PaygateAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnBean(PaygateEndpointRegistry.class)
@ConditionalOnProperty(
    name = "paygate.actuator.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PaygateActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
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
}
