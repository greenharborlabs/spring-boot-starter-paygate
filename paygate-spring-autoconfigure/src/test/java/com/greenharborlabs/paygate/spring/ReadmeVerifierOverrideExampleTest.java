package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mirrors the "Override the Validator" and "Override Caveat Verifiers" examples in the
 * autoconfigure README. The configuration classes below are the README examples verbatim apart from
 * Java visibility, so a README change that breaks startup also breaks this test.
 */
@DisplayName("README verifier override examples")
class ReadmeVerifierOverrideExampleTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.protocols.mpp.enabled=false")
          .withBean(LightningBackend.class, () -> mock(LightningBackend.class));

  @ParameterizedTest(name = "paygate.service-name={0}")
  @ValueSource(strings = {"", "my-service"})
  @DisplayName("the custom caveat verifier example starts with the configured service name")
  void customCaveatVerifierExampleStarts(String serviceName) {
    runner(serviceName)
        .withUserConfiguration(CustomCaveatConfig.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var validator = context.getBean(L402Validator.class);
              assertThat(validator.hasCaveatVerifier(MyCustomCaveatVerifier.KEY)).isTrue();
              assertThat(validator.hasCaveatVerifier(expectedService(serviceName) + "_valid_until"))
                  .isTrue();
            });
  }

  @ParameterizedTest(name = "paygate.service-name={0}")
  @ValueSource(strings = {"", "my-service"})
  @DisplayName("the custom validator example starts and replaces the default validator")
  void customValidatorExampleStarts(String serviceName) {
    runner(serviceName)
        .withUserConfiguration(CustomValidatorConfig.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(L402Validator.class);
              assertThat(context).hasBean("l402Validator");
              var validator = context.getBean(L402Validator.class);
              assertThat(validator.hasCaveatVerifier(MyCustomCaveatVerifier.KEY)).isTrue();
              assertThat(
                      validator.hasCaveatVerifier(expectedService(serviceName) + "_capabilities"))
                  .isTrue();
            });
  }

  @Test
  @DisplayName("the previous hard-coded service name fails startup under the default name")
  void previousHardCodedExampleFailsStartup() {
    contextRunner
        .withUserConfiguration(HardCodedServiceNameCaveatConfig.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("Missing required default_capabilities caveat verifier");
            });
  }

  private WebApplicationContextRunner runner(String serviceName) {
    return serviceName.isEmpty()
        ? contextRunner
        : contextRunner.withPropertyValues("paygate.service-name=" + serviceName);
  }

  private static String expectedService(String serviceName) {
    return serviceName.isEmpty() ? "default" : serviceName;
  }

  // --- README: "Override the Validator" ---------------------------------------------------

  @Configuration
  static class CustomValidatorConfig {

    @Bean
    public L402Validator l402Validator(
        RootKeyStore rootKeyStore,
        CredentialStore credentialStore,
        LightningBackend lightningBackend,
        @Value("${paygate.service-name:default}") String serviceName) {
      return new L402Validator(
          rootKeyStore,
          credentialStore,
          List.of(
              // Required by L402Validator: construction fails if any is missing.
              new ServicesCaveatVerifier(50),
              new RouteCaveatVerifier(50),
              new MethodCaveatVerifier(50),
              new CapabilitiesCaveatVerifier(serviceName, 50),
              new ValidUntilCaveatVerifier(serviceName),
              new MyCustomCaveatVerifier()),
          serviceName,
          lightningBackend);
    }
  }

  // --- README: "Override Caveat Verifiers" ------------------------------------------------

  @Configuration
  static class CustomCaveatConfig {

    @Bean("caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers(
        @Value("${paygate.service-name:default}") String serviceName) {
      return List.of(
          // Required by L402Validator: startup fails if any of these is missing.
          // The capabilities and valid_until keys must use paygate.service-name.
          new ServicesCaveatVerifier(50),
          new RouteCaveatVerifier(50),
          new MethodCaveatVerifier(50),
          new CapabilitiesCaveatVerifier(serviceName, 50),
          new ValidUntilCaveatVerifier(serviceName),
          // Required when paygate.protocols.l402.client-address-binding-enabled=true;
          // also enforces holder-added client_ip delegation caveats.
          new ClientIpCaveatVerifier(50),
          new PathCaveatVerifier(50),
          new MyCustomCaveatVerifier());
    }
  }

  /** The example as previously documented, hard-coding a service name. */
  @Configuration
  static class HardCodedServiceNameCaveatConfig {

    @Bean("caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers() {
      return List.of(
          new ServicesCaveatVerifier(50),
          new RouteCaveatVerifier(50),
          new MethodCaveatVerifier(50),
          new CapabilitiesCaveatVerifier("my-service", 50),
          new ValidUntilCaveatVerifier("my-service"),
          new MyCustomCaveatVerifier());
    }
  }

  /** Stand-in for the application's own verifier in the README examples. */
  static final class MyCustomCaveatVerifier implements CaveatVerifier {
    static final String KEY = "tier";

    @Override
    public String getKey() {
      return KEY;
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
      // Accepts every value; the examples only demonstrate registration.
    }
  }
}
