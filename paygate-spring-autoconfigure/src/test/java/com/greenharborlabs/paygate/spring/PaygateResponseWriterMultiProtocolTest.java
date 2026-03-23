package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the multi-protocol extension methods on {@link PaygateResponseWriter}:
 * {@code writePaymentRequired(response, context, challenges)}, {@code writeReceipt},
 * {@code writeMethodUnsupported}, and {@code writeMppError}.
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
                PAYMENT_HASH, "token-1", "lnbc100n1test", 100L,
                "Test endpoint", "test-service", 3600L, "",
                ROOT_KEY, null, null);
    }

    // --- Test 1: writePaymentRequired with multiple ChallengeResponse objects ---

    @Test
    @DisplayName("writePaymentRequired with challenges -> multiple WWW-Authenticate headers")
    void writePaymentRequiredMultipleHeaders() throws Exception {
        var context = createContext();
        var challenges = List.of(
                new ChallengeResponse("L402 macaroon=\"abc\", invoice=\"lnbc\"", "L402",
                        Map.of("macaroon", "abc")),
                new ChallengeResponse("Payment method=\"lightning\", token=\"xyz\"", "Payment",
                        Map.of("token", "xyz")));

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
        var challenges = List.of(
                new ChallengeResponse("L402 challenge", "L402", Map.of("macaroon", "abc")));

        PaygateResponseWriter.writePaymentRequired(response, context, challenges);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    // --- Test 3: writePaymentRequired JSON body contains protocols map ---

    @Test
    @DisplayName("writePaymentRequired JSON body -> contains protocols map with per-protocol data")
    void writePaymentRequiredJsonBodyContainsProtocols() throws Exception {
        var context = createContext();
        var challenges = List.of(
                new ChallengeResponse("L402 challenge", "L402",
                        Map.of("macaroon", "abc123")),
                new ChallengeResponse("Payment challenge", "Payment",
                        Map.of("token", "xyz789")));

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
        var receipt = new PaymentReceipt(
                "success", "challenge-id-1", "lightning", "ref-abc",
                100L, "2026-03-21T00:00:00Z", "Payment");

        PaygateResponseWriter.writeReceipt(response, receipt);

        String encoded = response.getHeader("Payment-Receipt");
        assertThat(encoded).isNotNull();
        // Must not contain padding characters
        assertThat(encoded).doesNotContain("=");
        // Must not contain standard base64 characters that differ from base64url
        assertThat(encoded).doesNotContain("+");
        assertThat(encoded).doesNotContain("/");

        // Decode and verify it is valid JSON with expected fields
        String decoded = new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
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
        var receipt = new PaymentReceipt(
                "success", "chal-1", "lightning", null,
                50L, "2026-03-21T00:00:00Z", "Payment");

        PaygateResponseWriter.writeReceipt(response, receipt);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
    }

    // --- Test 6: writeMethodUnsupported ---

    @Test
    @DisplayName("writeMethodUnsupported -> 400, application/problem+json, RFC 9457 body")
    void writeMethodUnsupported() throws Exception {
        PaygateResponseWriter.writeMethodUnsupported(response, "Only lightning is supported");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        String body = response.getContentAsString();
        assertThat(body).contains("\"type\": \"https://paymentauth.org/problems/method-unsupported\"");
        assertThat(body).contains("\"title\": \"Method Unsupported\"");
        assertThat(body).contains("\"status\": 400");
        assertThat(body).contains("\"detail\": \"Only lightning is supported\"");
    }

    // --- Test 7: writeMppError with challenges ---

    @Test
    @DisplayName("writeMppError with 402 status -> error body + fresh challenge headers")
    void writeMppErrorWithChallenges() throws Exception {
        var exception = new PaymentValidationException(
                PaymentValidationException.ErrorCode.INVALID_PREIMAGE,
                "Preimage does not match payment hash",
                "token-abc");

        var challenges = List.of(
                new ChallengeResponse("L402 fresh-challenge", "L402",
                        Map.of("macaroon", "new")),
                new ChallengeResponse("Payment fresh-challenge", "Payment",
                        Map.of("token", "new")));

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
        assertThat(body).contains("\"type\": \"https://paymentauth.org/problems/verification-failed\"");
        assertThat(body).contains("\"title\": \"INVALID_PREIMAGE\"");
        assertThat(body).contains("\"status\": 402");
        assertThat(body).contains("\"detail\": \"Preimage does not match payment hash\"");
        assertThat(body).contains("\"token_id\": \"token-abc\"");
    }
}
