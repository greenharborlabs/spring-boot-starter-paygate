package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;

/**
 * Service that encapsulates payment challenge creation logic: health check, rate limiting, root key
 * generation, invoice creation, and raw context assembly.
 *
 * <p>Returns a protocol-agnostic {@link ChallengeContext} that protocol-specific formatters (L402,
 * MPP) consume to produce their respective challenge headers. Macaroon minting has moved to the
 * protocol layer.
 *
 * <p>Extracted from {@link PaygateSecurityFilter} so that both the servlet filter and Spring
 * Security entry points can issue identical challenges.
 */
public class PaygateChallengeService {

  private static final System.Logger log =
      System.getLogger(PaygateChallengeService.class.getName());

  private final RootKeyStore rootKeyStore;
  private final LightningBackend lightningBackend;
  private final ApplicationContext applicationContext;
  private final String serviceName;

  private final PaygateEarningsTracker earningsTracker;
  private final PaygateRateLimiter rateLimiter;
  private final ClientIpResolver clientIpResolver;
  private final CapabilityCache capabilityCache;
  private final ConcurrentHashMap<String, PaygatePricingStrategy> pricingStrategyCache =
      new ConcurrentHashMap<>();

  public PaygateChallengeService(
      RootKeyStore rootKeyStore,
      LightningBackend lightningBackend,
      @Nullable PaygateProperties properties,
      @Nullable ApplicationContext applicationContext,
      @Nullable PaygateEarningsTracker earningsTracker,
      @Nullable PaygateRateLimiter rateLimiter,
      @Nullable ClientIpResolver clientIpResolver,
      @Nullable CapabilityCache capabilityCache) {
    this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
    this.lightningBackend =
        Objects.requireNonNull(lightningBackend, "lightningBackend must not be null");
    this.applicationContext = applicationContext;
    String svcName = (properties != null) ? properties.getServiceName() : null;
    this.serviceName = (svcName == null || svcName.isBlank()) ? "default" : svcName;
    this.earningsTracker = earningsTracker;
    this.rateLimiter = rateLimiter;
    this.clientIpResolver = clientIpResolver;
    this.capabilityCache = capabilityCache;
  }

  /**
   * Creates a protocol-agnostic challenge context for the given request and endpoint configuration.
   *
   * <p>Performs the following steps:
   *
   * <ol>
   *   <li>Check Lightning backend health
   *   <li>Check rate limit for the client IP
   *   <li>Generate root key and token ID
   *   <li>Resolve effective price (dynamic strategy or static)
   *   <li>Create Lightning invoice
   *   <li>Build and return the {@link ChallengeContext}
   * </ol>
   *
   * @param request the current HTTP request
   * @param config the endpoint configuration
   * @return the challenge context containing all data for protocol-specific formatting
   * @throws PaygateLightningUnavailableException if the Lightning backend is unhealthy or fails
   * @throws PaygateRateLimitedException if the client is rate-limited
   */
  public ChallengeContext createChallenge(HttpServletRequest request, PaygateEndpointConfig config)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {

    // 1. Check Lightning backend health
    if (!lightningBackend.isHealthy()) {
      throw new PaygateLightningUnavailableException("Lightning backend health check failed");
    }

    // 2. Check rate limit
    PaygateRateLimiter limiter = this.rateLimiter;
    String clientIp =
        clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
    if (limiter != null && !limiter.tryAcquire(clientIp)) {
      throw new PaygateRateLimitedException("Rate limit exceeded for client");
    }

    // 3. Generate root key, create invoice, build context
    try {
      return buildChallengeContext(request, config);
    } catch (PaygateLightningUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to create challenge: " + e.getMessage(), e);
    }
  }

  // NOTE: This method performs two sequential blocking operations:
  // (1) rootKeyStore.generateRootKey() -- file I/O with write lock
  // (2) lightningBackend.createInvoice() -- synchronous network call
  // The CachingLightningBackendWrapper mitigates (2) for health checks.
  // Future optimization: consider virtual threads or structured concurrency
  // to parallelize (1) and (2) when they are independent.
  private ChallengeContext buildChallengeContext(
      HttpServletRequest request, PaygateEndpointConfig config)
      throws PaygateLightningUnavailableException {

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
          throw new PaygateLightningUnavailableException(
              "Failed to create invoice: " + e.getMessage(), e);
        }

        // Record invoice creation in earnings tracker
        try {
          if (earningsTracker != null) {
            earningsTracker.recordInvoiceCreated();
          }
        } catch (Exception e) {
          log.log(
              System.Logger.Level.WARNING,
              "Failed to record invoice creation in earnings tracker: {0}",
              e.getMessage());
        }

        // Build opaque map for test preimage if present
        Map<String, String> opaque = null;
        byte[] invoicePreimage = invoice.preimage();
        if (invoicePreimage != null) {
          opaque = new LinkedHashMap<>();
          opaque.put("test_preimage", HexFormat.of().formatHex(invoicePreimage));
        }

        String tokenIdHex = HexFormat.of().formatHex(tokenId);

        // Clone rootKey so ChallengeContext has its own copy before we zeroize
        byte[] rootKeyClone = rootKey.clone();
        try {
          var challengeContext =
              new ChallengeContext(
                  invoice.paymentHash(),
                  tokenIdHex,
                  invoice.bolt11(),
                  effectivePrice,
                  config.description(),
                  serviceName,
                  config.timeoutSeconds(),
                  config.capability(),
                  rootKeyClone,
                  opaque,
                  null);

          // Populate capability cache after successful invoice creation
          if (capabilityCache != null
              && config.capability() != null
              && !config.capability().isEmpty()) {
            try {
              capabilityCache.store(tokenIdHex, config.capability(), config.timeoutSeconds());
            } catch (RuntimeException e) {
              log.log(
                  System.Logger.Level.WARNING,
                  "Failed to store capability in cache for token {0}: {1}",
                  tokenIdHex,
                  e.getMessage());
            }
          }

          return challengeContext;
        } finally {
          KeyMaterial.zeroize(rootKeyClone);
        }
      } finally {
        KeyMaterial.zeroize(rootKey);
      }
    } catch (PaygateLightningUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to generate root key: " + e.getMessage(), e);
    }
  }

  /**
   * Resolves the effective price for an endpoint by looking up the pricing strategy bean from the
   * ApplicationContext. Falls back to the static annotation price if no strategy is configured, the
   * ApplicationContext is unavailable, or the bean does not exist.
   */
  long resolvePrice(HttpServletRequest request, PaygateEndpointConfig config) {
    String strategyName = config.pricingStrategy();
    if (strategyName == null || strategyName.isBlank() || applicationContext == null) {
      return config.priceSats();
    }
    // Check cache first; failed lookups are NOT cached so they retry on each request.
    PaygatePricingStrategy strategy = pricingStrategyCache.get(strategyName);
    if (strategy == null) {
      try {
        strategy = applicationContext.getBean(strategyName, PaygatePricingStrategy.class);
        pricingStrategyCache.put(strategyName, strategy);
      } catch (Exception e) {
        log.log(
            System.Logger.Level.WARNING,
            "Pricing strategy bean ''{0}'' not found or failed; falling back to static price {1} sats",
            strategyName,
            config.priceSats());
        return config.priceSats();
      }
    }
    return strategy.calculatePrice(request, config.priceSats());
  }
}
