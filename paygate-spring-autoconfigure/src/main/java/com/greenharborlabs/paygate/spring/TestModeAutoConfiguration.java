package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for L402 test mode (R-014).
 *
 * <p>Activated when {@code paygate.test-mode=true}. Provides a {@link TestModeLightningBackend}
 * that returns dummy invoices and always reports payments as settled.
 *
 * <p>An explicit allowlist prevents accidental use in production:
 *
 * <ol>
 *   <li><b>Production veto:</b> an active production-like profile causes startup to fail
 *       immediately, even when an otherwise safe profile is also active.
 *   <li><b>Allowlist:</b> every active profile must be one of "test", "dev", "local", or
 *       "development". Empty and unknown profile sets fail closed.
 * </ol>
 */
@AutoConfiguration(before = PaygateAutoConfiguration.class)
@ConditionalOnProperty(
    name = {"paygate.enabled", "paygate.test-mode"},
    havingValue = "true")
public class TestModeAutoConfiguration {

  TestModeAutoConfiguration(Environment environment) {
    DevelopmentSafetyPolicy.validateProfiles(environment);
  }

  @Bean
  @ConditionalOnMissingBean
  LightningBackend testModeLightningBackend() {
    return new TestModeLightningBackend();
  }

  @Bean
  DevelopmentSafetyPolicy.ValidatedTestMode validatedTestMode(
      Environment environment,
      PaygateProperties properties,
      RootKeyStore rootKeyStore,
      List<LightningBackend> lightningBackends) {
    return DevelopmentSafetyPolicy.validateTestMode(
        environment, properties.getRootKeyStore(), rootKeyStore, lightningBackends);
  }
}
