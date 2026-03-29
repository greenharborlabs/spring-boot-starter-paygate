package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.util.JsonEscaper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for writing L402 HTTP error responses in a consistent JSON format. All methods are
 * static; this class is not instantiable.
 */
public final class PaygateResponseWriter {

  private PaygateResponseWriter() {}

  /** Writes a 402 Payment Required response with the L402 challenge. */
  public static void writePaymentRequired(
      HttpServletResponse response, PaygateChallengeResult result) throws IOException {
    response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    response.setHeader("WWW-Authenticate", result.wwwAuthenticateHeader());
    response.setContentType("application/json");
    String testPreimageField = "";
    if (result.testPreimage() != null) {
      testPreimageField = ", \"test_preimage\": \"" + result.testPreimage() + "\"";
    }
    response
        .getWriter()
        .write(
            """
                {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"%s}"""
                .formatted(
                    result.priceSats(),
                    JsonEscaper.escape(result.description()),
                    JsonEscaper.escape(result.bolt11()),
                    testPreimageField));
  }

  /**
   * Writes a 400 Bad Request response for a malformed L402 Authorization header.
   *
   * @param response the servlet response
   * @param message detail message for the caller's logging (not included in JSON body)
   * @param tokenId the token ID to include in the details, or null
   */
  public static void writeMalformedHeader(
      HttpServletResponse response, String message, String tokenId) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType("application/json");
    String tokenDetail = tokenId != null ? tokenId : "";
    response
        .getWriter()
        .write(
            """
                {"code": 400, "error": "MALFORMED_HEADER", "message": "Malformed L402 Authorization header", "details": {"token_id": "%s"}}"""
                .formatted(JsonEscaper.escape(tokenDetail)));
  }

  /**
   * Writes an error response for a credential validation failure.
   *
   * @param response the servlet response
   * @param errorCode the error code determining HTTP status and error name
   * @param message detail message for the caller's logging (not included in JSON body)
   * @param tokenId the token ID to include in the details, or null
   */
  public static void writeValidationError(
      HttpServletResponse response, ErrorCode errorCode, String message, String tokenId)
      throws IOException {
    response.setStatus(errorCode.getHttpStatus());
    response.setContentType("application/json");
    String tokenDetail = tokenId != null ? tokenId : "";
    String clientMessage = "Invalid L402 credential";
    response
        .getWriter()
        .write(
            """
                {"code": %d, "error": "%s", "message": "%s", "details": {"token_id": "%s"}}"""
                .formatted(
                    errorCode.getHttpStatus(),
                    errorCode.name(),
                    JsonEscaper.escape(clientMessage),
                    JsonEscaper.escape(tokenDetail)));
  }

  /** Writes a 429 Too Many Requests response with a Retry-After header. */
  public static void writeRateLimited(HttpServletResponse response) throws IOException {
    response.setStatus(429);
    response.setHeader("Retry-After", "1");
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            """
                {"code": 429, "error": "RATE_LIMITED", "message": "Too many payment challenge requests. Please try again later."}""");
  }

  /** Writes a 503 Service Unavailable response when the Lightning backend is down. */
  public static void writeLightningUnavailable(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            """
                {"code": 503, "error": "LIGHTNING_UNAVAILABLE", "message": "Lightning backend is not available. Please try again later."}""");
  }

  /** Writes a 401 Unauthorized response requiring a valid L402 credential. */
  public static void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "L402");
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            """
                {"code": 401, "error": "UNAUTHORIZED", "message": "Authentication required"}""");
  }

  /** Writes a 401 response indicating L402 authentication failed. */
  public static void writeAuthenticationFailed(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "L402");
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            """
                {"code": 401, "error": "AUTHENTICATION_FAILED", "message": "L402 authentication failed"}""");
  }

  /**
   * Writes a 402 Payment Required response with multi-protocol challenge headers. Each protocol
   * contributes a separate {@code WWW-Authenticate} header via {@code addHeader} (not {@code
   * setHeader}) to support multiple challenges.
   *
   * @param response the servlet response
   * @param context the challenge context with invoice details
   * @param challenges one challenge per registered protocol
   */
  public static void writePaymentRequired(
      HttpServletResponse response, ChallengeContext context, List<ChallengeResponse> challenges)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    response.setHeader("Cache-Control", "no-store");
    for (ChallengeResponse challenge : challenges) {
      response.addHeader("WWW-Authenticate", challenge.wwwAuthenticateHeader());
    }
    response.setContentType("application/json");

    var sb = new StringBuilder();
    sb.append("{\"code\": 402, \"message\": \"Payment required\"");
    sb.append(", \"price_sats\": ").append(context.priceSats());
    sb.append(", \"description\": \"")
        .append(JsonEscaper.escape(context.description()))
        .append('"');
    sb.append(", \"invoice\": \"").append(JsonEscaper.escape(context.bolt11Invoice())).append('"');

    // Build protocols map: one entry per challenge that provides bodyData
    sb.append(", \"protocols\": {");
    boolean firstProtocol = true;
    for (ChallengeResponse challenge : challenges) {
      if (challenge.bodyData() != null) {
        if (!firstProtocol) {
          sb.append(", ");
        }
        sb.append('"').append(JsonEscaper.escape(challenge.protocolScheme())).append("\": ");
        appendJsonValue(sb, challenge.bodyData());
        firstProtocol = false;
      }
    }
    sb.append('}');

    // Include test preimage if present in opaque context
    if (context.opaque() != null && context.opaque().containsKey("test_preimage")) {
      sb.append(", \"test_preimage\": \"")
          .append(JsonEscaper.escape(context.opaque().get("test_preimage")))
          .append('"');
    }

    sb.append('}');
    response.getWriter().write(sb.toString());
  }

  /**
   * Writes a {@code Payment-Receipt} header as base64url-nopad encoded JSON.
   *
   * @param response the servlet response
   * @param receipt the payment receipt data
   */
  public static void writeReceipt(HttpServletResponse response, PaymentReceipt receipt)
      throws IOException {
    response.setHeader("Cache-Control", "private");

    var receiptJson = new StringBuilder();
    receiptJson.append("{\"status\": \"").append(JsonEscaper.escape(receipt.status())).append('"');
    receiptJson
        .append(", \"challenge_id\": \"")
        .append(JsonEscaper.escape(receipt.challengeId()))
        .append('"');
    receiptJson.append(", \"method\": \"").append(JsonEscaper.escape(receipt.method())).append('"');
    if (receipt.reference() != null) {
      receiptJson
          .append(", \"reference\": \"")
          .append(JsonEscaper.escape(receipt.reference()))
          .append('"');
    }
    receiptJson.append(", \"amount_sats\": ").append(receipt.amountSats());
    receiptJson
        .append(", \"timestamp\": \"")
        .append(JsonEscaper.escape(receipt.timestamp()))
        .append('"');
    receiptJson
        .append(", \"protocol_scheme\": \"")
        .append(JsonEscaper.escape(receipt.protocolScheme()))
        .append('"');
    receiptJson.append('}');

    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(receiptJson.toString().getBytes(StandardCharsets.UTF_8));
    response.setHeader("Payment-Receipt", encoded);
  }

  /**
   * Writes a 400 Bad Request response for an unsupported payment method, using RFC 9457 Problem
   * Details format.
   *
   * @param response the servlet response
   * @param message detail message describing why the method is unsupported
   */
  public static void writeMethodUnsupported(HttpServletResponse response, String message)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            """
                {"type": "https://paymentauth.org/problems/method-unsupported", "title": "Method Unsupported", "status": 400, "detail": "%s"}"""
                .formatted(JsonEscaper.escape(message)));
  }

  /**
   * Writes an error response for a payment validation failure, using RFC 9457 Problem Details
   * format. For 402 errors, fresh challenge headers are included so the client can retry with a new
   * payment.
   *
   * @param response the servlet response
   * @param exception the validation exception with error code and HTTP status
   * @param challenges fresh challenges to include for 402 responses, may be empty
   */
  public static void writeMppError(
      HttpServletResponse response,
      PaymentValidationException exception,
      List<ChallengeResponse> challenges)
      throws IOException {
    int status = exception.getHttpStatus();
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");

    if (status == HttpServletResponse.SC_PAYMENT_REQUIRED && challenges != null) {
      for (ChallengeResponse challenge : challenges) {
        response.addHeader("WWW-Authenticate", challenge.wwwAuthenticateHeader());
      }
    }

    response.setContentType("application/problem+json");

    var sb = new StringBuilder();
    sb.append("{\"type\": \"")
        .append(JsonEscaper.escape(exception.getProblemTypeUri()))
        .append('"');
    sb.append(", \"title\": \"")
        .append(JsonEscaper.escape(exception.getErrorCode().name()))
        .append('"');
    sb.append(", \"status\": ").append(status);
    sb.append(", \"detail\": \"").append(JsonEscaper.escape(exception.getMessage())).append('"');
    if (exception.getTokenId() != null) {
      sb.append(", \"token_id\": \"")
          .append(JsonEscaper.escape(exception.getTokenId()))
          .append('"');
    }
    sb.append('}');
    response.getWriter().write(sb.toString());
  }

  /**
   * Serializes a value to JSON, appending to the given {@link StringBuilder}. Handles {@link Map},
   * {@link Number}, {@link Boolean}, and falls back to escaped string representation for all other
   * types.
   */
  @SuppressWarnings("unchecked")
  private static void appendJsonValue(StringBuilder sb, Object value) {
    switch (value) {
      case null -> sb.append("null");
      case Map<?, ?> map -> {
        sb.append('{');
        boolean first = true;
        for (var entry : ((Map<String, Object>) map).entrySet()) {
          if (!first) {
            sb.append(", ");
          }
          sb.append('"').append(JsonEscaper.escape(entry.getKey())).append("\": ");
          appendJsonValue(sb, entry.getValue());
          first = false;
        }
        sb.append('}');
      }
      case Number n -> sb.append(n);
      case Boolean b -> sb.append(b);
      default -> sb.append('"').append(JsonEscaper.escape(value.toString())).append('"');
    }
  }
}
