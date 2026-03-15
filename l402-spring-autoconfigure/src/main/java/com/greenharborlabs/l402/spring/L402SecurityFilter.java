package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.KeyMaterial;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
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
 *   <li>If no valid credential, check Lightning backend health; if down, return 503</li>
 *   <li>Create invoice and return 402 challenge</li>
 * </ol>
 */
public class L402SecurityFilter implements Filter {

    private static final System.Logger log = System.getLogger(L402SecurityFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String L402_PREFIX = "L402 ";
    private static final String LSAT_PREFIX = "LSAT ";

    /** Macaroon binary format version — V2 as defined by the L402 identifier layout. */
    private static final int MACAROON_IDENTIFIER_VERSION = 0;

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
    private volatile boolean reverseProxyWarningLogged;

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
                    limiter.tryAcquire(resolveClientIp(httpRequest));
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

        // 3. No valid credential — an invoice must be created.
        //    Check Lightning health first; only needed on this path.
        if (!lightningBackend.isHealthy()) {
            log.log(System.Logger.Level.WARNING, "Lightning backend health check failed for {0} {1}", method, path);
            writeLightningUnavailableResponse(httpResponse);
            return;
        }

        // 4. Check rate limit before creating invoice to prevent flooding
        L402RateLimiter limiter = this.rateLimiter;
        if (limiter != null && !limiter.tryAcquire(resolveClientIp(httpRequest))) {
            writeRateLimitedResponse(httpResponse);
            return;
        }

        // 5. Issue 402 challenge with a new invoice
        try {
            writePaymentRequiredResponse(httpRequest, httpResponse, config);
            recordChallenge(path);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                writeLightningUnavailableResponse(httpResponse);
            }
        }
    }

    // NOTE: This method performs three sequential blocking operations:
    // (1) rootKeyStore.generateRootKey() — file I/O with write lock
    // (2) lightningBackend.createInvoice() — synchronous network call
    // (3) MacaroonMinter.mint() — CPU-bound HMAC computation
    // The CachingLightningBackendWrapper mitigates (2) for health checks.
    // Future optimization: consider virtual threads or structured concurrency
    // to parallelize (1) and (2) when they are independent.
    private void writePaymentRequiredResponse(HttpServletRequest request,
                                               HttpServletResponse response,
                                               L402EndpointConfig config)
            throws IOException {

        // Generate root key and tokenId atomically; try-with-resources ensures
        // SensitiveBytes.destroy() is called even if an exception is thrown.
        try (RootKeyStore.GenerationResult generationResult = rootKeyStore.generateRootKey()) {
            byte[] rootKey = generationResult.rootKey().value();
            try {
                byte[] tokenId = generationResult.tokenId();

                // Resolve effective price: dynamic strategy overrides static annotation value
                long effectivePrice = resolvePrice(request, config);

                // Create Lightning invoice
                Invoice invoice = lightningBackend.createInvoice(effectivePrice, config.description());

                // Record invoice creation in earnings tracker
                try {
                    if (earningsTracker != null) { earningsTracker.recordInvoiceCreated(); }
                } catch (Exception e) {
                    log.log(System.Logger.Level.WARNING, "Failed to record invoice creation in earnings tracker: {0}", e.getMessage());
                }

                // Build MacaroonIdentifier and mint macaroon with service and expiry caveats
                MacaroonIdentifier identifier = new MacaroonIdentifier(MACAROON_IDENTIFIER_VERSION, invoice.paymentHash(), tokenId);
                Instant validUntil = Instant.now().plusSeconds(config.timeoutSeconds());
                List<Caveat> caveats = List.of(
                        new Caveat("services", serviceName + ":0"),
                        new Caveat(serviceName + "_valid_until", String.valueOf(validUntil.getEpochSecond()))
                );
                Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);

                // Serialize and encode
                byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
                String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

                // Build response — sanitize bolt11 to prevent header injection and JSON breaking
                String safeBolt11Header = sanitizeBolt11ForHeader(invoice.bolt11());
                String wwwAuth = "L402 version=\"0\", token=\"" + macaroonBase64
                        + "\", macaroon=\"" + macaroonBase64
                        + "\", invoice=\"" + safeBolt11Header + "\"";

                response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
                response.setHeader("WWW-Authenticate", wwwAuth);
                response.setContentType("application/json");

                // In test mode the backend includes the preimage on the PENDING invoice so
                // users can complete the full L402 flow with curl. Real backends never set
                // preimage on PENDING invoices, so this field only appears in test mode.
                String testPreimageField = "";
                byte[] invoicePreimage = invoice.preimage();
                if (invoicePreimage != null) {
                    testPreimageField = ", \"test_preimage\": \"" + HexFormat.of().formatHex(invoicePreimage) + "\"";
                }

                response.getWriter().write("""
                        {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"%s}"""
                        .formatted(effectivePrice, JsonEscaper.escape(config.description()), JsonEscaper.escape(invoice.bolt11()), testPreimageField));
            } finally {
                KeyMaterial.zeroize(rootKey);
            }
        }
    }

    /**
     * Resolves the effective price for an endpoint by looking up the pricing strategy
     * bean from the ApplicationContext. Falls back to the static annotation price if
     * no strategy is configured, the ApplicationContext is unavailable, or the bean
     * does not exist.
     */
    private long resolvePrice(HttpServletRequest request, L402EndpointConfig config) {
        String strategyName = config.pricingStrategy();
        if (strategyName == null || strategyName.isBlank() || applicationContext == null) {
            return config.priceSats();
        }
        try {
            L402PricingStrategy strategy = applicationContext.getBean(strategyName, L402PricingStrategy.class);
            return strategy.calculatePrice(request, config.priceSats());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Pricing strategy bean ''{0}'' not found or failed; falling back to static price {1} sats",
                    strategyName, config.priceSats());
            return config.priceSats();
        }
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
     * Strips characters from a bolt11 string that could enable HTTP header injection
     * or break the WWW-Authenticate header format. Removes double quotes, carriage
     * returns, and newlines.
     */
    private static String sanitizeBolt11ForHeader(String bolt11) {
        if (bolt11 == null) {
            return "";
        }
        var sb = new StringBuilder(bolt11.length());
        for (int i = 0; i < bolt11.length(); i++) {
            char c = bolt11.charAt(i);
            if (c != '"' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the client IP address. Only reads the X-Forwarded-For header
     * when {@code trustForwardedHeaders} is explicitly enabled in properties,
     * to prevent rate-limit bypass via header spoofing.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (this.properties != null && this.properties.isTrustForwardedHeaders()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // X-Forwarded-For: client, proxy1, proxy2 — take leftmost
                int comma = xff.indexOf(',');
                String ip = (comma > 0 ? xff.substring(0, comma) : xff).strip();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        } else if (!reverseProxyWarningLogged) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                reverseProxyWarningLogged = true;
                log.log(System.Logger.Level.WARNING,
                        "X-Forwarded-For header detected but trustForwardedHeaders is false. "
                                + "Rate limiting uses the direct remote address, which may be the proxy IP. "
                                + "If this service is behind a reverse proxy, set l402.trust-forwarded-headers=true "
                                + "to use the client IP from X-Forwarded-For for rate limiting.");
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Normalizes a raw request URI to collapse path traversal sequences
     * ({@code .} and {@code ..}) before endpoint registry lookup, preventing
     * bypass of protected endpoint matching. Handles percent-encoded traversal
     * sequences ({@code %2e%2e}, double-encoded {@code %252e%252e}) that
     * {@code URI.normalize()} does not decode.
     */
    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }

        // Step 1: Iteratively percent-decode until stable (max 3 iterations)
        // to handle double- and triple-encoded traversal sequences.
        String decoded = percentDecodePath(rawPath);
        String prev = rawPath;
        int iterations = 0;
        while (!decoded.equals(prev) && iterations < 3) {
            prev = decoded;
            decoded = percentDecodePath(decoded);
            iterations++;
        }

        // Step 2: Collapse . and .. using a segment stack
        String[] segments = decoded.split("/", -1);
        var stack = new java.util.ArrayDeque<String>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(segment);
            }
        }

        // Step 3: Reconstruct
        if (stack.isEmpty()) {
            return "/";
        }
        var sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        return sb.toString();
    }

    /**
     * Percent-decodes a path string without treating {@code +} as space.
     * Handles multi-byte UTF-8 sequences and passes incomplete {@code %} sequences through unchanged.
     */
    static String percentDecodePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        var out = new java.io.ByteArrayOutputStream(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                } else {
                    // Invalid hex digits — pass '%' through unchanged
                    out.write(c);
                    i++;
                }
            } else {
                // Non-percent character or incomplete sequence at end — pass through as UTF-8
                if (c < 0x80) {
                    out.write(c);
                } else {
                    byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.writeBytes(bytes);
                }
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

}
