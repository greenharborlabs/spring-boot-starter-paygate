package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * Verifies that the correct {@link CredentialStore} implementation is selected based on whether
 * Caffeine is present on the classpath.
 *
 * <p>When Caffeine is available, {@link CaffeineCredentialStore} should be used. When Caffeine is
 * absent (filtered from the classpath), the fallback {@link InMemoryCredentialStore} should be used
 * instead.
 */
@DisplayName("Cache conditional auto-configuration")
class CacheConditionalTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues("paygate.enabled=true", "paygate.root-key-store=memory")
          .withBean(LightningBackend.class, StubLightningBackend::new);

  @Test
  @DisplayName("uses CaffeineCredentialStore when Caffeine is on the classpath")
  void caffeineOnClasspath_usesCaffeineCredentialStore() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(CredentialStore.class);
          assertThat(context.getBean(CredentialStore.class))
              .isInstanceOf(CaffeineCredentialStore.class);
        });
  }

  @Test
  @DisplayName("uses InMemoryCredentialStore when Caffeine is absent from the classpath")
  void caffeineAbsent_usesInMemoryCredentialStore() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(Caffeine.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(CredentialStore.class);
              assertThat(context.getBean(CredentialStore.class))
                  .isInstanceOf(InMemoryCredentialStore.class);
            });
  }

  /** Minimal stub of {@link LightningBackend} for auto-configuration testing. */
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
