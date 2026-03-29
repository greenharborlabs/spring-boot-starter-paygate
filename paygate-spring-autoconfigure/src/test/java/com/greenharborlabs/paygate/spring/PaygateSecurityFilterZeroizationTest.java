package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;

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
 * in {@link PaygateSecurityFilter}, covering both the success path and the
 * exception path (e.g. createInvoice throws).
 */
@DisplayName("PaygateSecurityFilter root key zeroization")
class PaygateSecurityFilterZeroizationTest {

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

        PaygateSecurityFilter filter = createFilter(trackingStore);
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

        PaygateSecurityFilter filter = createFilter(trackingStore);

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
        var registry = new PaygateEndpointRegistry();
        var rootKeyStore = new ZeroizationTrackingRootKeyStore();
        var validator = new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);
        var l402Protocol = new L402Protocol(validator, SERVICE_NAME);

        assertThatNullPointerException()
                .isThrownBy(() -> new PaygateSecurityFilter(
                        registry, List.of(l402Protocol), null, SERVICE_NAME, null, null, null, null))
                .withMessageContaining("challengeService");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PaygateSecurityFilter createFilter(RootKeyStore rootKeyStore) {
        PaygateEndpointRegistry registry = new PaygateEndpointRegistry();
        registry.register(new PaygateEndpointConfig(
                "GET", PROTECTED_PATH, PRICE_SATS, TIMEOUT_SECONDS,
                "Test protected endpoint", "", ""));

        var validator = new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);
        var l402Protocol = new L402Protocol(validator, SERVICE_NAME);
        var properties = new PaygateProperties();
        properties.setServiceName("test-service");
        var challengeService = new PaygateChallengeService(
                rootKeyStore, lightningBackend, properties, mock(ApplicationContext.class), null, null, null, null);
        return new PaygateSecurityFilter(
                registry, List.of(l402Protocol), challengeService, SERVICE_NAME,
                null, null, null, null);
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
