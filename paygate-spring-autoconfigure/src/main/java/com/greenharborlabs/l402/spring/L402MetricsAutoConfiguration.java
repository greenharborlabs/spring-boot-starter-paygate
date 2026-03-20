package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers L402 Micrometer metrics when both
 * {@code l402.enabled=true} and Micrometer is on the classpath.
 *
 * <p>Registers the {@link L402Metrics} bean which eagerly creates gauges
 * for credential store size and Lightning health, and provides methods
 * for recording counter metrics from the security filter.
 */
@AutoConfiguration(after = L402AutoConfiguration.class)
@ConditionalOnProperty(name = "l402.enabled", havingValue = "true")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
public class L402MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public L402Metrics l402Metrics(MeterRegistry meterRegistry,
                                   CredentialStore credentialStore,
                                   LightningBackend lightningBackend) {
        var metrics = new L402Metrics(meterRegistry, credentialStore, lightningBackend);
        credentialStore.setEvictionListener((tokenId, reason) -> metrics.recordCacheEviction(reason));
        return metrics;
    }

    @Bean
    @ConditionalOnMissingBean
    public L402MeterFilter l402MeterFilter(MeterRegistry meterRegistry, L402Properties properties) {
        L402MeterFilter filter = new L402MeterFilter(
                properties.getMetrics().getMaxEndpointCardinality(),
                properties.getMetrics().getOverflowTagValue()
        );
        meterRegistry.config().meterFilter(filter);
        return filter;
    }
}
