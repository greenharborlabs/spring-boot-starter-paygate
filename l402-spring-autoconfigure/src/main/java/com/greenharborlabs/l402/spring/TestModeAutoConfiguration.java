package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.LightningBackend;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for L402 test mode (R-014).
 *
 * <p>Activated when {@code l402.test-mode=true}. Provides a {@link TestModeLightningBackend}
 * that returns dummy invoices and always reports payments as settled.
 *
 * <p>A production profile guard prevents accidental use: if any active Spring profile
 * matches "production" or "prod" (case-insensitive), the application fails to start
 * with an {@link IllegalStateException}.
 */
@AutoConfiguration(before = L402AutoConfiguration.class)
@ConditionalOnProperty(name = "l402.test-mode", havingValue = "true")
public class TestModeAutoConfiguration {

    TestModeAutoConfiguration(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(profile) || "prod".equalsIgnoreCase(profile)) {
                throw new IllegalStateException(
                        "L402 test mode must not be used with production profiles");
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    LightningBackend testModeLightningBackend() {
        return new TestModeLightningBackend();
    }
}
