package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.SensitiveBytes;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

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

/**
 * Pure unit tests for {@link L402ChallengeService}, verifying all paths
 * independently of the servlet filter.
 */
@DisplayName("L402ChallengeService")
class L402ChallengeServiceTest {

    private static final String SERVICE_NAME = "test-service";
    private static final long PRICE_SATS = 50;
    private static final long TIMEOUT_SECONDS = 600;
    private static final String DESCRIPTION = "Test endpoint";
    private static final String BOLT11 = "lnbc500n1p0testinvoice";

    private LightningBackend lightningBackend;
    private L402Properties properties;
    private ApplicationContext applicationContext;
    private MockHttpServletRequest request;
    private L402EndpointConfig config;

    @BeforeEach
    void setUp() {
        lightningBackend = mock(LightningBackend.class);
        properties = new L402Properties();
        properties.setServiceName(SERVICE_NAME);
        applicationContext = mock(ApplicationContext.class);
        request = new MockHttpServletRequest("GET", "/api/protected");
        request.setRemoteAddr("127.0.0.1");

        config = new L402EndpointConfig("GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "");
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("createChallenge returns valid result with non-null fields")
        void createChallengeReturnsValidResult() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, config);

            assertThat(result).isNotNull();
            assertThat(result.macaroonBase64()).isNotNull().isNotEmpty();
            // Verify it is valid base64
            byte[] decoded = Base64.getDecoder().decode(result.macaroonBase64());
            assertThat(decoded).isNotEmpty();

            assertThat(result.bolt11()).isEqualTo(BOLT11);
            assertThat(result.wwwAuthenticateHeader())
                    .isNotNull()
                    .contains("L402 version=\"0\"")
                    .contains("token=")
                    .contains("macaroon=")
                    .contains("invoice=");
            assertThat(result.priceSats()).isEqualTo(PRICE_SATS);
            assertThat(result.description()).isEqualTo(DESCRIPTION);
            assertThat(result.testPreimage()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // Rate limiting
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("rate limiting")
    class RateLimiting {

        @Test
        @DisplayName("throws L402RateLimitedException when rate limiter denies request")
        void throwsRateLimitedExceptionWhenDenied() {
            when(lightningBackend.isHealthy()).thenReturn(true);

            L402RateLimiter rateLimiter = mock(L402RateLimiter.class);
            when(rateLimiter.tryAcquire(anyString())).thenReturn(false);

            L402ChallengeService service = new L402ChallengeService(
                    createTrackingRootKeyStore(), lightningBackend, properties, applicationContext, null, rateLimiter);

            assertThatThrownBy(() -> service.createChallenge(request, config))
                    .isInstanceOf(L402RateLimitedException.class);

            verify(lightningBackend, never()).createInvoice(anyLong(), anyString());
        }

        @Test
        @DisplayName("succeeds without rate limiter set")
        void succeedsWithoutRateLimiter() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            // No rateLimiter set

            L402ChallengeResult result = service.createChallenge(request, config);
            assertThat(result).isNotNull();
            assertThat(result.macaroonBase64()).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Lightning backend health
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lightning backend health")
    class LightningHealth {

        @Test
        @DisplayName("throws L402LightningUnavailableException when backend is unhealthy")
        void throwsWhenLightningUnhealthy() {
            when(lightningBackend.isHealthy()).thenReturn(false);

            L402ChallengeService service = createService(createTrackingRootKeyStore());

            assertThatThrownBy(() -> service.createChallenge(request, config))
                    .isInstanceOf(L402LightningUnavailableException.class)
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
        @DisplayName("wraps RuntimeException from createInvoice in L402LightningUnavailableException")
        void wrapsInvoiceCreationException() {
            when(lightningBackend.isHealthy()).thenReturn(true);
            RuntimeException cause = new RuntimeException("Connection refused");
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenThrow(cause);

            L402ChallengeService service = createService(createTrackingRootKeyStore());

            assertThatThrownBy(() -> service.createChallenge(request, config))
                    .isInstanceOf(L402LightningUnavailableException.class)
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
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402PricingStrategy strategy = (req, defaultPrice) -> 42L;
            when(applicationContext.getBean("myStrategy", L402PricingStrategy.class)).thenReturn(strategy);

            L402EndpointConfig configWithStrategy = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "myStrategy", "");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, configWithStrategy);

            assertThat(result.priceSats()).isEqualTo(42L);
            verify(lightningBackend).createInvoice(eq(42L), anyString());
        }

        @Test
        @DisplayName("caches pricing strategy bean — getBean called only once for repeated lookups")
        void cachesPricingStrategyBean() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402PricingStrategy strategy = (req, defaultPrice) -> 42L;
            when(applicationContext.getBean("cachedStrategy", L402PricingStrategy.class)).thenReturn(strategy);

            L402EndpointConfig configWithStrategy = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "cachedStrategy", "");

            L402ChallengeService service = createService(createTrackingRootKeyStore());

            // First call — populates cache
            L402ChallengeResult result1 = service.createChallenge(request, configWithStrategy);
            assertThat(result1.priceSats()).isEqualTo(42L);

            // Second call — should use cache, not getBean again
            L402ChallengeResult result2 = service.createChallenge(request, configWithStrategy);
            assertThat(result2.priceSats()).isEqualTo(42L);

            verify(applicationContext, times(1)).getBean("cachedStrategy", L402PricingStrategy.class);
        }

        @Test
        @DisplayName("falls back to static price when strategy bean not found")
        void fallsBackToStaticPriceWhenBeanMissing() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            when(applicationContext.getBean("missing", L402PricingStrategy.class))
                    .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("missing"));

            L402EndpointConfig configWithStrategy = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "missing", "");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, configWithStrategy);

            assertThat(result.priceSats()).isEqualTo(PRICE_SATS);
        }
    }

    // -----------------------------------------------------------------------
    // Test preimage
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("test preimage")
    class TestPreimage {

        @Test
        @DisplayName("result includes hex-encoded preimage when invoice has one")
        void includesPreimageWhenPresent() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);

            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(preimage));

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, config);

            assertThat(result.testPreimage()).isNotNull();
            assertThat(result.testPreimage()).isEqualTo(HexFormat.of().formatHex(preimage));
        }

        @Test
        @DisplayName("result testPreimage is null when invoice has no preimage")
        void noPreimageWhenInvoiceLacksOne() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, config);

            assertThat(result.testPreimage()).isNull();
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
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            ZeroizationTrackingRootKeyStore trackingStore = new ZeroizationTrackingRootKeyStore();
            L402ChallengeService service = createService(trackingStore);
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
            L402ChallengeService service = createService(trackingStore);

            assertThatThrownBy(() -> service.createChallenge(request, config))
                    .isInstanceOf(L402LightningUnavailableException.class);

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
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402EarningsTracker earningsTracker = mock(L402EarningsTracker.class);
            L402ChallengeService service = new L402ChallengeService(
                    createTrackingRootKeyStore(), lightningBackend, properties, applicationContext, earningsTracker, null);

            service.createChallenge(request, config);

            verify(earningsTracker).recordInvoiceCreated();
        }

        @Test
        @DisplayName("recordInvoiceCreated is not called when earningsTracker is not set")
        void noEarningsTrackerDoesNotFail() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            // No earningsTracker set — should not throw

            L402ChallengeResult result = service.createChallenge(request, config);
            assertThat(result).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // resolveClientIp
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolveClientIp")
    class ResolveClientIp {

        @Test
        @DisplayName("returns XFF header value when trustForwardedHeaders is true")
        void returnsXffWhenTrusted() {
            properties.setTrustForwardedHeaders(true);
            request.addHeader("X-Forwarded-For", "10.0.0.1");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            assertThat(service.resolveClientIp(request)).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("returns first IP from multi-proxy XFF when trustForwardedHeaders is true")
        void returnsFirstIpFromMultiProxyXff() {
            properties.setTrustForwardedHeaders(true);
            request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            assertThat(service.resolveClientIp(request)).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("ignores XFF and returns remoteAddr when trustForwardedHeaders is false")
        void ignoresXffWhenNotTrusted() {
            properties.setTrustForwardedHeaders(false);
            request.addHeader("X-Forwarded-For", "10.0.0.1");
            request.setRemoteAddr("192.168.1.1");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            assertThat(service.resolveClientIp(request)).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("falls back to remoteAddr when XFF is empty and trust is true")
        void fallsBackToRemoteAddrWhenXffEmpty() {
            properties.setTrustForwardedHeaders(true);
            request.addHeader("X-Forwarded-For", "   ");
            request.setRemoteAddr("192.168.1.1");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            assertThat(service.resolveClientIp(request)).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("returns remoteAddr when no XFF header present")
        void returnsRemoteAddrWhenNoXff() {
            properties.setTrustForwardedHeaders(true);
            request.setRemoteAddr("192.168.1.1");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            assertThat(service.resolveClientIp(request)).isEqualTo("192.168.1.1");
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
            assertThat(L402ChallengeService.sanitizeBolt11ForHeader(null)).isEqualTo("");
        }

        @Test
        @DisplayName("returns empty string for empty input")
        void returnsEmptyForEmpty() {
            assertThat(L402ChallengeService.sanitizeBolt11ForHeader("")).isEqualTo("");
        }

        @Test
        @DisplayName("rejects double quote with IllegalArgumentException")
        void rejectsDoubleQuote() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("lnbc\"test"))
                    .withMessageContaining("illegal character at index 4");
        }

        @Test
        @DisplayName("rejects CR and LF with IllegalArgumentException")
        void rejectsCrLf() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("lnbc\r\ntest"))
                    .withMessageContaining("illegal character at index 4");
        }

        @Test
        @DisplayName("rejects null byte (0x00)")
        void rejectsNullByte() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("lnbc\0test"))
                    .withMessageContaining("illegal character at index 4")
                    .withMessageContaining("0x0");
        }

        @Test
        @DisplayName("rejects tab character (0x09)")
        void rejectsTab() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("lnbc\ttest"))
                    .withMessageContaining("illegal character at index 4")
                    .withMessageContaining("0x9");
        }

        @Test
        @DisplayName("rejects DEL character (0x7F)")
        void rejectsDel() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("lnbc\u007Ftest"))
                    .withMessageContaining("illegal character at index 4")
                    .withMessageContaining("0x7f");
        }

        @Test
        @DisplayName("rejects mid-range control character (0x1A SUB)")
        void rejectsMidRangeControl() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> L402ChallengeService.sanitizeBolt11ForHeader("abc\u001Adef"))
                    .withMessageContaining("illegal character at index 3")
                    .withMessageContaining("0x1a");
        }

        @Test
        @DisplayName("passes clean input through unchanged")
        void passesCleanInputUnchanged() {
            String clean = "lnbc500n1p0testinvoice";
            assertThat(L402ChallengeService.sanitizeBolt11ForHeader(clean)).isEqualTo(clean);
        }
    }

    // -----------------------------------------------------------------------
    // Capabilities caveat minting
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("capabilities caveat minting")
    class CapabilitiesCaveatMinting {

        @Test
        @DisplayName("mints capabilities caveat when capability is non-empty")
        void mintsCapabilitiesCaveatWhenNonEmpty() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402EndpointConfig configWithCapability = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "search");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, configWithCapability);

            Macaroon macaroon = deserializeMacaroon(result.macaroonBase64());
            assertThat(macaroon.caveats())
                    .anyMatch(c -> c.key().equals(SERVICE_NAME + "_capabilities") && c.value().equals("search"));
        }

        @Test
        @DisplayName("does not mint capabilities caveat when capability is empty")
        void noCapabilitiesCaveatWhenEmpty() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            // config from @BeforeEach has empty capability ""
            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, config);

            Macaroon macaroon = deserializeMacaroon(result.macaroonBase64());
            assertThat(macaroon.caveats())
                    .noneMatch(c -> c.key().equals(SERVICE_NAME + "_capabilities"));
        }

        @Test
        @DisplayName("does not mint capabilities caveat when capability is null")
        void noCapabilitiesCaveatWhenNull() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402EndpointConfig configWithNull = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", null);

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, configWithNull);

            Macaroon macaroon = deserializeMacaroon(result.macaroonBase64());
            assertThat(macaroon.caveats())
                    .noneMatch(c -> c.key().equals(SERVICE_NAME + "_capabilities"));
        }

        @Test
        @DisplayName("does not mint capabilities caveat when capability is blank")
        void noCapabilitiesCaveatWhenBlank() throws Exception {
            when(lightningBackend.isHealthy()).thenReturn(true);
            when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice(null));

            L402EndpointConfig configWithBlank = new L402EndpointConfig(
                    "GET", "/api/protected", PRICE_SATS, TIMEOUT_SECONDS, DESCRIPTION, "", "   ");

            L402ChallengeService service = createService(createTrackingRootKeyStore());
            L402ChallengeResult result = service.createChallenge(request, configWithBlank);

            Macaroon macaroon = deserializeMacaroon(result.macaroonBase64());
            assertThat(macaroon.caveats())
                    .noneMatch(c -> c.key().equals(SERVICE_NAME + "_capabilities"));
        }

        private Macaroon deserializeMacaroon(String base64) {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return MacaroonSerializer.deserializeV2(bytes);
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
            var service = new L402ChallengeService(
                    createTrackingRootKeyStore(), lightningBackend, null, applicationContext, null, null);
            assertThat(service).isNotNull();
        }

        @Test
        @DisplayName("accepts null applicationContext")
        void acceptsNullApplicationContext() {
            var service = new L402ChallengeService(
                    createTrackingRootKeyStore(), lightningBackend, properties, null, null, null);
            assertThat(service).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private L402ChallengeService createService(RootKeyStore rootKeyStore) {
        return new L402ChallengeService(
                rootKeyStore, lightningBackend, properties, applicationContext, null, null);
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
     * RootKeyStore that captures the {@link SensitiveBytes} reference so tests
     * can verify {@code isDestroyed()} after the service completes.
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
