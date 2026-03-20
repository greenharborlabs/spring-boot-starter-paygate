package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateSecurityFilter;

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
 * Verifies that {@code paygate.security-mode=servlet} forces servlet filter registration
 * and skips Spring Security beans, even though Spring Security is on the classpath.
 */
@DisplayName("SecurityMode: servlet (explicit)")
class SecurityModeServletTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    PaygateSecurityAutoConfiguration.class
            ))
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.backend=lnbits",
                    "paygate.root-key-store=memory",
                    "paygate.security-mode=servlet"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    @Test
    @DisplayName("servlet filter registration bean DOES exist")
    void servletFilterRegistrationCreated() {
        contextRunner.run(context ->
                assertThat(context.containsBean("paygateSecurityFilterRegistration")).isTrue());
    }

    @Test
    @DisplayName("PaygateSecurityFilter bean exists")
    void securityFilterBeanExists() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(PaygateSecurityFilter.class));
    }

    @Test
    @DisplayName("PaygateAuthenticationEntryPoint bean does NOT exist")
    void authenticationEntryPointNotCreated() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(PaygateAuthenticationEntryPoint.class));
    }

    @Test
    @DisplayName("PaygateAuthenticationProvider bean does NOT exist")
    void authenticationProviderNotCreated() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(PaygateAuthenticationProvider.class));
    }

    @Test
    @DisplayName("resolved mode is servlet")
    void resolvedModeIsServlet() {
        contextRunner.run(context -> {
            Object validator = context.getBean("paygateSecurityModeStartupValidator");
            var method = validator.getClass().getMethod("resolvedMode");
            method.setAccessible(true);
            String resolvedMode = (String) method.invoke(validator);
            assertThat(resolvedMode).isEqualTo("servlet");
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
