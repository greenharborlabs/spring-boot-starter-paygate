package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying rate limiting behavior in {@link PaygateSecurityFilter}.
 */
@DisplayName("L402 Rate Limiting")
class PaygateRateLimitingTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final HexFormat HEX = HexFormat.of();
    private static final String PROTECTED_PATH = "/api/rate-limited";
    private static final String SERVICE_NAME = "test-service";
    private static final long TIMEOUT_SECONDS = 600;
    private static final int MAX_TOKENS = 3;

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    // -----------------------------------------------------------------------
    // Test: unauthenticated requests beyond rate limit return 429
    // -----------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = RateLimitEnabledApp.class)
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @DisplayName("with rate limiting enabled")
    class RateLimitEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private LightningBackend lightningBackend;

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("unauthenticated requests beyond rate limit return 429")
        void unauthenticatedRequestsBeyondLimitReturn429() throws Exception {
            // First MAX_TOKENS requests should get 402
            for (int i = 0; i < MAX_TOKENS; i++) {
                mockMvc.perform(get(PROTECTED_PATH))
                        .andExpect(status().isPaymentRequired());
            }

            // Next request should be rate-limited
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.code", is(429)))
                    .andExpect(jsonPath("$.error", is("RATE_LIMITED")));
        }

        @Test
        @DisplayName("authenticated requests are NOT rate limited")
        void authenticatedRequestsAreNotRateLimited() throws Exception {
            // Exhaust the rate limit with unauthenticated requests
            for (int i = 0; i < MAX_TOKENS; i++) {
                mockMvc.perform(get(PROTECTED_PATH))
                        .andExpect(status().isPaymentRequired());
            }

            // Verify rate limit is hit for unauthenticated
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().is(429));

            // Authenticated request should still pass through
            String authHeader = buildValidAuthHeader();
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk());
        }
    }

    // -----------------------------------------------------------------------
    // Test: XFF ignored when trustForwardedHeaders=false (default)
    // -----------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = RateLimitWithXffUntrustedApp.class)
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @DisplayName("with XFF untrusted (default)")
    class XffUntrusted {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private LightningBackend lightningBackend;

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("XFF header is ignored; different XFF values share the same rate limit bucket")
        void xffIgnoredSharesSameRateLimitBucket() throws Exception {
            // Exhaust the rate limit using different XFF headers.
            // If XFF were trusted, each would get its own bucket and none would be rate-limited.
            for (int i = 0; i < MAX_TOKENS; i++) {
                mockMvc.perform(get(PROTECTED_PATH)
                                .header("X-Forwarded-For", "10.0.0." + i))
                        .andExpect(status().isPaymentRequired());
            }

            // Next request (even with a new XFF) should be rate-limited because
            // all requests resolved to the same remoteAddr (127.0.0.1)
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("X-Forwarded-For", "10.0.0.99"))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.error", is("RATE_LIMITED")));
        }
    }

    // -----------------------------------------------------------------------
    // Test: XFF used when trustForwardedHeaders=true
    // -----------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = RateLimitWithXffTrustedApp.class)
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @DisplayName("with XFF trusted")
    class XffTrusted {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private LightningBackend lightningBackend;

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("XFF header is used; different XFF values get separate rate limit buckets")
        void xffTrustedSeparateRateLimitBuckets() throws Exception {
            // Exhaust the rate limit for IP 10.0.0.1
            for (int i = 0; i < MAX_TOKENS; i++) {
                mockMvc.perform(get(PROTECTED_PATH)
                                .header("X-Forwarded-For", "10.0.0.1"))
                        .andExpect(status().isPaymentRequired());
            }

            // 10.0.0.1 should now be rate-limited
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("X-Forwarded-For", "10.0.0.1"))
                    .andExpect(status().is(429));

            // But 10.0.0.2 should still get 402 (separate bucket via XFF)
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("X-Forwarded-For", "10.0.0.2"))
                    .andExpect(status().isPaymentRequired());
        }
    }

    // -----------------------------------------------------------------------
    // Test: rate limiter can be disabled via property
    // -----------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = RateLimitDisabledApp.class)
    @AutoConfigureMockMvc
    @DisplayName("with rate limiting disabled")
    class RateLimitDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private LightningBackend lightningBackend;

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("all requests get 402 even beyond what would be the rate limit")
        void allRequestsGet402WhenDisabled() throws Exception {
            // Send more requests than MAX_TOKENS — all should get 402, not 429
            for (int i = 0; i < MAX_TOKENS + 5; i++) {
                mockMvc.perform(get(PROTECTED_PATH))
                        .andExpect(status().isPaymentRequired());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test application configurations
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class RateLimitEnabledApp {

        @Bean
        PaygateProperties paygateProperties() {
            return new PaygateProperties();
        }

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
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(SERVICE_NAME));
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, 10, TIMEOUT_SECONDS, "Rate limited endpoint", "", "")
            );
            return registry;
        }

        @Bean
        PaygateEarningsTracker paygateEarningsTracker() {
            return new PaygateEarningsTracker();
        }

        @Bean
        PaygateRateLimiter paygateRateLimiter() {
            return new TokenBucketRateLimiter(MAX_TOKENS, 0.001, 100_000); // effectively no refill during test
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                PaygateEarningsTracker paygateEarningsTracker,
                PaygateRateLimiter paygateRateLimiter
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, SERVICE_NAME);
            var l402Protocol = new L402Protocol(validator, SERVICE_NAME);
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, null, null, paygateEarningsTracker, paygateRateLimiter, null);
            return new PaygateSecurityFilter(
                    endpointRegistry, List.of(l402Protocol), challengeService, SERVICE_NAME,
                    null, null, paygateEarningsTracker, paygateRateLimiter);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class RateLimitDisabledApp {

        @Bean
        PaygateProperties paygateProperties() {
            return new PaygateProperties();
        }

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
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(SERVICE_NAME));
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, 10, TIMEOUT_SECONDS, "Rate limited endpoint", "", "")
            );
            return registry;
        }

        @Bean
        PaygateEarningsTracker paygateEarningsTracker() {
            return new PaygateEarningsTracker();
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                PaygateEarningsTracker paygateEarningsTracker
        ) {
            // No rate limiter set — disabled
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, SERVICE_NAME);
            var l402Protocol = new L402Protocol(validator, SERVICE_NAME);
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, null, null, paygateEarningsTracker, null, null);
            return new PaygateSecurityFilter(
                    endpointRegistry, List.of(l402Protocol), challengeService, SERVICE_NAME,
                    null, null, paygateEarningsTracker, null);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class RateLimitWithXffUntrustedApp {

        @Bean
        PaygateProperties paygateProperties() {
            // trustForwardedHeaders defaults to false
            return new PaygateProperties();
        }

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
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(SERVICE_NAME));
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, 10, TIMEOUT_SECONDS, "Rate limited endpoint", "", "")
            );
            return registry;
        }

        @Bean
        PaygateEarningsTracker paygateEarningsTracker() {
            return new PaygateEarningsTracker();
        }

        @Bean
        PaygateRateLimiter paygateRateLimiter() {
            return new TokenBucketRateLimiter(MAX_TOKENS, 0.001, 100_000);
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                PaygateEarningsTracker paygateEarningsTracker,
                PaygateRateLimiter paygateRateLimiter,
                PaygateProperties paygateProperties
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, SERVICE_NAME);
            var l402Protocol = new L402Protocol(validator, SERVICE_NAME);
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, paygateProperties, null, paygateEarningsTracker, paygateRateLimiter, null);
            return new PaygateSecurityFilter(
                    endpointRegistry, List.of(l402Protocol), challengeService, SERVICE_NAME,
                    null, null, paygateEarningsTracker, paygateRateLimiter);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class RateLimitWithXffTrustedApp {

        @Bean
        PaygateProperties paygateProperties() {
            return new PaygateProperties();
        }

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
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(SERVICE_NAME));
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(
                    new PaygateEndpointConfig("GET", PROTECTED_PATH, 10, TIMEOUT_SECONDS, "Rate limited endpoint", "", "")
            );
            return registry;
        }

        @Bean
        PaygateEarningsTracker paygateEarningsTracker() {
            return new PaygateEarningsTracker();
        }

        @Bean
        PaygateRateLimiter paygateRateLimiter() {
            return new TokenBucketRateLimiter(MAX_TOKENS, 0.001, 100_000);
        }

        @Bean
        ClientIpResolver clientIpResolver() {
            // Trust XFF from MockMvc's default remoteAddr (127.0.0.1)
            return new ClientIpResolver(true, List.of("127.0.0.1"));
        }

        @Bean
        PaygateSecurityFilter paygateSecurityFilter(
                PaygateEndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                PaygateEarningsTracker paygateEarningsTracker,
                PaygateRateLimiter paygateRateLimiter,
                ClientIpResolver clientIpResolver,
                PaygateProperties paygateProperties
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, SERVICE_NAME);
            var l402Protocol = new L402Protocol(validator, SERVICE_NAME);
            var challengeService = new PaygateChallengeService(
                    rootKeyStore, lightningBackendBean, paygateProperties, null, paygateEarningsTracker, paygateRateLimiter, clientIpResolver);
            return new PaygateSecurityFilter(
                    endpointRegistry, List.of(l402Protocol), challengeService, SERVICE_NAME,
                    null, null, paygateEarningsTracker, paygateRateLimiter);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @PaymentRequired(priceSats = 10, description = "Rate limited endpoint")
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "protected-content";
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static String buildValidAuthHeader() {
        try {
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
            List<Caveat> caveats = List.of(
                    new Caveat("services", SERVICE_NAME + ":0"),
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond()))
            );
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            return "L402 " + macaroonBase64 + ":" + preimageHex;
        } catch (Exception e) {
            throw new AssertionError("Failed to build auth header", e);
        }
    }

    private static Invoice createStubInvoice() {
        byte[] paymentHash = new byte[32];
        new SecureRandom().nextBytes(paymentHash);
        Instant now = Instant.now();
        return new Invoice(paymentHash, "lnbc100n1p0testinvoice", 10, "Test invoice",
                InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
    }

    // -----------------------------------------------------------------------
    // Stub implementations (mirrors PaygateSecurityFilterTest stubs)
    // -----------------------------------------------------------------------

    static class StubLightningBackend implements LightningBackend {

        private volatile boolean healthy = true;
        private volatile Invoice nextInvoice;

        void setHealthy(boolean healthy) { this.healthy = healthy; }
        void setNextInvoice(Invoice invoice) { this.nextInvoice = invoice; }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            if (nextInvoice != null) return nextInvoice;
            byte[] ph = new byte[32];
            new SecureRandom().nextBytes(ph);
            Instant now = Instant.now();
            return new Invoice(ph, "lnbc" + amountSats + "n1pstub", amountSats, memo,
                    InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) { return null; }

        @Override
        public boolean isHealthy() { return healthy; }
    }

    static class InMemoryTestRootKeyStore implements RootKeyStore {

        private final byte[] rootKey;

        InMemoryTestRootKeyStore(byte[] rootKey) { this.rootKey = rootKey.clone(); }

        @Override
        public GenerationResult generateRootKey() {
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);
            return new GenerationResult(new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(rootKey.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.paygate.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) { return new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(rootKey.clone()); }

        @Override
        public void revokeRootKey(byte[] keyId) { }
    }

    static class InMemoryTestCredentialStore implements CredentialStore {

        private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) { store.put(tokenId, credential); }

        @Override
        public L402Credential get(String tokenId) { return store.get(tokenId); }

        @Override
        public void revoke(String tokenId) { store.remove(tokenId); }

        @Override
        public long activeCount() { return store.size(); }
    }
}
