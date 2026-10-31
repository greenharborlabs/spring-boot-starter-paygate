package com.greenharborlabs.paygate.spring;

import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails startup when a Paygate on/off switch is not spelled {@code true} or {@code false}.
 *
 * <p>Spring's binder converts {@code on}, {@code yes} and {@code 1} to {@code true}, and trims
 * whitespace, but {@code @ConditionalOnProperty(havingValue = "true")} compares the raw value
 * case-insensitively. Such a spelling therefore binds as enabled while the conditional
 * configuration stays off, which silently removes the payment wall. This guard runs for every
 * {@link SpringApplication}, including when Paygate is disabled, so the mismatch can never take
 * effect. Unset switches keep their defaults.
 */
final class PaygateSwitchPropertyGuard implements EnvironmentPostProcessor {

  static final List<String> SWITCH_PROPERTIES =
      List.of("paygate.enabled", "paygate.protocols.l402.enabled");

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    for (String property : SWITCH_PROPERTIES) {
      String value = environment.getProperty(property);
      if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
        throw new IllegalStateException(
            property
                + " must be exactly 'true' or 'false' (case-insensitive, without surrounding "
                + "whitespace) but was '"
                + value
                + "'. Other spellings such as 'on' or 'yes' would bind as true while the "
                + "conditional Paygate configuration stays disabled.");
      }
    }
  }
}
