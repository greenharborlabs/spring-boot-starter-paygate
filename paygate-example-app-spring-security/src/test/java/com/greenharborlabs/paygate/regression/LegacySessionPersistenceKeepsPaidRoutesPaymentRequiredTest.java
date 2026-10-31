package com.greenharborlabs.paygate.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the behaviour documented in the {@code paygate-spring-security} README for the deprecated
 * {@code securityContext(c -> c.requireExplicitSave(false))} opt-in.
 *
 * <p>Payment authorities are per-request by default. With the deprecated opt-in, the authenticated
 * context of one payment is saved to the HTTP session, so its {@code ROLE_PAYMENT} authority
 * applies to later unpaid routes in that session. Paid routes are unaffected: the Paygate filter
 * runs on every paid request and still returns 402 without a fresh credential.
 *
 * <p>Uses the reference Spring Security wiring (rate-limit filter and Paygate filter before {@link
 * BasicAuthenticationFilter}) with a real {@link SecurityFilterChain}, MVC handler mapping, and the
 * auto-configured Paygate beans.
 */
@SpringBootTest(
    classes = LegacySessionPersistenceKeepsPaidRoutesPaymentRequiredTest.LegacySessionApp.class,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.security-mode=spring-security",
      "paygate.service-name=legacy",
      "paygate.protocols.mpp.enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Deprecated requireExplicitSave(false): paid routes still return 402")
class LegacySessionPersistenceKeepsPaidRoutesPaymentRequiredTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final String CLIENT_IP = "198.51.100.50";

  @Autowired private MockMvc mockMvc;
  @Autowired private RootKeyStore rootKeyStore;

  @Test
  @DisplayName("a saved payment context reaches unpaid routes but never another paid route")
  void savedPaymentContextNeverOpensAnotherPaidRoute() throws Exception {
    MockHttpSession session = new MockHttpSession();
    int paid =
        status(
            withIp(get("/legacy/reports/123"))
                .session(session)
                .header("Authorization", validL402("/legacy/reports/{id}")));
    assertThat(paid).isEqualTo(200);
    assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT"))
        .as("the deprecated opt-in saves the payment context to the session")
        .isNotNull();

    int member = status(withIp(get("/legacy/member")).session(session));
    int paidAgain = status(withIp(get("/legacy/reports/456")).session(session));

    assertThat(member)
        .as("documented: the saved ROLE_PAYMENT authority applies to unpaid routes")
        .isEqualTo(200);
    assertThat(paidAgain).as("paid routes still require a fresh payment credential").isEqualTo(402);
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder) {
    return builder.with(
        request -> {
          request.setRemoteAddr(CLIENT_IP);
          return request;
        });
  }

  private String validL402(String route) {
    byte[] preimage = random(32);
    RootKeyStore.GenerationResult generated = rootKeyStore.generateRootKey();
    MacaroonIdentifier identifier =
        new MacaroonIdentifier(1, sha256(preimage), generated.tokenId());
    Macaroon macaroon =
        MacaroonMinter.mint(
            generated.rootKey().value(),
            identifier,
            null,
            List.of(
                new Caveat("services", "legacy:0"),
                new Caveat("route", route),
                new Caveat("method", "GET"),
                new Caveat("legacy_capabilities", "~"),
                new Caveat("legacy_price_sats", "10"),
                new Caveat(
                    "legacy_valid_until",
                    String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond()))));
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(preimage);
  }

  private static byte[] random(int length) {
    byte[] bytes = new byte[length];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({LegacySessionController.class, LegacySessionSecurityConfig.class})
  static class LegacySessionApp {}

  @RestController
  @RequestMapping("/legacy")
  static class LegacySessionController {

    @PaymentRequired(priceSats = 10)
    @GetMapping("/reports/{id}")
    Map<String, String> report(@PathVariable String id) {
      return Map.of("report", id);
    }

    /** Not paid; guarded only by ROLE_PAYMENT authorization. */
    @GetMapping("/member")
    Map<String, String> member() {
      return Map.of("member", "yes");
    }
  }

  @Configuration
  @EnableWebSecurity
  static class LegacySessionSecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig)
        throws Exception {
      return authConfig.getAuthenticationManager();
    }

    @Bean
    @SuppressWarnings("deprecation") // The deprecated opt-in is the behaviour under test.
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        PaygateAuthenticationFilter paygateFilter,
        PaygateAuthenticationProvider paygateProvider,
        PaygateAuthenticationEntryPoint paygateEntryPoint,
        PaygateAuthFailureRateLimitFilter rateLimitFilter)
        throws Exception {
      http.addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class);
      return http.authenticationProvider(paygateProvider)
          .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
          .authorizeHttpRequests(
              auth ->
                  auth.requestMatchers("/legacy/member")
                      .hasRole("PAYMENT")
                      .requestMatchers("/legacy/**")
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .exceptionHandling(ex -> ex.authenticationEntryPoint(paygateEntryPoint))
          .securityContext(c -> c.requireExplicitSave(false))
          .build();
    }
  }
}
