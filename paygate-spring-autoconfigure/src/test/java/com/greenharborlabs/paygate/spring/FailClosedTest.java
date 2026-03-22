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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed integration tests for {@link PaygateSecurityFilter}.
 *
 * <p>Verifies that the filter returns 503 LIGHTNING_UNAVAILABLE when the
 * Lightning backend is unhealthy or when invoice creation fails. This is
 * a critical security property: the system must never grant access when
 * the payment infrastructure is unavailable.
 *
 * <p>Written TDD-first per T066. The createInvoice-throws scenario is
 * expected to fail until T072 adds try-catch handling in the filter.
 */
@SpringBootTest(classes = FailClosedTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("PaygateSecurityFilter fail-closed behavior")
class FailClosedTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final long PRICE_SATS = 10;
    private static final String PROTECTED_PATH = "/api/protected";
    private static final String PUBLIC_PATH = "/api/public";

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
            return new FailClosedStubLightningBackend();
        }

        @Bean
        RootKeyStore rootKeyStore() {
            return new FailClosedTestRootKeyStore(ROOT_KEY);
        }

        @Bean
        CredentialStore credentialStore() {
            return new FailClosedTestCredentialStore();
        }

        @Bean
        List<CaveatVerifier> caveatVerifiers() {
            return List.of();
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Test protected endpoint", "", "")
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
        FailClosedTestController testController() {
            return new FailClosedTestController();
        }
    }

    @RestController
    static class FailClosedTestController {

        @PaymentRequired(priceSats = 10, description = "Test protected endpoint")
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
    // Test scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("isHealthy() returns false")
    class HealthCheckFails {

        @BeforeEach
        void setUp() {
            var stub = (FailClosedStubLightningBackend) lightningBackend;
            stub.setHealthy(false);
            stub.setCreateInvoiceException(null);
        }

        @Test
        @DisplayName("returns 503 with LIGHTNING_UNAVAILABLE error on protected endpoint")
        void returns503WhenUnhealthy() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(503)))
                    .andExpect(jsonPath("$.error", is("LIGHTNING_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message", containsString("Lightning backend is not available")));
        }

        @Test
        @DisplayName("returns 400 when Authorization header has malformed L402 value even when Lightning is down")
        void returns400WithMalformedAuthHeaderWhenUnhealthy() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", "L402 some-macaroon:some-preimage"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("MALFORMED_HEADER")));
        }

        @Test
        @DisplayName("unprotected endpoint still responds 200 when Lightning is down")
        void publicEndpointUnaffected() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }

    @Nested
    @DisplayName("createInvoice() throws RuntimeException")
    class CreateInvoiceThrows {

        @BeforeEach
        void setUp() {
            var stub = (FailClosedStubLightningBackend) lightningBackend;
            stub.setHealthy(true);
            stub.setCreateInvoiceException(new RuntimeException("LND connection refused"));
        }

        @Test
        @DisplayName("returns 503 with LIGHTNING_UNAVAILABLE when createInvoice fails (TDD: expected to fail until T072)")
        void returns503WhenCreateInvoiceThrows() throws Exception {
            // Request with no auth header triggers writePaymentRequiredResponse,
            // which calls createInvoice(). The filter currently lacks try-catch
            // around createInvoice(), so this test documents the DESIRED behavior.
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(503)))
                    .andExpect(jsonPath("$.error", is("LIGHTNING_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message", containsString("Lightning backend is not available")));
        }

        @Test
        @DisplayName("unprotected endpoint still responds 200 when createInvoice would throw")
        void publicEndpointUnaffectedByCreateInvoiceFailure() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }

    @Nested
    @DisplayName("baseline: healthy Lightning backend")
    class HealthyBaseline {

        @BeforeEach
        void setUp() {
            var stub = (FailClosedStubLightningBackend) lightningBackend;
            stub.setHealthy(true);
            stub.setCreateInvoiceException(null);
        }

        @Test
        @DisplayName("protected endpoint returns 402 challenge when healthy and no auth header")
        void returns402WhenHealthy() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("unprotected endpoint passes through normally")
        void publicEndpointPassesThrough() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }

    // -----------------------------------------------------------------------
    // Stub / in-memory implementations for test isolation
    // -----------------------------------------------------------------------

    static class FailClosedStubLightningBackend implements LightningBackend {

        private volatile boolean healthy = true;
        private volatile RuntimeException createInvoiceException;

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        void setCreateInvoiceException(RuntimeException exception) {
            this.createInvoiceException = exception;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            if (createInvoiceException != null) {
                throw createInvoiceException;
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

    static class FailClosedTestRootKeyStore implements RootKeyStore {

        private final byte[] rootKey;

        FailClosedTestRootKeyStore(byte[] rootKey) {
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

    static class FailClosedTestCredentialStore implements CredentialStore {

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
