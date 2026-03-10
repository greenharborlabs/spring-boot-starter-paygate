package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link L402AutoConfiguration}.
 *
 * <p>Uses {@link WebApplicationContextRunner} to verify that all expected beans
 * are created when {@code l402.enabled=true} and required dependencies are present.
 */
@DisplayName("L402AutoConfiguration")
class AutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    L402AutoConfiguration.class,
                    WebMvcAutoConfiguration.class
            ))
            .withPropertyValues(
                    "l402.enabled=true",
                    "l402.backend=lnbits",
                    "l402.root-key-store=memory"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    @Test
    @DisplayName("creates RootKeyStore bean when l402.enabled=true")
    void createsRootKeyStore() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(RootKeyStore.class));
    }

    @Test
    @DisplayName("creates CredentialStore bean when l402.enabled=true")
    void createsCredentialStore() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(CredentialStore.class));
    }

    @Test
    @DisplayName("creates L402SecurityFilter bean when l402.enabled=true")
    void createsSecurityFilter() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402SecurityFilter.class));
    }

    @Test
    @DisplayName("creates L402EndpointRegistry bean when l402.enabled=true")
    void createsEndpointRegistry() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402EndpointRegistry.class));
    }

    @Test
    @DisplayName("creates FilterRegistrationBean for L402SecurityFilter when l402.enabled=true")
    void createsFilterRegistration() {
        contextRunner.run(context ->
                assertThat(context).hasBean("l402SecurityFilterRegistration"));
    }

    @Test
    @DisplayName("creates caveatVerifiers list bean when l402.enabled=true")
    void createsCaveatVerifiers() {
        contextRunner.run(context ->
                assertThat(context).hasBean("caveatVerifiers"));
    }

    @Test
    @DisplayName("all L402 beans are created together when l402.enabled=true and l402.backend=lnbits")
    void allBeansCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RootKeyStore.class);
            assertThat(context).hasSingleBean(CredentialStore.class);
            assertThat(context).hasSingleBean(L402SecurityFilter.class);
            assertThat(context).hasSingleBean(L402EndpointRegistry.class);
            assertThat(context).hasBean("l402SecurityFilterRegistration");
            assertThat(context).hasBean("caveatVerifiers");
        });
    }

    @Test
    @DisplayName("RootKeyStore is InMemoryRootKeyStore when l402.root-key-store=memory")
    void inMemoryRootKeyStoreWhenMemoryMode() {
        contextRunner.run(context -> {
            RootKeyStore store = context.getBean(RootKeyStore.class);
            assertThat(store).isInstanceOf(
                    com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore.class);
        });
    }

    /**
     * Minimal stub of {@link LightningBackend} for auto-configuration testing.
     * The auto-config does not create a LightningBackend, so tests must supply one.
     */
    static class StubLightningBackend implements LightningBackend {

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
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
