package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.EvictionReason;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Validator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD tests for L402 Micrometer metrics integration.
 *
 * <p>Verifies that the {@link L402SecurityFilter} records the correct Micrometer
 * counters and gauges when processing requests to protected endpoints. These tests
 * will FAIL until T094 integrates metrics recording into the filter.
 *
 * <p>Metrics under test:
 * <ul>
 *   <li>{@code l402.requests} counter with {@code endpoint} and {@code result} tags</li>
 *   <li>{@code l402.revenue.sats} counter with {@code endpoint} tag</li>
 *   <li>{@code l402.invoices.created} counter with {@code endpoint} tag</li>
 *   <li>{@code l402.invoices.settled} counter with {@code endpoint} tag</li>
 *   <li>{@code l402.credentials.active} gauge</li>
 *   <li>{@code l402.lightning.healthy} gauge</li>
 * </ul>
 */
@SpringBootTest(classes = L402MetricsTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402 Micrometer Metrics")
class L402MetricsTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final HexFormat HEX = HexFormat.of();
    private static final long PRICE_SATS = 21;
    private static final String PROTECTED_PATH = "/api/paid";
    private static final String PUBLIC_PATH = "/api/free";
    private static final String PARAMETERIZED_PATH_PATTERN = "/api/items/{id}";

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LightningBackend lightningBackend;

    @Autowired
    private CredentialStore credentialStore;

    @Autowired
    private L402Metrics l402Metrics;

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
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
            return List.of();
        }

        @Bean
        L402EndpointRegistry l402EndpointRegistry() {
            var registry = new L402EndpointRegistry();
            registry.register(
                    new L402EndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Paid endpoint", "", "")
            );
            registry.register(
                    new L402EndpointConfig("GET", PARAMETERIZED_PATH_PATTERN, 10, 600, "Items endpoint", "", "")
            );
            return registry;
        }

        @Bean
        L402Metrics l402Metrics(MeterRegistry meterRegistry,
                                CredentialStore credentialStore,
                                LightningBackend lightningBackend) {
            return new L402Metrics(meterRegistry, credentialStore, lightningBackend);
        }

        @Bean
        L402SecurityFilter l402SecurityFilter(
                L402EndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                L402Metrics l402Metrics
        ) {
            var validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
            var challengeService = new L402ChallengeService(
                    rootKeyStore, lightningBackendBean, null, null, null, null);
            return new L402SecurityFilter(
                    endpointRegistry, validator, challengeService, "test-service",
                    l402Metrics, null, null);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @L402Protected(priceSats = 21, description = "Paid endpoint")
        @GetMapping(PROTECTED_PATH)
        String paidEndpoint() {
            return "paid-content";
        }

        @GetMapping(PUBLIC_PATH)
        String freeEndpoint() {
            return "free-content";
        }

        @GetMapping("/api/items/{id}")
        String itemEndpoint(@PathVariable String id) {
            return "item-" + id;
        }
    }

    @BeforeEach
    void resetStubs() {
        ((StubLightningBackend) lightningBackend).setHealthy(true);
        ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(false);
        ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
    }

    // -----------------------------------------------------------------------
    // Challenge scenario (402) — no auth header on protected endpoint
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a 402 challenge is issued")
    class ChallengeMetrics {

        @Test
        @DisplayName("increments l402.requests counter with result=challenged")
        void incrementsRequestsChallengedCounter() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("increments l402.invoices.created counter")
        void incrementsInvoicesCreatedCounter() throws Exception {
            double before = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("does not increment l402.revenue.sats counter")
        void doesNotIncrementRevenueCounter() throws Exception {
            double before = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not increment l402.invoices.settled counter")
        void doesNotIncrementInvoicesSettledCounter() throws Exception {
            double before = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not increment l402.requests counter with result=passed")
        void doesNotIncrementPassedCounter() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "passed");

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "passed");
            assertThat(after).isEqualTo(before);
        }
    }

    // -----------------------------------------------------------------------
    // Passed scenario (200) — valid credential on protected endpoint
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a valid credential passes")
    class PassedMetrics {

        @Test
        @DisplayName("increments l402.requests counter with result=passed")
        void incrementsRequestsPassedCounter() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "passed");

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildValidAuthHeader()))
                    .andExpect(status().isOk());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "passed");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("increments l402.revenue.sats counter by the endpoint price")
        void incrementsRevenueSatsCounter() throws Exception {
            double before = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildValidAuthHeader()))
                    .andExpect(status().isOk());

            double after = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before + PRICE_SATS);
        }

        @Test
        @DisplayName("increments l402.invoices.settled counter")
        void incrementsInvoicesSettledCounter() throws Exception {
            double before = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildValidAuthHeader()))
                    .andExpect(status().isOk());

            double after = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("does not increment l402.invoices.created counter")
        void doesNotIncrementInvoicesCreatedCounter() throws Exception {
            double before = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildValidAuthHeader()))
                    .andExpect(status().isOk());

            double after = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not increment l402.requests counter with result=challenged")
        void doesNotIncrementChallengedCounter() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildValidAuthHeader()))
                    .andExpect(status().isOk());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");
            assertThat(after).isEqualTo(before);
        }
    }

    // -----------------------------------------------------------------------
    // Rejected scenario (401) — invalid credential on protected endpoint
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a credential is rejected")
    class RejectedMetrics {

        @Test
        @DisplayName("increments l402.requests counter with result=rejected")
        void incrementsRequestsRejectedCounter() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "rejected");

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildInvalidAuthHeader()))
                    .andExpect(status().isUnauthorized());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "rejected");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("does not increment l402.revenue.sats counter")
        void doesNotIncrementRevenueCounter() throws Exception {
            double before = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildInvalidAuthHeader()))
                    .andExpect(status().isUnauthorized());

            double after = counterValue("l402.revenue.sats", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not increment l402.invoices.created counter")
        void doesNotIncrementInvoicesCreatedCounter() throws Exception {
            double before = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildInvalidAuthHeader()))
                    .andExpect(status().isUnauthorized());

            double after = counterValue("l402.invoices.created", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("does not increment l402.invoices.settled counter")
        void doesNotIncrementInvoicesSettledCounter() throws Exception {
            double before = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", buildInvalidAuthHeader()))
                    .andExpect(status().isUnauthorized());

            double after = counterValue("l402.invoices.settled", "endpoint", PROTECTED_PATH);
            assertThat(after).isEqualTo(before);
        }
    }

    // -----------------------------------------------------------------------
    // Gauge metrics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("gauge metrics")
    class GaugeMetrics {

        @Test
        @DisplayName("l402.credentials.active gauge reflects credential store active count")
        void credentialsActiveGaugeReflectsStoreCount() throws Exception {
            // Trigger a request so the filter has been invoked at least once
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            Double gaugeValue = gaugeValue("l402.credentials.active");
            assertThat(gaugeValue).isNotNull();
            assertThat(gaugeValue).isEqualTo((double) credentialStore.activeCount());
        }

        @Test
        @DisplayName("l402.lightning.healthy gauge reports 1.0 when Lightning is healthy")
        void lightningHealthyGaugeReportsOne() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            // Trigger a request so gauges are registered
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            Double gaugeValue = gaugeValue("l402.lightning.healthy");
            assertThat(gaugeValue).isNotNull();
            assertThat(gaugeValue).isEqualTo(1.0);
        }

        @Test
        @DisplayName("l402.lightning.healthy gauge reports 0.0 when Lightning is unhealthy")
        void lightningHealthyGaugeReportsZero() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(false);

            // Trigger a request — will get 503 but gauge should still be set
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isServiceUnavailable());

            Double gaugeValue = gaugeValue("l402.lightning.healthy");
            assertThat(gaugeValue).isNotNull();
            assertThat(gaugeValue).isEqualTo(0.0);
        }

        @Test
        @DisplayName("l402.lightning.healthy gauge reports 0.0 when isHealthy() throws exception")
        void lightningHealthyGaugeReportsZeroOnException() {
            ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(true);

            Double gaugeValue = gaugeValue("l402.lightning.healthy");
            assertThat(gaugeValue).isNotNull();
            assertThat(gaugeValue).isEqualTo(0.0);
        }

        @Test
        @DisplayName("l402.lightning.healthy gauge recovers to 1.0 after exception clears")
        void lightningHealthyGaugeRecoversAfterException() {
            ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(true);

            Double duringException = gaugeValue("l402.lightning.healthy");
            assertThat(duringException).isNotNull();
            assertThat(duringException).isEqualTo(0.0);

            // Backend recovers
            ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(false);
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            Double afterRecovery = gaugeValue("l402.lightning.healthy");
            assertThat(afterRecovery).isNotNull();
            assertThat(afterRecovery).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // Cache eviction metrics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("cache eviction metrics")
    class CacheEvictionMetrics {

        @Test
        @DisplayName("recordCacheEviction(EXPIRED) increments l402.cache.evictions with reason=expired")
        void incrementsExpiredEvictionCounter() {
            double before = counterValue("l402.cache.evictions", "reason", "expired");

            l402Metrics.recordCacheEviction(EvictionReason.EXPIRED);

            double after = counterValue("l402.cache.evictions", "reason", "expired");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("recordCacheEviction(CAPACITY) increments l402.cache.evictions with reason=capacity")
        void incrementsCapacityEvictionCounter() {
            double before = counterValue("l402.cache.evictions", "reason", "capacity");

            l402Metrics.recordCacheEviction(EvictionReason.CAPACITY);

            double after = counterValue("l402.cache.evictions", "reason", "capacity");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("recordCacheEviction(REVOKED) increments l402.cache.evictions with reason=revoked")
        void incrementsRevokedEvictionCounter() {
            double before = counterValue("l402.cache.evictions", "reason", "revoked");

            l402Metrics.recordCacheEviction(EvictionReason.REVOKED);

            double after = counterValue("l402.cache.evictions", "reason", "revoked");
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("each reason tag is independent — incrementing one does not affect others")
        void reasonTagsAreIndependent() {
            double expiredBefore = counterValue("l402.cache.evictions", "reason", "expired");
            double capacityBefore = counterValue("l402.cache.evictions", "reason", "capacity");
            double revokedBefore = counterValue("l402.cache.evictions", "reason", "revoked");

            l402Metrics.recordCacheEviction(EvictionReason.EXPIRED);
            l402Metrics.recordCacheEviction(EvictionReason.EXPIRED);
            l402Metrics.recordCacheEviction(EvictionReason.CAPACITY);

            assertThat(counterValue("l402.cache.evictions", "reason", "expired"))
                    .isEqualTo(expiredBefore + 2.0);
            assertThat(counterValue("l402.cache.evictions", "reason", "capacity"))
                    .isEqualTo(capacityBefore + 1.0);
            assertThat(counterValue("l402.cache.evictions", "reason", "revoked"))
                    .isEqualTo(revokedBefore);
        }
    }

    // -----------------------------------------------------------------------
    // Unprotected endpoint — should not increment any L402 counters
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("unprotected endpoint")
    class UnprotectedEndpointMetrics {

        @Test
        @DisplayName("does not increment any l402.requests counter")
        void doesNotIncrementRequestsCounter() throws Exception {
            double passedBefore = counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "passed");
            double challengedBefore = counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "challenged");
            double rejectedBefore = counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "rejected");

            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk());

            assertThat(counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "passed"))
                    .isEqualTo(passedBefore);
            assertThat(counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "challenged"))
                    .isEqualTo(challengedBefore);
            assertThat(counterValue("l402.requests", "endpoint", PUBLIC_PATH, "result", "rejected"))
                    .isEqualTo(rejectedBefore);
        }
    }

    // -----------------------------------------------------------------------
    // Parameterized path cardinality — counters use pattern, not raw path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("parameterized path cardinality")
    class ParameterizedPathCardinalityMetrics {

        @Test
        @DisplayName("requests to /api/items/1 and /api/items/2 both tag with endpoint=/api/items/{id}")
        void bothConcretePathsUsePatternTag() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PARAMETERIZED_PATH_PATTERN, "result", "challenged");

            mockMvc.perform(get("/api/items/1"))
                    .andExpect(status().isPaymentRequired());
            mockMvc.perform(get("/api/items/2"))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.requests", "endpoint", PARAMETERIZED_PATH_PATTERN, "result", "challenged");
            assertThat(after).isEqualTo(before + 2.0);
        }

        @Test
        @DisplayName("no counter exists tagged with raw path /api/items/1")
        void noCounterForRawPath1() throws Exception {
            mockMvc.perform(get("/api/items/1"))
                    .andExpect(status().isPaymentRequired());

            assertThat(counterValue("l402.requests", "endpoint", "/api/items/1", "result", "challenged"))
                    .isZero();
        }

        @Test
        @DisplayName("no counter exists tagged with raw path /api/items/2")
        void noCounterForRawPath2() throws Exception {
            mockMvc.perform(get("/api/items/2"))
                    .andExpect(status().isPaymentRequired());

            assertThat(counterValue("l402.requests", "endpoint", "/api/items/2", "result", "challenged"))
                    .isZero();
        }

        @Test
        @DisplayName("invoices.created counter uses pattern tag for parameterized paths")
        void invoicesCreatedUsesPatternTag() throws Exception {
            double before = counterValue("l402.invoices.created", "endpoint", PARAMETERIZED_PATH_PATTERN);

            mockMvc.perform(get("/api/items/1"))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.invoices.created", "endpoint", PARAMETERIZED_PATH_PATTERN);
            assertThat(after).isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("existing exact-path tests still use endpoint=/api/paid")
        void exactPathStillWorks() throws Exception {
            double before = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            double after = counterValue("l402.requests", "endpoint", PROTECTED_PATH, "result", "challenged");
            assertThat(after).isEqualTo(before + 1.0);
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private double counterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private Double gaugeValue(String name, String... tags) {
        var gauge = meterRegistry.find(name).tags(tags).gauge();
        return gauge != null ? gauge.value() : null;
    }

    private String buildValidAuthHeader() {
        byte[] preimage = new byte[32];
        new SecureRandom().nextBytes(preimage);
        byte[] paymentHash = sha256(preimage);

        byte[] tokenId = new byte[32];
        new SecureRandom().nextBytes(tokenId);

        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
        Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimage);

        return "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    private String buildInvalidAuthHeader() {
        // Mint a macaroon with a valid structure but use a WRONG preimage
        // so the preimage does not hash to the payment hash in the identifier
        byte[] realPreimage = new byte[32];
        new SecureRandom().nextBytes(realPreimage);
        byte[] paymentHash = sha256(realPreimage);

        byte[] tokenId = new byte[32];
        new SecureRandom().nextBytes(tokenId);

        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
        Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

        // Use a different preimage that won't match the payment hash
        byte[] wrongPreimage = new byte[32];
        new SecureRandom().nextBytes(wrongPreimage);
        String wrongPreimageHex = HEX.formatHex(wrongPreimage);

        return "L402 " + macaroonBase64 + ":" + wrongPreimageHex;
    }

    private static Invoice createStubInvoice() {
        byte[] paymentHash = new byte[32];
        new SecureRandom().nextBytes(paymentHash);
        Instant now = Instant.now();
        return new Invoice(
                paymentHash,
                "lnbc210n1p0testmetricsinvoice",
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
        private volatile boolean throwOnHealthCheck = false;
        private volatile Invoice nextInvoice;

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        void setThrowOnHealthCheck(boolean throwOnHealthCheck) {
            this.throwOnHealthCheck = throwOnHealthCheck;
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
            if (throwOnHealthCheck) {
                throw new RuntimeException("Lightning backend health check failed");
            }
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
            return new GenerationResult(new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(rootKey.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.l402.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            return new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(rootKey.clone());
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
