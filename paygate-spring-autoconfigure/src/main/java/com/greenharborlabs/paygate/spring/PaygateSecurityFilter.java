package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.protocol.l402.L402Metadata;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/**
 * Servlet filter that enforces payment authentication on endpoints
 * registered in the {@link PaygateEndpointRegistry}.
 *
 * <p>Flow per request:
 * <ol>
 *   <li>Match request against registry; if no match, pass through</li>
 *   <li>If Authorization header is present, iterate registered protocols to find
 *       one that can handle it; validate the credential (purely local, no Lightning
 *       health check needed)</li>
 *   <li>If no valid credential, delegate to {@link PaygateChallengeService} which checks
 *       Lightning health, rate limits, and creates the 402 challenge</li>
 * </ol>
 */
public class PaygateSecurityFilter implements Filter {

    private static final System.Logger log = System.getLogger(PaygateSecurityFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final PaygateEndpointRegistry registry;
    private final List<PaymentProtocol> protocols;
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
                              List<PaymentProtocol> protocols,
                              PaygateChallengeService challengeService,
                              String serviceName,
                              @Nullable ClientIpResolver clientIpResolver,
                              @Nullable PaygateMetrics metrics,
                              @Nullable PaygateEarningsTracker earningsTracker,
                              @Nullable PaygateRateLimiter rateLimiter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols must not be null"));
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
            recordRejected("_malformed", "unknown");
            return;
        }

        String safePath = sanitizeForLog(path);

        // 1. Check if this endpoint is protected
        PaygateEndpointConfig config = registry.findConfig(method, path);
        if (config == null) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Check Authorization header — validate credentials before checking Lightning health,
        //    so requests with valid cached credentials skip the health-check cost entirely.
        String authHeader = httpRequest.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null) {
            for (PaymentProtocol protocol : protocols) {
                if (protocol.canHandle(authHeader)) {
                    // Found matching protocol — try validate
                    long verifyStart = System.nanoTime();
                    try {
                        PaymentCredential credential = protocol.parseCredential(authHeader);

                        // Build requestContext for protocol.validate()
                        Map<String, String> requestContext = buildRequestContext(httpRequest, path, method, config);
                        protocol.validate(credential, requestContext);

                        // Success — handle protocol-specific post-validation
                        log.log(System.Logger.Level.DEBUG, "{0} credential validated successfully", protocol.scheme());

                        if ("L402".equals(protocol.scheme())) {
                            handleL402Success(credential, config, httpResponse);
                        }

                        // Check for receipt (MPP produces receipts, L402 does not)
                        try {
                            ChallengeContext receiptContext = new ChallengeContext(
                                    credential.paymentHash(),
                                    credential.tokenId(),
                                    "",  // bolt11 not needed for receipt
                                    config.priceSats(),
                                    config.description(),
                                    serviceName,
                                    config.timeoutSeconds(),
                                    config.capability(),
                                    null,  // rootKeyBytes not needed for receipt
                                    null,  // opaque
                                    null   // digest
                            );
                            Optional<PaymentReceipt> receiptOpt = protocol.createReceipt(credential, receiptContext);
                            if (receiptOpt.isPresent()) {
                                PaygateResponseWriter.writeReceipt(httpResponse, receiptOpt.get());
                            }
                        } catch (Exception _) {
                            // Receipt creation is best-effort; failure does not block the request
                            log.log(System.Logger.Level.DEBUG,
                                    "Receipt creation skipped for {0}: not applicable", protocol.scheme());
                        }

                        chain.doFilter(request, response);
                        recordCaveatVerifyDuration(verifyStart);
                        // Treat all validated credentials as freshValidation=true; the credential
                        // cache inside L402Validator handles deduplication internally.
                        recordPassed(config.pathPattern(), config.priceSats(), true, protocol.scheme());
                        return;

                    } catch (PaymentValidationException e) {
                        recordCaveatVerifyDuration(verifyStart);
                        // classifyCaveatType only does keyword matching and returns a constant string —
                        // sanitize the message anyway to satisfy static analysis taint tracking.
                        recordCaveatRejected(sanitizeForLog(e.getMessage() != null ? e.getMessage() : ""));
                        // Consume rate limiter token on auth failure to penalize brute-force probing
                        PaygateRateLimiter limiter = this.rateLimiter;
                        if (limiter != null) {
                            limiter.tryAcquire(this.challengeService.resolveClientIp(httpRequest));
                        }
                        // Sanitize the token ID before logging — it is derived from user-supplied input
                        // and could contain newlines or other control characters used in log injection attacks.
                        String tokenDetail = sanitizeForLog(e.getTokenId() != null ? e.getTokenId() : "");
                        int tokenCorrelationId = Objects.hash(protocol.scheme(), tokenDetail);
                        log.log(System.Logger.Level.WARNING, "{0} validation failed, errorCode={1}",
                                protocol.scheme(), e.getErrorCode());

                        if (e.getErrorCode() == PaymentValidationException.ErrorCode.METHOD_UNSUPPORTED) {
                            PaygateResponseWriter.writeMethodUnsupported(httpResponse, e.getMessage());
                            recordRejected(config.pathPattern(), protocol.scheme());
                            return;
                        }

                        if (e.getErrorCode() == PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL
                                && "L402".equals(protocol.scheme())) {
                            // L402-specific: clearly malformed header — return 400 Bad Request
                            // Avoid logging the raw token to prevent leaking potentially sensitive information.
                            log.log(System.Logger.Level.WARNING, "Malformed L402 header for protocol {0}",
                                    protocol.scheme());
                            PaygateResponseWriter.writeMalformedHeader(httpResponse, e.getMessage(), e.getTokenId());
                            recordRejected(config.pathPattern(), protocol.scheme());
                            return;
                        }

                        // For non-L402 protocols or non-malformed errors: use RFC 9457 error response
                        // Issue fresh challenges so the client can retry with a new payment.
                        // tokenDetail is sanitized; log only a non-sensitive correlation ID and the error code enum,
                        // never the exception message or the raw token value.
                        log.log(System.Logger.Level.WARNING,
                                "{0} validation failed for tokenCorrelationId {1}, errorCode={2}",
                                protocol.scheme(), tokenCorrelationId, e.getErrorCode());
                        try {
                            ChallengeContext challengeContext = challengeService.createChallenge(httpRequest, config);
                            List<ChallengeResponse> challenges = buildChallenges(challengeContext);
                            PaygateResponseWriter.writeMppError(httpResponse, e, challenges);
                        } catch (Exception challengeEx) {
                            // If challenge creation fails, write the error without challenges
                            PaygateResponseWriter.writeMppError(httpResponse, e, List.of());
                        }
                        recordRejected(config.pathPattern(), protocol.scheme());
                        return;

                    } catch (Exception e) {
                        recordCaveatVerifyDuration(verifyStart);
                        if (e instanceof MacaroonVerificationException) {
                            recordCaveatRejected(sanitizeForLog(e.getMessage() != null ? e.getMessage() : ""));
                        }
                        // Fail closed: any unexpected exception from validation produces 503, never 500
                        String safeMessage = sanitizeForLog(e.getMessage());
                        log.log(System.Logger.Level.WARNING, "Unexpected error during {0} validation for {1} {2}: {3}",
                                protocol.scheme(), method, safePath, safeMessage);
                        if (!httpResponse.isCommitted()) {
                            PaygateResponseWriter.writeLightningUnavailable(httpResponse);
                        }
                        return;
                    }
                }
            }
            // No protocol matched the auth header — fall through to challenge
        }

        // 3. No valid credential — delegate to ChallengeService for health check,
        //    rate limiting, invoice creation, and macaroon minting.
        try {
            ChallengeContext challengeContext = challengeService.createChallenge(httpRequest, config);
            List<ChallengeResponse> challenges = buildChallenges(challengeContext);
            PaygateResponseWriter.writePaymentRequired(httpResponse, challengeContext, challenges);
            recordChallenge(config.pathPattern(), "all");
        } catch (PaygateRateLimitedException _) {
            PaygateResponseWriter.writeRateLimited(httpResponse);
        } catch (PaygateLightningUnavailableException e) {
            // Log exception type only — the message may contain internal backend hostnames/addresses.
            log.log(System.Logger.Level.WARNING, "Lightning unavailable for {0} {1}: {2}",
                    method, safePath, e.getClass().getSimpleName());
            if (!httpResponse.isCommitted()) {
                PaygateResponseWriter.writeLightningUnavailable(httpResponse);
            }
        } catch (Exception e) {
            // Log exception type only — the message may contain internal backend details.
            log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}",
                    method, safePath, e.getClass().getSimpleName());
            if (!httpResponse.isCommitted()) {
                PaygateResponseWriter.writeLightningUnavailable(httpResponse);
            }
        }
    }

    /**
     * Builds a request context map for protocol validation, including path, method,
     * client IP, and requested capability using the standard {@link VerificationContextKeys}.
     */
    private Map<String, String> buildRequestContext(HttpServletRequest httpRequest,
                                                     String path, String method,
                                                     PaygateEndpointConfig config) {
        String clientIp = clientIpResolver != null
                ? clientIpResolver.resolve(httpRequest)
                : httpRequest.getRemoteAddr();
        String capability = config.capability();
        if (capability != null && !capability.isEmpty()) {
            return Map.of(
                    VerificationContextKeys.REQUEST_PATH, path,
                    VerificationContextKeys.REQUEST_METHOD, method,
                    VerificationContextKeys.REQUEST_CLIENT_IP, clientIp,
                    VerificationContextKeys.REQUESTED_CAPABILITY, capability);
        }
        return Map.of(
                VerificationContextKeys.REQUEST_PATH, path,
                VerificationContextKeys.REQUEST_METHOD, method,
                VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
    }

    /**
     * Builds challenge responses from all registered protocols.
     */
    private List<ChallengeResponse> buildChallenges(ChallengeContext challengeContext) {
        List<ChallengeResponse> challenges = new ArrayList<>();
        for (PaymentProtocol protocol : protocols) {
            challenges.add(protocol.formatChallenge(challengeContext));
        }
        return challenges;
    }

    /**
     * Handles L402-specific post-validation behavior: sets the credential expiry header.
     */
    private void handleL402Success(PaymentCredential credential,
                                    PaygateEndpointConfig config,
                                    HttpServletResponse httpResponse) {
        httpResponse.setHeader("X-L402-Credential-Expires",
                resolveCredentialExpiry(credential, config).toString());
    }

    /**
     * Extracts the credential expiry from the earliest {@code {serviceName}_valid_until}
     * caveat on the macaroon. Falls back to {@code Instant.now() + config.timeoutSeconds()}
     * when no valid_until caveat is present or parseable.
     *
     * <p>For L402 credentials, the macaroon is extracted from {@link L402Metadata}.
     * For non-L402 credentials, falls back to the default timeout.
     */
    Instant resolveCredentialExpiry(PaymentCredential credential, PaygateEndpointConfig config) {
        if (credential.metadata() instanceof L402Metadata l402Meta) {
            String caveatKey = serviceName + "_valid_until";
            OptionalLong earliest = l402Meta.macaroon().caveats().stream()
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
        }
        return Instant.now().plus(config.timeoutSeconds(), ChronoUnit.SECONDS);
    }

    private void recordChallenge(String endpoint, String protocol) {
        try {
            if (metrics != null) { metrics.recordChallenge(endpoint, protocol); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record challenge metric: {0}", e.getMessage());
        }
    }

    private void recordPassed(String endpoint, long priceSats, boolean freshValidation, String protocol) {
        try {
            if (metrics != null) { metrics.recordPassed(endpoint, priceSats, protocol); }
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

    private void recordRejected(String endpoint, String protocol) {
        try {
            if (metrics != null) { metrics.recordRejected(endpoint, protocol); }
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
