package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that test-mode configuration provides a {@link TestModeLightningBackend}
 * when {@code paygate.test-mode=true}.
 *
 * <p>Per R-014, TestModeLightningBackend.createInvoice() returns a dummy Invoice
 * with a random payment hash and fake bolt11 string. lookupInvoice() always returns
 * an Invoice with {@link InvoiceStatus#SETTLED}. Preimage verification is skipped.
 *
 * <p>This test is written TDD-first: it references {@link TestModeLightningBackend}
 * and {@link TestModeAutoConfiguration}, which do not exist yet. The test will fail
 * to compile until those classes are implemented (T081, T082).
 */
@DisplayName("L402 test-mode configuration")
class TestModeConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateAutoConfiguration.class,
                    TestModeAutoConfiguration.class
            ))
            .withBean(RequestMappingHandlerMapping.class)
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.test-mode=true",
                    "paygate.root-key-store=memory",
                    "spring.profiles.active=test"
            );

    @Test
    @DisplayName("LightningBackend bean is TestModeLightningBackend when test-mode is enabled")
    void lightningBackendIsTestModeInstance() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LightningBackend.class);
            assertThat(context.getBean(LightningBackend.class))
                    .isInstanceOf(TestModeLightningBackend.class);
        });
    }

    @Test
    @DisplayName("TestModeLightningBackend.createInvoice returns a valid dummy invoice")
    void createInvoiceReturnsDummyInvoice() {
        contextRunner.run(context -> {
            var backend = context.getBean(LightningBackend.class);

            var invoice = backend.createInvoice(100, "test memo");

            assertThat(invoice).isNotNull();
            assertThat(invoice.paymentHash()).hasSize(32);
            assertThat(invoice.bolt11()).isNotBlank();
            assertThat(invoice.amountSats()).isEqualTo(100);
            assertThat(invoice.memo()).isEqualTo("test memo");
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
            assertThat(invoice.createdAt()).isNotNull();
            assertThat(invoice.expiresAt()).isNotNull();
            assertThat(invoice.expiresAt()).isAfter(invoice.createdAt());
        });
    }

    @Test
    @DisplayName("TestModeLightningBackend.lookupInvoice always returns SETTLED status")
    void lookupInvoiceAlwaysReturnsSettled() {
        contextRunner.run(context -> {
            var backend = context.getBean(LightningBackend.class);

            // Create an invoice first to get a valid payment hash
            var created = backend.createInvoice(50, "lookup test");
            var looked = backend.lookupInvoice(created.paymentHash());

            assertThat(looked).isNotNull();
            assertThat(looked.status()).isEqualTo(InvoiceStatus.SETTLED);
        });
    }

    @Test
    @DisplayName("TestModeLightningBackend.isHealthy returns true")
    void isHealthyReturnsTrue() {
        contextRunner.run(context -> {
            var backend = context.getBean(LightningBackend.class);

            assertThat(backend.isHealthy()).isTrue();
        });
    }

    @Test
    @DisplayName("TestModeLightningBackend not created when paygate.test-mode=false")
    void testModeNotActiveWhenDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestModeAutoConfiguration.class))
                .withPropertyValues("paygate.enabled=true", "paygate.test-mode=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
                });
    }

    @Test
    @DisplayName("TestModeLightningBackend not created when paygate.test-mode=true but paygate.enabled is not set")
    void testModeNotActiveWhenMasterSwitchDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestModeAutoConfiguration.class))
                .withPropertyValues("paygate.test-mode=true", "spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
                });
    }

    @Test
    @DisplayName("TestModeLightningBackend not created when paygate.test-mode=true and paygate.enabled=false")
    void testModeNotActiveWhenMasterSwitchExplicitlyDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestModeAutoConfiguration.class))
                .withPropertyValues("paygate.enabled=false", "paygate.test-mode=true", "spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
                });
    }
}
