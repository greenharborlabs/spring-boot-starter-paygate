package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import com.greenharborlabs.paygate.spring.PaygateProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Real Spring Boot startup of the Spring Security example with client-address binding enabled and
 * an integrator-supplied {@code caveatVerifiers} bean that includes every mandatory boundary
 * verifier but omits {@code ClientIpCaveatVerifier}. The minted {@code client_ip} caveat would be
 * skipped as unknown, so startup must fail instead of serving unenforced bound credentials.
 */
@Tag("integration")
@DisplayName("Client-address binding verifier startup guard")
class ClientAddressBindingVerifierStartupIT {

  @Configuration(proxyBeanMethods = false)
  static class VerifiersWithoutClientIp {
    @Bean("caveatVerifiers")
    List<CaveatVerifier> caveatVerifiers(PaygateProperties properties) {
      String service =
          properties.getServiceName() == null || properties.getServiceName().isBlank()
              ? "default"
              : properties.getServiceName();
      return List.of(
          new ServicesCaveatVerifier(50),
          new ValidUntilCaveatVerifier(service),
          new CapabilitiesCaveatVerifier(service, 50),
          new PathCaveatVerifier(50),
          new RouteCaveatVerifier(50),
          new MethodCaveatVerifier(50));
    }
  }

  @Test
  @DisplayName("binding with a custom verifier list lacking client_ip fails application startup")
  void bindingWithoutClientIpVerifierFailsStartup() {
    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  new SpringApplicationBuilder(
                          SecurityExampleApplication.class, VerifiersWithoutClientIp.class)
                      .profiles("test")
                      .properties(
                          "server.port=0",
                          "paygate.enabled=true",
                          "paygate.test-mode=true",
                          "paygate.root-key-store=memory",
                          "paygate.protocols.mpp.challenge-binding-secret="
                              + "integration-test-secret-at-least-32-bytes-long",
                          "paygate.protocols.l402.client-address-binding-enabled=true",
                          "paygate.trust-forwarded-headers=true",
                          "paygate.trusted-proxy-addresses[0]=127.0.0.1")
                      .run()) {
                assertThat(context.isActive()).isFalse();
              }
            });

    assertThat(failure).isNotNull();
    assertThat(failure)
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ClientIpCaveatVerifier")
        .hasMessageContaining("client_ip");
  }
}
