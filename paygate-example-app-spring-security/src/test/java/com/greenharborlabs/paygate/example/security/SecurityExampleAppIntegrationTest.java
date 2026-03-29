package com.greenharborlabs.paygate.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the Spring Security example application.
 *
 * <p>Starts the full application context with Spring Security integration and exercises the
 * dual-protocol (L402 + MPP) flow: unauthenticated challenge, credential presentation, access
 * grant, protocol-info endpoint, and L402-only authorization. Uses {@code
 * paygate.root-key-store=memory} so the test can mint valid macaroons against the same in-memory
 * store used by the security filter.
 */
@SpringBootTest(classes = SecurityExampleApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.security-mode=spring-security",
      "paygate.protocols.mpp.challenge-binding-secret=test-only-mpp-secret-minimum-32-bytes-long"
    })
@DisplayName("Spring Security example application integration")
class SecurityExampleAppIntegrationTest {

  private static final HexFormat HEX = HexFormat.of();

  @Autowired private MockMvc mockMvc;

  @Autowired private RootKeyStore rootKeyStore;

  // -------------------------------------------------------------------
  // 1. Health endpoint (unprotected)
  // -------------------------------------------------------------------

  @Nested
  @DisplayName("health endpoint")
  class HealthEndpoint {

    @Test
    @DisplayName("returns 200 without authentication")
    void returns200WithoutAuth() throws Exception {
      mockMvc
          .perform(get("/api/v1/health"))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.status", is("ok")));
    }
  }

  // -------------------------------------------------------------------
  // 2. Protected data endpoint (dual-protocol 402 challenge)
  // -------------------------------------------------------------------

  @Nested
  @DisplayName("data endpoint without authentication")
  class DataEndpointNoAuth {

    @Test
    @DisplayName("returns 402 with L402 challenge in WWW-Authenticate header")
    void returns402WithL402Challenge() throws Exception {
      var result =
          mockMvc.perform(get("/api/v1/data")).andExpect(status().isPaymentRequired()).andReturn();

      List<String> wwwAuthHeaders = result.getResponse().getHeaders("WWW-Authenticate");
      assertThat(wwwAuthHeaders)
          .anyMatch(h -> h.startsWith("L402") && h.contains("macaroon=") && h.contains("invoice="));
    }

    @Test
    @DisplayName("returns 402 with MPP challenge in WWW-Authenticate header")
    void returns402WithMppChallenge() throws Exception {
      var result =
          mockMvc.perform(get("/api/v1/data")).andExpect(status().isPaymentRequired()).andReturn();

      List<String> wwwAuthHeaders = result.getResponse().getHeaders("WWW-Authenticate");
      assertThat(wwwAuthHeaders).anyMatch(h -> h.startsWith("Payment"));
    }

    @Test
    @DisplayName("returns JSON body with 402 code and price_sats=10")
    void returns402JsonBody() throws Exception {
      mockMvc
          .perform(get("/api/v1/data"))
          .andExpect(status().isPaymentRequired())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.code", is(402)))
          .andExpect(jsonPath("$.price_sats", is(10)));
    }

    @Test
    @DisplayName("returns 402 with protocols object containing at least one protocol")
    void returns402WithProtocolsArray() throws Exception {
      mockMvc
          .perform(get("/api/v1/data"))
          .andExpect(status().isPaymentRequired())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.protocols").isMap())
          .andExpect(jsonPath("$.protocols.Payment").isMap())
          .andExpect(jsonPath("$.protocols.Payment.method", is("lightning")));
    }
  }

  // -------------------------------------------------------------------
  // 3. Full L402 flow: challenge -> credential -> 200
  // -------------------------------------------------------------------

  @Nested
  @DisplayName("full L402 credential flow on data endpoint")
  class FullL402Flow {

    @Test
    @DisplayName("valid L402 credential grants access with 200")
    void validCredentialReturns200() throws Exception {
      String authHeader = mintL402AuthHeader();

      mockMvc
          .perform(get("/api/v1/data").header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", is("premium content")));
    }
  }

  // -------------------------------------------------------------------
  // 4. Protocol info endpoint
  // -------------------------------------------------------------------

  @Nested
  @DisplayName("protocol-info endpoint")
  class ProtocolInfoEndpoint {

    @Test
    @DisplayName("L402 credential returns protocol info with protocol and tokenId")
    void l402CredentialReturnsProtocolInfo() throws Exception {
      String authHeader = mintL402AuthHeader();

      mockMvc
          .perform(get("/api/v1/protocol-info").header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.protocol", is("L402")))
          .andExpect(jsonPath("$.tokenId", notNullValue()));
    }
  }

  // -------------------------------------------------------------------
  // 5. L402-only endpoint
  // -------------------------------------------------------------------

  @Nested
  @DisplayName("L402-only endpoint")
  class L402OnlyEndpoint {

    @Test
    @DisplayName("L402 credential grants access to L402-only endpoint")
    void l402CredentialGrantsAccess() throws Exception {
      String authHeader = mintL402AuthHeader();

      mockMvc
          .perform(get("/api/v1/l402-only").header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", is("L402-exclusive content")));
    }
  }

  // -------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------

  private String mintL402AuthHeader() {
    byte[] preimage = new byte[32];
    new SecureRandom().nextBytes(preimage);
    byte[] paymentHash = sha256(preimage);

    RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
    byte[] rootKey = genResult.rootKey().value();
    byte[] tokenId = genResult.tokenId();

    MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
    Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, List.of());
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

    String preimageHex = HEX.formatHex(preimage);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
