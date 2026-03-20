package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

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
 * <p>Activates only when Spring Boot Actuator is on the classpath and the
 * required L402 beans are present. Separated from {@link L402AutoConfiguration}
 * to allow independent conditional activation.
 *
 * <p>Can be disabled by setting {@code l402.actuator.enabled=false}.
 */
@AutoConfiguration(after = L402AutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnBean(L402EndpointRegistry.class)
@ConditionalOnProperty(name = "l402.actuator.enabled", havingValue = "true", matchIfMissing = true)
public class L402ActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = L402ActuatorEndpoint.class)
    L402ActuatorEndpoint l402ActuatorEndpoint(
            L402Properties properties,
            LightningBackend lightningBackend,
            L402EndpointRegistry endpointRegistry,
            CredentialStore credentialStore,
            L402EarningsTracker earningsTracker) {
        return new L402ActuatorEndpoint(
                properties, lightningBackend, endpointRegistry, credentialStore, earningsTracker);
    }
}
