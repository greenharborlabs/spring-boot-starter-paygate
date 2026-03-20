package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.spring.L402AutoConfiguration;
import com.greenharborlabs.l402.spring.L402SecurityFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code l402.security-mode=auto} resolves to {@code spring-security}
 * when Spring Security is on the classpath (which it is in this module).
 */
@DisplayName("SecurityMode: auto (with Spring Security on classpath)")
class SecurityModeAutoTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    L402AutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    L402SecurityAutoConfiguration.class
            ))
            .withPropertyValues(
                    "l402.enabled=true",
                    "l402.backend=lnbits",
                    "l402.root-key-store=memory",
                    "l402.security-mode=auto"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    @Test
    @DisplayName("servlet filter registration bean does NOT exist (auto resolves to spring-security)")
    void servletFilterRegistrationNotCreated() {
        contextRunner.run(context ->
                assertThat(context.containsBean("l402SecurityFilterRegistration")).isFalse());
    }

    @Test
    @DisplayName("L402SecurityFilter bean still exists")
    void securityFilterBeanExists() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402SecurityFilter.class));
    }

    @Test
    @DisplayName("L402AuthenticationEntryPoint bean exists")
    void authenticationEntryPointExists() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402AuthenticationEntryPoint.class));
    }

    @Test
    @DisplayName("L402AuthenticationProvider bean exists")
    void authenticationProviderExists() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(L402AuthenticationProvider.class));
    }

    @Test
    @DisplayName("resolved mode is spring-security")
    void resolvedModeIsSpringSecurity() {
        contextRunner.run(context -> {
            Object validator = context.getBean("l402SecurityModeStartupValidator");
            var method = validator.getClass().getMethod("resolvedMode");
            method.setAccessible(true);
            String resolvedMode = (String) method.invoke(validator);
            assertThat(resolvedMode).isEqualTo("spring-security");
        });
    }

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
        public Invoice lookupInvoice(byte[] paymentHash) { return null; }

        @Override
        public boolean isHealthy() { return true; }
    }
}
