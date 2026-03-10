package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link L402AutoConfiguration} respects {@code @ConditionalOnMissingBean}
 * for {@link RootKeyStore}: a user-defined bean must suppress the auto-configured one.
 */
@DisplayName("RootKeyStore @ConditionalOnMissingBean override")
class BeanOverrideTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(L402AutoConfiguration.class))
            .withUserConfiguration(RequiredBeansConfig.class)
            .withPropertyValues(
                    "l402.enabled=true",
                    "l402.root-key-store=memory"
            );

    @Test
    @DisplayName("auto-configured InMemoryRootKeyStore is used when no custom bean is defined")
    void autoConfiguredRootKeyStoreWhenNoOverride() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    assertThat(context.getBean(RootKeyStore.class))
                            .isInstanceOf(InMemoryRootKeyStore.class);
                });
    }

    @Test
    @DisplayName("custom RootKeyStore bean replaces auto-configured store")
    void customRootKeyStorePreventsAutoConfigured() {
        contextRunner
                .withUserConfiguration(CustomRootKeyStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    assertThat(context.getBean(RootKeyStore.class))
                            .isInstanceOf(CustomRootKeyStore.class)
                            .isNotInstanceOf(InMemoryRootKeyStore.class);
                });
    }

    // -----------------------------------------------------------------------
    // Configuration that provides required dependencies for L402AutoConfiguration
    // -----------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeansConfig {

        @Bean
        LightningBackend lightningBackend() {
            return new StubLightningBackend();
        }

        @Bean
        RequestMappingHandlerMapping requestMappingHandlerMapping() {
            return new RequestMappingHandlerMapping();
        }
    }

    // -----------------------------------------------------------------------
    // User-provided configuration that overrides the auto-configured RootKeyStore
    // -----------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class CustomRootKeyStoreConfig {

        @Bean
        RootKeyStore rootKeyStore() {
            return new CustomRootKeyStore();
        }
    }

    // -----------------------------------------------------------------------
    // Custom RootKeyStore implementation for override verification
    // -----------------------------------------------------------------------

    static class CustomRootKeyStore implements RootKeyStore {

        @Override
        public byte[] generateRootKey() {
            return new byte[32];
        }

        @Override
        public byte[] getRootKey(byte[] keyId) {
            return new byte[32];
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op
        }
    }

    // -----------------------------------------------------------------------
    // Minimal stub for LightningBackend (required by L402SecurityFilter bean)
    // -----------------------------------------------------------------------

    static class StubLightningBackend implements LightningBackend {

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            Instant now = Instant.now();
            return new Invoice(
                    new byte[32], "lnbc1stub", amountSats, memo,
                    InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS)
            );
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            return null;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }
}
