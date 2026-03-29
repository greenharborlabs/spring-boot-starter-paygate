package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pure unit tests for {@link PaygateChallengeService}, verifying all paths independently of the
 * servlet filter.
 */
@DisplayName("PaygateChallengeService")
class PaygateChallengeServiceTest {

  private static final String SERVICE_NAME = "test-service";
  private static final long PRICE_SATS = 50;
  private static final long TIMEOUT_SECONDS = 600;
  private static final String DESCRIPTION = "Test endpoint";
  private static final String BOLT11 = "lnbc500n1p0testinvoice";

  private LightningBackend lightningBackend;
  private PaygateProperties properties;
  private ApplicationContext applicationContext;
  private MockHttpServletRequest request;
  private PaygateEndpointConfig config;

  @BeforeEach
  void setUp() {
    lightningBackend = mock(LightningBackend.class);
    properties = new PaygateProperties();
    properties.setServiceName(SERVICE_NAME);
    applicationContext = mock(ApplicationContext.class);
    request = new MockHttpServletRequest("GET", "/api/protected");
    request.setRemoteAddr("127.0.0.1");

    config =
        new PaygateEndpointConfig(
            "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "");
  }

  // -----------------------------------------------------------------------
  // Happy path
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("happy path")
  class HappyPath {

    @Test
    @DisplayName("createChallenge returns valid ChallengeContext with correct fields")
    void createChallengeReturnsValidContext() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, config);

      assertThat(ctx).isNotNull();
      assertThat(ctx.paymentHash()).isNotNull().hasSize(32);
      assertThat(ctx.tokenId()).isNotNull().isNotEmpty();
      // tokenId should be a valid hex string (64 hex chars for 32 bytes)
      assertThat(ctx.tokenId()).matches("[0-9a-f]{64}");
      assertThat(ctx.bolt11Invoice()).isEqualTo(BOLT11);
      assertThat(ctx.priceSats()).isEqualTo(PRICE_SATS);
      assertThat(ctx.description()).isEqualTo(DESCRIPTION);
      assertThat(ctx.serviceName()).isEqualTo(SERVICE_NAME);
      assertThat(ctx.timeoutSeconds()).isEqualTo(TIMEOUT_SECONDS);
      assertThat(ctx.rootKeyBytes()).isNotNull().hasSize(32);
      assertThat(ctx.opaque()).isNull();
      assertThat(ctx.digest()).isNull();
    }

    @Test
    @DisplayName("createChallenge passes capability through to ChallengeContext")
    void passesCapabilityThrough() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateEndpointConfig configWithCapability =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "search");

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, configWithCapability);

      assertThat(ctx.capability()).isEqualTo("search");
    }
  }

  // -----------------------------------------------------------------------
  // Rate limiting
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("rate limiting")
  class RateLimiting {

    @Test
    @DisplayName("throws PaygateRateLimitedException when rate limiter denies request")
    void throwsRateLimitedExceptionWhenDenied() {
      when(lightningBackend.isHealthy()).thenReturn(true);

      PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString())).thenReturn(false);

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              rateLimiter,
              null,
              null);

      assertThatThrownBy(() -> service.createChallenge(request, config))
          .isInstanceOf(PaygateRateLimitedException.class);

      verify(lightningBackend, never()).createInvoice(anyLong(), anyString());
    }

    @Test
    @DisplayName("succeeds without rate limiter set")
    void succeedsWithoutRateLimiter() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateChallengeService service = createService(createTrackingRootKeyStore());

      ChallengeContext ctx = service.createChallenge(request, config);
      assertThat(ctx).isNotNull();
      assertThat(ctx.bolt11Invoice()).isEqualTo(BOLT11);
    }
  }

  // -----------------------------------------------------------------------
  // Lightning backend health
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("Lightning backend health")
  class LightningHealth {

    @Test
    @DisplayName("throws PaygateLightningUnavailableException when backend is unhealthy")
    void throwsWhenLightningUnhealthy() {
      when(lightningBackend.isHealthy()).thenReturn(false);

      PaygateChallengeService service = createService(createTrackingRootKeyStore());

      assertThatThrownBy(() -> service.createChallenge(request, config))
          .isInstanceOf(PaygateLightningUnavailableException.class)
          .hasMessageContaining("health check failed");

      verify(lightningBackend, never()).createInvoice(anyLong(), anyString());
    }
  }

  // -----------------------------------------------------------------------
  // Invoice creation failure
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("invoice creation failure")
  class InvoiceCreationFailure {

    @Test
    @DisplayName(
        "wraps RuntimeException from createInvoice in PaygateLightningUnavailableException")
    void wrapsInvoiceCreationException() {
      when(lightningBackend.isHealthy()).thenReturn(true);
      RuntimeException cause = new RuntimeException("Connection refused");
      when(lightningBackend.createInvoice(anyLong(), anyString())).thenThrow(cause);

      PaygateChallengeService service = createService(createTrackingRootKeyStore());

      assertThatThrownBy(() -> service.createChallenge(request, config))
          .isInstanceOf(PaygateLightningUnavailableException.class)
          .hasCauseReference(cause);
    }
  }

  // -----------------------------------------------------------------------
  // Pricing strategy
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("pricing strategy")
  class PricingStrategy {

    @Test
    @DisplayName("uses pricing strategy bean when configured and found")
    void usesPricingStrategyBean() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygatePricingStrategy strategy = (req, defaultPrice) -> 42L;
      when(applicationContext.getBean("myStrategy", PaygatePricingStrategy.class))
          .thenReturn(strategy);

      PaygateEndpointConfig configWithStrategy =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "myStrategy", "");

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, configWithStrategy);

      assertThat(ctx.priceSats()).isEqualTo(42L);
      verify(lightningBackend).createInvoice(eq(42L), anyString());
    }

    @Test
    @DisplayName("caches pricing strategy bean -- getBean called only once for repeated lookups")
    void cachesPricingStrategyBean() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygatePricingStrategy strategy = (req, defaultPrice) -> 42L;
      when(applicationContext.getBean("cachedStrategy", PaygatePricingStrategy.class))
          .thenReturn(strategy);

      PaygateEndpointConfig configWithStrategy =
          new PaygateEndpointConfig(
              "GET",
              "/api/protected",
              PRICE_SATS,
              TIMEOUT_SECONDS,
              DESCRIPTION,
              "cachedStrategy",
              "");

      PaygateChallengeService service = createService(createTrackingRootKeyStore());

      // First call -- populates cache
      ChallengeContext ctx1 = service.createChallenge(request, configWithStrategy);
      assertThat(ctx1.priceSats()).isEqualTo(42L);

      // Second call -- should use cache, not getBean again
      ChallengeContext ctx2 = service.createChallenge(request, configWithStrategy);
      assertThat(ctx2.priceSats()).isEqualTo(42L);

      verify(applicationContext, times(1)).getBean("cachedStrategy", PaygatePricingStrategy.class);
    }

    @Test
    @DisplayName("falls back to static price when strategy bean not found")
    void fallsBackToStaticPriceWhenBeanMissing() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      when(applicationContext.getBean("missing", PaygatePricingStrategy.class))
          .thenThrow(
              new org.springframework.beans.factory.NoSuchBeanDefinitionException("missing"));

      PaygateEndpointConfig configWithStrategy =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "missing", "");

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, configWithStrategy);

      assertThat(ctx.priceSats()).isEqualTo(PRICE_SATS);
    }
  }

  // -----------------------------------------------------------------------
  // Test preimage (via opaque map)
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("test preimage")
  class TestPreimage {

    @Test
    @DisplayName("opaque map includes hex-encoded test_preimage when invoice has one")
    void includesPreimageInOpaqueWhenPresent() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);

      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(preimage));

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, config);

      assertThat(ctx.opaque()).isNotNull();
      assertThat(ctx.opaque()).containsKey("test_preimage");
      assertThat(ctx.opaque().get("test_preimage")).isEqualTo(HexFormat.of().formatHex(preimage));
    }

    @Test
    @DisplayName("opaque is null when invoice has no preimage")
    void noOpaqueWhenInvoiceLacksPreimage() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateChallengeService service = createService(createTrackingRootKeyStore());
      ChallengeContext ctx = service.createChallenge(request, config);

      assertThat(ctx.opaque()).isNull();
    }
  }

  // -----------------------------------------------------------------------
  // Root key zeroization
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("root key zeroization")
  class RootKeyZeroization {

    @Test
    @DisplayName("SensitiveBytes is destroyed after successful createChallenge")
    void sensitiveBytesDestroyedAfterSuccess() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      ZeroizationTrackingRootKeyStore trackingStore = new ZeroizationTrackingRootKeyStore();
      PaygateChallengeService service = createService(trackingStore);
      service.createChallenge(request, config);

      assertThat(trackingStore.lastSensitiveBytes).isNotNull();
      assertThat(trackingStore.lastSensitiveBytes.isDestroyed())
          .as("SensitiveBytes must be destroyed after createChallenge completes")
          .isTrue();
    }

    @Test
    @DisplayName("SensitiveBytes is destroyed even when createInvoice throws")
    void sensitiveBytesDestroyedOnFailure() {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenThrow(new RuntimeException("Lightning exploded"));

      ZeroizationTrackingRootKeyStore trackingStore = new ZeroizationTrackingRootKeyStore();
      PaygateChallengeService service = createService(trackingStore);

      assertThatThrownBy(() -> service.createChallenge(request, config))
          .isInstanceOf(PaygateLightningUnavailableException.class);

      assertThat(trackingStore.lastSensitiveBytes).isNotNull();
      assertThat(trackingStore.lastSensitiveBytes.isDestroyed())
          .as("SensitiveBytes must be destroyed even when createInvoice throws")
          .isTrue();
    }
  }

  // -----------------------------------------------------------------------
  // Earnings tracker
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("earnings tracker")
  class EarningsTrackerTests {

    @Test
    @DisplayName("recordInvoiceCreated is called on happy path")
    void recordInvoiceCreatedCalledOnSuccess() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateEarningsTracker earningsTracker = mock(PaygateEarningsTracker.class);
      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              earningsTracker,
              null,
              null,
              null);

      service.createChallenge(request, config);

      verify(earningsTracker).recordInvoiceCreated();
    }

    @Test
    @DisplayName("recordInvoiceCreated is not called when earningsTracker is not set")
    void noEarningsTrackerDoesNotFail() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateChallengeService service = createService(createTrackingRootKeyStore());

      ChallengeContext ctx = service.createChallenge(request, config);
      assertThat(ctx).isNotNull();
    }
  }

  // -----------------------------------------------------------------------
  // ClientIpResolver delegation
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("ClientIpResolver delegation")
  class ClientIpDelegation {

    @Test
    @DisplayName("trusted proxy: rightmost untrusted XFF entry is used for rate limiting")
    void trustedProxyUsesRightmostUntrustedXffEntry() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

      ClientIpResolver resolver = new ClientIpResolver(true, List.of("10.0.0.2"));
      request.addHeader("X-Forwarded-For", "spoofed, 203.0.113.50, 10.0.0.2");
      request.setRemoteAddr("10.0.0.2");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              rateLimiter,
              resolver,
              null);

      service.createChallenge(request, config);
      verify(rateLimiter).tryAcquire("203.0.113.50");
    }

    @Test
    @DisplayName("null ClientIpResolver falls back to request.getRemoteAddr()")
    void nullResolverFallsBackToRemoteAddr() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

      request.setRemoteAddr("192.168.1.1");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              rateLimiter,
              null,
              null);

      service.createChallenge(request, config);
      verify(rateLimiter).tryAcquire("192.168.1.1");
    }

    @Test
    @DisplayName("configured resolver with no XFF header falls back to remoteAddr")
    void noXffHeaderFallsBackToRemoteAddr() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

      ClientIpResolver resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
      request.setRemoteAddr("192.168.1.1");
      // No XFF header added

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              rateLimiter,
              resolver,
              null);

      service.createChallenge(request, config);
      verify(rateLimiter).tryAcquire("192.168.1.1");
    }

    @Test
    @DisplayName("delegates to ClientIpResolver.resolve() when resolver is present")
    void delegatesToResolverWhenPresent() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateRateLimiter rateLimiter = mock(PaygateRateLimiter.class);
      when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

      ClientIpResolver resolver = mock(ClientIpResolver.class);
      when(resolver.resolve(request)).thenReturn("10.0.0.1");

      request.setRemoteAddr("127.0.0.1");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              rateLimiter,
              resolver,
              null);

      service.createChallenge(request, config);
      verify(resolver).resolve(request);
      verify(rateLimiter).tryAcquire("10.0.0.1");
    }
  }

  // -----------------------------------------------------------------------
  // Capability cache population
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("capability cache population")
  class CapabilityCachePopulation {

    @Test
    @DisplayName("stores capability in cache after successful challenge creation")
    void storesCapabilityInCacheAfterSuccess() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      CapabilityCache capabilityCache = mock(CapabilityCache.class);
      PaygateEndpointConfig configWithCapability =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "search");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              null,
              null,
              capabilityCache);

      ChallengeContext ctx = service.createChallenge(request, configWithCapability);

      verify(capabilityCache).store(eq(ctx.tokenId()), eq("search"), eq(TIMEOUT_SECONDS));
    }

    @Test
    @DisplayName("does not store in cache when capability is empty")
    void doesNotStoreWhenCapabilityEmpty() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      CapabilityCache capabilityCache = mock(CapabilityCache.class);

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              null,
              null,
              capabilityCache);

      service.createChallenge(request, config);

      verify(capabilityCache, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("challenge completes normally when capabilityCache is null")
    void completesNormallyWhenCacheNull() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      PaygateEndpointConfig configWithCapability =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "search");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              null,
              null,
              null);

      ChallengeContext ctx = service.createChallenge(request, configWithCapability);
      assertThat(ctx).isNotNull();
      assertThat(ctx.capability()).isEqualTo("search");
    }

    @Test
    @DisplayName("cache store failure is swallowed and challenge still succeeds")
    void cacheStoreFailureDoesNotBreakChallenge() throws Exception {
      when(lightningBackend.isHealthy()).thenReturn(true);
      when(lightningBackend.createInvoice(anyLong(), anyString()))
          .thenReturn(createStubInvoice(null));

      CapabilityCache capabilityCache = mock(CapabilityCache.class);
      org.mockito.Mockito.doThrow(new RuntimeException("cache explosion"))
          .when(capabilityCache)
          .store(anyString(), anyString(), anyLong());

      PaygateEndpointConfig configWithCapability =
          new PaygateEndpointConfig(
              "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "search");

      PaygateChallengeService service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              applicationContext,
              null,
              null,
              null,
              capabilityCache);

      ChallengeContext ctx = service.createChallenge(request, configWithCapability);
      assertThat(ctx).isNotNull();
      assertThat(ctx.capability()).isEqualTo("search");
    }
  }

  // -----------------------------------------------------------------------
  // sanitizeBolt11ForHeader
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("sanitizeBolt11ForHeader")
  class SanitizeBolt11 {

    @Test
    @DisplayName("returns empty string for null input")
    void returnsEmptyForNull() {
      assertThat(PaygateChallengeService.sanitizeBolt11ForHeader(null)).isEqualTo("");
    }

    @Test
    @DisplayName("returns empty string for empty input")
    void returnsEmptyForEmpty() {
      assertThat(PaygateChallengeService.sanitizeBolt11ForHeader("")).isEqualTo("");
    }

    @Test
    @DisplayName("rejects double quote with IllegalArgumentException")
    void rejectsDoubleQuote() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("lnbc\"test"))
          .withMessageContaining("illegal character at index 4");
    }

    @Test
    @DisplayName("rejects CR and LF with IllegalArgumentException")
    void rejectsCrLf() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("lnbc\r\ntest"))
          .withMessageContaining("illegal character at index 4");
    }

    @Test
    @DisplayName("rejects null byte (0x00)")
    void rejectsNullByte() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("lnbc\0test"))
          .withMessageContaining("illegal character at index 4")
          .withMessageContaining("0x0");
    }

    @Test
    @DisplayName("rejects tab character (0x09)")
    void rejectsTab() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("lnbc\ttest"))
          .withMessageContaining("illegal character at index 4")
          .withMessageContaining("0x9");
    }

    @Test
    @DisplayName("rejects DEL character (0x7F)")
    void rejectsDel() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("lnbc\u007Ftest"))
          .withMessageContaining("illegal character at index 4")
          .withMessageContaining("0x7f");
    }

    @Test
    @DisplayName("rejects mid-range control character (0x1A SUB)")
    void rejectsMidRangeControl() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> PaygateChallengeService.sanitizeBolt11ForHeader("abc\u001Adef"))
          .withMessageContaining("illegal character at index 3")
          .withMessageContaining("0x1a");
    }

    @Test
    @DisplayName("passes clean input through unchanged")
    void passesCleanInputUnchanged() {
      String clean = "lnbc500n1p0testinvoice";
      assertThat(PaygateChallengeService.sanitizeBolt11ForHeader(clean)).isEqualTo(clean);
    }
  }

  // -----------------------------------------------------------------------
  // Constructor validation
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("accepts null properties and defaults service name")
    void acceptsNullProperties() {
      var service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              null,
              applicationContext,
              null,
              null,
              null,
              null);
      assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("accepts null applicationContext")
    void acceptsNullApplicationContext() {
      var service =
          new PaygateChallengeService(
              createTrackingRootKeyStore(),
              lightningBackend,
              properties,
              null,
              null,
              null,
              null,
              null);
      assertThat(service).isNotNull();
    }
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private PaygateChallengeService createService(RootKeyStore rootKeyStore) {
    return new PaygateChallengeService(
        rootKeyStore, lightningBackend, properties, applicationContext, null, null, null, null);
  }

  private static ZeroizationTrackingRootKeyStore createTrackingRootKeyStore() {
    return new ZeroizationTrackingRootKeyStore();
  }

  private static Invoice createStubInvoice(byte[] preimage) {
    byte[] paymentHash = new byte[32];
    new SecureRandom().nextBytes(paymentHash);
    Instant now = Instant.now();
    return new Invoice(
        paymentHash,
        BOLT11,
        PRICE_SATS,
        "Test invoice",
        InvoiceStatus.PENDING,
        preimage,
        now,
        now.plus(1, ChronoUnit.HOURS));
  }

  /**
   * RootKeyStore that captures the {@link SensitiveBytes} reference so tests can verify {@code
   * isDestroyed()} after the service completes.
   */
  static class ZeroizationTrackingRootKeyStore implements RootKeyStore {

    volatile SensitiveBytes lastSensitiveBytes;

    @Override
    public GenerationResult generateRootKey() {
      byte[] rawKey = new byte[32];
      new SecureRandom().nextBytes(rawKey);

      SensitiveBytes sensitiveBytes = new SensitiveBytes(rawKey);
      this.lastSensitiveBytes = sensitiveBytes;

      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      return new GenerationResult(sensitiveBytes, tokenId);
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      return null;
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
      // no-op
    }
  }
}
