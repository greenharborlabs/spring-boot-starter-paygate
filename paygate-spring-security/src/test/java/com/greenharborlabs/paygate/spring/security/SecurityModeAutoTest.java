package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygateAutoConfiguration;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateSecurityFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Verifies that {@code paygate.security-mode=auto} resolves to {@code spring-security} when both
 * Spring Security and the Paygate Spring Security integration are on the classpath.
 */
@DisplayName("SecurityMode: auto (with Spring Security on classpath)")
class SecurityModeAutoTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PaygateAutoConfiguration.class,
                  WebMvcAutoConfiguration.class,
                  PaygateSecurityAutoConfiguration.class,
                  PaygateSpringSecurityFilterChainGuardAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.security-mode=auto",
              "paygate.spring-security.custom-filter-chain-acknowledged=true")
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

  @Test
  @DisplayName("fails closed when auto resolves to spring-security without Paygate filter")
  void failsClosedWhenPaygateFilterMissing() {
    contextRunner
        .withPropertyValues("paygate.spring-security.custom-filter-chain-acknowledged=false")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("servlet enforcement is disabled in Spring Security mode")
                  .hasMessageContaining("no PaygateAuthenticationFilter")
                  .hasMessageContaining("http.addFilterBefore(paygateFilter")
                  .hasMessageContaining("paygate.spring-security.custom-filter-chain-acknowledged");
            });
  }

  @Test
  @DisplayName("reference filter-chain wiring starts successfully")
  void referenceFilterChainWiringStarts() {
    contextRunner
        .withPropertyValues("paygate.spring-security.custom-filter-chain-acknowledged=false")
        .withBean(FilterChainProxy.class, () -> new FilterChainProxy(referenceSecurityChain()))
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("custom filter-chain acknowledgement allows startup without Paygate filter")
  void customFilterChainAcknowledgementAllowsStartup() {
    contextRunner.run(context -> assertThat(context).hasNotFailed());
  }

  private static SecurityFilterChain referenceSecurityChain() {
    PaygateAuthenticationFilter paygateFilter =
        new PaygateAuthenticationFilter(
            authentication -> authentication, List.of(), mock(PaygateEndpointRegistry.class));
    return new TestSecurityFilterChain(List.of(paygateFilter));
  }

  private record TestSecurityFilterChain(List<Filter> filters) implements SecurityFilterChain {

    @Override
    public boolean matches(HttpServletRequest request) {
      return true;
    }

    @Override
    public List<Filter> getFilters() {
      return filters;
    }
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
