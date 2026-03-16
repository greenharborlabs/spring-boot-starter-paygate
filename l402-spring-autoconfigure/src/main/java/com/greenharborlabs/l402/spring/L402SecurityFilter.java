package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import com.greenharborlabs.l402.core.protocol.L402HeaderComponents;
import com.greenharborlabs.l402.core.protocol.L402Validator;
import com.greenharborlabs.l402.core.util.JsonEscaper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Servlet filter that enforces L402 payment authentication on endpoints
 * registered in the {@link L402EndpointRegistry}.
 *
 * <p>Flow per request:
 * <ol>
 *   <li>Match request against registry; if no match, pass through</li>
 *   <li>If Authorization header contains an L402/LSAT credential, validate it immediately
 *       (no Lightning health check needed — credential validation is purely local)</li>
 *   <li>If no valid credential, delegate to {@link L402ChallengeService} which checks
 *       Lightning health, rate limits, and creates the 402 challenge</li>
 * </ol>
 */
public class L402SecurityFilter implements Filter {

    private static final System.Logger log = System.getLogger(L402SecurityFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final L402EndpointRegistry registry;
    private final L402Validator validator;
    private final L402ChallengeService challengeService;
    private final String serviceName;
    private final L402Metrics metrics;
    private final L402EarningsTracker earningsTracker;
    private final L402RateLimiter rateLimiter;

    /**
     * Canonical constructor. All dependencies are provided up front; optional
     * collaborators ({@code metrics}, {@code earningsTracker}, {@code rateLimiter})
     * may be {@code null}.
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              L402Validator validator,
                              L402ChallengeService challengeService,
                              String serviceName,
                              @Nullable L402Metrics metrics,
                              @Nullable L402EarningsTracker earningsTracker,
                              @Nullable L402RateLimiter rateLimiter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.challengeService = Objects.requireNonNull(challengeService, "challengeService must not be null");
        this.serviceName = (serviceName == null || serviceName.isBlank()) ? "default" : serviceName;
        this.metrics = metrics;
        this.earningsTracker = earningsTracker;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String method = httpRequest.getMethod();
        String path;
        try {
            path = normalizePath(httpRequest.getRequestURI());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Rejected request with malformed URI: {0}", httpRequest.getRequestURI());
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
            recordRejected("_malformed");
            return;
        }

        // 1. Check if this endpoint is protected
        L402EndpointConfig config = registry.findConfig(method, path);
        if (config == null) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Check Authorization header — validate credentials before checking Lightning health,
        //    so requests with valid cached credentials skip the health-check cost entirely.
        String authHeader = httpRequest.getHeader(AUTHORIZATION_HEADER);
        var componentsOpt = L402HeaderComponents.extract(authHeader);
        if (componentsOpt.isEmpty() && L402HeaderComponents.isL402Header(authHeader)) {
            // Header starts with L402/LSAT prefix but fails structural validation — malformed
            writeMalformedHeaderResponse(httpResponse, "Malformed L402 Authorization header", null);
            recordRejected(config.pathPattern());
            return;
        }
        if (componentsOpt.isPresent()) {
            var components = componentsOpt.get();
            // Header matches L402/LSAT format — attempt validation (purely local, no Lightning needed)
            try {
                // Build per-request context with capability from endpoint config
                L402VerificationContext context = L402VerificationContext.builder()
                        .serviceName(serviceName)
                        .currentTime(Instant.now())
                        .requestedCapability(config.capability().isEmpty() ? null : config.capability())
                        .build();
                L402Validator.ValidationResult result = validator.validate(components, context);
                L402Credential credential = result.credential();

                // Success: add expiry header and pass through
                log.log(System.Logger.Level.DEBUG, "L402 credential validated successfully, tokenId={0}", credential.tokenId());
                httpResponse.setHeader("X-L402-Credential-Expires",
                        Instant.now().plus(config.timeoutSeconds(), ChronoUnit.SECONDS).toString());

                chain.doFilter(request, response);
                recordPassed(config.pathPattern(), config.priceSats(), result.freshValidation());
                return;

            } catch (L402Exception e) {
                // Consume rate limiter token on auth failure to penalize brute-force probing
                L402RateLimiter limiter = this.rateLimiter;
                if (limiter != null) {
                    limiter.tryAcquire(this.challengeService.resolveClientIp(httpRequest));
                }
                ErrorCode errorCode = e.getErrorCode();
                log.log(System.Logger.Level.WARNING, "L402 validation failed, errorCode={0}, tokenId={1}", errorCode, e.getTokenId());
                if (errorCode == ErrorCode.MALFORMED_HEADER) {
                    // Clearly malformed L402 header — return 400 Bad Request, do not issue a new invoice
                    writeMalformedHeaderResponse(httpResponse, e.getMessage(), e.getTokenId());
                    recordRejected(config.pathPattern());
                    return;
                }
                // Non-malformed errors (expired, invalid signature, etc.) are terminal — no invoice needed
                writeErrorResponse(httpResponse, errorCode, e.getMessage(), e.getTokenId());
                recordRejected(config.pathPattern());
                return;
            } catch (Exception e) {
                // Fail closed: any unexpected exception from validation produces 503, never 500
                log.log(System.Logger.Level.WARNING, "Unexpected error during L402 validation for {0} {1}: {2}", method, path, e.getMessage());
                if (!httpResponse.isCommitted()) {
                    writeLightningUnavailableResponse(httpResponse);
                }
                return;
            }
        }

        // 3. No valid credential — delegate to ChallengeService for health check,
        //    rate limiting, invoice creation, and macaroon minting.
        try {
            L402ChallengeResult challengeResult = this.challengeService.createChallenge(httpRequest, config);
            writePaymentRequiredResponse(httpResponse, challengeResult);
            recordChallenge(config.pathPattern());
        } catch (L402RateLimitedException _) {
            writeRateLimitedResponse(httpResponse);
        } catch (L402LightningUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "Lightning unavailable for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                writeLightningUnavailableResponse(httpResponse);
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                writeLightningUnavailableResponse(httpResponse);
            }
        }
    }

    private void writePaymentRequiredResponse(HttpServletResponse response,
                                               L402ChallengeResult result)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setHeader("WWW-Authenticate", result.wwwAuthenticateHeader());
        response.setContentType("application/json");

        String testPreimageField = "";
        if (result.testPreimage() != null) {
            testPreimageField = ", \"test_preimage\": \"" + result.testPreimage() + "\"";
        }

        response.getWriter().write("""
                {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"%s}"""
                .formatted(result.priceSats(), JsonEscaper.escape(result.description()), JsonEscaper.escape(result.bolt11()), testPreimageField));
    }

    private void writeMalformedHeaderResponse(HttpServletResponse response,
                                                 String message, String tokenId) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");

        String tokenDetail = tokenId != null ? tokenId : "";
        log.log(System.Logger.Level.WARNING, "Malformed L402 header for token {0}: {1}", tokenDetail, message);

        response.getWriter().write("""
                {"code": 400, "error": "MALFORMED_HEADER", "message": "Malformed L402 Authorization header", "details": {"token_id": "%s"}}"""
                .formatted(JsonEscaper.escape(tokenDetail)));
    }

    private void writeErrorResponse(HttpServletResponse response, ErrorCode errorCode,
                                    String message, String tokenId) throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType("application/json");

        String tokenDetail = tokenId != null ? tokenId : "";
        log.log(System.Logger.Level.WARNING, "L402 validation failed for token {0}: {1}", tokenDetail, message);

        String clientMessage = "Invalid L402 credential";
        response.getWriter().write("""
                {"code": %d, "error": "%s", "message": "%s", "details": {"token_id": "%s"}}"""
                .formatted(errorCode.getHttpStatus(), errorCode.name(),
                        JsonEscaper.escape(clientMessage), JsonEscaper.escape(tokenDetail)));
    }

    private void writeRateLimitedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 429, "error": "RATE_LIMITED", "message": "Too many payment challenge requests. Please try again later."}""");
    }

    private void writeLightningUnavailableResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 503, "error": "LIGHTNING_UNAVAILABLE", "message": "Lightning backend is not available. Please try again later."}""");
    }

    private void recordChallenge(String endpoint) {
        try {
            if (metrics != null) { metrics.recordChallenge(endpoint); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record challenge metric: {0}", e.getMessage());
        }
    }

    private void recordPassed(String endpoint, long priceSats, boolean freshValidation) {
        try {
            if (metrics != null) { metrics.recordPassed(endpoint, priceSats); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record passed metric: {0}", e.getMessage());
        }
        if (freshValidation) {
            try {
                if (earningsTracker != null) { earningsTracker.recordInvoiceSettled(priceSats); }
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "Failed to record invoice settlement in earnings tracker: {0}", e.getMessage());
            }
        }
    }

    private void recordRejected(String endpoint) {
        try {
            if (metrics != null) { metrics.recordRejected(endpoint); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record rejected metric: {0}", e.getMessage());
        }
    }

    /**
     * Delegates to {@link L402PathUtils#normalizePath(String)}.
     */
    static String normalizePath(String rawPath) {
        return L402PathUtils.normalizePath(rawPath);
    }

    /**
     * Delegates to {@link L402PathUtils#percentDecodePath(String)}.
     */
    static String percentDecodePath(String path) {
        return L402PathUtils.percentDecodePath(path);
    }

}
