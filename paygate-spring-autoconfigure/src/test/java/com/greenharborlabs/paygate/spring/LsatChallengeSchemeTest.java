package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;

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
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the WWW-Authenticate header in 402 challenge responses uses only
 * the {@code L402} scheme and never the legacy {@code LSAT} scheme (T096, T098).
 */
@SpringBootTest(classes = LsatChallengeSchemeTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("WWW-Authenticate header uses L402 scheme, never LSAT")
class LsatChallengeSchemeTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final long PRICE_SATS = 10;
    private static final String PROTECTED_PATH = "/api/scheme-test";

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LightningBackend lightningBackend;

    @BeforeEach
    void setUp() {
        var stub = (StubLightningBackend) lightningBackend;
        stub.setHealthy(true);
        stub.setNextInvoice(createStubInvoice());
    }

    @Test
    @DisplayName("T096: 402 challenge WWW-Authenticate header starts with L402 scheme")
    void wwwAuthenticateStartsWithL402Scheme() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(header().string("WWW-Authenticate", startsWith("L402 ")))
                .andExpect(header().string("WWW-Authenticate", containsString("version=\"0\"")))
                .andExpect(header().string("WWW-Authenticate", containsString("token=")));
    }

    @Test
    @DisplayName("T098: 402 challenge WWW-Authenticate header does not contain LSAT anywhere")
    void wwwAuthenticateDoesNotContainLsat() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(header().string("WWW-Authenticate", not(containsString("LSAT"))));
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

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Scheme test endpoint", "", "")
            );
            return registry;
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
            var l402Protocol = new L402Protocol(validator, "test-service");
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, null, null, null, null);
            return new PaygateSecurityFilter(
                    endpointRegistry, List.of(l402Protocol), challengeService, "test-service",
                    null, null, null, null);
        }

        @Bean
        SchemeTestController schemeTestController() {
            return new SchemeTestController();
        }
    }

    @RestController
    static class SchemeTestController {

        @PaymentRequired(priceSats = 10, description = "Scheme test endpoint")
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "scheme-test-content";
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
                "lnbc100n1p0testinvoice",
                PRICE_SATS,
                "Test invoice",
                InvoiceStatus.PENDING,
                null,
                now,
                now.plus(1, ChronoUnit.HOURS)
        );
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
            return new GenerationResult(new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(rootKey.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.paygate.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            return new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(rootKey.clone());
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
