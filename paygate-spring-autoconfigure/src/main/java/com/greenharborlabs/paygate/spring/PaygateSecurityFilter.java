package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;

import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/**
 * Servlet filter that enforces L402 payment authentication on endpoints
 * registered in the {@link PaygateEndpointRegistry}.
 *
 * <p>Flow per request:
 * <ol>
 *   <li>Match request against registry; if no match, pass through</li>
 *   <li>If Authorization header contains an L402/LSAT credential, validate it immediately
 *       (no Lightning health check needed — credential validation is purely local)</li>
 *   <li>If no valid credential, delegate to {@link PaygateChallengeService} which checks
 *       Lightning health, rate limits, and creates the 402 challenge</li>
 * </ol>
 */
public class PaygateSecurityFilter implements Filter {

    private static final System.Logger log = System.getLogger(PaygateSecurityFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final PaygateEndpointRegistry registry;
    private final L402Validator validator;
    private final PaygateChallengeService challengeService;
    private final String serviceName;
    private final ClientIpResolver clientIpResolver;
    private final PaygateMetrics metrics;
    private final PaygateEarningsTracker earningsTracker;
    private final PaygateRateLimiter rateLimiter;

    /**
     * Canonical constructor. All dependencies are provided up front; optional
     * collaborators ({@code metrics}, {@code earningsTracker}, {@code rateLimiter})
     * may be {@code null}.
     */
    public PaygateSecurityFilter(PaygateEndpointRegistry registry,
                              L402Validator validator,
                              PaygateChallengeService challengeService,
                              String serviceName,
                              @Nullable ClientIpResolver clientIpResolver,
                              @Nullable PaygateMetrics metrics,
                              @Nullable PaygateEarningsTracker earningsTracker,
                              @Nullable PaygateRateLimiter rateLimiter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.challengeService = Objects.requireNonNull(challengeService, "challengeService must not be null");
        this.serviceName = (serviceName == null || serviceName.isBlank()) ? "default" : serviceName;
        this.clientIpResolver = clientIpResolver;
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
                    "Rejected request with malformed URI: {0}", sanitizeForLog(httpRequest.getRequestURI()));
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
            recordRejected("_malformed");
            return;
        }

        // 1. Check if this endpoint is protected
        PaygateEndpointConfig config = registry.findConfig(method, path);
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
            log.log(System.Logger.Level.WARNING, "Malformed L402 header for token {0}: {1}", "", "Malformed L402 Authorization header");
            PaygateResponseWriter.writeMalformedHeader(httpResponse, "Malformed L402 Authorization header", null);
            recordRejected(config.pathPattern());
            return;
        }
        if (componentsOpt.isPresent()) {
            var components = componentsOpt.get();
            // Header matches L402/LSAT format — attempt validation (purely local, no Lightning needed)
            long verifyStart = System.nanoTime();
            try {
                // Build per-request context with capability from endpoint config
                String clientIp = clientIpResolver != null
                        ? clientIpResolver.resolve(httpRequest)
                        : httpRequest.getRemoteAddr();
                L402VerificationContext context = L402VerificationContext.builder()
                        .serviceName(serviceName)
                        .currentTime(Instant.now())
                        .requestedCapability(config.capability().isEmpty() ? null : config.capability())
                        .requestMetadata(Map.of(
                                VerificationContextKeys.REQUEST_PATH, path,
                                VerificationContextKeys.REQUEST_METHOD, method,
                                VerificationContextKeys.REQUEST_CLIENT_IP, clientIp))
                        .build();
                L402Validator.ValidationResult result = validator.validate(components, context);
                L402Credential credential = result.credential();

                // Success: add expiry header and pass through
                log.log(System.Logger.Level.DEBUG, "L402 credential validated successfully");
                httpResponse.setHeader("X-L402-Credential-Expires",
                        resolveCredentialExpiry(credential, config).toString());

                chain.doFilter(request, response);
                recordCaveatVerifyDuration(verifyStart);
                recordPassed(config.pathPattern(), config.priceSats(), result.freshValidation());
                return;

            } catch (L402Exception e) {
                recordCaveatVerifyDuration(verifyStart);
                recordCaveatRejected(e.getMessage());
                // Consume rate limiter token on auth failure to penalize brute-force probing
                PaygateRateLimiter limiter = this.rateLimiter;
                if (limiter != null) {
                    limiter.tryAcquire(this.challengeService.resolveClientIp(httpRequest));
                }
                ErrorCode errorCode = e.getErrorCode();
                String tokenDetail = e.getTokenId() != null ? e.getTokenId() : "";
                log.log(System.Logger.Level.WARNING, "L402 validation failed, errorCode={0}", errorCode);
                if (errorCode == ErrorCode.MALFORMED_HEADER) {
                    // Clearly malformed L402 header — return 400 Bad Request, do not issue a new invoice
                    log.log(System.Logger.Level.WARNING, "Malformed L402 header for token {0}: {1}", tokenDetail, e.getMessage());
                    PaygateResponseWriter.writeMalformedHeader(httpResponse, e.getMessage(), e.getTokenId());
                    recordRejected(config.pathPattern());
                    return;
                }
                // Non-malformed errors (expired, invalid signature, etc.) are terminal — no invoice needed
                log.log(System.Logger.Level.WARNING, "L402 validation failed for token {0}: {1}", tokenDetail, e.getMessage());
                PaygateResponseWriter.writeValidationError(httpResponse, errorCode, e.getMessage(), e.getTokenId());
                recordRejected(config.pathPattern());
                return;
            } catch (Exception e) {
                recordCaveatVerifyDuration(verifyStart);
                if (e instanceof MacaroonVerificationException) {
                    recordCaveatRejected(e.getMessage());
                }
                // Fail closed: any unexpected exception from validation produces 503, never 500
                log.log(System.Logger.Level.WARNING, "Unexpected error during L402 validation for {0} {1}: {2}", method, path, e.getMessage());
                if (!httpResponse.isCommitted()) {
                    PaygateResponseWriter.writeLightningUnavailable(httpResponse);
                }
                return;
            }
        }

        // 3. No valid credential — delegate to ChallengeService for health check,
        //    rate limiting, invoice creation, and macaroon minting.
        try {
            PaygateChallengeResult challengeResult = this.challengeService.createChallenge(httpRequest, config);
            PaygateResponseWriter.writePaymentRequired(httpResponse, challengeResult);
            recordChallenge(config.pathPattern());
        } catch (PaygateRateLimitedException _) {
            PaygateResponseWriter.writeRateLimited(httpResponse);
        } catch (PaygateLightningUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "Lightning unavailable for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                PaygateResponseWriter.writeLightningUnavailable(httpResponse);
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                PaygateResponseWriter.writeLightningUnavailable(httpResponse);
            }
        }
    }

    /**
     * Extracts the credential expiry from the earliest {@code {serviceName}_valid_until}
     * caveat on the macaroon. Falls back to {@code Instant.now() + config.timeoutSeconds()}
     * when no valid_until caveat is present or parseable.
     */
    Instant resolveCredentialExpiry(L402Credential credential, PaygateEndpointConfig config) {
        String caveatKey = serviceName + "_valid_until";
        OptionalLong earliest = credential.macaroon().caveats().stream()
                .filter(c -> caveatKey.equals(c.key()))
                .flatMapToLong(c -> {
                    try {
                        return LongStream.of(Long.parseLong(c.value()));
                    } catch (NumberFormatException e) {
                        log.log(System.Logger.Level.WARNING,
                                "Ignoring unparseable valid_until caveat for key {0}: {1}",
                                caveatKey, e.getMessage());
                        return LongStream.empty();
                    }
                })
                .min();
        if (earliest.isPresent()) {
            return Instant.ofEpochSecond(earliest.getAsLong());
        }
        return Instant.now().plus(config.timeoutSeconds(), ChronoUnit.SECONDS);
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

    private void recordCaveatVerifyDuration(long startNanos) {
        try {
            if (metrics != null) { metrics.recordCaveatVerifyDuration(System.nanoTime() - startNanos); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record caveat verify duration metric: {0}", e.getMessage());
        }
    }

    private void recordCaveatRejected(String exceptionMessage) {
        try {
            if (metrics != null) { metrics.recordCaveatRejected(classifyCaveatType(exceptionMessage)); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record caveat rejected metric: {0}", e.getMessage());
        }
    }

    /**
     * Classifies the caveat type from an exception message thrown during caveat verification.
     * Uses known message patterns from the delegation caveat verifiers:
     * <ul>
     *   <li>{@code path} — PathCaveatVerifier messages contain "path"</li>
     *   <li>{@code method} — MethodCaveatVerifier messages contain "method" (but not "Request method missing")</li>
     *   <li>{@code client_ip} — ClientIpCaveatVerifier messages contain "client IP" or "Client IP"</li>
     *   <li>{@code escalation} — MacaroonVerificationException messages contain "escalation"</li>
     * </ul>
     * Falls back to {@code "unknown"} if no pattern matches.
     */
    static String classifyCaveatType(String message) {
        if (message == null) {
            return "unknown";
        }
        String lower = message.toLowerCase();
        if (lower.contains("escalation")) {
            return "escalation";
        }
        if (lower.contains("client ip")) {
            return "client_ip";
        }
        if (lower.contains("path")) {
            return "path";
        }
        if (lower.contains("method")) {
            return "method";
        }
        return "unknown";
    }

    /**
     * Delegates to {@link PaygatePathUtils#normalizePath(String)}.
     */
    static String normalizePath(String rawPath) {
        return PaygatePathUtils.normalizePath(rawPath);
    }

    static String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.codePoints()
                .filter(cp -> cp >= 0x20 && cp != 0x7F)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

}
