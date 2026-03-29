package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.protocol.mpp.MppProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backward compatibility test (T040): verifies that when only existing {@code paygate.*} properties
 * are set — with NO MPP configuration — the system operates in L402-only mode, producing the same
 * response format as before the dual-protocol refactoring.
 *
 * <p>Uses real auto-configuration ({@link PaygateAutoConfiguration} and {@link
 * TestModeAutoConfiguration}) to prove that the default config path still works end-to-end.
 */
@SpringBootTest(
    classes = BackwardCompatibilityTest.TestApp.class,
    properties = {
      "paygate.enabled=true",
      "paygate.backend=lnbits",
      "paygate.root-key-store=memory",
      "paygate.test-mode=true",
      "paygate.service-name=backward-compat-test",
      "spring.profiles.active=test",
      "management.endpoints.web.exposure.exclude=*"
    })
@AutoConfigureMockMvc
@DisplayName("Backward compatibility — L402-only mode (no MPP config)")
class BackwardCompatibilityTest {

  private static final String PROTECTED_PATH = "/api/backward-compat/protected";
  private static final String PUBLIC_PATH = "/api/backward-compat/public";

  @Autowired private MockMvc mockMvc;

  @Autowired private ApplicationContext applicationContext;

  // -----------------------------------------------------------------------
  // Test application — uses @EnableAutoConfiguration, no manual bean wiring
  // -----------------------------------------------------------------------

  @Configuration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration"
      })
  static class TestApp {

    @Bean
    BackwardCompatController backwardCompatController() {
      return new BackwardCompatController();
    }
  }

  @RestController
  static class BackwardCompatController {

    @PaymentRequired(priceSats = 42, description = "Backward compat protected endpoint")
    @GetMapping(PROTECTED_PATH)
    String protectedEndpoint() {
      return "protected-content";
    }

    @GetMapping(PUBLIC_PATH)
    String publicEndpoint() {
      return "public-content";
    }
  }

  // -----------------------------------------------------------------------
  // Test: MppProtocol bean is NOT present in the context
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("MppProtocol bean is not registered when no MPP properties are set")
  void mppProtocolBeanNotPresent() {
    assertThat(applicationContext.getBeansOfType(MppProtocol.class)).isEmpty();
  }

  // -----------------------------------------------------------------------
  // Test: 402 response has ONLY L402 WWW-Authenticate header
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("402 response contains exactly one WWW-Authenticate header starting with 'L402 '")
  void onlyL402WwwAuthenticateHeader() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(header().exists("WWW-Authenticate"))
        .andExpect(header().string("WWW-Authenticate", startsWith("L402 ")));
  }

  @Test
  @DisplayName("402 response has NO WWW-Authenticate header starting with 'Payment '")
  void noPaymentWwwAuthenticateHeader() throws Exception {
    var result =
        mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired()).andReturn();

    var wwwAuthHeaders = result.getResponse().getHeaders("WWW-Authenticate");
    assertThat(wwwAuthHeaders)
        .as("Should have exactly one WWW-Authenticate header (L402 only)")
        .hasSize(1);
    assertThat(wwwAuthHeaders.getFirst())
        .as("The single header should be L402, not Payment")
        .startsWith("L402 ");
  }

  // -----------------------------------------------------------------------
  // Test: 402 response body matches pre-refactoring format
  // -----------------------------------------------------------------------

  @Test
  @DisplayName(
      "402 response body has code, message, price_sats, description, invoice, and protocols fields")
  void responseBodyFormat() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code", is(402)))
        .andExpect(jsonPath("$.message", is("Payment required")))
        .andExpect(jsonPath("$.price_sats", is(42)))
        .andExpect(jsonPath("$.description", is("Backward compat protected endpoint")))
        .andExpect(jsonPath("$.invoice", notNullValue()));
  }

  @Test
  @DisplayName(
      "402 response body has protocols object (L402Protocol provides null bodyData, so protocols is empty)")
  void responseBodyHasProtocolsObject() throws Exception {
    // L402Protocol.formatChallenge() returns null bodyData, so the protocols map
    // is present but empty. The L402 challenge is conveyed entirely via the
    // WWW-Authenticate header, not the body.
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.protocols").exists());
  }

  @Test
  @DisplayName("402 response body protocols object does NOT contain a Payment key")
  void responseBodyNoPaymentProtocol() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.protocols.Payment").doesNotExist());
  }

  // -----------------------------------------------------------------------
  // Test: Cache-Control: no-store on 402
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("402 response includes Cache-Control: no-store header")
  void cacheControlNoStore() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  // -----------------------------------------------------------------------
  // Test: Unprotected endpoint returns 200
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("GET to unprotected endpoint returns 200 OK")
  void unprotectedEndpointReturns200() throws Exception {
    mockMvc
        .perform(get(PUBLIC_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string("public-content"));
  }

  // -----------------------------------------------------------------------
  // Test: L402 auth flow works end-to-end in test mode
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("L402 end-to-end: 402 response includes test_preimage for completing auth")
  void l402EndToEndTestPreimagePresent() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.test_preimage", notNullValue()));
  }

  @Test
  @DisplayName("L402 end-to-end: valid credential grants access to protected endpoint")
  void l402EndToEndAuthFlow() throws Exception {
    // Step 1: Get the 402 challenge
    var challengeResult =
        mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired()).andReturn();

    // Extract the WWW-Authenticate header to get macaroon and invoice
    String wwwAuth = challengeResult.getResponse().getHeader("WWW-Authenticate");
    assertThat(wwwAuth).isNotNull().startsWith("L402 ");

    // Parse macaroon from the header: L402 token="...", macaroon="...", invoice="..."
    String macaroon = extractParam(wwwAuth, "macaroon");
    assertThat(macaroon).as("macaroon parameter in WWW-Authenticate").isNotNull();

    // Extract test_preimage from response body
    String body = challengeResult.getResponse().getContentAsString();
    String testPreimage = extractJsonString(body, "test_preimage");
    assertThat(testPreimage).as("test_preimage in response body").isNotNull();

    // Step 2: Present L402 credential — macaroon:preimage
    String authHeader = "L402 " + macaroon + ":" + testPreimage;

    mockMvc
        .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
        .andExpect(status().isOk())
        .andExpect(content().string("protected-content"));
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private static String extractParam(String header, String paramName) {
    String prefix = paramName + "=\"";
    int start = header.indexOf(prefix);
    if (start < 0) {
      return null;
    }
    start += prefix.length();
    int end = header.indexOf('"', start);
    if (end < 0) {
      return null;
    }
    return header.substring(start, end);
  }

  private static String extractJsonString(String json, String key) {
    String prefix = "\"" + key + "\": \"";
    int start = json.indexOf(prefix);
    if (start < 0) {
      // Try without space after colon
      prefix = "\"" + key + "\":\"";
      start = json.indexOf(prefix);
    }
    if (start < 0) {
      return null;
    }
    start += prefix.length();
    int end = json.indexOf('"', start);
    if (end < 0) {
      return null;
    }
    return json.substring(start, end);
  }
}
