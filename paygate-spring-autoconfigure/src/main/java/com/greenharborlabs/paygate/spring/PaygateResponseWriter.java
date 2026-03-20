package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.util.JsonEscaper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Shared utility for writing L402 HTTP error responses in a consistent JSON format.
 * All methods are static; this class is not instantiable.
 */
public final class PaygateResponseWriter {

    private PaygateResponseWriter() {
    }

    /**
     * Writes a 402 Payment Required response with the L402 challenge.
     */
    public static void writePaymentRequired(HttpServletResponse response,
                                            PaygateChallengeResult result) throws IOException {
        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setHeader("WWW-Authenticate", result.wwwAuthenticateHeader());
        response.setContentType("application/json");
        String testPreimageField = "";
        if (result.testPreimage() != null) {
            testPreimageField = ", \"test_preimage\": \"" + result.testPreimage() + "\"";
        }
        response.getWriter().write("""
                {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"%s}"""
                .formatted(result.priceSats(), JsonEscaper.escape(result.description()),
                        JsonEscaper.escape(result.bolt11()), testPreimageField));
    }

    /**
     * Writes a 400 Bad Request response for a malformed L402 Authorization header.
     *
     * @param response the servlet response
     * @param message  detail message for the caller's logging (not included in JSON body)
     * @param tokenId  the token ID to include in the details, or null
     */
    public static void writeMalformedHeader(HttpServletResponse response,
                                            String message, String tokenId) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        String tokenDetail = tokenId != null ? tokenId : "";
        response.getWriter().write("""
                {"code": 400, "error": "MALFORMED_HEADER", "message": "Malformed L402 Authorization header", "details": {"token_id": "%s"}}"""
                .formatted(JsonEscaper.escape(tokenDetail)));
    }

    /**
     * Writes an error response for a credential validation failure.
     *
     * @param response  the servlet response
     * @param errorCode the error code determining HTTP status and error name
     * @param message   detail message for the caller's logging (not included in JSON body)
     * @param tokenId   the token ID to include in the details, or null
     */
    public static void writeValidationError(HttpServletResponse response, ErrorCode errorCode,
                                            String message, String tokenId) throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType("application/json");
        String tokenDetail = tokenId != null ? tokenId : "";
        String clientMessage = "Invalid L402 credential";
        response.getWriter().write("""
                {"code": %d, "error": "%s", "message": "%s", "details": {"token_id": "%s"}}"""
                .formatted(errorCode.getHttpStatus(), errorCode.name(),
                        JsonEscaper.escape(clientMessage), JsonEscaper.escape(tokenDetail)));
    }

    /**
     * Writes a 429 Too Many Requests response with a Retry-After header.
     */
    public static void writeRateLimited(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 429, "error": "RATE_LIMITED", "message": "Too many payment challenge requests. Please try again later."}""");
    }

    /**
     * Writes a 503 Service Unavailable response when the Lightning backend is down.
     */
    public static void writeLightningUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 503, "error": "LIGHTNING_UNAVAILABLE", "message": "Lightning backend is not available. Please try again later."}""");
    }

    /**
     * Writes a 401 Unauthorized response requiring a valid L402 credential.
     */
    public static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "L402");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 401, "error": "UNAUTHORIZED", "message": "Authentication required"}""");
    }

    /**
     * Writes a 401 response indicating L402 authentication failed.
     */
    public static void writeAuthenticationFailed(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "L402");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 401, "error": "AUTHENTICATION_FAILED", "message": "L402 authentication failed"}""");
    }
}
