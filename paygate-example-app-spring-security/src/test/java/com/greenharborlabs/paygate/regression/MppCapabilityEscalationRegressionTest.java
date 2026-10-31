package com.greenharborlabs.paygate.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression for SR-02 in Spring Security mode: an MPP credential bought for a 1-sat handler must
 * not unlock a 1000-sat handler that MVC selects for the same request line by header, nor satisfy
 * that handler's {@code PAYGATE_CAPABILITY_premium} method-security check.
 */
@SpringBootTest(
    classes = MppCapabilityEscalationRegressionTest.App.class,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.security-mode=spring-security",
      "paygate.service-name=mpp-binding",
      "paygate.protocols.mpp.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MPP credential bound to handler, price and capability (Spring Security mode)")
class MppCapabilityEscalationRegressionTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @DynamicPropertySource
  static void mppSecret(DynamicPropertyRegistry registry) {
    byte[] secret = new byte[48];
    new SecureRandom().nextBytes(secret);
    String value = HexFormat.of().formatHex(secret);
    registry.add("paygate.protocols.mpp.challenge-binding-secret", () -> value);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private Invocations invocations;

  @BeforeEach
  void reset() {
    invocations.clear();
  }

  @Test
  @DisplayName("1-sat credential for the basic handler does not unlock the premium handler")
  void cheapCredentialDoesNotUnlockPremiumHandler() throws Exception {
    MvcResult challenge = mockMvc.perform(get("/mpp/data/special")).andReturn();
    assertThat(challenge.getResponse().getStatus()).isEqualTo(402);
    assertThat(priceSats(challenge)).isEqualTo(1L);
    String authorization = mppAuthorization(challenge);

    int basic =
        mockMvc
            .perform(get("/mpp/data/special").header("Authorization", authorization))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(basic).isEqualTo(200);
    invocations.clear();

    MvcResult premium =
        mockMvc
            .perform(
                get("/mpp/data/special")
                    .header("X-Tier", "premium")
                    .header("Authorization", authorization))
            .andReturn();

    assertThat(premium.getResponse().getStatus()).isEqualTo(402);
    assertThat(premium.getResponse().getContentAsString()).doesNotContain("PREMIUM");
    assertThat(invocations.list()).isEmpty();
  }

  @Test
  @DisplayName("credential bought for the premium handler carries its verified capability")
  void premiumCredentialSatisfiesCapabilityCheck() throws Exception {
    MvcResult challenge =
        mockMvc.perform(get("/mpp/data/special").header("X-Tier", "premium")).andReturn();
    assertThat(challenge.getResponse().getStatus()).isEqualTo(402);
    assertThat(priceSats(challenge)).isEqualTo(1000L);

    MvcResult premium =
        mockMvc
            .perform(
                get("/mpp/data/special")
                    .header("X-Tier", "premium")
                    .header("Authorization", mppAuthorization(challenge)))
            .andReturn();

    assertThat(premium.getResponse().getStatus()).isEqualTo(200);
    assertThat(premium.getResponse().getContentAsString()).contains("PREMIUM");
    assertThat(invocations.list()).containsExactly("premium");
  }

  @SuppressWarnings("unchecked")
  private static long priceSats(MvcResult challenge) throws Exception {
    Map<String, Object> body =
        MAPPER.readValue(challenge.getResponse().getContentAsString(), Map.class);
    return ((Number) body.get("price_sats")).longValue();
  }

  @SuppressWarnings("unchecked")
  private static String mppAuthorization(MvcResult challenge) throws Exception {
    Map<String, Object> body =
        MAPPER.readValue(challenge.getResponse().getContentAsString(), Map.class);
    Map<String, Object> paymentChallenge =
        (Map<String, Object>) ((Map<String, Object>) body.get("protocols")).get("Payment");
    String json =
        MAPPER.writeValueAsString(
            Map.of(
                "challenge",
                paymentChallenge,
                "payload",
                Map.of("preimage", body.get("test_preimage"))));
    return "Payment "
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  static final class Invocations {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    void add(String handler) {
      calls.add(handler);
    }

    List<String> list() {
      return List.copyOf(calls);
    }

    void clear() {
      calls.clear();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({Controller.class, SecurityConfig.class})
  static class App {
    @Bean
    Invocations invocations() {
      return new Invocations();
    }
  }

  @RestController
  static class Controller {
    private final Invocations invocations;

    Controller(Invocations invocations) {
      this.invocations = invocations;
    }

    @PaymentRequired(priceSats = 1)
    @GetMapping("/mpp/data/{id}")
    Map<String, String> basic(@PathVariable String id) {
      invocations.add("basic");
      return Map.of("tier", "basic", "id", id);
    }

    @PaymentRequired(priceSats = 1000, capability = "premium")
    @PreAuthorize("hasAuthority('PAYGATE_CAPABILITY_premium')")
    @GetMapping(value = "/mpp/data/special", headers = "X-Tier=premium")
    Map<String, String> premium() {
      invocations.add("premium");
      return Map.of("tier", "PREMIUM");
    }
  }

  @Configuration
  @EnableWebSecurity
  @EnableMethodSecurity
  static class SecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig)
        throws Exception {
      return authConfig.getAuthenticationManager();
    }

    @Bean
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
          .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("PAYMENT"))
          .exceptionHandling(ex -> ex.authenticationEntryPoint(paygateEntryPoint))
          .build();
    }
  }
}
