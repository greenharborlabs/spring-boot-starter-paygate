package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers L402 Micrometer metrics when both
 * {@code paygate.enabled=true} and Micrometer is on the classpath.
 *
 * <p>Registers the {@link PaygateMetrics} bean which eagerly creates gauges
 * for credential store size and Lightning health, and provides methods
 * for recording counter metrics from the security filter.
 */
@AutoConfiguration(after = PaygateAutoConfiguration.class)
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
public class PaygateMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PaygateMetrics paygateMetrics(MeterRegistry meterRegistry,
                                   CredentialStore credentialStore,
                                   LightningBackend lightningBackend) {
        var metrics = new PaygateMetrics(meterRegistry, credentialStore, lightningBackend);
        credentialStore.setEvictionListener((tokenId, reason) -> metrics.recordCacheEviction(reason));
        return metrics;
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateMeterFilter paygateMeterFilter(MeterRegistry meterRegistry, PaygateProperties properties) {
        PaygateMeterFilter filter = new PaygateMeterFilter(
                properties.getMetrics().getMaxEndpointCardinality(),
                properties.getMetrics().getOverflowTagValue()
        );
        meterRegistry.config().meterFilter(filter);
        return filter;
    }
}
