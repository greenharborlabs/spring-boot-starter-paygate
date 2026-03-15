package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.KeyMaterial;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Service that encapsulates L402 challenge creation logic: health check,
 * rate limiting, root key generation, invoice creation, macaroon minting,
 * and response header formatting.
 *
 * <p>Extracted from {@link L402SecurityFilter} so that both the servlet filter
 * and Spring Security entry points can issue identical 402 challenges.
 */
public class L402ChallengeService {

    private static final System.Logger log = System.getLogger(L402ChallengeService.class.getName());

    /** Macaroon binary format version — V2 as defined by the L402 identifier layout. */
    private static final int MACAROON_IDENTIFIER_VERSION = 0;

    private final RootKeyStore rootKeyStore;
    private final LightningBackend lightningBackend;
    private final L402Properties properties;
    private final ApplicationContext applicationContext;
    private final String serviceName;

    private volatile L402EarningsTracker earningsTracker;
    private volatile L402RateLimiter rateLimiter;
    private volatile boolean reverseProxyWarningLogged;

    public L402ChallengeService(RootKeyStore rootKeyStore,
                                 LightningBackend lightningBackend,
                                 L402Properties properties,
                                 ApplicationContext applicationContext) {
        this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
        this.lightningBackend = Objects.requireNonNull(lightningBackend, "lightningBackend must not be null");
        this.properties = properties;
        this.applicationContext = applicationContext;
        String svcName = (properties != null) ? properties.getServiceName() : null;
        this.serviceName = (svcName == null || svcName.isBlank()) ? "default" : svcName;
    }

    public void setEarningsTracker(L402EarningsTracker earningsTracker) {
        this.earningsTracker = earningsTracker;
    }

    public void setRateLimiter(L402RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Creates an L402 challenge for the given request and endpoint configuration.
     *
     * <p>Performs the following steps:
     * <ol>
     *   <li>Check Lightning backend health</li>
     *   <li>Check rate limit for the client IP</li>
     *   <li>Generate root key and token ID</li>
     *   <li>Resolve effective price (dynamic strategy or static)</li>
     *   <li>Create Lightning invoice</li>
     *   <li>Mint macaroon with service and expiry caveats</li>
     *   <li>Build and return the challenge result</li>
     * </ol>
     *
     * @param request the current HTTP request
     * @param config  the endpoint configuration
     * @return the challenge result containing all data for the 402 response
     * @throws L402LightningUnavailableException if the Lightning backend is unhealthy or fails
     * @throws L402RateLimitedException          if the client is rate-limited
     */
    public L402ChallengeResult createChallenge(HttpServletRequest request, L402EndpointConfig config)
            throws L402LightningUnavailableException, L402RateLimitedException {

        // 1. Check Lightning backend health
        if (!lightningBackend.isHealthy()) {
            throw new L402LightningUnavailableException("Lightning backend health check failed");
        }

        // 2. Check rate limit
        L402RateLimiter limiter = this.rateLimiter;
        if (limiter != null && !limiter.tryAcquire(resolveClientIp(request))) {
            throw new L402RateLimitedException("Rate limit exceeded for client");
        }

        // 3. Generate root key, create invoice, mint macaroon
        try {
            return mintChallenge(request, config);
        } catch (L402LightningUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new L402LightningUnavailableException(
                    "Failed to create challenge: " + e.getMessage(), e);
        }
    }

    // NOTE: This method performs three sequential blocking operations:
    // (1) rootKeyStore.generateRootKey() — file I/O with write lock
    // (2) lightningBackend.createInvoice() — synchronous network call
    // (3) MacaroonMinter.mint() — CPU-bound HMAC computation
    // The CachingLightningBackendWrapper mitigates (2) for health checks.
    // Future optimization: consider virtual threads or structured concurrency
    // to parallelize (1) and (2) when they are independent.
    private L402ChallengeResult mintChallenge(HttpServletRequest request, L402EndpointConfig config)
            throws L402LightningUnavailableException {

        // Generate root key and tokenId atomically; try-with-resources ensures
        // SensitiveBytes.destroy() is called even if an exception is thrown.
        try (RootKeyStore.GenerationResult generationResult = rootKeyStore.generateRootKey()) {
            byte[] rootKey = generationResult.rootKey().value();
            try {
                byte[] tokenId = generationResult.tokenId();

                // Resolve effective price: dynamic strategy overrides static annotation value
                long effectivePrice = resolvePrice(request, config);

                // Create Lightning invoice
                Invoice invoice;
                try {
                    invoice = lightningBackend.createInvoice(effectivePrice, config.description());
                } catch (RuntimeException e) {
                    throw new L402LightningUnavailableException(
                            "Failed to create invoice: " + e.getMessage(), e);
                }

                // Record invoice creation in earnings tracker
                try {
                    if (earningsTracker != null) { earningsTracker.recordInvoiceCreated(); }
                } catch (Exception e) {
                    log.log(System.Logger.Level.WARNING, "Failed to record invoice creation in earnings tracker: {0}", e.getMessage());
                }

                // Build MacaroonIdentifier and mint macaroon with service, capability, and expiry caveats
                MacaroonIdentifier identifier = new MacaroonIdentifier(MACAROON_IDENTIFIER_VERSION, invoice.paymentHash(), tokenId);
                Instant validUntil = Instant.now().plusSeconds(config.timeoutSeconds());
                List<Caveat> caveats = new ArrayList<>();
                caveats.add(new Caveat("services", serviceName + ":0"));
                String capability = config.capability();
                if (capability != null && !capability.isBlank()) {
                    caveats.add(new Caveat(serviceName + "_capabilities", capability));
                }
                caveats.add(new Caveat(serviceName + "_valid_until", String.valueOf(validUntil.getEpochSecond())));
                Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);

                // Serialize and encode
                byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
                String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

                // Build WWW-Authenticate header — sanitize bolt11 to prevent header injection
                String safeBolt11Header = sanitizeBolt11ForHeader(invoice.bolt11());
                String wwwAuth = "L402 version=\"0\", token=\"" + macaroonBase64
                        + "\", macaroon=\"" + macaroonBase64
                        + "\", invoice=\"" + safeBolt11Header + "\"";

                // In test mode the backend includes the preimage on the PENDING invoice so
                // users can complete the full L402 flow with curl. Real backends never set
                // preimage on PENDING invoices, so this field only appears in test mode.
                String testPreimage = null;
                byte[] invoicePreimage = invoice.preimage();
                if (invoicePreimage != null) {
                    testPreimage = HexFormat.of().formatHex(invoicePreimage);
                }

                return new L402ChallengeResult(
                        macaroonBase64,
                        invoice.bolt11(),
                        wwwAuth,
                        effectivePrice,
                        config.description(),
                        testPreimage
                );
            } finally {
                KeyMaterial.zeroize(rootKey);
            }
        } catch (L402LightningUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new L402LightningUnavailableException(
                    "Failed to generate root key: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the effective price for an endpoint by looking up the pricing strategy
     * bean from the ApplicationContext. Falls back to the static annotation price if
     * no strategy is configured, the ApplicationContext is unavailable, or the bean
     * does not exist.
     */
    long resolvePrice(HttpServletRequest request, L402EndpointConfig config) {
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

    /**
     * Strips characters from a bolt11 string that could enable HTTP header injection
     * or break the WWW-Authenticate header format. Removes double quotes, carriage
     * returns, and newlines.
     */
    static String sanitizeBolt11ForHeader(String bolt11) {
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
    String resolveClientIp(HttpServletRequest request) {
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
}
