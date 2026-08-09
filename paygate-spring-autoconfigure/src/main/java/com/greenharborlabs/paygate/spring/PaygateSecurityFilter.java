package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.UnsupportedPaymentMethodException;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.protocol.l402.L402Metadata;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.jspecify.annotations.Nullable;

/**
 * Servlet filter that enforces payment authentication on endpoints registered in the {@link
 * PaygateEndpointRegistry}.
 *
 * <p>Flow per request:
 *
 * <ol>
 *   <li>Match request against registry; if no match, pass through
 *   <li>If Authorization header is present, iterate registered protocols to find one that can
 *       handle it; validate the credential (purely local, no Lightning health check needed)
 *   <li>If no valid credential, delegate to {@link PaygateChallengeService} which checks Lightning
 *       health, rate limits, and creates the 402 challenge
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
  private final boolean mppEnabled;
  private final int requestBodyMaxBytes;

  /**
   * Canonical constructor. All dependencies are provided up front; optional collaborators ({@code
   * metrics}, {@code earningsTracker}, {@code rateLimiter}) may be {@code null}.
   */
  public PaygateSecurityFilter(
      PaygateEndpointRegistry registry,
      List<PaymentProtocol> protocols,
      PaygateChallengeService challengeService,
      String serviceName,
      @Nullable ClientIpResolver clientIpResolver,
      @Nullable PaygateMetrics metrics,
      @Nullable PaygateEarningsTracker earningsTracker,
      @Nullable PaygateRateLimiter rateLimiter) {
    this(
        registry,
        protocols,
        challengeService,
        serviceName,
        clientIpResolver,
        metrics,
        earningsTracker,
        rateLimiter,
        RequestDigestSupport.MAX_CACHED_BODY_BYTES);
  }

  /** Creates a filter with the configured protected request-body bound. */
  public PaygateSecurityFilter(
      PaygateEndpointRegistry registry,
      List<PaymentProtocol> protocols,
      PaygateChallengeService challengeService,
      String serviceName,
      @Nullable ClientIpResolver clientIpResolver,
      @Nullable PaygateMetrics metrics,
      @Nullable PaygateEarningsTracker earningsTracker,
      @Nullable PaygateRateLimiter rateLimiter,
      int requestBodyMaxBytes) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols must not be null"));
    this.challengeService =
        Objects.requireNonNull(challengeService, "challengeService must not be null");
    this.serviceName = (serviceName == null || serviceName.isBlank()) ? "default" : serviceName;
    this.clientIpResolver = clientIpResolver;
    this.metrics = metrics;
    this.earningsTracker = earningsTracker;
    this.rateLimiter = rateLimiter;
    this.mppEnabled = this.protocols.stream().anyMatch(RequestDigestSupport::isMppProtocol);
    this.requestBodyMaxBytes = requestBodyMaxBytes;
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
      path = ApplicationRelativeRequestResolver.resolve(httpRequest);
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING, "Rejected request with malformed URI: {0}", "<unavailable>");
      PaygateResponseWriter.writeMalformedUri(httpResponse);
      recordRejected("_malformed", "unknown");
      return;
    }

    String safePath = LogSanitizer.sanitize(path);

    // 1. Check if this endpoint is protected
    ResolvedEndpoint resolvedEndpoint;
    try {
      resolvedEndpoint = registry.resolve(method, path);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Endpoint policy resolution failed for {0} {1}: {2}",
          method,
          safePath,
          e.getClass().getSimpleName());
      PaygateResponseWriter.writeInternalError(httpResponse);
      recordRejected("_resolution_error", "unknown");
      return;
    }
    if (resolvedEndpoint == null) {
      chain.doFilter(request, response);
      return;
    }

    // 2. Check Authorization header — validate credentials before checking Lightning health,
    //    so requests with valid cached credentials skip the health-check cost entirely.
    String authHeader = httpRequest.getHeader(AUTHORIZATION_HEADER);
    CredentialState credentialState =
        authHeader == null ? CredentialState.NO_CREDENTIAL : CredentialState.PRESENTED;
    if (credentialState == CredentialState.PRESENTED) {
      for (PaymentProtocol protocol : protocols) {
        if (protocol.canHandle(authHeader)) {
          if (!tryAcquireRateLimit(httpRequest)) {
            PaygateResponseWriter.writeRateLimited(httpResponse);
            return;
          }
          HttpServletRequest protocolRequest = httpRequest;
          if (RequestDigestSupport.isMppProtocol(protocol)) {
            try {
              protocolRequest =
                  RequestDigestSupport.wrapForDigest(httpRequest, requestBodyMaxBytes);
            } catch (RequestBodyTooLargeException e) {
              PaygateResponseWriter.writeRequestBodyTooLarge(httpResponse);
              recordRejected(resolvedEndpoint.routePattern(), protocol.scheme());
              return;
            }
          }
          tryValidateWithProtocol(
              protocol,
              authHeader,
              protocolRequest,
              httpResponse,
              path,
              method,
              safePath,
              resolvedEndpoint,
              chain,
              protocolRequest,
              response);
          return;
        }
      }
      // A presented credential that no enabled protocol accepts is not a request for a new
      // credential. Bound its cost with the normal validation limiter and fail closed without
      // consulting Lightning or minting replacement state.
      if (!tryAcquireRateLimit(httpRequest)) {
        PaygateResponseWriter.writeRateLimited(httpResponse);
        return;
      }
      PaygateResponseWriter.writeMethodUnsupported(httpResponse, "Unsupported payment credential");
      recordRejected(resolvedEndpoint.routePattern(), "unknown");
      return;
    }

    // 3. Only NO_CREDENTIAL reaches ChallengeService for health check,
    //    rate limiting, invoice creation, and macaroon minting.
    HttpServletRequest challengeRequest = httpRequest;
    try {
      challengeService.acquireChallengeRateLimit(httpRequest);
    } catch (PaygateRateLimitedException _) {
      PaygateResponseWriter.writeRateLimited(httpResponse);
      return;
    }
    if (mppEnabled) {
      try {
        challengeRequest = RequestDigestSupport.wrapForDigest(httpRequest, requestBodyMaxBytes);
        RequestDigestSupport.ensureDigestAttribute(challengeRequest, path, requestBodyMaxBytes);
      } catch (RequestBodyTooLargeException e) {
        PaygateResponseWriter.writeRequestBodyTooLarge(httpResponse);
        recordRejected(resolvedEndpoint.routePattern(), "Payment");
        return;
      }
    }
    issuePaymentChallenge(challengeRequest, httpResponse, method, safePath, resolvedEndpoint);
  }

  /**
   * Builds a request context map for protocol validation, including path, method, client IP, and
   * requested capability using the standard {@link VerificationContextKeys}.
   */
  private Map<String, String> buildRequestContext(
      HttpServletRequest httpRequest,
      String path,
      String method,
      ResolvedEndpoint resolvedEndpoint,
      boolean includeDigest)
      throws IOException {
    String clientIp = resolveClientIp(httpRequest);
    PaygateEndpointConfig config = resolvedEndpoint.config();
    String capability = config.capability();
    String digest =
        includeDigest
            ? RequestDigestSupport.computeDigest(httpRequest, path, requestBodyMaxBytes)
            : null;

    var context = new java.util.LinkedHashMap<String, String>(6);
    context.put(VerificationContextKeys.REQUEST_PATH, path);
    context.put(VerificationContextKeys.REQUEST_ROUTE, resolvedEndpoint.routePattern());
    context.put(VerificationContextKeys.REQUEST_METHOD, method);
    context.put(VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
    if (digest != null) {
      context.put(VerificationContextKeys.REQUEST_DIGEST, digest);
    }
    if (capability != null && !capability.isEmpty()) {
      context.put(VerificationContextKeys.REQUESTED_CAPABILITY, capability);
    }
    return Map.copyOf(context);
  }

  /**
   * Issues a payment challenge when no valid credential was presented. Delegates to {@link
   * PaygateChallengeService} for health check, rate limiting, invoice creation, and macaroon
   * minting.
   */
  private void issuePaymentChallenge(
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      String method,
      String safePath,
      ResolvedEndpoint resolvedEndpoint)
      throws IOException {
    try {
      ChallengeContext challengeContext =
          challengeService.createChallenge(
              httpRequest,
              resolvedEndpoint,
              PaygateChallengeService.ChallengeOptions.rateLimitAlreadyConsumed());
      List<ChallengeResponse> challenges = buildChallenges(challengeContext);
      if (challenges.isEmpty()) {
        challengeService.discardChallenge(challengeContext);
        PaygateResponseWriter.writeLightningUnavailable(httpResponse);
        return;
      }
      PaygateResponseWriter.writePaymentRequired(httpResponse, challengeContext, challenges);
      recordChallenge(resolvedEndpoint.routePattern());
    } catch (PaygateRateLimitedException _) {
      PaygateResponseWriter.writeRateLimited(httpResponse);
    } catch (PaygateLightningUnavailableException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Lightning unavailable for {0} {1}: {2}",
          method,
          safePath,
          e.getClass().getSimpleName());
      if (!httpResponse.isCommitted()) {
        PaygateResponseWriter.writeLightningUnavailable(httpResponse);
      }
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to create invoice for {0} {1}: {2}",
          method,
          safePath,
          e.getClass().getSimpleName());
      if (!httpResponse.isCommitted()) {
        PaygateResponseWriter.writeLightningUnavailable(httpResponse);
      }
    }
  }

  /**
   * Validates a credential with the given protocol and writes the appropriate response. On success,
   * the request is forwarded down the filter chain. On failure, an error response is written
   * directly (fail-closed).
   */
  private void tryValidateWithProtocol(
      PaymentProtocol protocol,
      String authHeader,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      String path,
      String method,
      String safePath,
      ResolvedEndpoint resolvedEndpoint,
      FilterChain chain,
      ServletRequest request,
      ServletResponse response)
      throws IOException {

    long verifyStart = System.nanoTime();
    try {
      PaymentCredential credential = protocol.parseCredential(authHeader);
      PaygateEndpointConfig config = resolvedEndpoint.config();
      Map<String, String> requestContext =
          buildRequestContext(
              httpRequest,
              path,
              method,
              resolvedEndpoint,
              RequestDigestSupport.isMppProtocol(protocol));
      protocol.validate(credential, requestContext);

      log.log(
          System.Logger.Level.DEBUG, "{0} credential validated successfully", protocol.scheme());

      if ("L402".equals(protocol.scheme())) {
        handleL402Success(credential, config, httpResponse);
      }
      generateReceipt(credential, config, protocol, httpResponse);

      chain.doFilter(request, response);
      recordCaveatVerifyDuration(verifyStart);
      recordPassed(resolvedEndpoint.routePattern(), config.priceSats(), protocol.scheme());

    } catch (PaymentValidationException e) {
      recordCaveatVerifyDuration(verifyStart);
      handlePaymentValidationFailure(e, protocol, httpRequest, httpResponse, resolvedEndpoint);

    } catch (RequestBodyTooLargeException e) {
      recordCaveatVerifyDuration(verifyStart);
      PaygateResponseWriter.writeRequestBodyTooLarge(httpResponse);
      recordRejected(resolvedEndpoint.routePattern(), protocol.scheme());
    } catch (Exception e) {
      recordCaveatVerifyDuration(verifyStart);
      handleUnexpectedValidationError(e, protocol, httpRequest, httpResponse, method, safePath);
    }
  }

  /**
   * Handles a {@link PaymentValidationException} thrown during credential validation. Writes the
   * appropriate error response based on the error code and protocol.
   */
  private void handlePaymentValidationFailure(
      PaymentValidationException e,
      PaymentProtocol protocol,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      ResolvedEndpoint resolvedEndpoint)
      throws IOException {
    PaygateEndpointConfig config = resolvedEndpoint.config();
    recordCaveatRejected(LogSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : ""));
    consumeRateLimitPenalty(httpRequest);

    String tokenDetail = LogSanitizer.sanitize(e.getTokenId() != null ? e.getTokenId() : "");
    int tokenCorrelationId = Objects.hash(protocol.scheme(), tokenDetail);
    log.log(
        System.Logger.Level.WARNING,
        "{0} validation failed, errorCode={1}",
        protocol.scheme(),
        e.getErrorCode());

    if (e.getErrorCode() == PaymentValidationException.ErrorCode.MALFORMED
        && "L402".equals(protocol.scheme())) {
      log.log(
          System.Logger.Level.WARNING, "Malformed L402 header for protocol {0}", protocol.scheme());
      PaygateResponseWriter.writeMalformedHeader(httpResponse, e.getMessage(), e.getTokenId());
      recordRejected(resolvedEndpoint.routePattern(), protocol.scheme());
      return;
    }

    // An unsupported MPP method is a classified, presented-credential failure. Do not allocate a
    // replacement challenge, and use a response-safe exception so its diagnostic token ID cannot
    // be serialized to the client.
    if (RequestDigestSupport.isMppProtocol(protocol)
        && e instanceof UnsupportedPaymentMethodException) {
      PaygateResponseWriter.writeMethodUnsupported(httpResponse, "Unsupported payment method");
      recordRejected(resolvedEndpoint.routePattern(), protocol.scheme());
      return;
    }

    // Every remaining validation error came from a presented credential. Return its safe RFC
    // 9457 response without allocating a replacement invoice or root key.
    log.log(
        System.Logger.Level.WARNING,
        "{0} validation failed for tokenCorrelationId {1}, errorCode={2}",
        protocol.scheme(),
        tokenCorrelationId,
        e.getErrorCode());
    PaygateResponseWriter.writeMppError(httpResponse, e, List.of());
    recordRejected(resolvedEndpoint.routePattern(), protocol.scheme());
  }

  /** Distinguishes legitimate challenge requests from presented-credential validation attempts. */
  private enum CredentialState {
    NO_CREDENTIAL,
    PRESENTED
  }

  /**
   * Handles unexpected exceptions thrown during credential validation. Fails closed with a 503
   * response.
   */
  private void handleUnexpectedValidationError(
      Exception e,
      PaymentProtocol protocol,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      String method,
      String safePath)
      throws IOException {
    if (e instanceof MacaroonVerificationException) {
      recordCaveatRejected(LogSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : ""));
    }
    consumeRateLimitPenalty(httpRequest);
    // Exception messages can be supplied by protocol providers and may contain request credentials.
    // Do not include the exception (or any of its content) in production logs.
    log.log(
        System.Logger.Level.WARNING,
        "Unexpected credential validation error; failing closed with service unavailable");
    if (!httpResponse.isCommitted()) {
      PaygateResponseWriter.writeLightningUnavailable(httpResponse);
    }
  }

  /**
   * Resolves the client IP from the request, using the configured {@link ClientIpResolver} if
   * available, falling back to {@code getRemoteAddr()}.
   */
  private String resolveClientIp(HttpServletRequest request) {
    return clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
  }

  /**
   * Attempts to acquire a rate limiter token for the requesting client. Returns {@code true} if the
   * request is allowed (or no rate limiter is configured). Rate limiter exceptions are logged and
   * treated as denied (fail-closed).
   */
  private boolean tryAcquireRateLimit(HttpServletRequest request) {
    PaygateRateLimiter limiter = this.rateLimiter;
    if (limiter == null) {
      return true;
    }
    try {
      return limiter.tryAcquire(resolveRateLimitIdentity(request));
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Rate limiter threw exception, denying request: {0}",
          e.getMessage());
      return false;
    }
  }

  /**
   * Consumes a penalty rate limiter token after a failed credential validation. Failures in the
   * rate limiter itself are logged and swallowed (defense-in-depth).
   */
  private void consumeRateLimitPenalty(HttpServletRequest request) {
    PaygateRateLimiter limiter = this.rateLimiter;
    if (limiter == null) {
      return;
    }
    try {
      limiter.tryAcquire(resolveRateLimitIdentity(request));
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Rate limiter threw exception during penalty consumption: {0}",
          e.getMessage());
    }
  }

  private String resolveRateLimitIdentity(HttpServletRequest request) {
    return clientIpResolver != null
        ? clientIpResolver.resolveRateLimitIdentity(request)
        : request.getRemoteAddr();
  }

  /** Builds the challenge responses that can be safely delivered to the client. */
  private List<ChallengeResponse> buildChallenges(ChallengeContext challengeContext) {
    List<ChallengeResponse> challenges = new ArrayList<>();
    for (PaymentProtocol protocol : protocols) {
      try {
        ChallengeResponse challenge = protocol.formatChallenge(challengeContext);
        if (challenge != null) {
          challenges.add(challenge);
        }
      } catch (RuntimeException e) {
        // A formatter must not prevent another enabled protocol from issuing a usable challenge.
        // Do not log its message because it may contain credential material.
        log.log(
            System.Logger.Level.WARNING,
            "Payment challenge formatter failed; attempting remaining enabled protocols");
      }
    }
    return challenges;
  }

  /**
   * Generates a payment receipt after successful credential validation. Receipt creation is
   * best-effort; failure does not block the request.
   */
  private void generateReceipt(
      PaymentCredential credential,
      PaygateEndpointConfig config,
      PaymentProtocol protocol,
      HttpServletResponse httpResponse) {
    try {
      ChallengeContext receiptContext =
          new ChallengeContext(
              credential.paymentHash(),
              credential.tokenId(),
              "", // bolt11 not needed for receipt
              config.priceSats(),
              config.description(),
              serviceName,
              config.timeoutSeconds(),
              config.capability(),
              null, // rootKeyBytes not needed for receipt
              null, // opaque
              null // digest
              );
      Optional<PaymentReceipt> receiptOpt = protocol.createReceipt(credential, receiptContext);
      if (receiptOpt.isPresent()) {
        PaygateResponseWriter.writeReceipt(httpResponse, receiptOpt.get());
      }
    } catch (Exception e) {
      // Receipt creation is best-effort; failure does not block the request
      log.log(
          System.Logger.Level.DEBUG,
          "Receipt creation failed for protocol {0}",
          protocol.scheme(),
          e);
    }
  }

  /** Handles L402-specific post-validation behavior: sets the credential expiry header. */
  private void handleL402Success(
      PaymentCredential credential,
      PaygateEndpointConfig config,
      HttpServletResponse httpResponse) {
    httpResponse.setHeader(
        "X-L402-Credential-Expires", resolveCredentialExpiry(credential, config).toString());
  }

  /**
   * Extracts the credential expiry from the earliest {@code {serviceName}_valid_until} caveat on
   * the macaroon. Falls back to {@code Instant.now() + config.timeoutSeconds()} when no valid_until
   * caveat is present or parseable.
   *
   * <p>For L402 credentials, the macaroon is extracted from {@link L402Metadata}. For non-L402
   * credentials, falls back to the default timeout.
   */
  Instant resolveCredentialExpiry(PaymentCredential credential, PaygateEndpointConfig config) {
    if (credential.metadata() instanceof L402Metadata l402Meta) {
      String caveatKey = serviceName + "_valid_until";
      OptionalLong earliest =
          l402Meta.macaroon().caveats().stream()
              .filter(c -> caveatKey.equals(c.key()))
              .flatMapToLong(
                  c -> {
                    try {
                      return LongStream.of(Long.parseLong(c.value()));
                    } catch (NumberFormatException e) {
                      log.log(
                          System.Logger.Level.WARNING,
                          "Ignoring unparseable valid_until caveat for key {0}: {1}",
                          caveatKey,
                          e.getMessage());
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

  private void recordChallenge(String endpoint) {
    try {
      if (metrics != null) {
        metrics.recordChallenge(endpoint, "all");
      }
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING, "Failed to record challenge metric: {0}", e.getMessage());
    }
  }

  private void recordPassed(String endpoint, long priceSats, String protocol) {
    try {
      if (metrics != null) {
        metrics.recordPassed(endpoint, priceSats, protocol);
      }
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "Failed to record passed metric: {0}", e.getMessage());
    }
    try {
      if (earningsTracker != null) {
        earningsTracker.recordInvoiceSettled(priceSats);
      }
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to record invoice settlement in earnings tracker: {0}",
          e.getMessage());
    }
  }

  private void recordRejected(String endpoint, String protocol) {
    try {
      if (metrics != null) {
        metrics.recordRejected(endpoint, protocol);
      }
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "Failed to record rejected metric: {0}", e.getMessage());
    }
  }

  private void recordCaveatVerifyDuration(long startNanos) {
    try {
      if (metrics != null) {
        metrics.recordCaveatVerifyDuration(System.nanoTime() - startNanos);
      }
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to record caveat verify duration metric: {0}",
          e.getMessage());
    }
  }

  private void recordCaveatRejected(String exceptionMessage) {
    try {
      if (metrics != null) {
        metrics.recordCaveatRejected(classifyCaveatType(exceptionMessage));
      }
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to record caveat rejected metric: {0}",
          e.getMessage());
    }
  }

  /**
   * Classifies the caveat type from an exception message thrown during caveat verification. Uses
   * known message patterns from the delegation caveat verifiers:
   *
   * <ul>
   *   <li>{@code path} — PathCaveatVerifier messages contain "path"
   *   <li>{@code method} — MethodCaveatVerifier messages contain "method" (but not "Request method
   *       missing")
   *   <li>{@code client_ip} — ClientIpCaveatVerifier messages contain "client IP" or "Client IP"
   *   <li>{@code escalation} — MacaroonVerificationException messages contain "escalation"
   * </ul>
   *
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

  /** Delegates to {@link PathNormalizer#normalize(String)}. */
  static String normalizePath(String rawPath) {
    return PathNormalizer.normalize(rawPath);
  }
}
