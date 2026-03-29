package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.util.Set;
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
 * <p>A two-layer guard prevents accidental use in production:
 *
 * <ol>
 *   <li><b>Denylist (belt):</b> if any active profile matches "production" or "prod"
 *       (case-insensitive), startup fails immediately — even if an allowed profile is also active.
 *   <li><b>Allowlist (suspenders):</b> at least one of "test", "dev", "local", or "development"
 *       must be active; otherwise startup fails. This catches custom production profile names like
 *       "prd", "live", or "staging" that would bypass the denylist.
 * </ol>
 */
@AutoConfiguration(before = PaygateAutoConfiguration.class)
@ConditionalOnProperty(
    name = {"paygate.enabled", "paygate.test-mode"},
    havingValue = "true")
public class TestModeAutoConfiguration {

  private static final Set<String> DENIED_PROFILES = Set.of("production", "prod");
  private static final Set<String> ALLOWED_PROFILES = Set.of("test", "dev", "local", "development");

  TestModeAutoConfiguration(Environment environment) {
    String[] activeProfiles = environment.getActiveProfiles();

    // Belt: reject known production profiles unconditionally
    for (String profile : activeProfiles) {
      if (DENIED_PROFILES.contains(profile.toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalStateException("L402 test mode must not be used with production profiles");
      }
    }

    // Suspenders: require at least one explicit dev/test profile
    boolean hasAllowedProfile = false;
    for (String profile : activeProfiles) {
      if (ALLOWED_PROFILES.contains(profile.toLowerCase(java.util.Locale.ROOT))) {
        hasAllowedProfile = true;
        break;
      }
    }
    if (!hasAllowedProfile) {
      throw new IllegalStateException(
          "L402 test mode requires an explicit dev/test profile "
              + "(test, dev, local, development)");
    }
  }

  @Bean
  @ConditionalOnMissingBean
  LightningBackend testModeLightningBackend() {
    return new TestModeLightningBackend();
  }
}
