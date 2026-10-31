package com.greenharborlabs.paygate.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression tests for SR-2026-10-18-14 and SR-2026-10-18-15 in Spring Security mode.
 *
 * <ul>
 *   <li>SR-14: the auth-failure rate limiter resolves the endpoint with full MVC resolution, so a
 *       paid route shadowed in a path-only lookup by a more specific, condition-guarded unpaid
 *       mapping is still rate limited.
 *   <li>SR-15: the post-failure penalty follows the authentication filter's client-fault marker.
 *       Malformed (400) and invalid (402) presented credentials are penalized; backend-outage 503s
 *       and first challenges without a credential are not.
 * </ul>
 *
 * <p>Uses the reference wiring (rate-limit filter and Paygate filter before {@link
 * BasicAuthenticationFilter}) with a real {@link SecurityFilterChain}, MVC handler mapping, and the
 * auto-configured Paygate beans. With {@code burst-size=3} and a negligible refill rate, each
 * admitted attempt costs one token, plus one penalty token when penalized. Each test uses its own
 * client address, so buckets are independent.
 */
@SpringBootTest(
    classes = SpringSecurityAuthFailureRateLimitRegressionTest.TestApp.class,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.security-mode=spring-security",
      "paygate.service-name=ratelimit",
      "paygate.protocols.mpp.enabled=false",
      "paygate.rate-limit.burst-size=3",
      "paygate.rate-limit.requests-per-second=0.01"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Spring Security auth-failure rate limit (SR-14, SR-15) regression")
class SpringSecurityAuthFailureRateLimitRegressionTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private MockMvc mockMvc;
  @Autowired private RootKeyStore rootKeyStore;

  // ---------------------------------------------------------------------------------------------
  // SR-14: endpoint resolution
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("control: invalid credentials to an unshadowed paid route reach 429")
  void invalidCredentialsToUnshadowedPaidRouteAreRateLimited() throws Exception {
    List<Integer> statuses = sendInvalidCredentials("/rl/reports/123", "198.51.100.10", 8);

    assertThat(statuses).contains(429);
  }

  @Test
  @DisplayName("invalid credentials to a paid route shadowed in a path-only lookup reach 429")
  void shadowedPaidRouteIsRateLimited() throws Exception {
    // The request is a paid route enforced by PaygateAuthenticationFilter (full MVC resolution).
    MvcResult challenge =
        mockMvc.perform(withIp(get("/rl/reports/summary"), "198.51.100.21")).andReturn();
    assertThat(challenge.getResponse().getStatus()).isEqualTo(402);
    assertThat(challenge.getResponse().getHeaders("WWW-Authenticate"))
        .anyMatch(h -> h.startsWith("L402"));

    List<Integer> statuses = sendInvalidCredentials("/rl/reports/summary", "198.51.100.20", 6);

    assertThat(statuses).containsExactly(402, 402, 429, 429, 429, 429);
  }

  // ---------------------------------------------------------------------------------------------
  // SR-15: penalty classification
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("invalid credential (402) is penalized: burst of 3 reaches 429 on the 3rd attempt")
  void invalidCredentialIsPenalized() throws Exception {
    List<Integer> statuses = sendInvalidCredentials("/rl/reports/123", "198.51.100.30", 4);

    assertThat(statuses).containsExactly(402, 402, 429, 429);
  }

  @Test
  @DisplayName("malformed credential (400) is penalized: burst of 3 reaches 429 on the 3rd attempt")
  void malformedCredentialIsPenalized() throws Exception {
    List<Integer> statuses =
        send("/rl/reports/123", "198.51.100.31", "L402 AAAA:" + HEX.formatHex(random(32)), 4);

    assertThat(statuses).containsExactly(400, 400, 429, 429);
  }

  @Test
  @DisplayName("backend-outage 503 is not penalized: one token per attempt")
  void backendOutageIsNotPenalized() throws Exception {
    List<Integer> statuses = sendInvalidCredentials("/rl/outage", "198.51.100.32", 4);

    assertThat(statuses).containsExactly(503, 503, 503, 429);
  }

  @Test
  @DisplayName("first challenge without a credential is not penalized: one token per challenge")
  void firstChallengeIsNotPenalized() throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      statuses.add(status(withIp(get("/rl/reports/123"), "198.51.100.33")));
    }

    assertThat(statuses).containsExactly(402, 402, 402, 429);
  }

  // ---------------------------------------------------------------------------------------------
  // Controls carried over from the review PoC
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("control: paid authentication is not persisted to HttpSession (default config)")
  void paidAuthenticationIsNotPersistedToSession() throws Exception {
    MockHttpSession session = new MockHttpSession();
    int paid =
        status(
            withIp(get("/rl/reports/123"), "198.51.100.40")
                .session(session)
                .header("Authorization", validL402("/rl/reports/{id}")));
    assertThat(paid).isEqualTo(200);
    assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();

    int member = status(withIp(get("/rl/member"), "198.51.100.40").session(session));
    int paidAgain = status(withIp(get("/rl/reports/123"), "198.51.100.40").session(session));
    assertThat(member).isNotEqualTo(200);
    assertThat(paidAgain).isEqualTo(402);
  }

  @Test
  @DisplayName("control: HEAD unpaid and cross-route replay stay closed in Spring Security mode")
  void headAndCrossRouteReplayStayClosed() throws Exception {
    int headStatus = status(withIp(head("/rl/reports/123"), "198.51.100.60"));
    int crossRoute =
        status(
            withIp(get("/rl/reports/123"), "198.51.100.61")
                .header("Authorization", validL402("/rl/other/{id}")));
    int sameRoute =
        status(
            withIp(get("/rl/reports/123"), "198.51.100.62")
                .header("Authorization", validL402("/rl/reports/{id}")));

    assertThat(headStatus).isEqualTo(402);
    assertThat(crossRoute).isNotEqualTo(200);
    assertThat(sameRoute).isEqualTo(200);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private List<Integer> sendInvalidCredentials(String path, String ip, int attempts)
      throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      statuses.add(status(withIp(get(path), ip).header("Authorization", invalidL402())));
    }
    return statuses;
  }

  private List<Integer> send(String path, String ip, String authorization, int attempts)
      throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      statuses.add(status(withIp(get(path), ip).header("Authorization", authorization)));
    }
    return statuses;
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder b, String ip) {
    return b.with(
        request -> {
          request.setRemoteAddr(ip);
          return request;
        });
  }

  /** Structurally valid L402 credential signed with a random root key unknown to the store. */
  private static String invalidL402() {
    byte[] rootKey = random(32);
    byte[] preimage = random(32);
    MacaroonIdentifier identifier = new MacaroonIdentifier(1, sha256(preimage), random(32));
    Macaroon macaroon =
        MacaroonMinter.mint(
            rootKey, identifier, null, List.of(new Caveat("services", "ratelimit:0")));
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(preimage);
  }

  private String validL402(String route) {
    byte[] preimage = random(32);
    RootKeyStore.GenerationResult gen = rootKeyStore.generateRootKey();
    MacaroonIdentifier identifier = new MacaroonIdentifier(1, sha256(preimage), gen.tokenId());
    Macaroon macaroon =
        MacaroonMinter.mint(
            gen.rootKey().value(),
            identifier,
            null,
            List.of(
                new Caveat("services", "ratelimit:0"),
                new Caveat("route", route),
                new Caveat("method", "GET"),
                new Caveat("ratelimit_capabilities", "~"),
                new Caveat("ratelimit_price_sats", "10"),
                new Caveat(
                    "ratelimit_valid_until",
                    String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond()))));
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(preimage);
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Application under test
  // ---------------------------------------------------------------------------------------------

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({RateLimitController.class, RateLimitSecurityConfig.class})
  static class TestApp {

    /** Simulates a pricing backend outage: every evaluation fails, which Paygate maps to 503. */
    @Bean
    PaygatePricingStrategy unavailablePricingBackend() {
      return (request, defaultPrice) -> {
        throw new IllegalStateException("pricing backend unavailable");
      };
    }
  }

  @RestController
  @RequestMapping("/rl")
  static class RateLimitController {

    @PaymentRequired(priceSats = 10)
    @GetMapping("/reports/{id}")
    Map<String, String> report(@PathVariable String id) {
      return Map.of("report", id);
    }

    /** Unpaid, more specific, but only selected by MVC when ?public is present. */
    @GetMapping(value = "/reports/summary", params = "public")
    Map<String, String> publicSummary() {
      return Map.of("summary", "public");
    }

    @PaymentRequired(priceSats = 10, pricingStrategy = "unavailablePricingBackend")
    @GetMapping("/outage")
    Map<String, String> outage() {
      return Map.of("outage", "unreachable");
    }

    /** Not paid; guarded only by ROLE_PAYMENT authorization. */
    @GetMapping("/member")
    Map<String, String> member() {
      return Map.of("member", "yes");
    }
  }

  @Configuration
  @EnableWebSecurity
  static class RateLimitSecurityConfig {

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
          .authorizeHttpRequests(
              auth ->
                  auth.requestMatchers("/rl/member")
                      .hasRole("PAYMENT")
                      .requestMatchers("/rl/**")
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .exceptionHandling(ex -> ex.authenticationEntryPoint(paygateEntryPoint))
          .build();
    }
  }
}
