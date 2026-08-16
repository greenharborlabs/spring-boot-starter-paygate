package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.lightning.lnd.ValidatedLndTarget;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Shared fail-closed policy for development-only payment conveniences. */
final class DevelopmentSafetyPolicy {

  private static final System.Logger log =
      System.getLogger(DevelopmentSafetyPolicy.class.getName());
  private static final Set<String> ALLOWED_PROFILES = Set.of("dev", "local", "development", "test");

  private DevelopmentSafetyPolicy() {}

  static ValidatedTestMode validateTestMode(
      Environment environment,
      String configuredRootKeyStore,
      RootKeyStore effectiveRootKeyStore,
      List<LightningBackend> effectiveBackends) {
    validateProfiles(environment);
    if (!"memory".equalsIgnoreCase(configuredRootKeyStore)) {
      throw rejected("root-key-store");
    }
    if (effectiveRootKeyStore.persistenceCapability()
        != RootKeyStore.PersistenceCapability.EPHEMERAL) {
      throw rejected("effective-root-key-store");
    }
    if (effectiveBackends.size() != 1
        || effectiveBackends.getFirst().getClass() != TestModeLightningBackend.class) {
      throw rejected("effective-lightning-backend");
    }
    log.log(
        System.Logger.Level.WARNING,
        "Test-mode payment bypass is enabled for an explicitly local development environment");
    return new ValidatedTestMode();
  }

  static void validatePlaintextLnd(Environment environment, String host) {
    validateProfiles(environment);
    ValidatedLndTarget.validate(host);
    log.log(
        System.Logger.Level.WARNING,
        "Plaintext LND transport is enabled for an explicitly local development environment");
  }

  static void validateProfiles(Environment environment) {
    var profiles = environment.getActiveProfiles();
    if (profiles.length == 0) {
      throw rejected("active-profiles");
    }
    for (String profile : profiles) {
      if (profile == null || !ALLOWED_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
        throw rejected("active-profiles");
      }
    }
  }

  private static IllegalStateException rejected(String category) {
    return new IllegalStateException("Unsafe development configuration category: " + category);
  }

  /** Marker created only after all test-mode prerequisites have been verified. */
  static final class ValidatedTestMode {
    private ValidatedTestMode() {}
  }
}
