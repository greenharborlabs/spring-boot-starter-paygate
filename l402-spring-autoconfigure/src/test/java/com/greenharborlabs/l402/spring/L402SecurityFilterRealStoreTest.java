package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Validator;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test using the REAL {@link InMemoryRootKeyStore} (from l402-core).
 *
 * <p>Proves end-to-end: request -> 402 challenge -> mint credential using the
 * real store's generated root key and tokenId -> present L402 credential -> 200.
 *
 * <p>This test validates that the {@link RootKeyStore.GenerationResult} refactoring
 * works correctly with the production implementation, not just test stubs.
 */
@SpringBootTest(classes = L402SecurityFilterRealStoreTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402SecurityFilter with real InMemoryRootKeyStore")
class L402SecurityFilterRealStoreTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final long PRICE_SATS = 10;
    private static final String PROTECTED_PATH = "/api/real-store-test";
    private static final String SERVICE_NAME = "test-service";
    private static final long TIMEOUT_SECONDS = 600;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LightningBackend lightningBackend;

    @Autowired
    private RootKeyStore rootKeyStore;

    @BeforeEach
    void setUp() {
        var stub = (StubLightningBackend) lightningBackend;
        stub.setHealthy(true);
        stub.setNextInvoice(createStubInvoice());
    }

    @Test
    @DisplayName("unauthenticated request returns 402 challenge with macaroon and invoice")
    void unauthenticatedRequestReturns402() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(header().string("WWW-Authenticate", startsWith("L402 ")))
                .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
                .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
    }

    @Test
    @DisplayName("full 402 -> credential -> 200 flow using real InMemoryRootKeyStore")
    void fullFlowWithRealStore() throws Exception {
        // Generate a preimage and compute its SHA-256 payment hash
        byte[] preimage = new byte[32];
        new SecureRandom().nextBytes(preimage);
        byte[] paymentHash = sha256(preimage);

        // Use the REAL InMemoryRootKeyStore to generate a root key and tokenId
        RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
        byte[] rootKey = genResult.rootKey().value();
        byte[] tokenId = genResult.tokenId();

        // Mint a macaroon using the real root key with service and expiry caveats
        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
        Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
        List<Caveat> caveats = List.of(
                new Caveat("services", SERVICE_NAME + ":0"),
                new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond()))
        );
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

        // Build L402 Authorization header
        String preimageHex = HEX.formatHex(preimage);
        String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

        // Present credential and expect 200
        mockMvc.perform(get(PROTECTED_PATH)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-L402-Token-Id"))
                .andExpect(header().exists("X-L402-Credential-Expires"))
                .andExpect(content().string("real-store-content"));
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
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(SERVICE_NAME));
        }

        @Bean
        L402EndpointRegistry l402EndpointRegistry() {
            var registry = new L402EndpointRegistry();
            registry.register(
                    new L402EndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Real store test endpoint", "", "")
            );
            return registry;
        }

        @Bean
        L402SecurityFilter l402SecurityFilter(
                L402EndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
            var challengeService = new L402ChallengeService(
                    rootKeyStore, lightningBackendBean, null, null, null, null);
            return new L402SecurityFilter(
                    endpointRegistry, validator, challengeService, "test-service",
                    null, null, null);
        }

        @Bean
        RealStoreTestController realStoreTestController() {
            return new RealStoreTestController();
        }
    }

    @RestController
    static class RealStoreTestController {

        @L402Protected(priceSats = 10, description = "Real store test endpoint")
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "real-store-content";
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
                now.plus(1, ChronoUnit.HOURS)
        );
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
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
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
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
