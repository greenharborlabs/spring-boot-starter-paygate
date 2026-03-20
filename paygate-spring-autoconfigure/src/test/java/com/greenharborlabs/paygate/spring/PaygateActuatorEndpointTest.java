package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD tests for {@link PaygateActuatorEndpoint}.
 *
 * <p>Verifies the JSON contract defined in l402-http-protocol.md section 7:
 * GET /actuator/paygate must return all required fields including enabled, backend,
 * backendHealthy, testMode, serviceName, protectedEndpoints, credentials, and earnings.
 *
 * <p>The {@code PaygateActuatorEndpoint} class does not exist yet (T092).
 * These tests are written first per TDD and will not compile until the endpoint is implemented.
 */
@SpringBootTest(
        classes = PaygateActuatorEndpointTest.TestApp.class,
        properties = {
                "management.endpoints.web.exposure.include=paygate",
                "paygate.enabled=true",
                "paygate.backend=lnbits",
                "paygate.service-name=my-api",
                "paygate.test-mode=false",
                "paygate.credential-cache-max-size=10000"
        }
)
@AutoConfigureMockMvc
@DisplayName("PaygateActuatorEndpoint")
class PaygateActuatorEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        LightningBackend lightningBackend() {
            return new StubLightningBackend(true);
        }

        @Bean
        RootKeyStore rootKeyStore() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new StubRootKeyStore(key);
        }

        @Bean
        CredentialStore credentialStore() {
            return new StubCredentialStore(42);
        }

        @Bean
        PaygateEndpointRegistry paygateEndpointRegistry() {
            var registry = new PaygateEndpointRegistry();
            registry.register(new PaygateEndpointConfig(
                    "POST",
                    "/api/v1/analyze",
                    10,
                    3600,
                    "Financial data enrichment",
                    "",
                    ""
            ));
            return registry;
        }

        @Bean
        PaygateEarningsTracker paygateEarningsTracker() {
            return new StubEarningsTracker(1523, 1201, 12010);
        }

        @Bean
        PaygateActuatorEndpoint paygateActuatorEndpoint(
                PaygateProperties properties,
                LightningBackend lightningBackend,
                PaygateEndpointRegistry endpointRegistry,
                CredentialStore credentialStore,
                PaygateEarningsTracker earningsTracker
        ) {
            return new PaygateActuatorEndpoint(
                    properties, lightningBackend, endpointRegistry, credentialStore, earningsTracker
            );
        }
    }

    // -----------------------------------------------------------------------
    // Top-level fields
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("top-level fields")
    class TopLevelFields {

        @Test
        @DisplayName("returns 200 OK")
        void returnsOk() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns enabled field")
        void returnsEnabled() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.enabled", is(true)));
        }

        @Test
        @DisplayName("returns backend field")
        void returnsBackend() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.backend", is("lnbits")));
        }

        @Test
        @DisplayName("returns backendHealthy field")
        void returnsBackendHealthy() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.backendHealthy", is(true)));
        }

        @Test
        @DisplayName("testMode field is not present in response")
        void testModeNotPresent() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.testMode").doesNotExist());
        }

        @Test
        @DisplayName("returns serviceName field")
        void returnsServiceName() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.serviceName", is("my-api")));
        }
    }

    // -----------------------------------------------------------------------
    // Protected endpoints array
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("protectedEndpoints array")
    class ProtectedEndpoints {

        @Test
        @DisplayName("contains one registered endpoint")
        void containsOneEndpoint() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints", hasSize(1)));
        }

        @Test
        @DisplayName("endpoint has correct method")
        void endpointHasMethod() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].method", is("POST")));
        }

        @Test
        @DisplayName("endpoint has correct path")
        void endpointHasPath() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].path", is("/api/v1/analyze")));
        }

        @Test
        @DisplayName("endpoint has correct priceSats")
        void endpointHasPriceSats() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].priceSats", is(10)));
        }

        @Test
        @DisplayName("endpoint has correct timeoutSeconds")
        void endpointHasTimeoutSeconds() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].timeoutSeconds", is(3600)));
        }

        @Test
        @DisplayName("endpoint has correct description")
        void endpointHasDescription() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].description", is("Financial data enrichment")));
        }

        @Test
        @DisplayName("endpoint has null pricingStrategy when empty")
        void endpointHasNullPricingStrategy() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.protectedEndpoints[0].pricingStrategy", is(nullValue())));
        }
    }

    // -----------------------------------------------------------------------
    // Credentials object
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("credentials object")
    class Credentials {

        @Test
        @DisplayName("contains active count")
        void containsActiveCount() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.credentials.active", is(42)));
        }

        @Test
        @DisplayName("contains maxSize")
        void containsMaxSize() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.credentials.maxSize", is(10000)));
        }
    }

    // -----------------------------------------------------------------------
    // Earnings object
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("earnings object")
    class Earnings {

        @Test
        @DisplayName("contains totalInvoicesCreated")
        void containsTotalInvoicesCreated() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.earnings.totalInvoicesCreated", is(1523)));
        }

        @Test
        @DisplayName("contains totalInvoicesSettled")
        void containsTotalInvoicesSettled() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.earnings.totalInvoicesSettled", is(1201)));
        }

        @Test
        @DisplayName("contains totalSatsEarned")
        void containsTotalSatsEarned() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.earnings.totalSatsEarned", is(12010)));
        }

        @Test
        @DisplayName("contains in-memory volatility note")
        void containsVolatilityNote() throws Exception {
            mockMvc.perform(get("/actuator/paygate"))
                    .andExpect(jsonPath("$.earnings.note",
                            is("In-memory only; resets on application restart")));
        }
    }

    // -----------------------------------------------------------------------
    // Stub / in-memory implementations for test isolation
    // -----------------------------------------------------------------------

    static class StubLightningBackend implements LightningBackend {

        private final boolean healthy;

        StubLightningBackend(boolean healthy) {
            this.healthy = healthy;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            throw new UnsupportedOperationException("Not needed for actuator test");
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            throw new UnsupportedOperationException("Not needed for actuator test");
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }

    static class StubRootKeyStore implements RootKeyStore {

        private final byte[] key;

        StubRootKeyStore(byte[] key) {
            this.key = key.clone();
        }

        @Override
        public GenerationResult generateRootKey() {
            return new GenerationResult(new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(key.clone()), new byte[32]);
        }

        @Override
        public com.greenharborlabs.paygate.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            return new com.greenharborlabs.paygate.core.macaroon.SensitiveBytes(key.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op
        }
    }

    static class StubCredentialStore implements CredentialStore {

        private final long fixedActiveCount;
        private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

        StubCredentialStore(long fixedActiveCount) {
            this.fixedActiveCount = fixedActiveCount;
        }

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
            return fixedActiveCount;
        }
    }

    /**
     * Stub earnings tracker that returns fixed values.
     * The real {@link PaygateEarningsTracker} class does not exist yet (T093/T094).
     */
    static class StubEarningsTracker extends PaygateEarningsTracker {

        private final long invoicesCreated;
        private final long invoicesSettled;
        private final long satsEarned;

        StubEarningsTracker(long invoicesCreated, long invoicesSettled, long satsEarned) {
            this.invoicesCreated = invoicesCreated;
            this.invoicesSettled = invoicesSettled;
            this.satsEarned = satsEarned;
        }

        @Override
        public long getTotalInvoicesCreated() {
            return invoicesCreated;
        }

        @Override
        public long getTotalInvoicesSettled() {
            return invoicesSettled;
        }

        @Override
        public long getTotalSatsEarned() {
            return satsEarned;
        }
    }
}
