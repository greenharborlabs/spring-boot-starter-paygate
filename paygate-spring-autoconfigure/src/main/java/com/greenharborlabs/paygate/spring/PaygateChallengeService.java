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
   * <p><strong>Route identity warning:</strong> This compatibility overload signs the exact route
   * spelling produced by parsing {@link PaygateEndpointConfig#pathPattern()}. A manually
   * constructed configuration using {@code /api/orders/}, for example, can fail against a
   * registered {@code /api/orders} route. Callers that resolved policy through {@link
   * PaygateEndpointRegistry} should pass its {@link ResolvedEndpoint} instead. A spelling mismatch
   * intentionally fails closed and causes credential rejection and re-challenge.
   *
   * <p>Performs the following steps:
   *
   * <ol>
   *   <li>Check Lightning backend health
   *   <li>Check rate limit for the client IP
   *   <li>Resolve effective price (dynamic strategy or static)
   *   <li>Create Lightning invoice
   *   <li>Generate root key and token ID
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
    return createChallenge(request, config, ChallengeOptions.enforceRateLimit());
  }

  /**
   * Creates a protocol-agnostic challenge using the selected policy and its canonical registered
   * route.
   *
   * <p>The request's actual HTTP method remains the challenge boundary. In particular, a {@code
   * HEAD} request resolved through a {@code GET} policy is issued a {@code HEAD}-bound credential.
   *
   * @param request the current HTTP request
   * @param resolvedEndpoint the endpoint policy selected for the request
   * @return the challenge context containing all data for protocol-specific formatting
   * @throws PaygateLightningUnavailableException if the Lightning backend is unhealthy or fails
   * @throws PaygateRateLimitedException if the client is rate-limited
   */
  public ChallengeContext createChallenge(
      HttpServletRequest request, ResolvedEndpoint resolvedEndpoint)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {
    return createChallenge(request, resolvedEndpoint, ChallengeOptions.enforceRateLimit());
  }

  /**
   * Creates a protocol-agnostic challenge context with explicit Spring integration options.
   *
   * <p><strong>Route identity warning:</strong> This compatibility overload signs the exact route
   * spelling produced by parsing {@link PaygateEndpointConfig#pathPattern()}. A manually
   * constructed configuration using {@code /api/orders/}, for example, can fail against a
   * registered {@code /api/orders} route. Callers that resolved policy through {@link
   * PaygateEndpointRegistry} should pass its {@link ResolvedEndpoint} instead. A spelling mismatch
   * intentionally fails closed and causes credential rejection and re-challenge.
   *
   * <p>This overload is public only so sibling Spring modules can coordinate request-body digest
   * capture with challenge rate limiting. It is internal-to-Spring integration behavior and does
   * not change any payment protocol API or wire format.
   *
   * @param request the current HTTP request
   * @param config the endpoint configuration
   * @param options internal Spring integration options for challenge creation
   * @return the challenge context containing all data for protocol-specific formatting
   * @throws PaygateLightningUnavailableException if the Lightning backend is unhealthy or fails
   * @throws PaygateRateLimitedException if the client is rate-limited
   */
  public ChallengeContext createChallenge(
      HttpServletRequest request, PaygateEndpointConfig config, ChallengeOptions options)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {
    Objects.requireNonNull(config, "config must not be null");
    var routePattern = PaygateEndpointRegistry.parsePathPattern(config.pathPattern());
    return createChallenge(request, config, routePattern.getPatternString(), options);
  }

  /**
   * Creates a protocol-agnostic challenge using a resolved endpoint and explicit Spring integration
   * options.
   *
   * <p>Policy values come from {@link ResolvedEndpoint#config()}, the route boundary comes from
   * {@link ResolvedEndpoint#routePattern()}, and the method boundary always comes from the actual
   * request.
   *
   * @param request the current HTTP request
   * @param resolvedEndpoint the endpoint policy selected for the request
   * @param options internal Spring integration options for challenge creation
   * @return the challenge context containing all data for protocol-specific formatting
   * @throws PaygateLightningUnavailableException if the Lightning backend is unhealthy or fails
   * @throws PaygateRateLimitedException if the client is rate-limited
   */
  public ChallengeContext createChallenge(
      HttpServletRequest request, ResolvedEndpoint resolvedEndpoint, ChallengeOptions options)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {
    Objects.requireNonNull(resolvedEndpoint, "resolvedEndpoint must not be null");
    return createChallenge(
        request, resolvedEndpoint.config(), resolvedEndpoint.routePattern(), options);
  }

  private ChallengeContext createChallenge(
      HttpServletRequest request,
      PaygateEndpointConfig config,
      String routePattern,
      ChallengeOptions options)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(routePattern, "routePattern must not be null");
    Objects.requireNonNull(options, "options must not be null");

    // 1. Check Lightning backend health
    if (!lightningBackend.isHealthy()) {
      throw new PaygateLightningUnavailableException("Lightning backend health check failed");
    }

    // 2. Check rate limit
    if (!options.skipRateLimitCheck()) {
      acquireChallengeRateLimit(request);
    }

    // 3. Generate root key, create invoice, build context
    try {
      return buildChallengeContext(request, config, routePattern, request.getMethod());
    } catch (PaygateLightningUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to create challenge: " + e.getMessage(), e);
    }
  }

  /**
   * Acquires the unauthenticated challenge rate-limit token for the current request.
   *
   * <p>This method is public only so Spring servlet and Spring Security integrations can rate-limit
   * before MPP request-body digest capture, then call {@link #createChallenge(HttpServletRequest,
   * PaygateEndpointConfig, ChallengeOptions)} with {@link
   * ChallengeOptions#rateLimitAlreadyConsumed()}. It is internal-to-Spring integration behavior and
   * does not change any payment protocol API or wire format.
   *
   * @throws PaygateRateLimitedException if the rate limiter denies the request or fails closed
   */
  public void acquireChallengeRateLimit(HttpServletRequest request)
      throws PaygateRateLimitedException {
    PaygateRateLimiter limiter = this.rateLimiter;
    if (limiter == null) {
      return;
    }
    try {
      String clientIp =
          clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
      if (!limiter.tryAcquire(clientIp)) {
        throw new PaygateRateLimitedException("Rate limit exceeded for client");
      }
    } catch (PaygateRateLimitedException e) {
      throw e;
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Rate limiter threw exception, denying challenge request: {0}",
          e.getMessage());
      throw new PaygateRateLimitedException("Rate limiter denied challenge request");
    }
  }

  /**
   * Internal Spring integration options for challenge creation.
   *
   * <p>This type is public only because {@code paygate-spring-security} is a sibling Java package.
   * It is not part of the payment protocol API and does not affect challenge wire format.
   */
  public record ChallengeOptions(boolean skipRateLimitCheck) {

    /** Uses the default service behavior: the challenge service checks rate limits. */
    public static ChallengeOptions enforceRateLimit() {
      return new ChallengeOptions(false);
    }

    /** Indicates the caller already consumed the challenge rate-limit token for this request. */
    public static ChallengeOptions rateLimitAlreadyConsumed() {
      return new ChallengeOptions(true);
    }
  }

  // NOTE: This method performs two sequential blocking operations:
  // (1) lightningBackend.createInvoice() -- synchronous network call
  // (2) rootKeyStore.generateRootKey() -- file I/O with write lock
  // The CachingLightningBackendWrapper mitigates health checks before this method runs.
  // Future optimization: consider virtual threads or structured concurrency
  // to parallelize (1) and (2) when they are independent.
  private ChallengeContext buildChallengeContext(
      HttpServletRequest request,
      PaygateEndpointConfig config,
      String routePattern,
      String requestMethod)
      throws PaygateLightningUnavailableException {

    // Resolve effective price before creating any root key material.
    long effectivePrice = resolvePrice(request, config);

    // Create Lightning invoice before root key generation so invoice failures do not allocate
    // sensitive key material.
    Invoice invoice;
    try {
      invoice = lightningBackend.createInvoice(effectivePrice, config.description());
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to create invoice: " + e.getMessage(), e);
    }

    // Generate root key and tokenId atomically after invoice creation; try-with-resources ensures
    // SensitiveBytes.destroy() is called if a later step fails.
    try (RootKeyStore.GenerationResult generationResult = rootKeyStore.generateRootKey()) {
      byte[] rootKey = generationResult.rootKey().value();
      try {
        byte[] tokenId = generationResult.tokenId();

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
        String requestDigest = RequestDigestSupport.digestAttribute(request);

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
                  requestDigest,
                  routePattern,
                  requestMethod);

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
