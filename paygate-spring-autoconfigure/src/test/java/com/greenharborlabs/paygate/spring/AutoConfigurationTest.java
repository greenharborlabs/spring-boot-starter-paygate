package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link PaygateAutoConfiguration}.
 *
 * <p>Uses {@link WebApplicationContextRunner} to verify that all expected beans
 * are created when {@code paygate.enabled=true} and required dependencies are present.
 */
@DisplayName("PaygateAutoConfiguration")
class AutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateAutoConfiguration.class,
                    WebMvcAutoConfiguration.class
            ))
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.backend=lnbits",
                    "paygate.root-key-store=memory"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    @Test
    @DisplayName("creates RootKeyStore bean when paygate.enabled=true")
    void createsRootKeyStore() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(RootKeyStore.class));
    }

    @Test
    @DisplayName("creates CredentialStore bean when paygate.enabled=true")
    void createsCredentialStore() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(CredentialStore.class));
    }

    @Test
    @DisplayName("creates PaygateSecurityFilter bean when paygate.enabled=true")
    void createsSecurityFilter() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(PaygateSecurityFilter.class));
    }

    @Test
    @DisplayName("creates PaygateEndpointRegistry bean when paygate.enabled=true")
    void createsEndpointRegistry() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(PaygateEndpointRegistry.class));
    }

    @Test
    @DisplayName("creates FilterRegistrationBean for PaygateSecurityFilter when paygate.enabled=true")
    void createsFilterRegistration() {
        contextRunner.run(context ->
                assertThat(context).hasBean("paygateSecurityFilterRegistration"));
    }

    @Test
    @DisplayName("creates caveatVerifiers list bean when paygate.enabled=true")
    void createsCaveatVerifiers() {
        contextRunner.run(context ->
                assertThat(context).hasBean("caveatVerifiers"));
    }

    @Test
    @DisplayName("creates L402Validator bean when paygate.enabled=true")
    void createsL402Validator() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402Validator.class));
    }

    @Test
    @DisplayName("all L402 beans are created together when paygate.enabled=true and paygate.backend=lnbits")
    void allBeansCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RootKeyStore.class);
            assertThat(context).hasSingleBean(CredentialStore.class);
            assertThat(context).hasSingleBean(PaygateSecurityFilter.class);
            assertThat(context).hasSingleBean(PaygateEndpointRegistry.class);
            assertThat(context).hasBean("paygateSecurityFilterRegistration");
            assertThat(context).hasBean("caveatVerifiers");
            assertThat(context).hasSingleBean(L402Validator.class);
        });
    }

    @Test
    @DisplayName("PaygateEndpointRegistry receives defaultTimeoutSeconds from properties")
    void registryReceivesDefaultTimeoutFromProperties() {
        contextRunner
                .withPropertyValues("paygate.default-timeout-seconds=9999")
                .withBean("testController", SentinelTimeoutController.class, SentinelTimeoutController::new)
                .run(context -> {
                    PaygateEndpointRegistry registry = context.getBean(PaygateEndpointRegistry.class);
                    // The controller endpoint uses @PaygateProtected(priceSats=5) with default timeoutSeconds=-1
                    PaygateEndpointConfig config = registry.findConfig("GET", "/api/sentinel-test");
                    assertThat(config).isNotNull();
                    assertThat(config.timeoutSeconds()).isEqualTo(9999);
                });
    }

    @Test
    @DisplayName("spring-security mode without Spring Security on classpath fails startup")
    void springSecurityModeWithoutSpringSecurityFails() {
        // Spring Security is not on this module's test classpath, so spring-security mode
        // should cause a validation failure at startup.
        contextRunner
                .withPropertyValues("paygate.security-mode=spring-security")
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("FilterRegistrationBean created in servlet mode")
    void filterRegistrationCreatedInServletMode() {
        contextRunner
                .withPropertyValues("paygate.security-mode=servlet")
                .run(context -> {
                    assertThat(context).hasSingleBean(PaygateSecurityFilter.class);
                    assertThat(context).hasBean("paygateSecurityFilterRegistration");
                });
    }

    @Test
    @DisplayName("invalid security-mode causes startup failure")
    void invalidSecurityModeFailsStartup() {
        contextRunner
                .withPropertyValues("paygate.security-mode=bogus")
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("PaygateSecurityModeStartupValidator bean is created")
    void securityModeValidatorBeanCreated() {
        contextRunner
                .withPropertyValues("paygate.security-mode=servlet")
                .run(context ->
                        assertThat(context).hasSingleBean(
                                PaygateAutoConfiguration.PaygateSecurityModeStartupValidator.class));
    }

    @Test
    @DisplayName("RootKeyStore is wrapped in ObservableRootKeyStore when paygate.root-key-store=memory")
    void inMemoryRootKeyStoreWhenMemoryMode() {
        contextRunner.run(context -> {
            RootKeyStore store = context.getBean(RootKeyStore.class);
            assertThat(store).isInstanceOf(
                    com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore.class);
        });
    }

    @Test
    @DisplayName("default caveatVerifiers contains delegation verifiers (PathCaveatVerifier, MethodCaveatVerifier, ClientIpCaveatVerifier)")
    @SuppressWarnings("unchecked")
    void defaultCaveatVerifiersContainsDelegationVerifiers() {
        contextRunner.run(context -> {
            List<CaveatVerifier> verifiers = (List<CaveatVerifier>) context.getBean("caveatVerifiers");
            assertThat(verifiers)
                    .hasAtLeastOneElementOfType(PathCaveatVerifier.class)
                    .hasAtLeastOneElementOfType(MethodCaveatVerifier.class)
                    .hasAtLeastOneElementOfType(ClientIpCaveatVerifier.class);
        });
    }

    @Test
    @DisplayName("default caveatVerifiers still contains existing verifiers (ServicesCaveatVerifier, ValidUntilCaveatVerifier, CapabilitiesCaveatVerifier)")
    @SuppressWarnings("unchecked")
    void defaultCaveatVerifiersContainsExistingVerifiers() {
        contextRunner.run(context -> {
            List<CaveatVerifier> verifiers = (List<CaveatVerifier>) context.getBean("caveatVerifiers");
            assertThat(verifiers)
                    .hasAtLeastOneElementOfType(ServicesCaveatVerifier.class)
                    .hasAtLeastOneElementOfType(ValidUntilCaveatVerifier.class)
                    .hasAtLeastOneElementOfType(CapabilitiesCaveatVerifier.class);
        });
    }

    @Test
    @DisplayName("custom caveatVerifiers bean overrides all defaults")
    @SuppressWarnings("unchecked")
    void customCaveatVerifiersBeanOverridesDefaults() {
        contextRunner
                .withUserConfiguration(CustomCaveatVerifiersConfig.class)
                .run(context -> {
                    List<CaveatVerifier> verifiers = (List<CaveatVerifier>) context.getBean("caveatVerifiers");
                    assertThat(verifiers).hasSize(1);
                    assertThat(verifiers.getFirst()).isInstanceOf(ServicesCaveatVerifier.class);
                });
    }

    @Test
    @DisplayName("ClientIpResolver bean is created when paygate.enabled=true")
    void clientIpResolverBeanCreated() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(ClientIpResolver.class));
    }

    @Test
    @DisplayName("trustedProxyAddresses binds from properties")
    void trustedProxyAddressesBindFromProperties() {
        contextRunner
                .withPropertyValues("paygate.trusted-proxy-addresses=10.0.0.1,10.0.0.2")
                .run(context -> {
                    PaygateProperties props = context.getBean(PaygateProperties.class);
                    assertThat(props.getTrustedProxyAddresses())
                            .containsExactly("10.0.0.1", "10.0.0.2");
                });
    }

    @Test
    @DisplayName("trustedProxyAddresses defaults to empty list")
    void trustedProxyAddressesDefaultIsEmpty() {
        contextRunner.run(context -> {
            PaygateProperties props = context.getBean(PaygateProperties.class);
            assertThat(props.getTrustedProxyAddresses()).isEmpty();
        });
    }

    @Test
    @DisplayName("maxValuesPerCaveat binds from properties")
    void maxValuesPerCaveatBindsFromProperties() {
        contextRunner
                .withPropertyValues("paygate.caveat.max-values-per-caveat=25")
                .run(context -> {
                    PaygateProperties props = context.getBean(PaygateProperties.class);
                    assertThat(props.getCaveat().getMaxValuesPerCaveat()).isEqualTo(25);
                });
    }

    @Test
    @DisplayName("maxValuesPerCaveat defaults to 50")
    void maxValuesPerCaveatDefaultIs50() {
        contextRunner.run(context -> {
            PaygateProperties props = context.getBean(PaygateProperties.class);
            assertThat(props.getCaveat().getMaxValuesPerCaveat()).isEqualTo(50);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCaveatVerifiersConfig {

        @Bean
        List<CaveatVerifier> caveatVerifiers() {
            return List.of(new ServicesCaveatVerifier());
        }
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

    /**
     * Controller with sentinel timeout (-1) to test default resolution.
     */
    @org.springframework.web.bind.annotation.RestController
    static class SentinelTimeoutController {

        @PaygateProtected(priceSats = 5)
        @org.springframework.web.bind.annotation.GetMapping("/api/sentinel-test")
        String sentinelEndpoint() {
            return "sentinel";
        }
    }
}
