package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.mpp.MppProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration tests for {@link PaygateAutoConfiguration}.
 *
 * <p>Uses {@link WebApplicationContextRunner} to verify that all expected beans are created when
 * {@code paygate.enabled=true} and required dependencies are present.
 */
@DisplayName("PaygateAutoConfiguration")
class AutoConfigurationTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true", "paygate.backend=lnbits", "paygate.root-key-store=memory")
          .withBean(LightningBackend.class, StubLightningBackend::new);

  @Test
  @DisplayName("creates RootKeyStore bean when paygate.enabled=true")
  void createsRootKeyStore() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(RootKeyStore.class));
  }

  @Test
  @DisplayName("creates CredentialStore bean when paygate.enabled=true")
  void createsCredentialStore() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(CredentialStore.class));
  }

  @Test
  @DisplayName("creates PaygateSecurityFilter bean when paygate.enabled=true")
  void createsSecurityFilter() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(PaygateSecurityFilter.class));
  }

  @Test
  @DisplayName("creates PaygateEndpointRegistry bean when paygate.enabled=true")
  void createsEndpointRegistry() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(PaygateEndpointRegistry.class));
  }

  @Test
  @DisplayName("endpoint registry uses the configured caveat value limit")
  void endpointRegistryUsesConfiguredCaveatValueLimit() {
    contextRunner
        .withPropertyValues("paygate.caveat.max-values-per-caveat=1")
        .run(
            context -> {
              var registry = context.getBean(PaygateEndpointRegistry.class);
              var config =
                  new PaygateEndpointConfig("GET", "/bounded", 10, 600, "", "", "read,write");

              assertThatThrownBy(() -> registry.register(config))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("maximum allowed is 1");
            });
  }

  @Test
  @DisplayName("creates endpoint registry when actuator handler mapping is also present")
  void endpointRegistryUsesMvcHandlerMappingWhenActuatorMappingAlsoPresent() {
    contextRunner
        .withBean(
            "controllerEndpointHandlerMapping",
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
                .class,
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping::new)
        .run(context -> assertThat(context).hasSingleBean(PaygateEndpointRegistry.class));
  }

  @Test
  @DisplayName("creates FilterRegistrationBean for PaygateSecurityFilter when paygate.enabled=true")
  void createsFilterRegistration() {
    contextRunner.run(context -> assertThat(context).hasBean("paygateSecurityFilterRegistration"));
  }

  @Test
  @DisplayName("auto mode keeps servlet filter registration active without Paygate integration")
  void autoModeKeepsServletFilterRegistrationWhenIntegrationMissing() {
    contextRunner
        .withPropertyValues("paygate.security-mode=auto")
        .run(
            context -> {
              assertThat(context).hasBean("paygateSecurityFilterRegistration");
              assertThat(context).doesNotHaveBean("paygateSecurityFilterDisabledRegistration");
              Object validator = context.getBean("paygateSecurityModeStartupValidator");
              var method = validator.getClass().getMethod("resolvedMode");
              method.setAccessible(true);
              String resolvedMode = (String) method.invoke(validator);
              assertThat(resolvedMode).isEqualTo("servlet");
            });
  }

  @Test
  @DisplayName("creates caveatVerifiers list bean when paygate.enabled=true")
  void createsCaveatVerifiers() {
    contextRunner.run(context -> assertThat(context).hasBean("caveatVerifiers"));
  }

  @Test
  @DisplayName("creates L402Validator bean when paygate.enabled=true")
  void createsL402Validator() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(L402Validator.class));
  }

  @Test
  @DisplayName(
      "all L402 beans are created together when paygate.enabled=true and paygate.backend=lnbits")
  void allBeansCreated() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(RootKeyStore.class);
          assertThat(context).hasSingleBean(CredentialStore.class);
          assertThat(context).hasSingleBean(PaygateSecurityFilter.class);
          assertThat(context).hasSingleBean(PaygateEndpointRegistry.class);
          assertThat(context).hasBean("paygateSecurityFilterRegistration");
          assertThat(context).hasBean("caveatVerifiers");
          assertThat(context).hasSingleBean(L402Validator.class);
        });
  }

  @Test
  @DisplayName("PaygateEndpointRegistry receives defaultTimeoutSeconds from properties")
  void registryReceivesDefaultTimeoutFromProperties() {
    contextRunner
        .withPropertyValues("paygate.default-timeout-seconds=9999")
        .withBean("testController", SentinelTimeoutController.class, SentinelTimeoutController::new)
        .run(
            context -> {
              PaygateEndpointRegistry registry = context.getBean(PaygateEndpointRegistry.class);
              // The controller endpoint uses @PaymentRequired(priceSats=5) with default
              // timeoutSeconds=-1
              PaygateEndpointConfig config = registry.findConfig("GET", "/api/sentinel-test");
              assertThat(config).isNotNull();
              assertThat(config.timeoutSeconds()).isEqualTo(9999);
            });
  }

  @Test
  @DisplayName("spring-security mode without required classpath fails startup")
  void springSecurityModeWithoutRequiredClasspathFails() {
    contextRunner
        .withPropertyValues("paygate.security-mode=spring-security")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("paygate.security-mode=spring-security")
                  .hasMessageContaining("paygate-spring-security integration module");
            });
  }

  @Test
  @DisplayName("starter does not declare paygate-spring-security dependency")
  void starterDoesNotDeclarePaygateSpringSecurityDependency() throws Exception {
    Path starterBuildFile = Path.of("..", "paygate-spring-boot-starter", "build.gradle.kts");
    String buildFile = Files.readString(starterBuildFile);
    assertThat(buildFile)
        .contains("paygate-spring-autoconfigure")
        .doesNotContain("paygate-spring-security");
  }

  @Test
  @DisplayName("FilterRegistrationBean created in servlet mode")
  void filterRegistrationCreatedInServletMode() {
    contextRunner
        .withPropertyValues("paygate.security-mode=servlet")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PaygateSecurityFilter.class);
              assertThat(context).hasBean("paygateSecurityFilterRegistration");
            });
  }

  @Test
  @DisplayName("invalid security-mode causes startup failure")
  void invalidSecurityModeFailsStartup() {
    contextRunner
        .withPropertyValues("paygate.security-mode=bogus")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("PaygateSecurityModeStartupValidator bean is created")
  void securityModeValidatorBeanCreated() {
    contextRunner
        .withPropertyValues("paygate.security-mode=servlet")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(
                        PaygateAutoConfiguration.PaygateSecurityModeStartupValidator.class));
  }

  @Test
  @DisplayName(
      "RootKeyStore is wrapped in ObservableRootKeyStore when paygate.root-key-store=memory")
  void inMemoryRootKeyStoreWhenMemoryMode() {
    contextRunner.run(
        context -> {
          RootKeyStore store = context.getBean(RootKeyStore.class);
          assertThat(store)
              .isInstanceOf(com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore.class);
        });
  }

  @Test
  @DisplayName(
      "default caveatVerifiers contains delegation verifiers (PathCaveatVerifier, MethodCaveatVerifier, ClientIpCaveatVerifier)")
  @SuppressWarnings("unchecked")
  void defaultCaveatVerifiersContainsDelegationVerifiers() {
    contextRunner.run(
        context -> {
          List<CaveatVerifier> verifiers =
              (List<CaveatVerifier>) context.getBean("caveatVerifiers");
          assertThat(verifiers)
              .hasAtLeastOneElementOfType(PathCaveatVerifier.class)
              .hasAtLeastOneElementOfType(MethodCaveatVerifier.class)
              .hasAtLeastOneElementOfType(ClientIpCaveatVerifier.class);
        });
  }

  @Test
  @DisplayName(
      "default caveatVerifiers still contains existing verifiers (ServicesCaveatVerifier, ValidUntilCaveatVerifier, CapabilitiesCaveatVerifier)")
  @SuppressWarnings("unchecked")
  void defaultCaveatVerifiersContainsExistingVerifiers() {
    contextRunner.run(
        context -> {
          List<CaveatVerifier> verifiers =
              (List<CaveatVerifier>) context.getBean("caveatVerifiers");
          assertThat(verifiers)
              .hasAtLeastOneElementOfType(ServicesCaveatVerifier.class)
              .hasAtLeastOneElementOfType(ValidUntilCaveatVerifier.class)
              .hasAtLeastOneElementOfType(CapabilitiesCaveatVerifier.class);
        });
  }

  @Test
  @DisplayName("custom caveatVerifiers bean overrides all defaults")
  @SuppressWarnings("unchecked")
  void customCaveatVerifiersBeanOverridesDefaults() {
    contextRunner
        .withUserConfiguration(CustomCaveatVerifiersConfig.class)
        .run(
            context -> {
              List<CaveatVerifier> verifiers =
                  (List<CaveatVerifier>) context.getBean("caveatVerifiers");
              assertThat(verifiers).hasSize(5);
              assertThat(verifiers.getFirst()).isInstanceOf(ServicesCaveatVerifier.class);
              assertThat(verifiers.get(1))
                  .isInstanceOf(
                      com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier.class);
              assertThat(verifiers.get(2))
                  .isInstanceOf(
                      com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier.class);
              assertThat(verifiers.get(3)).isInstanceOf(CapabilitiesCaveatVerifier.class);
              assertThat(verifiers.get(4)).isInstanceOf(ValidUntilCaveatVerifier.class);
            });
  }

  @Test
  @DisplayName("ClientIpResolver bean is created when paygate.enabled=true")
  void clientIpResolverBeanCreated() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(ClientIpResolver.class));
  }

  @Test
  @DisplayName("trustedProxyAddresses binds from properties")
  void trustedProxyAddressesBindFromProperties() {
    contextRunner
        .withPropertyValues("paygate.trusted-proxy-addresses=10.0.0.1,10.0.0.2")
        .run(
            context -> {
              PaygateProperties props = context.getBean(PaygateProperties.class);
              assertThat(props.getTrustedProxyAddresses()).containsExactly("10.0.0.1", "10.0.0.2");
            });
  }

  @Test
  @DisplayName("trustedProxyAddresses defaults to empty list")
  void trustedProxyAddressesDefaultIsEmpty() {
    contextRunner.run(
        context -> {
          PaygateProperties props = context.getBean(PaygateProperties.class);
          assertThat(props.getTrustedProxyAddresses()).isEmpty();
        });
  }

  @Test
  @DisplayName("maxValuesPerCaveat binds from properties")
  void maxValuesPerCaveatBindsFromProperties() {
    contextRunner
        .withPropertyValues("paygate.caveat.max-values-per-caveat=25")
        .run(
            context -> {
              PaygateProperties props = context.getBean(PaygateProperties.class);
              assertThat(props.getCaveat().getMaxValuesPerCaveat()).isEqualTo(25);
            });
  }

  @Test
  @DisplayName("maxValuesPerCaveat defaults to 50")
  void maxValuesPerCaveatDefaultIs50() {
    contextRunner.run(
        context -> {
          PaygateProperties props = context.getBean(PaygateProperties.class);
          assertThat(props.getCaveat().getMaxValuesPerCaveat()).isEqualTo(50);
        });
  }

  // --- Protocol conditional registration tests ---

  /** A secret that is exactly 32 ASCII characters = 32 UTF-8 bytes. */
  private static final String VALID_SECRET = "abcdefghijklmnopqrstuvwxyz012345";

  /** A secret that is only 10 ASCII characters = 10 UTF-8 bytes. */
  private static final String SHORT_SECRET = "short_sec!";

  /** A committed sample secret that must only be accepted in test mode. */
  private static final String UNSAFE_SAMPLE_SECRET =
      "dev-only-mpp-test-secret-do-not-use-in-production";

  @Nested
  @DisplayName("L402 protocol conditional registration")
  class L402ProtocolRegistration {

    @Test
    @DisplayName("L402Protocol bean created by default (l402.enabled defaults to true)")
    void l402ProtocolCreatedByDefault() {
      contextRunner.run(context -> assertThat(context).hasBean("l402Protocol"));
    }

    @Test
    @DisplayName("L402Protocol bean created when paygate.protocols.l402.enabled=true")
    void l402ProtocolCreatedWhenExplicitlyEnabled() {
      contextRunner
          .withPropertyValues("paygate.protocols.l402.enabled=true")
          .run(context -> assertThat(context).hasBean("l402Protocol"));
    }

    @Test
    @DisplayName("L402Protocol bean NOT created when paygate.protocols.l402.enabled=false")
    void l402ProtocolNotCreatedWhenDisabled() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.l402.enabled=false",
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(context -> assertThat(context).doesNotHaveBean("l402Protocol"));
    }
  }

  @Nested
  @DisplayName("MPP protocol conditional registration")
  class MppProtocolRegistration {

    @Test
    @DisplayName("MppProtocol bean created when mpp.enabled=auto and secret is present")
    void mppProtocolCreatedWhenAutoAndSecretPresent() {
      contextRunner
          .withPropertyValues("paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(
              context -> {
                assertThat(context).hasBean("mppProtocol");
                PaymentProtocol mpp = context.getBean("mppProtocol", PaymentProtocol.class);
                assertThat(mpp).isInstanceOf(MppProtocol.class);
              });
    }

    @Test
    @DisplayName("MppProtocol bean created when mpp.enabled=true and secret is present")
    void mppProtocolCreatedWhenExplicitlyEnabled() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(context -> assertThat(context).hasBean("mppProtocol"));
    }

    @Test
    @DisplayName(
        "MppProtocol bean created when previous challenge-binding secret is configured for rotation")
    void mppProtocolCreatedWithPreviousSecret() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET,
              "paygate.protocols.mpp.previous-challenge-binding-secret="
                  + "0123456789abcdefghijklmnopqrstuvwxyz")
          .run(context -> assertThat(context).hasBean("mppProtocol"));
    }

    @Test
    @DisplayName("MppProtocol bean NOT created when mpp.enabled=false")
    void mppProtocolNotCreatedWhenDisabled() {
      contextRunner
          .withPropertyValues("paygate.protocols.mpp.enabled=false")
          .run(context -> assertThat(context).doesNotHaveBean("mppProtocol"));
    }

    @Test
    @DisplayName("MppProtocol bean created when mpp.enabled=AUTO (mixed case) and secret present")
    void mppProtocolCreatedWhenAutoUpperCase() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=AUTO",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(context -> assertThat(context).hasBean("mppProtocol"));
    }

    @Test
    @DisplayName("MppProtocol bean NOT created when mpp.enabled=auto and no secret")
    void mppProtocolNotCreatedWhenAutoAndNoSecret() {
      contextRunner.run(context -> assertThat(context).doesNotHaveBean("mppProtocol"));
    }

    @Test
    @DisplayName("custom MPP parser limit properties flow through to MppProtocol bean")
    void customMppParserLimitsFlowThrough() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET,
              "paygate.protocols.mpp.max-json-depth=3",
              "paygate.protocols.mpp.max-string-length=4096",
              "paygate.protocols.mpp.max-keys-per-object=16",
              "paygate.protocols.mpp.max-credential-bytes=32768")
          .run(
              context -> {
                assertThat(context).hasBean("mppProtocol");
                PaymentProtocol mpp = context.getBean("mppProtocol", PaymentProtocol.class);
                assertThat(mpp).isInstanceOf(MppProtocol.class);

                // Verify properties were bound correctly
                PaygateProperties props = context.getBean(PaygateProperties.class);
                var mppProps = props.getProtocols().getMpp();
                assertThat(mppProps.getMaxJsonDepth()).isEqualTo(3);
                assertThat(mppProps.getMaxStringLength()).isEqualTo(4096);
                assertThat(mppProps.getMaxKeysPerObject()).isEqualTo(16);
                assertThat(mppProps.getMaxCredentialBytes()).isEqualTo(32768);
              });
    }

    @Test
    @DisplayName("MPP parser limit properties default to MppParserLimits.defaults() values")
    void mppParserLimitsDefaultValues() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(
              context -> {
                PaygateProperties props = context.getBean(PaygateProperties.class);
                var mppProps = props.getProtocols().getMpp();
                assertThat(mppProps.getMaxJsonDepth()).isEqualTo(5);
                assertThat(mppProps.getMaxStringLength()).isEqualTo(8192);
                assertThat(mppProps.getMaxKeysPerObject()).isEqualTo(32);
                assertThat(mppProps.getMaxCredentialBytes()).isEqualTo(65_536);
              });
    }
  }

  @Nested
  @DisplayName("Protocol startup validation")
  class ProtocolStartupValidation {

    @Test
    @DisplayName("startup fails when mpp.enabled=true but no secret provided")
    void failsWhenMppEnabledTrueNoSecret() {
      contextRunner
          .withPropertyValues("paygate.protocols.mpp.enabled=true")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("challenge-binding-secret")
                    .hasMessageContaining("is not set");
              });
    }

    @Test
    @DisplayName("startup fails when secret is present but < 32 UTF-8 bytes")
    void failsWhenSecretTooShort() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + SHORT_SECRET)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName("startup fails when previous secret is provided but primary secret is missing")
    void failsWhenPreviousSecretProvidedWithoutPrimarySecret() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.previous-challenge-binding-secret="
                  + "0123456789abcdefghijklmnopqrstuvwxyz")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("previous-challenge-binding-secret")
                    .hasMessageContaining("challenge-binding-secret");
              });
    }

    @Test
    @DisplayName("startup fails when previous secret is present but < 32 UTF-8 bytes")
    void failsWhenPreviousSecretTooShort() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET,
              "paygate.protocols.mpp.previous-challenge-binding-secret=short_prev!")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("previous-challenge-binding-secret")
                    .hasMessageContaining("at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName("startup fails when primary secret uses committed sample outside test mode")
    void failsWhenPrimarySecretUsesCommittedSampleOutsideTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.challenge-binding-secret=" + UNSAFE_SAMPLE_SECRET)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paygate.protocols.mpp.challenge-binding-secret")
                    .hasMessageContaining("Committed sample secrets are test-only")
                    .hasMessageContaining("PAYGATE_MPP_SECRET")
                    .hasMessageContaining("unique secret of at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName(
        "startup fails when disabled MPP primary secret uses committed sample outside test mode")
    void failsWhenDisabledMppPrimarySecretUsesCommittedSampleOutsideTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=false",
              "paygate.protocols.mpp.challenge-binding-secret=" + UNSAFE_SAMPLE_SECRET)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paygate.protocols.mpp.challenge-binding-secret")
                    .hasMessageContaining("Committed sample secrets are test-only")
                    .hasMessageContaining("PAYGATE_MPP_SECRET")
                    .hasMessageContaining("unique secret of at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName("startup fails when previous secret uses committed sample outside test mode")
    void failsWhenPreviousSecretUsesCommittedSampleOutsideTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET,
              "paygate.protocols.mpp.previous-challenge-binding-secret=" + UNSAFE_SAMPLE_SECRET)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paygate.protocols.mpp.previous-challenge-binding-secret")
                    .hasMessageContaining("Committed sample secrets are test-only")
                    .hasMessageContaining("PAYGATE_MPP_SECRET")
                    .hasMessageContaining("unique secret of at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName(
        "startup fails when disabled MPP previous secret uses committed sample outside test mode")
    void failsWhenDisabledMppPreviousSecretUsesCommittedSampleOutsideTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.mpp.enabled=false",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET,
              "paygate.protocols.mpp.previous-challenge-binding-secret=" + UNSAFE_SAMPLE_SECRET)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paygate.protocols.mpp.previous-challenge-binding-secret")
                    .hasMessageContaining("Committed sample secrets are test-only")
                    .hasMessageContaining("PAYGATE_MPP_SECRET")
                    .hasMessageContaining("unique secret of at least 32 UTF-8 bytes");
              });
    }

    @Test
    @DisplayName("startup succeeds with committed sample secret in test mode")
    void succeedsWithCommittedSampleSecretInTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.test-mode=true",
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + UNSAFE_SAMPLE_SECRET)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("mppProtocol");
              });
    }

    @Test
    @DisplayName("startup succeeds with unique production-length secret outside test mode")
    void succeedsWithUniqueProductionLengthSecretOutsideTestMode() {
      contextRunner
          .withPropertyValues(
              "paygate.test-mode=false",
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret="
                  + "unique-mpp-secret-for-production-1234567890")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("mppProtocol");
              });
    }

    @Test
    @DisplayName("startup fails when no protocols are enabled (L402 disabled, MPP disabled)")
    void failsWhenNoProtocolsEnabled() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.l402.enabled=false", "paygate.protocols.mpp.enabled=false")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No payment protocols are enabled");
              });
    }

    @Test
    @DisplayName(
        "startup fails when no protocols are enabled (L402 disabled, MPP auto with no secret)")
    void failsWhenNoProtocolsEnabledAutoMpp() {
      contextRunner
          .withPropertyValues("paygate.protocols.l402.enabled=false")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No payment protocols are enabled");
              });
    }

    @Test
    @DisplayName("startup fails safely when no payment protocol implementation is present")
    void failsWhenNoProtocolImplementationIsPresent() {
      String configuredSecret = "operator-secret-must-not-appear-in-diagnostics-123456";

      contextRunner
          .withClassLoader(
              new FilteredClassLoader(
                  "com.greenharborlabs.paygate.protocol.l402",
                  "com.greenharborlabs.paygate.protocol.mpp"))
          .withPropertyValues("paygate.protocols.mpp.challenge-binding-secret=" + configuredSecret)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No usable payment protocol")
                    .hasMessageContaining("paygate.protocols")
                    .hasMessageNotContaining(configuredSecret);
              });
    }

    @Test
    @DisplayName("startup succeeds with both protocols enabled")
    void succeedsWithBothProtocols() {
      contextRunner
          .withPropertyValues(
              "paygate.protocols.l402.enabled=true",
              "paygate.protocols.mpp.enabled=true",
              "paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("l402Protocol");
                assertThat(context).hasBean("mppProtocol");
                var validator =
                    context.getBean(PaygateAutoConfiguration.ProtocolStartupValidator.class);
                assertThat(validator.activeProtocolCount()).isEqualTo(2);
              });
    }

    @Test
    @DisplayName("ProtocolStartupValidator bean is created on successful startup")
    void protocolStartupValidatorCreated() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasSingleBean(PaygateAutoConfiguration.ProtocolStartupValidator.class));
    }

    @Test
    @DisplayName("existing caveat beans remain unchanged when protocols are configured")
    void existingCaveatBeansPreserved() {
      contextRunner
          .withPropertyValues("paygate.protocols.mpp.challenge-binding-secret=" + VALID_SECRET)
          .run(
              context -> {
                assertThat(context).hasBean("caveatVerifiers");
                assertThat(context).hasSingleBean(ClientIpResolver.class);
                assertThat(context).hasSingleBean(L402Validator.class);
                assertThat(context).hasSingleBean(PaygateSecurityFilter.class);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomCaveatVerifiersConfig {

    @Bean
    List<CaveatVerifier> caveatVerifiers() {
      return List.of(
          new ServicesCaveatVerifier(50),
          new com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier(50),
          new com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier(50),
          new com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier("default", 50),
          new ValidUntilCaveatVerifier("default"));
    }
  }

  /**
   * Minimal stub of {@link LightningBackend} for auto-configuration testing. The auto-config does
   * not create a LightningBackend, so tests must supply one.
   */
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

  /** Controller with sentinel timeout (-1) to test default resolution. */
  @org.springframework.web.bind.annotation.RestController
  static class SentinelTimeoutController {

    @PaymentRequired(priceSats = 5)
    @org.springframework.web.bind.annotation.GetMapping("/api/sentinel-test")
    String sentinelEndpoint() {
      return "sentinel";
    }
  }
}
