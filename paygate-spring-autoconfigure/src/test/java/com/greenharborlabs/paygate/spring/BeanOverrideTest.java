package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PaygateAutoConfiguration} respects {@code @ConditionalOnMissingBean}
 * for {@link RootKeyStore}: a user-defined bean must suppress the auto-configured one.
 */
@DisplayName("RootKeyStore @ConditionalOnMissingBean override")
class BeanOverrideTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PaygateAutoConfiguration.class))
            .withUserConfiguration(RequiredBeansConfig.class)
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.root-key-store=memory"
            );

    @Test
    @DisplayName("auto-configured InMemoryRootKeyStore is wrapped in ObservableRootKeyStore")
    void autoConfiguredRootKeyStoreWhenNoOverride() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    assertThat(context.getBean(RootKeyStore.class))
                            .isInstanceOf(com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore.class);
                });
    }

    @Test
    @DisplayName("custom RootKeyStore bean is wrapped in ObservableRootKeyStore")
    void customRootKeyStorePreventsAutoConfigured() {
        contextRunner
                .withUserConfiguration(CustomRootKeyStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    assertThat(context.getBean(RootKeyStore.class))
                            .isInstanceOf(com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore.class)
                            .isNotInstanceOf(InMemoryRootKeyStore.class);
                });
    }

    @Test
    @DisplayName("auto-configured L402Validator is used when no custom bean is defined")
    void autoConfiguredL402ValidatorWhenNoOverride() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(L402Validator.class);
                });
    }

    @Test
    @DisplayName("custom L402Validator bean replaces auto-configured validator")
    void customL402ValidatorPreventsAutoConfigured() {
        contextRunner
                .withUserConfiguration(CustomL402ValidatorConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(L402Validator.class);
                    assertThat(context.getBean(L402Validator.class))
                            .isSameAs(context.getBean(CustomL402ValidatorConfig.class).customValidator);
                });
    }

    // -----------------------------------------------------------------------
    // Configuration that provides required dependencies for PaygateAutoConfiguration
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
    // User-provided configuration that overrides the auto-configured L402Validator
    // -----------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class CustomL402ValidatorConfig {

        final L402Validator customValidator = new L402Validator(
                new InMemoryRootKeyStore(),
                new InMemoryCredentialStore(),
                List.of(),
                "custom-service"
        );

        @Bean
        L402Validator paygateValidator() {
            return customValidator;
        }
    }

    // -----------------------------------------------------------------------
    // Custom RootKeyStore implementation for override verification
    // -----------------------------------------------------------------------

    static class CustomRootKeyStore implements RootKeyStore {

        @Override
        public GenerationResult generateRootKey() {
            return new GenerationResult(new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(new byte[32]), new byte[32]);
        }

        @Override
        public com.greenharborlabs.paygate.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            return new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(new byte[32]);
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op
        }
    }

    // -----------------------------------------------------------------------
    // Minimal stub for LightningBackend (required by PaygateSecurityFilter bean)
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
