package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the multi-protocol extension methods on {@link PaygateResponseWriter}: {@code
 * writePaymentRequired(response, context, challenges)}, {@code writeReceipt}, {@code
 * writeMethodUnsupported}, and {@code writeMppError}.
 */
@DisplayName("PaygateResponseWriter — multi-protocol methods")
class PaygateResponseWriterMultiProtocolTest {

  private static final byte[] PAYMENT_HASH = new byte[32];
  private static final byte[] ROOT_KEY = new byte[32];

  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    response = new MockHttpServletResponse();
  }

  private ChallengeContext createContext() {
    return new ChallengeContext(
        PAYMENT_HASH,
        "token-1",
        "lnbc100n1test",
        100L,
        "Test endpoint",
        "test-service",
        3600L,
        "",
        ROOT_KEY,
        null,
        null);
  }

  // --- Test 1: writePaymentRequired with multiple ChallengeResponse objects ---

  @Test
  @DisplayName("writePaymentRequired with challenges -> multiple WWW-Authenticate headers")
  void writePaymentRequiredMultipleHeaders() throws Exception {
    var context = createContext();
    var challenges =
        List.of(
            new ChallengeResponse(
                "L402 macaroon=\"abc\", invoice=\"lnbc\"", "L402", Map.of("macaroon", "abc")),
            new ChallengeResponse(
                "Payment method=\"lightning\", token=\"xyz\"", "Payment", Map.of("token", "xyz")));

    PaygateResponseWriter.writePaymentRequired(response, context, challenges);

    assertThat(response.getStatus()).isEqualTo(402);
    List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate");
    assertThat(wwwAuthHeaders).hasSize(2);
    assertThat(wwwAuthHeaders.get(0)).isEqualTo("L402 macaroon=\"abc\", invoice=\"lnbc\"");
    assertThat(wwwAuthHeaders.get(1)).isEqualTo("Payment method=\"lightning\", token=\"xyz\"");
  }

  // --- Test 2: writePaymentRequired Cache-Control ---

  @Test
  @DisplayName("writePaymentRequired -> Cache-Control: no-store")
  void writePaymentRequiredCacheControl() throws Exception {
    var context = createContext();
    var challenges =
        List.of(new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc")));

    PaygateResponseWriter.writePaymentRequired(response, context, challenges);

    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  // --- Test 3: writePaymentRequired JSON body contains protocols map ---

  @Test
  @DisplayName("writePaymentRequired JSON body -> contains protocols map with per-protocol data")
  void writePaymentRequiredJsonBodyContainsProtocols() throws Exception {
    var context = createContext();
    var challenges =
        List.of(
            new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc123")),
            new ChallengeResponse("Payment challenge", "Payment", Map.of("token", "xyz789")));

    PaygateResponseWriter.writePaymentRequired(response, context, challenges);

    String body = response.getContentAsString();
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(body).contains("\"code\": 402");
    assertThat(body).contains("\"price_sats\": 100");
    assertThat(body).contains("\"invoice\": \"lnbc100n1test\"");
    assertThat(body).contains("\"protocols\":");
    assertThat(body).contains("\"L402\":");
    assertThat(body).contains("\"Payment\":");
    assertThat(body).contains("\"macaroon\": \"abc123\"");
    assertThat(body).contains("\"token\": \"xyz789\"");
  }

  // --- Test 4: writeReceipt header is base64url-nopad JSON ---

  @Test
  @DisplayName("writeReceipt -> Payment-Receipt is base64url-nopad encoded JSON")
  void writeReceiptHeaderIsBase64UrlNoPad() throws Exception {
    var receipt =
        new PaymentReceipt(
            "success",
            "challenge-id-1",
            "lightning",
            "ref-abc",
            100L,
            "2026-03-21T00:00:00Z",
            "Payment");

    PaygateResponseWriter.writeReceipt(response, receipt);

    String encoded = response.getHeader("Payment-Receipt");
    assertThat(encoded).isNotNull();
    // Must not contain padding characters
    assertThat(encoded).doesNotContain("=");
    // Must not contain standard base64 characters that differ from base64url
    assertThat(encoded).doesNotContain("+");
    assertThat(encoded).doesNotContain("/");

    // Decode and verify it is valid JSON with expected fields
    String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    assertThat(decoded).contains("\"status\": \"success\"");
    assertThat(decoded).contains("\"challenge_id\": \"challenge-id-1\"");
    assertThat(decoded).contains("\"method\": \"lightning\"");
    assertThat(decoded).contains("\"reference\": \"ref-abc\"");
    assertThat(decoded).contains("\"amount_sats\": 100");
    assertThat(decoded).contains("\"timestamp\": \"2026-03-21T00:00:00Z\"");
    assertThat(decoded).contains("\"protocol_scheme\": \"Payment\"");
  }

  // --- Test 5: writeReceipt Cache-Control ---

  @Test
  @DisplayName("writeReceipt -> Cache-Control: private")
  void writeReceiptCacheControlPrivate() throws Exception {
    var receipt =
        new PaymentReceipt(
            "success", "chal-1", "lightning", null, 50L, "2026-03-21T00:00:00Z", "Payment");

    PaygateResponseWriter.writeReceipt(response, receipt);

    assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
  }

  // --- Test 6: writeMethodUnsupported ---

  @Test
  @DisplayName("writeMethodUnsupported -> standard INVALID RFC 9457 response")
  void writeMethodUnsupported() throws Exception {
    PaygateResponseWriter.writeMethodUnsupported(response, "Only lightning is supported");

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

    String body = response.getContentAsString();
    assertThat(body).contains("\"type\": \"https://paymentauth.org/problems/invalid\"");
    assertThat(body).contains("\"title\": \"INVALID\"");
    assertThat(body).contains("\"status\": 402");
    assertThat(body).contains("\"detail\": \"Payment validation failed: INVALID\"");
    assertThat(body).doesNotContain("Only lightning is supported");
  }

  // --- Test 7: writeMppError with challenges ---

  @Test
  @DisplayName("writeMppError with 402 status -> error body + fresh challenge headers")
  void writeMppErrorWithChallenges() throws Exception {
    var exception =
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.INVALID,
            "Preimage does not match payment hash",
            "token-abc");

    var challenges =
        List.of(
            new ChallengeResponse("L402 fresh-challenge", "L402", Map.of("macaroon", "new")),
            new ChallengeResponse("Payment fresh-challenge", "Payment", Map.of("token", "new")));

    PaygateResponseWriter.writeMppError(response, exception, challenges);

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

    // Fresh challenge headers present for 402
    List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate");
    assertThat(wwwAuthHeaders).hasSize(2);
    assertThat(wwwAuthHeaders).anyMatch(h -> h.contains("L402"));
    assertThat(wwwAuthHeaders).anyMatch(h -> h.contains("Payment"));

    // RFC 9457 Problem Details body
    String body = response.getContentAsString();
    assertThat(body).contains("\"type\": \"https://paymentauth.org/problems/invalid\"");
    assertThat(body).contains("\"title\": \"INVALID\"");
    assertThat(body).contains("\"status\": 402");
    assertThat(body).contains("\"detail\": \"Payment validation failed: INVALID\"");
    assertThat(body).doesNotContain("Preimage does not match payment hash");
    assertThat(body).contains("\"token_id\": \"token-abc\"");
  }

  @Test
  @DisplayName("one formatter failure preserves another protocol's usable challenge")
  void partialFormatterFailurePreservesSuccessfulChallenge() throws Exception {
    var rootKeyStore = new TrackingRootKeyStore();
    var successful = mock(PaymentProtocol.class);
    when(successful.scheme()).thenReturn("L402");
    when(successful.formatChallenge(any()))
        .thenReturn(new ChallengeResponse("L402 usable-challenge", "L402", Map.of("token", "ok")));
    var failing = mock(PaymentProtocol.class);
    when(failing.scheme()).thenReturn("Broken");
    when(failing.formatChallenge(any())).thenThrow(new IllegalStateException("formatter secret"));
    var fixture = createChallengeFilter(rootKeyStore, List.of(successful, failing));

    fixture
        .filter()
        .doFilter(
            new MockHttpServletRequest("GET", "/api/protected"),
            response,
            mock(jakarta.servlet.FilterChain.class));

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeaders("WWW-Authenticate")).containsExactly("L402 usable-challenge");
    assertThat(response.getContentAsString())
        .contains("\"L402\"")
        .doesNotContain("formatter secret");
    verify(fixture.lightningBackend(), times(1)).createInvoice(anyLong(), anyString());
    assertThat(rootKeyStore.generateRootKeyInvocations).isEqualTo(1);
    assertThat(rootKeyStore.revokeRootKeyInvocations).isZero();
  }

  @Test
  @DisplayName("all formatter failures revoke the newly persisted shared root key")
  void allFormatterFailuresCleanUpPersistentChallengeState() throws Exception {
    var rootKeyStore = new TrackingRootKeyStore();
    var first = failingProtocol("L402");
    var second = failingProtocol("Broken");
    var fixture = createChallengeFilter(rootKeyStore, List.of(first, second));

    fixture
        .filter()
        .doFilter(
            new MockHttpServletRequest("GET", "/api/protected"),
            response,
            mock(jakarta.servlet.FilterChain.class));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
    verify(fixture.lightningBackend(), times(1)).createInvoice(anyLong(), anyString());
    assertThat(rootKeyStore.generateRootKeyInvocations).isEqualTo(1);
    assertThat(rootKeyStore.revokeRootKeyInvocations).isEqualTo(1);
    assertThat(rootKeyStore.lastRevokedTokenId).containsExactly(rootKeyStore.generatedTokenId);
  }

  private static PaymentProtocol failingProtocol(String scheme) {
    var protocol = mock(PaymentProtocol.class);
    when(protocol.scheme()).thenReturn(scheme);
    when(protocol.formatChallenge(any())).thenThrow(new IllegalStateException("format failed"));
    return protocol;
  }

  private static ChallengeFilterFixture createChallengeFilter(
      TrackingRootKeyStore rootKeyStore, List<PaymentProtocol> protocols) {
    var lightningBackend = mock(LightningBackend.class);
    when(lightningBackend.isHealthy()).thenReturn(true);
    when(lightningBackend.createInvoice(anyLong(), anyString()))
        .thenReturn(
            new Invoice(
                PAYMENT_HASH,
                "lnbc100n1test",
                100L,
                "Test invoice",
                InvoiceStatus.PENDING,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T01:00:00Z")));
    var properties = new PaygateProperties();
    properties.setServiceName("test-service");
    var challengeService =
        new PaygateChallengeService(
            rootKeyStore,
            lightningBackend,
            properties,
            mock(ApplicationContext.class),
            null,
            null,
            null,
            null);
    var registry = new PaygateEndpointRegistry();
    registry.register(
        new PaygateEndpointConfig("GET", "/api/protected", 100L, 3600L, "Test endpoint", "", ""));
    return new ChallengeFilterFixture(
        new PaygateSecurityFilter(
            registry, protocols, challengeService, "test-service", null, null, null, null),
        lightningBackend);
  }

  private record ChallengeFilterFixture(
      PaygateSecurityFilter filter, LightningBackend lightningBackend) {}

  private static final class TrackingRootKeyStore implements RootKeyStore {
    private final byte[] generatedTokenId = new byte[32];
    private int generateRootKeyInvocations;
    private int revokeRootKeyInvocations;
    private byte[] lastRevokedTokenId;

    private TrackingRootKeyStore() {
      Arrays.fill(generatedTokenId, (byte) 7);
    }

    @Override
    public GenerationResult generateRootKey() {
      generateRootKeyInvocations++;
      var key = new byte[32];
      Arrays.fill(key, (byte) 11);
      return new GenerationResult(new SensitiveBytes(key), generatedTokenId);
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      return null;
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
      revokeRootKeyInvocations++;
      lastRevokedTokenId = Arrays.copyOf(keyId, keyId.length);
    }
  }
}
