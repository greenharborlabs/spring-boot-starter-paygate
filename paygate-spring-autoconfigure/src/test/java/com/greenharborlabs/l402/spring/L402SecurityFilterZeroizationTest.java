package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.SensitiveBytes;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Validator;

import org.springframework.context.ApplicationContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that root key material is zeroized after minting
 * in {@link L402SecurityFilter}, covering both the success path and the
 * exception path (e.g. createInvoice throws).
 */
@DisplayName("L402SecurityFilter root key zeroization")
class L402SecurityFilterZeroizationTest {

    private static final String PROTECTED_PATH = "/api/protected";
    private static final String SERVICE_NAME = "test-service";
    private static final long PRICE_SATS = 10;
    private static final long TIMEOUT_SECONDS = 600;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private LightningBackend lightningBackend;
    private CredentialStore credentialStore;

    @BeforeEach
    void setUp() throws Exception {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        lightningBackend = mock(LightningBackend.class);
        credentialStore = mock(CredentialStore.class);

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        when(lightningBackend.isHealthy()).thenReturn(true);

        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    @DisplayName("SensitiveBytes is destroyed after successful 402 challenge")
    void sensitiveByteDestroyedAfterSuccessfulChallenge() throws Exception {
        var trackingStore = new ZeroizationTrackingRootKeyStore();

        when(lightningBackend.createInvoice(anyLong(), anyString())).thenReturn(createStubInvoice());

        L402SecurityFilter filter = createFilter(trackingStore);
        filter.doFilter(request, response, chain);

        assertThat(trackingStore.lastSensitiveBytes).isNotNull();
        assertThat(trackingStore.lastSensitiveBytes.isDestroyed())
                .as("SensitiveBytes must be destroyed via try-with-resources on GenerationResult")
                .isTrue();
    }

    @Test
    @DisplayName("SensitiveBytes is destroyed even when createInvoice throws")
    void sensitiveByteDestroyedWhenCreateInvoiceThrows() throws Exception {
        var trackingStore = new ZeroizationTrackingRootKeyStore();

        when(lightningBackend.createInvoice(anyLong(), anyString()))
                .thenThrow(new RuntimeException("Lightning backend exploded"));

        L402SecurityFilter filter = createFilter(trackingStore);

        // doFilter catches the exception internally and writes 503
        filter.doFilter(request, response, chain);

        assertThat(trackingStore.lastSensitiveBytes).isNotNull();
        assertThat(trackingStore.lastSensitiveBytes.isDestroyed())
                .as("SensitiveBytes must be destroyed even when createInvoice throws")
                .isTrue();
    }

    @Test
    @DisplayName("constructor rejects null challengeService with NullPointerException")
    void constructorRejectsNullChallengeService() {
        var registry = new L402EndpointRegistry();
        var rootKeyStore = new ZeroizationTrackingRootKeyStore();
        var validator = new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

        assertThatNullPointerException()
                .isThrownBy(() -> new L402SecurityFilter(
                        registry, validator, null, SERVICE_NAME, null, null, null))
                .withMessageContaining("challengeService");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private L402SecurityFilter createFilter(RootKeyStore rootKeyStore) {
        L402EndpointRegistry registry = new L402EndpointRegistry();
        registry.register(new L402EndpointConfig(
                "GET", PROTECTED_PATH, PRICE_SATS, TIMEOUT_SECONDS,
                "Test protected endpoint", "", ""));

        var validator = new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);
        var properties = new L402Properties();
        properties.setServiceName("test-service");
        var challengeService = new L402ChallengeService(
                rootKeyStore, lightningBackend, properties, mock(ApplicationContext.class), null, null);
        return new L402SecurityFilter(
                registry, validator, challengeService, SERVICE_NAME,
                null, null, null);
    }

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
                now.plus(1, ChronoUnit.HOURS));
    }

    /**
     * RootKeyStore that captures the {@link SensitiveBytes} reference so tests
     * can verify {@code isDestroyed()} after the filter completes.
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
