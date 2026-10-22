package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup guard for L402 client-address binding: a minted {@code client_ip} caveat without a
 * registered verifier would be skipped as unknown, so the context must refuse to start.
 */
@DisplayName("Client-address binding startup guard")
class ClientAddressBindingStartupTest {

  private static final String SERVICE = "binding-test";

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true",
              "paygate.backend=lnbits",
              "paygate.root-key-store=memory",
              "paygate.service-name=" + SERVICE,
              "paygate.protocols.mpp.enabled=false")
          .withBean(LightningBackend.class, () -> mock(LightningBackend.class));

  @Test
  @DisplayName("binding with a custom verifier list lacking client_ip fails startup")
  void bindingWithoutClientIpVerifierFailsStartup() {
    contextRunner
        .withUserConfiguration(VerifiersWithoutClientIp.class)
        .withPropertyValues("paygate.protocols.l402.client-address-binding-enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("ClientIpCaveatVerifier")
                  .hasMessageContaining("client_ip");
            });
  }

  @Test
  @DisplayName("binding with the default verifiers starts and registers client_ip")
  void bindingWithDefaultVerifiersStarts() {
    contextRunner
        .withPropertyValues("paygate.protocols.l402.client-address-binding-enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(L402Validator.class).hasCaveatVerifier("client_ip"))
                  .isTrue();
              assertThat(context.getBean("l402Protocol", PaymentProtocol.class))
                  .isInstanceOf(L402Protocol.class);
            });
  }

  @Test
  @DisplayName("binding with a custom verifier list that includes client_ip starts")
  void bindingWithCustomClientIpVerifierStarts() {
    contextRunner
        .withUserConfiguration(VerifiersWithClientIp.class)
        .withPropertyValues("paygate.protocols.l402.client-address-binding-enabled=true")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("a custom verifier list lacking client_ip still starts when binding is disabled")
  void customVerifiersWithoutClientIpStartWhenBindingDisabled() {
    contextRunner
        .withUserConfiguration(VerifiersWithoutClientIp.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  private static List<CaveatVerifier> boundaryVerifiers() {
    return new ArrayList<>(
        List.of(
            new ServicesCaveatVerifier(50),
            new ValidUntilCaveatVerifier(SERVICE),
            new CapabilitiesCaveatVerifier(SERVICE, 50),
            new PathCaveatVerifier(50),
            new RouteCaveatVerifier(50),
            new MethodCaveatVerifier(50)));
  }

  @Configuration(proxyBeanMethods = false)
  static class VerifiersWithoutClientIp {
    @Bean
    List<CaveatVerifier> caveatVerifiers() {
      return List.copyOf(boundaryVerifiers());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class VerifiersWithClientIp {
    @Bean
    List<CaveatVerifier> caveatVerifiers() {
      List<CaveatVerifier> verifiers = boundaryVerifiers();
      verifiers.add(new ClientIpCaveatVerifier(50));
      return List.copyOf(verifiers);
    }
  }
}
