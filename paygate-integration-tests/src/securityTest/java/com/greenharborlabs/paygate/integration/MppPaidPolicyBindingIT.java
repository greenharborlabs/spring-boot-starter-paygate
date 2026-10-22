package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.PricingStability;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end regression for SR-02: a request-dependent pricing strategy can charge different
 * amounts for the same method, path, query, and body (here, by header). An MPP credential paid at
 * the lower price must not unlock the higher-priced request, in either security mode.
 */
@Tag("integration")
@DisplayName("MPP credential bound to the paid amount")
class MppPaidPolicyBindingIT {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");

  @Nested
  @SpringBootTest(
      classes = ServletApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=mpp-binding",
        "paygate.security-mode=servlet",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        "paygate.protocols.mpp.challenge-binding-secret=mpp-binding-synthetic-secret-0123456789abcdef"
      })
  @DisplayName("Servlet mode")
  class ServletMode {
    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private Served served;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      served.clear();
    }

    @Test
    @DisplayName("MPP credential paid at the basic price is refused for premium work")
    void underpaidMppCredentialIsRejected() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertUnderpaymentRejected(client, port, backend, served);
      }
    }

    @Test
    @DisplayName("L402 credential paid at the basic price is refused for premium work")
    void underpaidL402CredentialIsRejected() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var basic = send(client, port, null, Map.of());
        assertThat(basic.statusCode()).isEqualTo(402);
        String l402 =
            basic.headers().allValues("WWW-Authenticate").stream()
                .filter(h -> h.startsWith("L402"))
                .findFirst()
                .orElseThrow();
        var matcher = MACAROON_PATTERN.matcher(l402);
        assertThat(matcher.find()).isTrue();
        String auth = "L402 " + matcher.group(1) + ":" + backend.preimageHex();

        var premium = send(client, port, auth, Map.of("X-Tier", "premium"));

        assertThat(premium.statusCode()).isNotEqualTo(200);
        assertThat(served.list()).isEmpty();
      }
    }

    @Test
    @DisplayName("MPP credential paid at the premium price unlocks premium work")
    void premiumMppCredentialIsAccepted() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertPremiumAccepted(client, port, backend, served);
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = SecurityApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=false",
        "paygate.root-key-store=memory",
        "paygate.service-name=mpp-binding",
        "paygate.security-mode=spring-security",
        "paygate.health-cache.enabled=false",
        "paygate.rate-limit.requests-per-second=1000",
        "paygate.rate-limit.burst-size=1000",
        "paygate.protocols.mpp.challenge-binding-secret=mpp-binding-synthetic-secret-0123456789abcdef"
      })
  @DisplayName("Spring Security mode")
  class SpringSecurityMode {
    @LocalServerPort private int port;
    @Autowired private DefenseInDepthSecurityIT.BackendControl backend;
    @Autowired private Served served;

    @BeforeEach
    void reset() {
      backend.setHealthy(true);
      backend.clearPreimage();
      served.clear();
    }

    @Test
    @DisplayName("MPP credential paid at the basic price is refused for premium work")
    void underpaidMppCredentialIsRejected() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertUnderpaymentRejected(client, port, backend, served);
      }
    }

    @Test
    @DisplayName("MPP credential paid at the premium price unlocks premium work")
    void premiumMppCredentialIsAccepted() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertPremiumAccepted(client, port, backend, served);
      }
    }
  }

  static void assertUnderpaymentRejected(
      HttpClient client, int port, DefenseInDepthSecurityIT.BackendControl backend, Served served)
      throws Exception {
    var premiumChallenge = send(client, port, null, Map.of("X-Tier", "premium"));
    assertThat(premiumChallenge.statusCode()).isEqualTo(402);
    assertThat(amount(mppChallenge(premiumChallenge))).isEqualTo("1000");
    backend.clearPreimage();

    var basicChallenge = send(client, port, null, Map.of());
    assertThat(basicChallenge.statusCode()).isEqualTo(402);
    assertThat(amount(mppChallenge(basicChallenge))).isEqualTo("1");

    String auth = mppAuthorization(basicChallenge, backend);
    var premium = send(client, port, auth, Map.of("X-Tier", "premium"));

    assertThat(premium.statusCode()).isEqualTo(402);
    assertThat(served.list()).isEmpty();

    var basic = send(client, port, auth, Map.of());
    assertThat(basic.statusCode()).isEqualTo(200);
    assertThat(served.list()).containsExactly("tiered:basic");
  }

  static void assertPremiumAccepted(
      HttpClient client, int port, DefenseInDepthSecurityIT.BackendControl backend, Served served)
      throws Exception {
    var premiumChallenge = send(client, port, null, Map.of("X-Tier", "premium"));
    assertThat(premiumChallenge.statusCode()).isEqualTo(402);

    var premium =
        send(
            client,
            port,
            mppAuthorization(premiumChallenge, backend),
            Map.of("X-Tier", "premium"));

    assertThat(premium.statusCode()).isEqualTo(200);
    assertThat(served.list()).containsExactly("tiered:premium");
  }

  static HttpResponse<String> send(
      HttpClient client, int port, String authorization, Map<String, String> headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/binding/tiered"))
            .GET();
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    headers.forEach(builder::header);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  static Map<String, String> mppChallenge(HttpResponse<String> response) throws Exception {
    Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
    var protocols = (Map<String, Object>) body.get("protocols");
    var payment = (Map<String, Object>) protocols.get("Payment");
    var out = new TreeMap<String, String>();
    payment.forEach((k, v) -> out.put(k, String.valueOf(v)));
    return out;
  }

  @SuppressWarnings("unchecked")
  static String amount(Map<String, String> challenge) throws Exception {
    byte[] json = Base64.getUrlDecoder().decode(challenge.get("request"));
    Map<String, Object> request = MAPPER.readValue(json, Map.class);
    return String.valueOf(request.get("amount"));
  }

  static String mppAuthorization(
      HttpResponse<String> challengeResponse, DefenseInDepthSecurityIT.BackendControl backend)
      throws Exception {
    var credential = new TreeMap<String, Object>();
    credential.put("challenge", new TreeMap<String, Object>(mppChallenge(challengeResponse)));
    credential.put("payload", Map.of("preimage", backend.preimageHex()));
    byte[] json = MAPPER.writeValueAsBytes(credential);
    return "Payment " + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
  }

  static final class Served {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    void add(String call) {
      calls.add(call);
    }

    List<String> list() {
      return List.copyOf(calls);
    }

    void clear() {
      calls.clear();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SharedConfiguration {
    @Bean
    Served served() {
      return new Served();
    }

    @Bean
    DefenseInDepthSecurityIT.BackendControl backendControl() {
      return new DefenseInDepthSecurityIT.BackendControl();
    }

    @Bean
    LightningBackend lightningBackend(DefenseInDepthSecurityIT.BackendControl control) {
      return new DefenseInDepthSecurityIT.ControlledLightningBackend(control);
    }

    @Bean("tierPricer")
    PaygatePricingStrategy tierPricer() {
      return (request, defaultPrice) ->
          "premium".equals(request.getHeader("X-Tier")) ? 1000L : defaultPrice;
    }

    @Bean
    TieredController tieredController(Served served) {
      return new TieredController(served);
    }
  }

  @RestController
  static class TieredController {
    private final Served served;

    TieredController(Served served) {
      this.served = served;
    }

    @PaymentRequired(
        priceSats = 1,
        pricingStrategy = "tierPricer",
        pricingStability = PricingStability.REQUEST_DEPENDENT)
    @GetMapping("/binding/tiered")
    Map<String, String> tiered(
        @RequestHeader(name = "X-Tier", required = false, defaultValue = "basic") String tier) {
      served.add("tiered:" + tier);
      return Map.of("tier", tier);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedConfiguration.class, ServletPermitAllSecurity.class})
  static class ServletApplication {}

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class ServletPermitAllSecurity {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .csrf(AbstractHttpConfigurer::disable)
          .build();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedConfiguration.class, PaygateChainSecurity.class})
  static class SecurityApplication {}

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class PaygateChainSecurity {
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config)
        throws Exception {
      return config.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http, org.springframework.context.ApplicationContext ctx) throws Exception {
      // paygate-spring-security is only on the runtime classpath of this source set.
      String pkg = "com.greenharborlabs.paygate.spring.security.";
      var paygateFilter =
          (jakarta.servlet.Filter) ctx.getBean(Class.forName(pkg + "PaygateAuthenticationFilter"));
      var paygateProvider =
          (org.springframework.security.authentication.AuthenticationProvider)
              ctx.getBean(Class.forName(pkg + "PaygateAuthenticationProvider"));
      var paygateEntryPoint =
          (org.springframework.security.web.AuthenticationEntryPoint)
              ctx.getBean(Class.forName(pkg + "PaygateAuthenticationEntryPoint"));
      var rateLimitFilter =
          (jakarta.servlet.Filter)
              ctx.getBean(Class.forName(pkg + "PaygateAuthFailureRateLimitFilter"));
      http.addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class);
      return http.csrf(AbstractHttpConfigurer::disable)
          .authenticationProvider(paygateProvider)
          .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
          .authorizeHttpRequests(
              a -> a.requestMatchers("/binding/**").hasRole("PAYMENT").anyRequest().permitAll())
          .exceptionHandling(e -> e.authenticationEntryPoint(paygateEntryPoint))
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .build();
    }
  }
}
