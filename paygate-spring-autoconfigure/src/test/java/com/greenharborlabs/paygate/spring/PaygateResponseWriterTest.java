package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PaygateResponseWriter}.
 */
@DisplayName("PaygateResponseWriter")
class PaygateResponseWriterTest {

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
    }

    // --- writePaymentRequired ---

    @Test
    @DisplayName("writePaymentRequired sets 402 status, WWW-Authenticate header, and JSON body")
    void writePaymentRequired_setsStatusHeadersAndBody() throws Exception {
        var result = new PaygateChallengeResult(
                "bWFjYXJvb24=", "lnbc100n1...", "L402 macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc100n1...\"",
                100L, "Test endpoint", null);

        PaygateResponseWriter.writePaymentRequired(response, result);

        assertThat(response.getStatus()).isEqualTo(402);
        assertThat(response.getHeader("WWW-Authenticate"))
                .isEqualTo("L402 macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc100n1...\"");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 402, \"message\": \"Payment required\", \"price_sats\": 100, \"description\": \"Test endpoint\", \"invoice\": \"lnbc100n1...\"}");
    }

    @Test
    @DisplayName("writePaymentRequired includes test_preimage when non-null")
    void writePaymentRequired_includesTestPreimage() throws Exception {
        var result = new PaygateChallengeResult(
                "bWFjYXJvb24=", "lnbc100n1...", "L402 token",
                50L, "desc", "abc123preimage");

        PaygateResponseWriter.writePaymentRequired(response, result);

        assertThat(response.getContentAsString()).contains("\"test_preimage\": \"abc123preimage\"");
    }

    @Test
    @DisplayName("writePaymentRequired omits test_preimage when null")
    void writePaymentRequired_omitsTestPreimageWhenNull() throws Exception {
        var result = new PaygateChallengeResult(
                "bWFjYXJvb24=", "lnbc100n1...", "L402 token",
                50L, "desc", null);

        PaygateResponseWriter.writePaymentRequired(response, result);

        assertThat(response.getContentAsString()).doesNotContain("test_preimage");
    }

    @Test
    @DisplayName("writePaymentRequired escapes special characters in description and invoice")
    void writePaymentRequired_escapesSpecialChars() throws Exception {
        var result = new PaygateChallengeResult(
                "bWFjYXJvb24=", "lnbc\"escape", "L402 token",
                10L, "desc with \"quotes\" and \\backslash", null);

        PaygateResponseWriter.writePaymentRequired(response, result);

        String body = response.getContentAsString();
        assertThat(body).contains("desc with \\\"quotes\\\" and \\\\backslash");
        assertThat(body).contains("lnbc\\\"escape");
    }

    // --- writeMalformedHeader ---

    @Test
    @DisplayName("writeMalformedHeader sets 400 status and JSON body with token_id")
    void writeMalformedHeader_setsStatusAndBody() throws Exception {
        PaygateResponseWriter.writeMalformedHeader(response, "bad header format", "tok-123");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 400, \"error\": \"MALFORMED_HEADER\", \"message\": \"Malformed L402 Authorization header\", \"details\": {\"token_id\": \"tok-123\"}}");
    }

    @Test
    @DisplayName("writeMalformedHeader renders empty token_id when null")
    void writeMalformedHeader_nullTokenIdRendersEmpty() throws Exception {
        PaygateResponseWriter.writeMalformedHeader(response, "bad", null);

        assertThat(response.getContentAsString()).contains("\"token_id\": \"\"");
    }

    @Test
    @DisplayName("writeMalformedHeader escapes special characters in tokenId")
    void writeMalformedHeader_escapesTokenId() throws Exception {
        PaygateResponseWriter.writeMalformedHeader(response, "bad", "tok\"with\nnewline");

        String body = response.getContentAsString();
        assertThat(body).contains("tok\\\"with\\nnewline");
    }

    // --- writeValidationError ---

    @Test
    @DisplayName("writeValidationError sets status from ErrorCode and includes error name")
    void writeValidationError_setsStatusAndBody() throws Exception {
        PaygateResponseWriter.writeValidationError(response, ErrorCode.INVALID_MACAROON,
                "internal detail", "tok-456");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 401, \"error\": \"INVALID_MACAROON\", \"message\": \"Invalid L402 credential\", \"details\": {\"token_id\": \"tok-456\"}}");
    }

    @Test
    @DisplayName("writeValidationError renders empty token_id when null")
    void writeValidationError_nullTokenIdRendersEmpty() throws Exception {
        PaygateResponseWriter.writeValidationError(response, ErrorCode.EXPIRED_CREDENTIAL,
                "expired", null);

        assertThat(response.getContentAsString()).contains("\"token_id\": \"\"");
    }

    @Test
    @DisplayName("writeValidationError uses correct HTTP status for each ErrorCode")
    void writeValidationError_usesCorrectStatusPerErrorCode() throws Exception {
        PaygateResponseWriter.writeValidationError(response, ErrorCode.LIGHTNING_UNAVAILABLE,
                "down", "tok-789");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\": 503");
        assertThat(response.getContentAsString()).contains("\"error\": \"LIGHTNING_UNAVAILABLE\"");
    }

    // --- writeRateLimited ---

    @Test
    @DisplayName("writeRateLimited sets 429 status, Retry-After header, and JSON body")
    void writeRateLimited_setsStatusHeadersAndBody() throws Exception {
        PaygateResponseWriter.writeRateLimited(response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 429, \"error\": \"RATE_LIMITED\", \"message\": \"Too many payment challenge requests. Please try again later.\"}");
    }

    // --- writeLightningUnavailable ---

    @Test
    @DisplayName("writeLightningUnavailable sets 503 status and JSON body")
    void writeLightningUnavailable_setsStatusAndBody() throws Exception {
        PaygateResponseWriter.writeLightningUnavailable(response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
    }

    // --- writeUnauthorized ---

    @Test
    @DisplayName("writeUnauthorized sets 401 status, WWW-Authenticate: L402 header, and JSON body")
    void writeUnauthorized_setsStatusHeadersAndBody() throws Exception {
        PaygateResponseWriter.writeUnauthorized(response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 401, \"error\": \"UNAUTHORIZED\", \"message\": \"Authentication required\"}");
    }

    // --- writeAuthenticationFailed ---

    @Test
    @DisplayName("writeAuthenticationFailed sets 401 status, WWW-Authenticate: L402 header, and JSON body")
    void writeAuthenticationFailed_setsStatusHeadersAndBody() throws Exception {
        PaygateResponseWriter.writeAuthenticationFailed(response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\": 401, \"error\": \"AUTHENTICATION_FAILED\", \"message\": \"L402 authentication failed\"}");
    }
}
