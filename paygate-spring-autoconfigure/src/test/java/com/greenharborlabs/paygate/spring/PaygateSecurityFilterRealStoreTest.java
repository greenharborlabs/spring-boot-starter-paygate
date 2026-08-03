package com.greenharborlabs.paygate.spring;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integration test using the REAL {@link InMemoryRootKeyStore} (from paygate-core).
 *
 * <p>Proves end-to-end: request -> 402 challenge -> mint credential using the real store's
 * generated root key and tokenId -> present L402 credential -> 200.
 *
 * <p>This test validates that the {@link RootKeyStore.GenerationResult} refactoring works correctly
 * with the production implementation, not just test stubs.
 */
@SpringBootTest(classes = PaygateSecurityFilterRealStoreTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("PaygateSecurityFilter with real InMemoryRootKeyStore")
class PaygateSecurityFilterRealStoreTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final long PRICE_SATS = 10;
  private static final String PROTECTED_PATH = "/api/real-store-test";
  private static final String CHEAP_PATH = "/api/replay/cheap";
  private static final String EXPENSIVE_PATH = "/api/replay/expensive";
  private static final String FAMILY_ROUTE = "/api/replay/items/{id}";
  private static final String SERVICE_NAME = "test-service";
  private static final long TIMEOUT_SECONDS = 600;

  @Autowired private MockMvc mockMvc;

  @Autowired private LightningBackend lightningBackend;

  @Autowired private RootKeyStore rootKeyStore;

  @BeforeEach
  void setUp() {
    var stub = (StubLightningBackend) lightningBackend;
    stub.setHealthy(true);
    stub.setNextInvoice(createStubInvoice());
    RealStoreTestController.resetInvocationCounts();
  }

  @Test
  @DisplayName("unauthenticated request returns 402 challenge with macaroon and invoice")
  void unauthenticatedRequestReturns402() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isPaymentRequired())
        .andExpect(header().exists("WWW-Authenticate"))
        .andExpect(header().string("WWW-Authenticate", startsWith("L402 ")))
        .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
        .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
  }

  @Test
  @DisplayName("full 402 -> credential -> 200 flow using real InMemoryRootKeyStore")
  void fullFlowWithRealStore() throws Exception {
    String authHeader = mintCredential(PROTECTED_PATH, "GET", true);

    // Present credential and expect 200
    mockMvc
        .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("X-L402-Token-Id"))
        .andExpect(header().exists("X-L402-Credential-Expires"))
        .andExpect(content().string("real-store-content"));
  }

  @Test
  @DisplayName("credential paid for a cheap route cannot replay on a more expensive route")
  void rejectsCredentialOnDifferentRegisteredRouteBeforeHandler() throws Exception {
    String credential = mintCredential(CHEAP_PATH, "GET", true);

    mockMvc.perform(get(CHEAP_PATH).header("Authorization", credential)).andExpect(status().isOk());
    mockMvc
        .perform(get(EXPENSIVE_PATH).header("Authorization", credential))
        .andExpect(status().isPaymentRequired());

    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.cheapInvocations()).isOne();
    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.expensiveInvocations())
        .isZero();
  }

  @Test
  @DisplayName("credential cannot replay under a different request method")
  void rejectsCredentialOnDifferentRequestMethodBeforeHandler() throws Exception {
    String credential = mintCredential(PROTECTED_PATH, "GET", true);

    mockMvc
        .perform(post(PROTECTED_PATH).header("Authorization", credential))
        .andExpect(status().isPaymentRequired());

    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.postInvocations()).isZero();
  }

  @Test
  @DisplayName("credential applies to every concrete path in its registered route family")
  void acceptsCredentialAcrossConcretePathsOfSameRoutePattern() throws Exception {
    String credential = mintCredential(FAMILY_ROUTE, "GET", true);

    mockMvc
        .perform(get("/api/replay/items/one").header("Authorization", credential))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/replay/items/two").header("Authorization", credential))
        .andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.familyInvocations())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("legacy credential without route and method boundaries fails closed")
  void rejectsLegacyCredentialMissingRequestBoundaryBeforeHandler() throws Exception {
    String credential = mintCredential(PROTECTED_PATH, "GET", false);

    mockMvc
        .perform(get(PROTECTED_PATH).header("Authorization", credential))
        .andExpect(status().isPaymentRequired());

    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.getInvocations()).isZero();
  }

  @Test
  @DisplayName("correctly bound unexpired credential works on fresh and cached validation")
  void acceptsCorrectlyBoundUnexpiredCredential() throws Exception {
    String credential = mintCredential(PROTECTED_PATH, "GET", true);

    mockMvc
        .perform(get(PROTECTED_PATH).header("Authorization", credential))
        .andExpect(status().isOk());
    mockMvc
        .perform(get(PROTECTED_PATH).header("Authorization", credential))
        .andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(RealStoreTestController.getInvocations())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("tokenId from GenerationResult matches what getRootKey accepts")
  void generationResultTokenIdIsConsistentWithGetRootKey() throws Exception {
    // Generate via the real store
    RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
    byte[] rootKey = genResult.rootKey().value();
    byte[] tokenId = genResult.tokenId();

    // Verify the store can look up the key by the returned tokenId
    byte[] retrieved = rootKeyStore.getRootKey(tokenId).value();
    org.assertj.core.api.Assertions.assertThat(retrieved).isEqualTo(rootKey);
  }

  // -----------------------------------------------------------------------
  // Test application and configuration
  // -----------------------------------------------------------------------

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    LightningBackend lightningBackend() {
      return new StubLightningBackend();
    }

    @Bean
    RootKeyStore rootKeyStore() {
      return new InMemoryRootKeyStore();
    }

    @Bean
    CredentialStore credentialStore() {
      return new TestCredentialStore();
    }

    @Bean
    List<CaveatVerifier> caveatVerifiers() {
      return List.of(
          new ServicesCaveatVerifier(50),
          new RouteCaveatVerifier(50),
          new MethodCaveatVerifier(50),
          new ValidUntilCaveatVerifier(SERVICE_NAME));
    }

    @Bean
    PaygateEndpointRegistry paygateEndpointRegistry() {
      var registry = new PaygateEndpointRegistry();
      registry.register(
          new PaygateEndpointConfig(
              "GET", PROTECTED_PATH, PRICE_SATS, 600, "Real store test endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig(
              "POST", PROTECTED_PATH, PRICE_SATS, 600, "POST endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig("GET", CHEAP_PATH, 1, 600, "Cheap endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig("GET", EXPENSIVE_PATH, 100, 600, "Expensive endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig("GET", FAMILY_ROUTE, 10, 600, "Route family", "", ""));
      return registry;
    }

    @Bean
    PaygateSecurityFilter paygateSecurityFilter(
        PaygateEndpointRegistry endpointRegistry,
        LightningBackend lightningBackendBean,
        RootKeyStore rootKeyStore,
        CredentialStore credentialStore,
        List<CaveatVerifier> caveatVerifiers) {
      var validator =
          new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
      var l402Protocol = new L402Protocol(validator, "test-service");
      var challengeService =
          new PaygateChallengeService(
              rootKeyStore, lightningBackendBean, null, null, null, null, null, null);
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
    RealStoreTestController realStoreTestController() {
      return new RealStoreTestController();
    }
  }

  @RestController
  static class RealStoreTestController {

    private static final AtomicInteger getInvocations = new AtomicInteger();
    private static final AtomicInteger postInvocations = new AtomicInteger();
    private static final AtomicInteger cheapInvocations = new AtomicInteger();
    private static final AtomicInteger expensiveInvocations = new AtomicInteger();
    private static final AtomicInteger familyInvocations = new AtomicInteger();

    @PaymentRequired(priceSats = 10, description = "Real store test endpoint")
    @GetMapping(PROTECTED_PATH)
    String protectedEndpoint() {
      getInvocations.incrementAndGet();
      return "real-store-content";
    }

    @PaymentRequired(priceSats = 10, description = "POST endpoint")
    @PostMapping(PROTECTED_PATH)
    String protectedPostEndpoint() {
      postInvocations.incrementAndGet();
      return "post-content";
    }

    @PaymentRequired(priceSats = 1, description = "Cheap endpoint")
    @GetMapping(CHEAP_PATH)
    String cheapEndpoint() {
      cheapInvocations.incrementAndGet();
      return "cheap-content";
    }

    @PaymentRequired(priceSats = 100, description = "Expensive endpoint")
    @GetMapping(EXPENSIVE_PATH)
    String expensiveEndpoint() {
      expensiveInvocations.incrementAndGet();
      return "expensive-content";
    }

    @PaymentRequired(priceSats = 10, description = "Route family")
    @GetMapping(FAMILY_ROUTE)
    String familyEndpoint(@PathVariable("id") String id) {
      familyInvocations.incrementAndGet();
      return id;
    }

    static void resetInvocationCounts() {
      getInvocations.set(0);
      postInvocations.set(0);
      cheapInvocations.set(0);
      expensiveInvocations.set(0);
      familyInvocations.set(0);
    }

    static int getInvocations() {
      return getInvocations.get();
    }

    static int postInvocations() {
      return postInvocations.get();
    }

    static int cheapInvocations() {
      return cheapInvocations.get();
    }

    static int expensiveInvocations() {
      return expensiveInvocations.get();
    }

    static int familyInvocations() {
      return familyInvocations.get();
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
        "lnbc100n1p0testrealstore",
        PRICE_SATS,
        "Test invoice",
        InvoiceStatus.PENDING,
        null,
        now,
        now.plus(1, ChronoUnit.HOURS));
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  private String mintCredential(
      String routePattern, String requestMethod, boolean includeBoundary) {
    byte[] preimage = new byte[32];
    new SecureRandom().nextBytes(preimage);
    byte[] paymentHash = sha256(preimage);

    try (RootKeyStore.GenerationResult generationResult = rootKeyStore.generateRootKey()) {
      byte[] rootKey = generationResult.rootKey().value();
      try {
        var identifier = new MacaroonIdentifier(0, paymentHash, generationResult.tokenId());
        var caveats = new java.util.ArrayList<Caveat>();
        caveats.add(new Caveat("services", SERVICE_NAME + ":0"));
        if (includeBoundary) {
          caveats.add(new Caveat("route", routePattern));
          caveats.add(new Caveat("method", requestMethod));
        }
        caveats.add(
            new Caveat(
                SERVICE_NAME + "_valid_until",
                String.valueOf(Instant.now().plusSeconds(TIMEOUT_SECONDS).getEpochSecond())));
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);
        String macaroonBase64 =
            Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon));
        return "L402 " + macaroonBase64 + ":" + HEX.formatHex(preimage);
      } finally {
        KeyMaterial.zeroize(rootKey);
        KeyMaterial.zeroize(preimage);
      }
    }
  }

  // -----------------------------------------------------------------------
  // Stub / in-memory implementations for test isolation
  // -----------------------------------------------------------------------

  static class StubLightningBackend implements LightningBackend {

    private volatile boolean healthy = true;
    private volatile Invoice nextInvoice;

    void setHealthy(boolean healthy) {
      this.healthy = healthy;
    }

    void setNextInvoice(Invoice invoice) {
      this.nextInvoice = invoice;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
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
      return null;
    }

    @Override
    public boolean isHealthy() {
      return healthy;
    }
  }

  static class TestCredentialStore implements CredentialStore {

    private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
      L402Credential previous = store.put(tokenId, credential.copy());
      if (previous != null) {
        previous.destroy();
      }
    }

    @Override
    public L402Credential get(String tokenId) {
      L402Credential credential = store.get(tokenId);
      return credential != null ? credential.copy() : null;
    }

    @Override
    public void revoke(String tokenId) {
      L402Credential removed = store.remove(tokenId);
      if (removed != null) {
        removed.destroy();
      }
    }

    @Override
    public long activeCount() {
      return store.size();
    }
  }
}
