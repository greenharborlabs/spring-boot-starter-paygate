package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Validator;

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

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD test for pricing strategy fallback behavior (T087).
 *
 * <p>Verifies that when {@code @PaygateProtected(priceSats = 50, pricingStrategy = "nonExistentPricer")}
 * references a bean name that does not exist in the application context, the system falls back
 * to the static {@code priceSats} value of 50 rather than failing.
 *
 * <p>This test is expected NOT to compile until {@link PaygatePricingStrategy} is created (T088),
 * and NOT to pass until the filter integrates pricing strategy lookup with fallback (T089).
 */
@SpringBootTest(classes = PricingFallbackTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402 pricing strategy fallback")
class PricingFallbackTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final long FALLBACK_PRICE_SATS = 50;
    private static final String FALLBACK_PATH = "/api/fallback-price";

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LightningBackend lightningBackend;

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        LightningBackend lightningBackend() {
            return new PricingCapturingStubLightningBackend();
        }

        @Bean
        RootKeyStore rootKeyStore() {
            return new PricingFallbackTestRootKeyStore(ROOT_KEY);
        }

        @Bean
        CredentialStore credentialStore() {
            return new PricingFallbackTestCredentialStore();
        }

        @Bean
        List<CaveatVerifier> caveatVerifiers() {
            return List.of();
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", FALLBACK_PATH, FALLBACK_PRICE_SATS, 600,
                            "Fallback price endpoint", "nonExistentPricer", "")
            );
            return registry;
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                ApplicationContext applicationContext
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, null, applicationContext, null, null);
            return new PaygateSecurityFilter(
                    endpointRegistry, validator, challengeService, "test-service",
                    null, null, null, null);
        }

        @Bean
        PricingFallbackController pricingFallbackController() {
            return new PricingFallbackController();
        }
    }

    // NOTE: No "nonExistentPricer" bean is registered — that is the entire point of this test.

    @RestController
    static class PricingFallbackController {

        @PaygateProtected(priceSats = 50, pricingStrategy = "nonExistentPricer")
        @GetMapping(FALLBACK_PATH)
        String fallbackPriceEndpoint() {
            return "fallback-content";
        }
    }

    // -----------------------------------------------------------------------
    // Test scenarios
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("returns 402 with fallback price_sats=50 when pricingStrategy bean does not exist")
    void returns402WithFallbackPriceWhenStrategyBeanMissing() throws Exception {
        mockMvc.perform(get(FALLBACK_PATH))
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", is(402)))
                .andExpect(jsonPath("$.message", is("Payment required")))
                .andExpect(jsonPath("$.price_sats", is(50)))
                .andExpect(jsonPath("$.invoice", notNullValue()));
    }

    @Test
    @DisplayName("invoice is created with fallback amountSats=50 when pricingStrategy bean does not exist")
    void invoiceCreatedWithFallbackAmount() throws Exception {
        var stub = (PricingCapturingStubLightningBackend) lightningBackend;
        stub.resetCapturedAmount();

        mockMvc.perform(get(FALLBACK_PATH))
                .andExpect(status().isPaymentRequired());

        assertThat(stub.getCapturedAmountSats())
                .as("Invoice should be created with the static priceSats fallback value")
                .isEqualTo(FALLBACK_PRICE_SATS);
    }

    // -----------------------------------------------------------------------
    // Stub / in-memory implementations for test isolation
    // -----------------------------------------------------------------------

    /**
     * Stub LightningBackend that captures the amountSats passed to createInvoice(),
     * allowing tests to verify the correct price was used for invoice generation.
     */
    static class PricingCapturingStubLightningBackend implements LightningBackend {

        private final AtomicLong capturedAmountSats = new AtomicLong(-1);

        void resetCapturedAmount() {
            capturedAmountSats.set(-1);
        }

        long getCapturedAmountSats() {
            return capturedAmountSats.get();
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            capturedAmountSats.set(amountSats);
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
            return true;
        }
    }

    static class PricingFallbackTestRootKeyStore implements RootKeyStore {

        private final byte[] rootKey;

        PricingFallbackTestRootKeyStore(byte[] rootKey) {
            this.rootKey = rootKey.clone();
        }

        @Override
        public GenerationResult generateRootKey() {
            byte[] tokenId = new byte[32];
            new java.security.SecureRandom().nextBytes(tokenId);
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

    static class PricingFallbackTestCredentialStore implements CredentialStore {

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
