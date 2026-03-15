package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import com.greenharborlabs.l402.core.protocol.L402Validator;
import com.greenharborlabs.l402.core.util.JsonEscaper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    private static final String L402_PREFIX = "L402 ";
    private static final String LSAT_PREFIX = "LSAT ";

    private final L402EndpointRegistry registry;
    private final LightningBackend lightningBackend;
    private final RootKeyStore rootKeyStore;
    private final L402Validator validator;
    private final ApplicationContext applicationContext;
    private final String serviceName;
    private final L402Properties properties;
    private volatile L402Metrics metrics;
    private volatile L402EarningsTracker earningsTracker;
    private volatile L402RateLimiter rateLimiter;
    private volatile L402ChallengeService challengeService;

    /**
     * Primary constructor accepting a pre-built L402Validator and properties (used by auto-configuration).
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              L402Validator validator,
                              ApplicationContext applicationContext,
                              String serviceName,
                              L402Properties properties) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.lightningBackend = Objects.requireNonNull(lightningBackend, "lightningBackend must not be null");
        this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.applicationContext = applicationContext;
        this.serviceName = (serviceName == null || serviceName.isBlank()) ? "default" : serviceName;
        this.properties = properties;
    }

    /**
     * Backward-compatible primary constructor without properties parameter.
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              L402Validator validator,
                              ApplicationContext applicationContext,
                              String serviceName) {
        this(registry, lightningBackend, rootKeyStore, validator, applicationContext, serviceName, null);
    }

    /**
     * Backward-compatible constructor that creates the L402Validator internally.
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              CredentialStore credentialStore,
                              List<CaveatVerifier> caveatVerifiers,
                              String serviceName) {
        this(registry, lightningBackend, rootKeyStore, credentialStore,
                caveatVerifiers, serviceName, null);
    }

    /**
     * Backward-compatible constructor that creates the L402Validator internally.
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              CredentialStore credentialStore,
                              List<CaveatVerifier> caveatVerifiers,
                              String serviceName,
                              ApplicationContext applicationContext) {
        this(registry, lightningBackend, rootKeyStore, credentialStore,
                caveatVerifiers, serviceName, applicationContext, null);
    }

    /**
     * Backward-compatible constructor that creates the L402Validator internally,
     * with optional L402Properties for forwarded header trust configuration.
     */
    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              CredentialStore credentialStore,
                              List<CaveatVerifier> caveatVerifiers,
                              String serviceName,
                              ApplicationContext applicationContext,
                              L402Properties properties) {
        this(registry, lightningBackend, rootKeyStore,
                new L402Validator(
                        Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null"),
                        Objects.requireNonNull(credentialStore, "credentialStore must not be null"),
                        Objects.requireNonNull(caveatVerifiers, "caveatVerifiers must not be null"),
                        (serviceName == null || serviceName.isBlank()) ? "default" : serviceName),
                applicationContext,
                serviceName,
                properties);
    }

    /**
     * Sets the optional metrics recorder. When non-null, the filter will
     * record Micrometer counters at each decision point (challenge, pass, reject).
     */
    public void setMetrics(L402Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Sets the optional earnings tracker. When non-null, the filter will
     * record invoice creation and settlement events for the actuator endpoint.
     */
    public void setEarningsTracker(L402EarningsTracker earningsTracker) {
        this.earningsTracker = earningsTracker;
    }

    /**
     * Sets the optional rate limiter. When non-null, the filter will check
     * rate limits before issuing 402 challenges to prevent invoice flooding.
     */
    public void setRateLimiter(L402RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Sets the challenge service that handles health checks, rate limiting,
     * invoice creation, and macaroon minting. When not set, an internal
     * instance is created lazily from the filter's own dependencies.
     */
    public void setChallengeService(L402ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    /**
     * Returns the challenge service, creating an internal one lazily if none
     * was explicitly set via {@link #setChallengeService(L402ChallengeService)}.
     * The internal instance is kept in sync with the filter's optional dependencies
     * (metrics, earnings tracker, rate limiter).
     */
    private L402ChallengeService getOrCreateChallengeService() {
        L402ChallengeService svc = this.challengeService;
        if (svc != null) {
            return svc;
        }
        // Create an internal instance from the filter's own dependencies.
        // This preserves backward compatibility for callers that construct
        // the filter directly without an externally provided ChallengeService.
        svc = new L402ChallengeService(
                rootKeyStore, lightningBackend, properties,
                applicationContext);
        svc.setEarningsTracker(this.earningsTracker);
        svc.setRateLimiter(this.rateLimiter);
        this.challengeService = svc;
        return svc;
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
        if (authHeader != null && !authHeader.isEmpty()
                && (authHeader.startsWith(L402_PREFIX) || authHeader.startsWith(LSAT_PREFIX))) {
            // Header looks like an L402/LSAT credential — attempt validation (purely local, no Lightning needed)
            try {
                L402Validator.ValidationResult result = validator.validate(authHeader);
                L402Credential credential = result.credential();

                // Success: add expiry header and pass through
                log.log(System.Logger.Level.DEBUG, "L402 credential validated successfully, tokenId={0}", credential.tokenId());
                httpResponse.setHeader("X-L402-Credential-Expires",
                        Instant.now().plus(config.timeoutSeconds(), ChronoUnit.SECONDS).toString());

                chain.doFilter(request, response);
                recordPassed(path, config.priceSats(), result.freshValidation());
                return;

            } catch (L402Exception e) {
                // Consume rate limiter token on auth failure to penalize brute-force probing
                L402RateLimiter limiter = this.rateLimiter;
                if (limiter != null) {
                    limiter.tryAcquire(getOrCreateChallengeService().resolveClientIp(httpRequest));
                }
                ErrorCode errorCode = e.getErrorCode();
                log.log(System.Logger.Level.WARNING, "L402 validation failed, errorCode={0}, tokenId={1}", errorCode, e.getTokenId());
                if (errorCode == ErrorCode.MALFORMED_HEADER) {
                    // Clearly malformed L402 header — return 400 Bad Request, do not issue a new invoice
                    writeMalformedHeaderResponse(httpResponse, e.getMessage(), e.getTokenId());
                    recordRejected(path);
                    return;
                }
                // Non-malformed errors (expired, invalid signature, etc.) are terminal — no invoice needed
                writeErrorResponse(httpResponse, errorCode, e.getMessage(), e.getTokenId());
                recordRejected(path);
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
            L402ChallengeService svc = getOrCreateChallengeService();
            L402ChallengeResult challengeResult = svc.createChallenge(httpRequest, config);
            writePaymentRequiredResponse(httpResponse, challengeResult);
            recordChallenge(path);
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

    private void recordChallenge(String path) {
        try {
            if (metrics != null) { metrics.recordChallenge(path); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record challenge metric: {0}", e.getMessage());
        }
    }

    private void recordPassed(String path, long priceSats, boolean freshValidation) {
        try {
            if (metrics != null) { metrics.recordPassed(path, priceSats); }
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

    private void recordRejected(String path) {
        try {
            if (metrics != null) { metrics.recordRejected(path); }
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
