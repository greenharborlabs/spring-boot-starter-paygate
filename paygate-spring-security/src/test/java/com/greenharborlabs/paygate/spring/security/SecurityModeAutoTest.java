package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateSecurityFilter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * Verifies that {@code paygate.security-mode=auto} resolves to {@code spring-security} when Spring
 * Security is on the classpath (which it is in this module).
 */
@DisplayName("SecurityMode: auto (with Spring Security on classpath)")
class SecurityModeAutoTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PaygateAutoConfiguration.class,
                  WebMvcAutoConfiguration.class,
                  PaygateSecurityAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.security-mode=auto")
          .withBean(LightningBackend.class, StubLightningBackend::new);

  @Test
  @DisplayName("servlet filter registration bean does NOT exist (auto resolves to spring-security)")
  void servletFilterRegistrationNotCreated() {
    contextRunner.run(
        context -> assertThat(context.containsBean("paygateSecurityFilterRegistration")).isFalse());
  }

  @Test
  @DisplayName("PaygateSecurityFilter bean still exists")
  void securityFilterBeanExists() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(PaygateSecurityFilter.class));
  }

  @Test
  @DisplayName("PaygateAuthenticationEntryPoint bean exists")
  void authenticationEntryPointExists() {
    contextRunner.run(
        context -> assertThat(context).hasSingleBean(PaygateAuthenticationEntryPoint.class));
  }

  @Test
  @DisplayName("PaygateAuthenticationProvider bean exists")
  void authenticationProviderExists() {
    contextRunner.run(
        context -> assertThat(context).hasSingleBean(PaygateAuthenticationProvider.class));
  }

  @Test
  @DisplayName("resolved mode is spring-security")
  void resolvedModeIsSpringSecurity() {
    contextRunner.run(
        context -> {
          Object validator = context.getBean("paygateSecurityModeStartupValidator");
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
      return new Invoice(
          paymentHash,
          "lnbc" + amountSats + "n1pstub",
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          null,
          now,
          now.plus(1, ChronoUnit.HOURS));
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
