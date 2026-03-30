package com.greenharborlabs.paygate.spring;

import static com.greenharborlabs.paygate.spring.PaygateTestSupport.createStubInvoice;
import static com.greenharborlabs.paygate.spring.PaygateTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.EvictionReason;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.InMemoryTestCredentialStore;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.InMemoryTestRootKeyStore;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.StubLightningBackend;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * TDD tests for L402 Micrometer metrics integration.
 *
 * <p>Verifies that the {@link PaygateSecurityFilter} records the correct Micrometer counters and
 * gauges when processing requests to protected endpoints. These tests will FAIL until T094
 * integrates metrics recording into the filter.
 *
 * <p>Metrics under test:
 *
 * <ul>
 *   <li>{@code paygate.requests} counter with {@code endpoint} and {@code result} tags
 *   <li>{@code paygate.revenue.sats} counter with {@code endpoint} tag
 *   <li>{@code paygate.invoices.created} counter with {@code endpoint} tag
 *   <li>{@code paygate.invoices.settled} counter with {@code endpoint} tag
 *   <li>{@code paygate.credentials.active} gauge
 *   <li>{@code paygate.lightning.healthy} gauge
 * </ul>
 */
@SpringBootTest(classes = PaygateMetricsTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402 Micrometer Metrics")
class PaygateMetricsTest {

  private static final byte[] ROOT_KEY = new byte[32];
  private static final HexFormat HEX = HexFormat.of();
  private static final long PRICE_SATS = 21;
  private static final String PROTECTED_PATH = "/api/paid";
  private static final String PUBLIC_PATH = "/api/free";
  private static final String PARAMETERIZED_PATH_PATTERN = "/api/items/{id}";

  static {
    new SecureRandom().nextBytes(ROOT_KEY);
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private LightningBackend lightningBackend;

  @Autowired private CredentialStore credentialStore;

  @Autowired private PaygateMetrics paygateMetrics;

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
    PaygateEndpointRegistry paygateEndpointRegistry() {
      var registry = new PaygateEndpointRegistry();
      registry.register(
          new PaygateEndpointConfig(
              "GET", PROTECTED_PATH, PRICE_SATS, 600, "Paid endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig(
              "GET", PARAMETERIZED_PATH_PATTERN, 10, 600, "Items endpoint", "", ""));
      return registry;
    }

    @Bean
    PaygateMetrics paygateMetrics(
        MeterRegistry meterRegistry,
        CredentialStore credentialStore,
        LightningBackend lightningBackend) {
      return new PaygateMetrics(meterRegistry, credentialStore, lightningBackend);
    }

    @Bean
    PaygateSecurityFilter paygateSecurityFilter(
        PaygateEndpointRegistry endpointRegistry,
        LightningBackend lightningBackendBean,
        RootKeyStore rootKeyStore,
        CredentialStore credentialStore,
        List<CaveatVerifier> caveatVerifiers,
        PaygateMetrics paygateMetrics,
        ApplicationContext applicationContext) {
      var properties = new PaygateProperties();
      properties.setServiceName("test-service");
      var validator =
          new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
      var l402Protocol = new L402Protocol(validator, "test-service");
      var challengeService =
          new PaygateChallengeService(
              rootKeyStore,
              lightningBackendBean,
              properties,
              applicationContext,
              null,
              null,
              null,
              null);
      return new PaygateSecurityFilter(
          endpointRegistry,
          List.of(l402Protocol),
          challengeService,
          "test-service",
          null,
          paygateMetrics,
          null,
          null);
    }

    @Bean
    TestController testController() {
      return new TestController();
    }
  }

  @RestController
  static class TestController {

    @PaymentRequired(priceSats = 21, description = "Paid endpoint")
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
    ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
  }

  // -----------------------------------------------------------------------
  // Challenge scenario (402) — no auth header on protected endpoint
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("when a 402 challenge is issued")
  class ChallengeMetrics {

    @Test
    @DisplayName("increments paygate.requests counter with result=challenged and protocol=all")
    void incrementsRequestsChallengedCounter() throws Exception {
      double before =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "challenged",
              "protocol",
              "all");

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "challenged",
              "protocol",
              "all");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("increments paygate.invoices.created counter with protocol=all")
    void incrementsInvoicesCreatedCounter() throws Exception {
      double before =
          counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH, "protocol", "all");

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after =
          counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH, "protocol", "all");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("does not increment paygate.revenue.sats counter")
    void doesNotIncrementRevenueCounter() throws Exception {
      double before = counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH);

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after = counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("does not increment paygate.invoices.settled counter")
    void doesNotIncrementInvoicesSettledCounter() throws Exception {
      double before = counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH);

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after = counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("does not increment paygate.requests counter with result=passed")
    void doesNotIncrementPassedCounter() throws Exception {
      double before =
          counterValue("paygate.requests", "endpoint", PROTECTED_PATH, "result", "passed");

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after =
          counterValue("paygate.requests", "endpoint", PROTECTED_PATH, "result", "passed");
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
    @DisplayName("increments paygate.requests counter with result=passed and protocol=L402")
    void incrementsRequestsPassedCounter() throws Exception {
      double before =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "passed",
              "protocol",
              "L402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      double after =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "passed",
              "protocol",
              "L402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("increments paygate.revenue.sats counter by the endpoint price with protocol=L402")
    void incrementsRevenueSatsCounter() throws Exception {
      double before =
          counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH, "protocol", "L402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      double after =
          counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH, "protocol", "L402");
      assertThat(after).isEqualTo(before + PRICE_SATS);
    }

    @Test
    @DisplayName("increments paygate.invoices.settled counter with protocol=L402")
    void incrementsInvoicesSettledCounter() throws Exception {
      double before =
          counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH, "protocol", "L402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      double after =
          counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH, "protocol", "L402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("does not increment paygate.invoices.created counter")
    void doesNotIncrementInvoicesCreatedCounter() throws Exception {
      double before = counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH);

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      double after = counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("does not increment paygate.requests counter with result=challenged")
    void doesNotIncrementChallengedCounter() throws Exception {
      double before =
          counterValue("paygate.requests", "endpoint", PROTECTED_PATH, "result", "challenged");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      double after =
          counterValue("paygate.requests", "endpoint", PROTECTED_PATH, "result", "challenged");
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
    @DisplayName("increments paygate.requests counter with result=rejected and protocol=L402")
    void incrementsRequestsRejectedCounter() throws Exception {
      double before =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "rejected",
              "protocol",
              "L402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildInvalidAuthHeader()))
          .andExpect(status().isPaymentRequired());

      double after =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "rejected",
              "protocol",
              "L402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("does not increment paygate.revenue.sats counter")
    void doesNotIncrementRevenueCounter() throws Exception {
      double before = counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH);

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildInvalidAuthHeader()))
          .andExpect(status().isPaymentRequired());

      double after = counterValue("paygate.revenue.sats", "endpoint", PROTECTED_PATH);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("does not increment paygate.invoices.created counter")
    void doesNotIncrementInvoicesCreatedCounter() throws Exception {
      double before = counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH);

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildInvalidAuthHeader()))
          .andExpect(status().isPaymentRequired());

      double after = counterValue("paygate.invoices.created", "endpoint", PROTECTED_PATH);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("does not increment paygate.invoices.settled counter")
    void doesNotIncrementInvoicesSettledCounter() throws Exception {
      double before = counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH);

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildInvalidAuthHeader()))
          .andExpect(status().isPaymentRequired());

      double after = counterValue("paygate.invoices.settled", "endpoint", PROTECTED_PATH);
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
    @DisplayName("paygate.credentials.active gauge reflects credential store active count")
    void credentialsActiveGaugeReflectsStoreCount() throws Exception {
      // Trigger a request so the filter has been invoked at least once
      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      Double gaugeValue = gaugeValue("paygate.credentials.active");
      assertThat(gaugeValue).isNotNull();
      assertThat(gaugeValue).isEqualTo((double) credentialStore.activeCount());
    }

    @Test
    @DisplayName("paygate.lightning.healthy gauge reports 1.0 when Lightning is healthy")
    void lightningHealthyGaugeReportsOne() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      paygateMetrics.refreshHealth();

      // Trigger a request so gauges are registered
      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      Double gaugeValue = gaugeValue("paygate.lightning.healthy");
      assertThat(gaugeValue).isNotNull();
      assertThat(gaugeValue).isEqualTo(1.0);
    }

    @Test
    @DisplayName("paygate.lightning.healthy gauge reports 0.0 when Lightning is unhealthy")
    void lightningHealthyGaugeReportsZero() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(false);
      paygateMetrics.refreshHealth();

      // Trigger a request — will get 503 but gauge should still be set
      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isServiceUnavailable());

      Double gaugeValue = gaugeValue("paygate.lightning.healthy");
      assertThat(gaugeValue).isNotNull();
      assertThat(gaugeValue).isEqualTo(0.0);
    }

    @Test
    @DisplayName("paygate.lightning.healthy gauge reports 0.0 when isHealthy() throws exception")
    void lightningHealthyGaugeReportsZeroOnException() {
      ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(true);
      paygateMetrics.refreshHealth();

      Double gaugeValue = gaugeValue("paygate.lightning.healthy");
      assertThat(gaugeValue).isNotNull();
      assertThat(gaugeValue).isEqualTo(0.0);
    }

    @Test
    @DisplayName("paygate.lightning.healthy gauge recovers to 1.0 after exception clears")
    void lightningHealthyGaugeRecoversAfterException() {
      ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(true);
      paygateMetrics.refreshHealth();

      Double duringException = gaugeValue("paygate.lightning.healthy");
      assertThat(duringException).isNotNull();
      assertThat(duringException).isEqualTo(0.0);

      // Backend recovers
      ((StubLightningBackend) lightningBackend).setThrowOnHealthCheck(false);
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      paygateMetrics.refreshHealth();

      Double afterRecovery = gaugeValue("paygate.lightning.healthy");
      assertThat(afterRecovery).isNotNull();
      assertThat(afterRecovery).isEqualTo(1.0);
    }

    @Test
    @DisplayName("gauge supplier never calls isHealthy() — reads from cached field only")
    void lightningHealthyGaugeNeverCallsIsHealthyDuringGaugeScrape() {
      // Ensure known state and reset call count after any prior calls
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      paygateMetrics.refreshHealth();
      ((StubLightningBackend) lightningBackend).resetIsHealthyCallCount();

      // Read the gauge multiple times — should NOT trigger isHealthy()
      for (int i = 0; i < 10; i++) {
        gaugeValue("paygate.lightning.healthy");
      }

      assertThat(((StubLightningBackend) lightningBackend).getIsHealthyCallCount())
          .as("Gauge reads must not call isHealthy() on the backend")
          .isZero();
    }
  }

  // -----------------------------------------------------------------------
  // Cache eviction metrics
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("cache eviction metrics")
  class CacheEvictionMetrics {

    @Test
    @DisplayName(
        "recordCacheEviction(EXPIRED) increments paygate.cache.evictions with reason=expired")
    void incrementsExpiredEvictionCounter() {
      double before = counterValue("paygate.cache.evictions", "reason", "expired");

      paygateMetrics.recordCacheEviction(EvictionReason.EXPIRED);

      double after = counterValue("paygate.cache.evictions", "reason", "expired");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName(
        "recordCacheEviction(CAPACITY) increments paygate.cache.evictions with reason=capacity")
    void incrementsCapacityEvictionCounter() {
      double before = counterValue("paygate.cache.evictions", "reason", "capacity");

      paygateMetrics.recordCacheEviction(EvictionReason.CAPACITY);

      double after = counterValue("paygate.cache.evictions", "reason", "capacity");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName(
        "recordCacheEviction(REVOKED) increments paygate.cache.evictions with reason=revoked")
    void incrementsRevokedEvictionCounter() {
      double before = counterValue("paygate.cache.evictions", "reason", "revoked");

      paygateMetrics.recordCacheEviction(EvictionReason.REVOKED);

      double after = counterValue("paygate.cache.evictions", "reason", "revoked");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("each reason tag is independent — incrementing one does not affect others")
    void reasonTagsAreIndependent() {
      double expiredBefore = counterValue("paygate.cache.evictions", "reason", "expired");
      double capacityBefore = counterValue("paygate.cache.evictions", "reason", "capacity");
      double revokedBefore = counterValue("paygate.cache.evictions", "reason", "revoked");

      paygateMetrics.recordCacheEviction(EvictionReason.EXPIRED);
      paygateMetrics.recordCacheEviction(EvictionReason.EXPIRED);
      paygateMetrics.recordCacheEviction(EvictionReason.CAPACITY);

      assertThat(counterValue("paygate.cache.evictions", "reason", "expired"))
          .isEqualTo(expiredBefore + 2.0);
      assertThat(counterValue("paygate.cache.evictions", "reason", "capacity"))
          .isEqualTo(capacityBefore + 1.0);
      assertThat(counterValue("paygate.cache.evictions", "reason", "revoked"))
          .isEqualTo(revokedBefore);
    }
  }

  // -----------------------------------------------------------------------
  // Caveat verification metrics — timer and rejection counter
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("caveat verification metrics")
  class CaveatVerificationMetrics {

    @Test
    @DisplayName("verify duration timer recorded on successful validation with protocol=l402")
    void verifyDurationTimerRecordedOnSuccess() throws Exception {
      long before = timerCount("paygate.caveats.verify.duration", "protocol", "l402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildValidAuthHeader()))
          .andExpect(status().isOk());

      long after = timerCount("paygate.caveats.verify.duration", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("verify duration timer recorded on rejected validation with protocol=l402")
    void verifyDurationTimerRecordedOnRejection() throws Exception {
      long before = timerCount("paygate.caveats.verify.duration", "protocol", "l402");

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", buildInvalidAuthHeader()))
          .andExpect(status().isPaymentRequired());

      long after = timerCount("paygate.caveats.verify.duration", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("no verify duration timer recorded for non-L402 requests")
    void noTimerForNonL402Requests() throws Exception {
      long before = timerCount("paygate.caveats.verify.duration", "protocol", "l402");

      mockMvc.perform(get(PUBLIC_PATH)).andExpect(status().isOk());

      long after = timerCount("paygate.caveats.verify.duration", "protocol", "l402");
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("no verify duration timer recorded for 402 challenge (no auth header)")
    void noTimerForChallengeRequests() throws Exception {
      long before = timerCount("paygate.caveats.verify.duration", "protocol", "l402");

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      long after = timerCount("paygate.caveats.verify.duration", "protocol", "l402");
      assertThat(after).isEqualTo(before);
    }
  }

  // -----------------------------------------------------------------------
  // Unprotected endpoint — should not increment any L402 counters
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("unprotected endpoint")
  class UnprotectedEndpointMetrics {

    @Test
    @DisplayName("does not increment any paygate.requests counter")
    void doesNotIncrementRequestsCounter() throws Exception {
      double passedBefore =
          counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "passed");
      double challengedBefore =
          counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "challenged");
      double rejectedBefore =
          counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "rejected");

      mockMvc.perform(get(PUBLIC_PATH)).andExpect(status().isOk());

      assertThat(counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "passed"))
          .isEqualTo(passedBefore);
      assertThat(counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "challenged"))
          .isEqualTo(challengedBefore);
      assertThat(counterValue("paygate.requests", "endpoint", PUBLIC_PATH, "result", "rejected"))
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
    @DisplayName(
        "requests to /api/items/1 and /api/items/2 both tag with endpoint=/api/items/{id} and protocol=all")
    void bothConcretePathsUsePatternTag() throws Exception {
      double before =
          counterValue(
              "paygate.requests",
              "endpoint",
              PARAMETERIZED_PATH_PATTERN,
              "result",
              "challenged",
              "protocol",
              "all");

      mockMvc.perform(get("/api/items/1")).andExpect(status().isPaymentRequired());
      mockMvc.perform(get("/api/items/2")).andExpect(status().isPaymentRequired());

      double after =
          counterValue(
              "paygate.requests",
              "endpoint",
              PARAMETERIZED_PATH_PATTERN,
              "result",
              "challenged",
              "protocol",
              "all");
      assertThat(after).isEqualTo(before + 2.0);
    }

    @Test
    @DisplayName("no counter exists tagged with raw path /api/items/1")
    void noCounterForRawPath1() throws Exception {
      mockMvc.perform(get("/api/items/1")).andExpect(status().isPaymentRequired());

      assertThat(
              counterValue(
                  "paygate.requests",
                  "endpoint",
                  "/api/items/1",
                  "result",
                  "challenged",
                  "protocol",
                  "all"))
          .isZero();
    }

    @Test
    @DisplayName("no counter exists tagged with raw path /api/items/2")
    void noCounterForRawPath2() throws Exception {
      mockMvc.perform(get("/api/items/2")).andExpect(status().isPaymentRequired());

      assertThat(
              counterValue(
                  "paygate.requests",
                  "endpoint",
                  "/api/items/2",
                  "result",
                  "challenged",
                  "protocol",
                  "all"))
          .isZero();
    }

    @Test
    @DisplayName(
        "invoices.created counter uses pattern tag for parameterized paths with protocol=all")
    void invoicesCreatedUsesPatternTag() throws Exception {
      double before =
          counterValue(
              "paygate.invoices.created",
              "endpoint",
              PARAMETERIZED_PATH_PATTERN,
              "protocol",
              "all");

      mockMvc.perform(get("/api/items/1")).andExpect(status().isPaymentRequired());

      double after =
          counterValue(
              "paygate.invoices.created",
              "endpoint",
              PARAMETERIZED_PATH_PATTERN,
              "protocol",
              "all");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("existing exact-path tests still use endpoint=/api/paid with protocol=all")
    void exactPathStillWorks() throws Exception {
      double before =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "challenged",
              "protocol",
              "all");

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

      double after =
          counterValue(
              "paygate.requests",
              "endpoint",
              PROTECTED_PATH,
              "result",
              "challenged",
              "protocol",
              "all");
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

  private long timerCount(String name, String... tags) {
    var timer = meterRegistry.find(name).tags(tags).timer();
    return timer != null ? timer.count() : 0L;
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
}
