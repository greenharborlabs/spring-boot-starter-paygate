package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link PaygateAuthFailureRateLimitFilter} bean creation is conditional
 * on the presence of a {@link PaygateRateLimiter} bean.
 */
@DisplayName("RateLimitFilter auto-configuration")
class RateLimitFilterAutoConfigurationTest {

    /**
     * Full context runner with PaygateAutoConfiguration, which always creates a PaygateRateLimiter.
     * Used for the "present" test case.
     */
    private final WebApplicationContextRunner fullContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    PaygateSecurityAutoConfiguration.class
            ))
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.backend=lnbits",
                    "paygate.root-key-store=memory",
                    "paygate.security-mode=spring-security"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    /**
     * Minimal context runner with only PaygateSecurityAutoConfiguration and manually
     * provided beans. No PaygateRateLimiter is registered.
     */
    private final WebApplicationContextRunner minimalContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateSecurityAutoConfiguration.class
            ))
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.security-mode=spring-security"
            )
            .withBean(L402Validator.class, () -> mock(L402Validator.class))
            .withBean(PaygateEndpointRegistry.class, () -> mock(PaygateEndpointRegistry.class))
            .withBean(PaygateChallengeService.class, () -> mock(PaygateChallengeService.class));

    @Test
    @DisplayName("PaygateAuthFailureRateLimitFilter bean IS created when PaygateRateLimiter is present")
    void rateLimitFilterCreatedWhenRateLimiterPresent() {
        fullContextRunner.run(context ->
                assertThat(context).hasSingleBean(PaygateAuthFailureRateLimitFilter.class));
    }

    @Test
    @DisplayName("PaygateAuthFailureRateLimitFilter bean is NOT created when PaygateRateLimiter is absent")
    void rateLimitFilterNotCreatedWhenRateLimiterAbsent() {
        minimalContextRunner.run(context ->
                assertThat(context).doesNotHaveBean(PaygateAuthFailureRateLimitFilter.class));
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
