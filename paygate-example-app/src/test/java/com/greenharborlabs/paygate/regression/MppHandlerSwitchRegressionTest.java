package com.greenharborlabs.paygate.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * Regression for SR-02 in servlet mode: Spring MVC can select a different paid handler for the same
 * method, path, query, and body by using a {@code headers} or {@code produces} condition. An MPP
 * credential bought for one handler must not unlock another.
 */
@SpringBootTest(
    classes = MppHandlerSwitchRegressionTest.App.class,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.security-mode=servlet",
      "paygate.service-name=mpp-binding",
      "paygate.protocols.mpp.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MPP credential bound to the handler that issued it (servlet mode)")
class MppHandlerSwitchRegressionTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern MACAROON = Pattern.compile("macaroon=\"([^\"]+)\"");

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
  @DisplayName("headers condition: 1-sat credential does not unlock the 1000-sat premium handler")
  void headersConditionSwitchIsRejected() throws Exception {
    assertSwitchRejected("/v/data/special", "X-Tier", "basic", "premium", 1L, 1000L);
  }

  @Test
  @DisplayName("produces condition: 1-sat credential does not unlock the 1000-sat CSV export")
  void producesConditionSwitchIsRejected() throws Exception {
    assertSwitchRejected("/v/report/export", "Accept", "application/json", "text/csv", 1L, 1000L);
  }

  @Test
  @DisplayName("equal-price handler on another route is not unlocked")
  void equalPriceDifferentRouteIsRejected() throws Exception {
    assertSwitchRejected("/v/item/special", "X-Variant", "a", "b", 5L, 5L);
  }

  @Test
  @DisplayName("credential bought for the premium handler unlocks it")
  void premiumCredentialUnlocksPremiumHandler() throws Exception {
    MvcResult challenge =
        mockMvc.perform(get("/v/data/special").header("X-Tier", "premium")).andReturn();
    assertThat(challenge.getResponse().getStatus()).isEqualTo(402);

    MvcResult paid =
        mockMvc
            .perform(
                get("/v/data/special")
                    .header("X-Tier", "premium")
                    .header("Authorization", mppAuthorization(challenge)))
            .andReturn();

    assertThat(paid.getResponse().getStatus()).isEqualTo(200);
    assertThat(invocations.list()).containsExactly("premium");
  }

  private void assertSwitchRejected(
      String path,
      String header,
      String issuedValue,
      String switchedValue,
      long issuedPrice,
      long switchedPrice)
      throws Exception {
    MvcResult issued = mockMvc.perform(get(path).header(header, issuedValue)).andReturn();
    MvcResult switched = mockMvc.perform(get(path).header(header, switchedValue)).andReturn();
    assertThat(priceSats(issued)).isEqualTo(issuedPrice);
    assertThat(priceSats(switched)).isEqualTo(switchedPrice);

    String mppAuth = mppAuthorization(issued);
    String l402Auth = l402Authorization(issued);

    MvcResult mppOnIssued =
        mockMvc
            .perform(get(path).header(header, issuedValue).header("Authorization", mppAuth))
            .andReturn();
    assertThat(mppOnIssued.getResponse().getStatus()).isEqualTo(200);
    invocations.clear();

    MvcResult mppOnSwitched =
        mockMvc
            .perform(get(path).header(header, switchedValue).header("Authorization", mppAuth))
            .andReturn();
    MvcResult l402OnSwitched =
        mockMvc
            .perform(get(path).header(header, switchedValue).header("Authorization", l402Auth))
            .andReturn();

    assertThat(mppOnSwitched.getResponse().getStatus()).isEqualTo(402);
    assertThat(l402OnSwitched.getResponse().getStatus()).isNotEqualTo(200);
    assertThat(invocations.list()).isEmpty();
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

  @SuppressWarnings("unchecked")
  private static String l402Authorization(MvcResult challenge) throws Exception {
    Map<String, Object> body =
        MAPPER.readValue(challenge.getResponse().getContentAsString(), Map.class);
    for (String header : challenge.getResponse().getHeaders("WWW-Authenticate")) {
      Matcher matcher = MACAROON.matcher(header);
      if (header.startsWith("L402") && matcher.find()) {
        return "L402 " + matcher.group(1) + ":" + body.get("test_preimage");
      }
    }
    throw new AssertionError("L402 challenge missing");
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
  @Import(Controller.class)
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
    @GetMapping("/v/data/{id}")
    Map<String, String> cheap(@PathVariable String id) {
      invocations.add("basic");
      return Map.of("tier", "basic");
    }

    @PaymentRequired(priceSats = 1000, capability = "premium")
    @GetMapping(value = "/v/data/special", headers = "X-Tier=premium")
    Map<String, String> premium() {
      invocations.add("premium");
      return Map.of("tier", "PREMIUM");
    }

    @PaymentRequired(priceSats = 1)
    @GetMapping("/v/report/{id}")
    Map<String, String> report(@PathVariable String id) {
      invocations.add("report");
      return Map.of("report", id);
    }

    @PaymentRequired(priceSats = 1000)
    @GetMapping(value = "/v/report/export", produces = "text/csv")
    String export() {
      invocations.add("export");
      return "CSV-EXPORT\n";
    }

    @PaymentRequired(priceSats = 5)
    @GetMapping("/v/item/{id}")
    Map<String, String> item(@PathVariable String id) {
      invocations.add("item");
      return Map.of("item", id);
    }

    @PaymentRequired(priceSats = 5)
    @GetMapping(value = "/v/item/special", headers = "X-Variant=b")
    Map<String, String> itemVariant() {
      invocations.add("item-variant");
      return Map.of("item", "variant-b");
    }
  }
}
