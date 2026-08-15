package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link PaygateResponseWriter}. */
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
    var context =
        new ChallengeContext(
            new byte[32],
            "a".repeat(64),
            "lnbc100n1...",
            100L,
            "Test endpoint",
            "svc",
            60L,
            null,
            null,
            null,
            null);
    var challenges =
        List.of(
            new ChallengeResponse(
                "L402 macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc100n1...\"", "L402", null));

    PaygateResponseWriter.writePaymentRequired(response, context, challenges);

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getHeaders("WWW-Authenticate"))
        .containsExactly("L402 macaroon=\"bWFjYXJvb24=\", invoice=\"lnbc100n1...\"");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 402, \"message\": \"Payment required\", \"price_sats\": 100, \"description\": \"Test endpoint\", \"invoice\": \"lnbc100n1...\", \"protocols\": {}}");
  }

  @Test
  @DisplayName("writePaymentRequired includes test_preimage when non-null")
  void writePaymentRequired_includesTestPreimage() throws Exception {
    var context =
        new ChallengeContext(
            new byte[32],
            "b".repeat(64),
            "lnbc100n1...",
            50L,
            "desc",
            "svc",
            60L,
            null,
            null,
            Map.of("test_preimage", "abc123preimage"),
            null);

    PaygateResponseWriter.writePaymentRequired(response, context, List.of());

    assertThat(response.getContentAsString()).contains("\"test_preimage\": \"abc123preimage\"");
  }

  @Test
  @DisplayName("writePaymentRequired omits test_preimage when null")
  void writePaymentRequired_omitsTestPreimageWhenNull() throws Exception {
    var context =
        new ChallengeContext(
            new byte[32],
            "c".repeat(64),
            "lnbc100n1...",
            50L,
            "desc",
            "svc",
            60L,
            null,
            null,
            null,
            null);

    PaygateResponseWriter.writePaymentRequired(response, context, List.of());

    assertThat(response.getContentAsString()).doesNotContain("test_preimage");
  }

  @Test
  @DisplayName("writePaymentRequired escapes special characters in description and invoice")
  void writePaymentRequired_escapesSpecialChars() throws Exception {
    var context =
        new ChallengeContext(
            new byte[32],
            "d".repeat(64),
            "lnbc\"escape",
            10L,
            "desc with \"quotes\" and \\backslash",
            "svc",
            60L,
            null,
            null,
            null,
            null);

    PaygateResponseWriter.writePaymentRequired(response, context, List.of());

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
    assertThat(response.getContentAsString())
        .isEqualTo(
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

  @Test
  @DisplayName("writeMalformedHeader uses the fixed safe 400 response contract")
  void writeMalformedHeader_usesSafeResponseContract() throws Exception {
    PaygateResponseWriter.writeMalformedHeader(
        response, "secret parser diagnostic", "token-prefix");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 400, \"error\": \"MALFORMED_HEADER\", \"message\": \"Malformed L402 Authorization header\", \"details\": {\"token_id\": \"token-prefix\"}}")
        .doesNotContain("secret parser diagnostic");
  }

  // --- writeValidationError ---

  @Test
  @DisplayName("writeValidationError maps invalid L402 credentials to payment required")
  void writeValidationError_setsStatusAndBody() throws Exception {
    PaygateResponseWriter.writeValidationError(
        response, ErrorCode.INVALID_MACAROON, "internal detail", "tok-456");

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 402, \"error\": \"INVALID_MACAROON\", \"message\": \"Invalid L402 credential\", \"details\": {\"token_id\": \"tok-456\"}}");
  }

  @Test
  @DisplayName("writeValidationError renders empty token_id when null")
  void writeValidationError_nullTokenIdRendersEmpty() throws Exception {
    PaygateResponseWriter.writeValidationError(
        response, ErrorCode.EXPIRED_CREDENTIAL, "expired", null);

    assertThat(response.getContentAsString()).contains("\"token_id\": \"\"");
  }

  @Test
  @DisplayName("writeValidationError uses correct HTTP status for each ErrorCode")
  void writeValidationError_usesCorrectStatusPerErrorCode() throws Exception {
    PaygateResponseWriter.writeValidationError(
        response, ErrorCode.LIGHTNING_UNAVAILABLE, "down", "tok-789");

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("\"code\": 503");
    assertThat(response.getContentAsString()).contains("\"error\": \"LIGHTNING_UNAVAILABLE\"");
  }

  @Test
  @DisplayName("malformed credential has fixed 400 response with safe cache and type headers")
  void malformedCredentialUsesSafeResponseContract() throws Exception {
    PaygateResponseWriter.writeMppError(
        response,
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.MALFORMED, "secret parser detail"),
        List.of());

    assertSafeFailureResponse(400, "MALFORMED", "secret parser detail");
  }

  @Test
  @DisplayName("invalid credential has fixed 402 response without a replacement challenge")
  void invalidCredentialUsesSafeResponseContract() throws Exception {
    PaygateResponseWriter.writeMppError(
        response,
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.INVALID, "secret verifier detail"),
        List.of());

    assertSafeFailureResponse(402, "INVALID", "secret verifier detail");
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
  }

  @Test
  @DisplayName("expired credential maps to the fixed invalid 402 response")
  void expiredCredentialUsesSafeInvalidResponseContract() throws Exception {
    PaygateResponseWriter.writeMppError(
        response,
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.INVALID, "credential expired at secret instant"),
        List.of());

    assertSafeFailureResponse(402, "INVALID", "credential expired at secret instant");
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
  }

  @Test
  @DisplayName("insufficient credential has fixed 402 response without a replacement challenge")
  void insufficientCredentialUsesSafeResponseContract() throws Exception {
    PaygateResponseWriter.writeMppError(
        response,
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.INSUFFICIENT, "secret amount detail"),
        List.of());

    assertSafeFailureResponse(402, "INSUFFICIENT", "secret amount detail");
    assertThat(response.getHeaders("WWW-Authenticate")).isEmpty();
  }

  @Test
  @DisplayName("unavailable credential validation has fixed 503 response")
  void unavailableCredentialUsesSafeResponseContract() throws Exception {
    PaygateResponseWriter.writeMppError(
        response,
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.UNAVAILABLE, "secret backend detail"),
        List.of());

    assertSafeFailureResponse(503, "UNAVAILABLE", "secret backend detail");
  }

  @Test
  @DisplayName("L402 invalid and expired failures map to 402 rather than authentication 401")
  void l402InvalidAndExpiredFailuresMapToPaymentRequired() throws Exception {
    for (ErrorCode errorCode : List.of(ErrorCode.INVALID_MACAROON, ErrorCode.EXPIRED_CREDENTIAL)) {
      response = new MockHttpServletResponse();

      PaygateResponseWriter.writeValidationError(
          response, errorCode, "secret protocol diagnostic", "token-prefix");

      assertThat(response.getStatus()).as(errorCode.name()).isEqualTo(402);
      assertThat(response.getHeader("Cache-Control")).as(errorCode.name()).isEqualTo("no-store");
      assertThat(response.getHeader("X-Content-Type-Options"))
          .as(errorCode.name())
          .isEqualTo("nosniff");
      assertThat(response.getContentAsString()).doesNotContain("secret protocol diagnostic");
    }
  }

  // --- writeRateLimited ---

  @Test
  @DisplayName("writeRateLimited sets 429 status, Retry-After header, and JSON body")
  void writeRateLimited_setsStatusHeadersAndBody() throws Exception {
    PaygateResponseWriter.writeRateLimited(response);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 429, \"error\": \"RATE_LIMITED\", \"message\": \"Too many payment challenge requests. Please try again later.\"}");
  }

  // --- writeLightningUnavailable ---

  @Test
  @DisplayName("writeLightningUnavailable sets 503 status and JSON body")
  void writeLightningUnavailable_setsStatusAndBody() throws Exception {
    PaygateResponseWriter.writeLightningUnavailable(response);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 503, \"error\": \"LIGHTNING_UNAVAILABLE\", \"message\": \"Lightning backend is not available. Please try again later.\"}");
  }

  @Test
  @DisplayName("writeInternalError sets 500 status and a bounded sanitized JSON body")
  void writeInternalError_setsStatusAndSanitizedBody() throws Exception {
    PaygateResponseWriter.writeInternalError(response);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 500, \"error\": \"INTERNAL_ERROR\", \"message\": \"An internal error occurred\"}")
        .doesNotContain("secret", "policy", "path")
        .hasSizeLessThan(256);
  }

  @Test
  @DisplayName("writeMalformedUri sets 400 status and MALFORMED_URI body")
  void writeMalformedUri_setsStatusAndBody() throws Exception {
    PaygateResponseWriter.writeMalformedUri(response);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
  }

  @Test
  @DisplayName("writeRequestBodyTooLarge sets 400 status and bounded-body error")
  void writeRequestBodyTooLarge_setsStatusAndBody() throws Exception {
    PaygateResponseWriter.writeRequestBodyTooLarge(response);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 400, \"error\": \"REQUEST_BODY_TOO_LARGE\", \"message\": \"Request body exceeds 8192 bytes for digest binding\"}");
  }

  // --- writeUnauthorized ---

  @Test
  @DisplayName("writeUnauthorized sets 401 status, WWW-Authenticate: L402 header, and JSON body")
  void writeUnauthorized_setsStatusHeadersAndBody() throws Exception {
    PaygateResponseWriter.writeUnauthorized(response);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 401, \"error\": \"UNAUTHORIZED\", \"message\": \"Authentication required\"}");
  }

  // --- writeAuthenticationFailed ---

  @Test
  @DisplayName(
      "writeAuthenticationFailed sets 401 status, WWW-Authenticate: L402 header, and JSON body")
  void writeAuthenticationFailed_setsStatusHeadersAndBody() throws Exception {
    PaygateResponseWriter.writeAuthenticationFailed(response);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("L402");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\": 401, \"error\": \"AUTHENTICATION_FAILED\", \"message\": \"L402 authentication failed\"}");
  }

  private void assertSafeFailureResponse(int status, String category, String diagnostic)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getContentAsString())
        .contains("\"title\": \"" + category + "\"")
        .contains("\"detail\": \"Payment validation failed: " + category + "\"")
        .doesNotContain(diagnostic);
  }
}
