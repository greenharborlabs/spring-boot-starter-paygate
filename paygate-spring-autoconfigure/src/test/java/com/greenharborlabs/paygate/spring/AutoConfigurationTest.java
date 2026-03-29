package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
  @DisplayName("creates FilterRegistrationBean for PaygateSecurityFilter when paygate.enabled=true")
  void createsFilterRegistration() {
    contextRunner.run(context -> assertThat(context).hasBean("paygateSecurityFilterRegistration"));
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
  @DisplayName("spring-security mode without Spring Security on classpath fails startup")
  void springSecurityModeWithoutSpringSecurityFails() {
    // Spring Security is not on this module's test classpath, so spring-security mode
    // should cause a validation failure at startup.
    contextRunner
        .withPropertyValues("paygate.security-mode=spring-security")
        .run(context -> assertThat(context).hasFailed());
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
              assertThat(verifiers).hasSize(1);
              assertThat(verifiers.getFirst()).isInstanceOf(ServicesCaveatVerifier.class);
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
      return List.of(new ServicesCaveatVerifier(50));
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
