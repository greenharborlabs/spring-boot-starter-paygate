package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
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
 * T086: Verifies that dynamic pricing via {@link PaygatePricingStrategy} overrides the static
 * {@code priceSats} value from the {@link PaymentRequired} annotation.
 *
 * <p>Scenario: {@code @PaymentRequired(priceSats = 50, pricingStrategy = "myPricer")} with a bean
 * "myPricer" returning 150 must produce an invoice for 150 sats, not the annotation's default of
 * 50.
 */
@SpringBootTest(classes = DynamicPricingTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402 Dynamic Pricing (T086)")
class DynamicPricingTest {

  private static final byte[] ROOT_KEY = new byte[32];
  private static final long STATIC_PRICE = 50;
  private static final long DYNAMIC_PRICE = 150;
  private static final String DYNAMIC_PATH = "/api/dynamic-price";

  static {
    new SecureRandom().nextBytes(ROOT_KEY);
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private LightningBackend lightningBackend;

  @BeforeEach
  void setUp() {
    var stub = (CapturingStubLightningBackend) lightningBackend;
    stub.setHealthy(true);
    stub.setNextInvoice(createStubInvoice());
    stub.resetCapturedAmount();
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  @Test
  @DisplayName(
      "invoice uses dynamic price (150) from pricing strategy, not static annotation value (50)")
  void invoiceUsesDynamicPriceFromStrategy() throws Exception {
    mockMvc
        .perform(get(DYNAMIC_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.price_sats", is(150)))
        .andExpect(jsonPath("$.invoice", notNullValue()));

    // Verify the Lightning backend received the dynamic price, not the static one
    var stub = (CapturingStubLightningBackend) lightningBackend;
    assertThat(stub.getLastRequestedAmountSats())
        .as("createInvoice should be called with the dynamic price from the strategy")
        .isEqualTo(DYNAMIC_PRICE);
  }

  @Test
  @DisplayName("402 response contains price_sats=150 reflecting strategy output")
  void responseBodyReflectsDynamicPrice() throws Exception {
    mockMvc
        .perform(get(DYNAMIC_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code", is(402)))
        .andExpect(jsonPath("$.message", is("Payment required")))
        .andExpect(jsonPath("$.price_sats", is(150)));
  }

  // -----------------------------------------------------------------------
  // Test application and configuration
  // -----------------------------------------------------------------------

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    LightningBackend lightningBackend() {
      return new CapturingStubLightningBackend();
    }

    @Bean
    RootKeyStore rootKeyStore() {
      return new InMemoryTestRootKeyStore(ROOT_KEY);
    }

    @Bean
    CredentialStore credentialStore() {
      return new InMemoryTestCredentialStore();
    }

    @Bean
    List<CaveatVerifier> caveatVerifiers() {
      return List.of();
    }

    @Bean("myPricer")
    PaygatePricingStrategy myPricer() {
      return (request, defaultPrice) -> DYNAMIC_PRICE;
    }

    @Bean
    PaygateEndpointRegistry paygateEndpointRegistry() {
      var registry = new PaygateEndpointRegistry();
      registry.register(
          new PaygateEndpointConfig(
              "GET", DYNAMIC_PATH, STATIC_PRICE, 600, "Dynamic pricing endpoint", "myPricer", ""));
      return registry;
    }

    @Bean
    PaygateSecurityFilter paygateSecurityFilter(
        PaygateEndpointRegistry endpointRegistry,
        LightningBackend lightningBackendBean,
        RootKeyStore rootKeyStore,
        CredentialStore credentialStore,
        List<CaveatVerifier> caveatVerifiers,
        ApplicationContext applicationContext) {
      var validator =
          new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
      var l402Protocol = new L402Protocol(validator, "test-service");
      var challengeService =
          new PaygateChallengeService(
              rootKeyStore, lightningBackendBean, null, applicationContext, null, null, null, null);
      return new PaygateSecurityFilter(
          endpointRegistry,
          List.of(l402Protocol),
          challengeService,
          "test-service",
          null,
          null,
          null,
          null);
    }

    @Bean
    DynamicPriceController dynamicPriceController() {
      return new DynamicPriceController();
    }
  }

  @RestController
  static class DynamicPriceController {

    @PaymentRequired(priceSats = 50, pricingStrategy = "myPricer")
    @GetMapping(DYNAMIC_PATH)
    String dynamicPriceEndpoint() {
      return "dynamic-content";
    }
  }

  // -----------------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------------

  private static Invoice createStubInvoice() {
    byte[] paymentHash = new byte[32];
    new SecureRandom().nextBytes(paymentHash);
    Instant now = Instant.now();
    return new Invoice(
        paymentHash,
        "lnbc150n1p0testinvoice",
        DYNAMIC_PRICE,
        "Dynamic pricing test invoice",
        InvoiceStatus.PENDING,
        null,
        now,
        now.plus(1, ChronoUnit.HOURS));
  }

  // -----------------------------------------------------------------------
  // Stub / in-memory implementations for test isolation
  // -----------------------------------------------------------------------

  /**
   * Extended stub that captures the amountSats parameter passed to createInvoice, allowing tests to
   * verify the correct price was requested.
   */
  static class CapturingStubLightningBackend implements LightningBackend {

    private volatile boolean healthy = true;
    private volatile Invoice nextInvoice;
    private volatile long lastRequestedAmountSats = -1;

    void setHealthy(boolean healthy) {
      this.healthy = healthy;
    }

    void setNextInvoice(Invoice invoice) {
      this.nextInvoice = invoice;
    }

    void resetCapturedAmount() {
      this.lastRequestedAmountSats = -1;
    }

    long getLastRequestedAmountSats() {
      return lastRequestedAmountSats;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      this.lastRequestedAmountSats = amountSats;
      if (!healthy) {
        throw new RuntimeException("Lightning backend is not available");
      }
      if (nextInvoice != null) {
        return nextInvoice;
      }
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
      if (!healthy) {
        throw new RuntimeException("Lightning backend is not available");
      }
      return null;
    }

    @Override
    public boolean isHealthy() {
      return healthy;
    }
  }

  static class InMemoryTestRootKeyStore implements RootKeyStore {

    private final byte[] rootKey;

    InMemoryTestRootKeyStore(byte[] rootKey) {
      this.rootKey = rootKey.clone();
    }

    @Override
    public GenerationResult generateRootKey() {
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);
      return new GenerationResult(
          new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(rootKey.clone()), tokenId);
    }

    @Override
    public com.greenharborlabs.paygate.api.crypto.SensitiveBytes getRootKey(byte[] keyId) {
      return new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(rootKey.clone());
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
      // no-op for tests
    }
  }

  static class InMemoryTestCredentialStore implements CredentialStore {

    private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
      store.put(tokenId, credential);
    }

    @Override
    public L402Credential get(String tokenId) {
      return store.get(tokenId);
    }

    @Override
    public void revoke(String tokenId) {
      store.remove(tokenId);
    }

    @Override
    public long activeCount() {
      return store.size();
    }
  }
}
