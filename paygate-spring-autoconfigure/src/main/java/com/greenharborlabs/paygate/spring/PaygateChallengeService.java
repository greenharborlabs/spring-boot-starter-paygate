package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Challenge;
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
  private final PaygateRequestPricingService requestPricingService;
  private final String serviceName;

  private final PaygateEarningsTracker earningsTracker;
  private final PaygateRateLimiter rateLimiter;
  private final AggregateInvoiceRateLimiter aggregateInvoiceRateLimiter;
  private final ClientIpResolver clientIpResolver;
  private final boolean clientAddressBindingEnabled;
  private final CapabilityCache capabilityCache;
  private final boolean validatedTestMode;
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
    this(
        rootKeyStore,
        lightningBackend,
        properties,
        applicationContext,
        earningsTracker,
        rateLimiter,
        null,
        clientIpResolver,
        capabilityCache,
        false,
        null);
  }

  PaygateChallengeService(
      RootKeyStore rootKeyStore,
      LightningBackend lightningBackend,
      @Nullable PaygateProperties properties,
      @Nullable ApplicationContext applicationContext,
      @Nullable PaygateEarningsTracker earningsTracker,
      @Nullable PaygateRateLimiter rateLimiter,
      @Nullable AggregateInvoiceRateLimiter aggregateInvoiceRateLimiter,
      @Nullable ClientIpResolver clientIpResolver,
      @Nullable CapabilityCache capabilityCache,
      boolean validatedTestMode) {
    this(
        rootKeyStore,
        lightningBackend,
        properties,
        applicationContext,
        earningsTracker,
        rateLimiter,
        aggregateInvoiceRateLimiter,
        clientIpResolver,
        capabilityCache,
        validatedTestMode,
        null);
  }

  PaygateChallengeService(
      RootKeyStore rootKeyStore,
      LightningBackend lightningBackend,
      @Nullable PaygateProperties properties,
      @Nullable ApplicationContext applicationContext,
      @Nullable PaygateEarningsTracker earningsTracker,
      @Nullable PaygateRateLimiter rateLimiter,
      @Nullable AggregateInvoiceRateLimiter aggregateInvoiceRateLimiter,
      @Nullable ClientIpResolver clientIpResolver,
      @Nullable CapabilityCache capabilityCache,
      boolean validatedTestMode,
      @Nullable PaygateRequestPricingService requestPricingService) {
    this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
    this.lightningBackend =
        Objects.requireNonNull(lightningBackend, "lightningBackend must not be null");
    this.applicationContext = applicationContext;
    this.requestPricingService = requestPricingService;
    String svcName = (properties != null) ? properties.getServiceName() : null;
    this.serviceName = (svcName == null || svcName.isBlank()) ? "default" : svcName;
    this.earningsTracker = earningsTracker;
    this.rateLimiter = rateLimiter;
    this.aggregateInvoiceRateLimiter = aggregateInvoiceRateLimiter;
    this.clientIpResolver = clientIpResolver;
    this.clientAddressBindingEnabled =
        properties != null && properties.getProtocols().getL402().isClientAddressBindingEnabled();
    this.capabilityCache = capabilityCache;
    this.validatedTestMode = validatedTestMode;
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

    String trustedClientAddress = null;
    if (clientAddressBindingEnabled) {
      trustedClientAddress =
          clientIpResolver != null
              ? clientIpResolver.resolveBindingAddress(request).orElse(null)
              : null;
      if (trustedClientAddress == null) {
        throw new PaygateLightningUnavailableException("Trusted client address is unavailable");
      }
    }

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
      return buildChallengeContext(
          request, config, routePattern, request.getMethod(), trustedClientAddress);
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
          clientIpResolver != null
              ? clientIpResolver.resolveRateLimitIdentity(request)
              : request.getRemoteAddr();
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
      String requestMethod,
      @Nullable String trustedClientAddress)
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {

    // Resolve and validate the effective price before any persistent or network side effect.
    long effectivePrice = resolvePrice(request, config);
    validatePrice(effectivePrice);

    acquireAggregateInvoiceCapacity();

    // Create Lightning invoice before root key generation so invoice failures do not allocate
    // sensitive key material.
    Invoice invoice = createValidatedInvoice(effectivePrice, config.description());
    String safeBolt11 = sanitizeInvoice(invoice);

    // Generate root key and tokenId atomically after invoice creation; try-with-resources ensures
    // SensitiveBytes.destroy() is called if a later step fails.
    try (RootKeyStore.GenerationResult generationResult = rootKeyStore.generateRootKey()) {
      byte[] rootKey = generationResult.rootKey().value();
      try {
        byte[] tokenId = generationResult.tokenId();
        boolean contextCreated = false;
        try {
          recordInvoiceCreated();
          Map<String, String> opaque = testModeOpaqueData(invoice);

          String tokenIdHex = HexFormat.of().formatHex(tokenId);
          String requestDigest = RequestDigestSupport.digestAttribute(request);

          // Clone rootKey so ChallengeContext has its own copy before we zeroize
          byte[] rootKeyClone = rootKey.clone();
          try {
            var challengeContext =
                new ChallengeContext(
                    invoice.paymentHash(),
                    tokenIdHex,
                    safeBolt11,
                    effectivePrice,
                    config.description(),
                    serviceName,
                    config.timeoutSeconds(),
                    config.capability(),
                    rootKeyClone,
                    opaque,
                    requestDigest,
                    routePattern,
                    requestMethod,
                    request.getQueryString(),
                    request.getQueryString() != null,
                    trustedClientAddress);

            storeCapability(tokenIdHex, config);

            contextCreated = true;
            return challengeContext;
          } finally {
            KeyMaterial.zeroize(rootKeyClone);
          }
        } finally {
          if (!contextCreated) {
            revokeGeneratedRootKey(tokenId);
          }
          KeyMaterial.zeroize(tokenId);
        }
      } finally {
        KeyMaterial.zeroize(rootKey);
      }
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to generate root key: " + e.getMessage(), e);
    }
  }

  private Invoice createValidatedInvoice(long effectivePrice, String description)
      throws PaygateLightningUnavailableException {
    final Invoice invoice;
    try {
      invoice = lightningBackend.createInvoice(effectivePrice, description);
    } catch (RuntimeException e) {
      throw new PaygateLightningUnavailableException(
          "Failed to create invoice: " + e.getMessage(), e);
    }
    if (invoice == null || invoice.amountSats() != effectivePrice) {
      throw new PaygateLightningUnavailableException(
          "Lightning backend returned an invoice with an unexpected amount");
    }
    return invoice;
  }

  private static String sanitizeInvoice(Invoice invoice)
      throws PaygateLightningUnavailableException {
    try {
      return L402Challenge.sanitizeBolt11ForHeader(invoice.bolt11());
    } catch (IllegalArgumentException e) {
      throw new PaygateLightningUnavailableException(
          "Lightning backend returned an invalid invoice", e);
    }
  }

  private void recordInvoiceCreated() {
    if (earningsTracker == null) {
      return;
    }
    try {
      earningsTracker.recordInvoiceCreated();
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to record invoice creation in earnings tracker: {0}",
          e.getMessage());
    }
  }

  private Map<String, String> testModeOpaqueData(Invoice invoice) {
    byte[] invoicePreimage = invoice.preimage();
    if (!validatedTestMode
        || lightningBackend.getClass() != TestModeLightningBackend.class
        || invoicePreimage == null) {
      return null;
    }
    var opaque = new LinkedHashMap<String, String>();
    opaque.put("test_preimage", HexFormat.of().formatHex(invoicePreimage));
    return opaque;
  }

  private void storeCapability(String tokenIdHex, PaygateEndpointConfig config) {
    if (capabilityCache == null || config.capability() == null || config.capability().isEmpty()) {
      return;
    }
    try {
      capabilityCache.store(tokenIdHex, config.capability(), config.timeoutSeconds());
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to store capability in cache for token correlation {0}: {1}",
          LogSanitizer.sanitizeTokenId(tokenIdHex),
          e.getClass().getSimpleName());
    }
  }

  private void acquireAggregateInvoiceCapacity()
      throws PaygateLightningUnavailableException, PaygateRateLimitedException {
    AggregateInvoiceRateLimiter limiter = aggregateInvoiceRateLimiter;
    if (limiter == null) {
      return;
    }
    try {
      if (!limiter.tryAcquire()) {
        throw new PaygateRateLimitedException("Aggregate invoice limit exceeded");
      }
    } catch (PaygateRateLimitedException e) {
      throw e;
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Aggregate invoice limiter failed; denying challenge: {0}",
          e.getClass().getSimpleName());
      throw new PaygateLightningUnavailableException("Aggregate invoice limiter unavailable", e);
    }
  }

  /**
   * Resolves the effective price for an endpoint by looking up the pricing strategy bean from the
   * ApplicationContext. A blank strategy deliberately uses the static annotation price; a named
   * strategy that cannot be resolved fails closed rather than silently undercharging.
   */
  long resolvePrice(HttpServletRequest request, PaygateEndpointConfig config) {
    Object memoized = request.getAttribute(PaygateRequestPricingService.REQUEST_PRICE_ATTRIBUTE);
    if (memoized instanceof TrustedRequestPrice(long amountSats)) {
      return amountSats;
    }
    String strategyName = config.pricingStrategy();
    if (strategyName == null || strategyName.isBlank()) {
      return config.priceSats();
    }
    if (applicationContext == null) {
      throw new IllegalStateException("Named pricing strategy resolution is unavailable");
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
            "Pricing strategy bean ''{0}'' could not be resolved; failing closed",
            strategyName);
        throw new IllegalStateException("Named pricing strategy could not be resolved", e);
      }
    }
    try {
      return strategy.calculatePrice(request, config.priceSats());
    } catch (ArithmeticException e) {
      // Dynamic pricing implementations commonly use Math.*Exact. Treat arithmetic failure as an
      // invalid price calculation and let the outer fail-closed path return 503 before minting.
      throw new IllegalArgumentException("Dynamic price calculation failed", e);
    }
  }

  /**
   * Resolves and stores a trusted price for this request before credential validation.
   *
   * <p>This compatibility implementation uses the challenge service's established strategy lookup.
   * It memoizes the outcome so validation and any replacement challenge consume the same amount.
   * Auto-configuration supplies the bounded runner for production request paths.
   */
  public TrustedRequestPrice resolveTrustedPrice(
      HttpServletRequest request, PaygateEndpointConfig config) {
    if (requestPricingService != null) {
      return requestPricingService.resolve(request, config);
    }
    Object memoized = request.getAttribute(PaygateRequestPricingService.REQUEST_PRICE_ATTRIBUTE);
    if (memoized instanceof TrustedRequestPrice trustedRequestPrice) {
      return trustedRequestPrice;
    }
    TrustedRequestPrice resolved = new TrustedRequestPrice(resolvePrice(request, config));
    request.setAttribute(PaygateRequestPricingService.REQUEST_PRICE_ATTRIBUTE, resolved);
    return resolved;
  }

  private static void validatePrice(long priceSats) {
    if (!SecurityBounds.isValidPrice(priceSats)) {
      throw new IllegalArgumentException(
          "Resolved price must be between "
              + SecurityBounds.MIN_PRICE_SATS
              + " and "
              + SecurityBounds.MAX_PRICE_SATS);
    }
  }

  /**
   * Revokes challenge state that could not be safely delivered to the client.
   *
   * <p>This is public for the servlet and Spring Security integrations to call after every enabled
   * protocol declined or failed to format a challenge. It intentionally accepts the
   * service-produced context rather than a token ID supplied by a request.
   */
  public void discardChallenge(ChallengeContext challengeContext) {
    Objects.requireNonNull(challengeContext, "challengeContext must not be null");
    try {
      byte[] tokenId = HexFormat.of().parseHex(challengeContext.tokenId());
      try {
        revokeGeneratedRootKey(tokenId);
      } finally {
        KeyMaterial.zeroize(tokenId);
      }
    } finally {
      challengeContext.close();
    }
  }

  private void revokeGeneratedRootKey(byte[] tokenId) {
    try {
      rootKeyStore.revokeRootKey(tokenId);
    } catch (RuntimeException e) {
      // Retaining unusable credentials is safer than surfacing an internal root-key-store detail.
      log.log(
          System.Logger.Level.WARNING,
          "Failed to revoke undeliverable challenge root key; failing request closed: {0}",
          e.getClass().getSimpleName());
    }
  }
}
