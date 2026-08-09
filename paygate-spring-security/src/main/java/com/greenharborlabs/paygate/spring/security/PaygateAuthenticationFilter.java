package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.ApplicationRelativeRequestResolver;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.LogSanitizer;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import com.greenharborlabs.paygate.spring.RequestBodyTooLargeException;
import com.greenharborlabs.paygate.spring.RequestDigestSupport;
import com.greenharborlabs.paygate.spring.ResolvedEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security filter that extracts payment credentials from the Authorization header and
 * delegates authentication to the {@link AuthenticationManager}.
 *
 * <p>First attempts to parse L402/LSAT credentials ({@code Authorization: L402
 * <macaroon>:<preimage>}). If the header does not match L402/LSAT, iterates the registered {@link
 * PaymentProtocol} instances to detect other credential formats (e.g., MPP {@code Payment} scheme).
 *
 * <p>On successful authentication the {@link SecurityContextHolder} is populated with an
 * authenticated {@link PaygateAuthenticationToken}.
 *
 * <p>For an unprotected route, an absent or unrelated authorization header is left for other
 * authentication mechanisms. For every registered paid route, however, this filter always runs: a
 * missing or unrelated credential is rejected before downstream authorization can apply a {@code
 * permitAll} rule.
 */
public final class PaygateAuthenticationFilter extends OncePerRequestFilter {

  private static final System.Logger log =
      System.getLogger(PaygateAuthenticationFilter.class.getName());

  private static final String AUTHORIZATION_HEADER = "Authorization";

  private final AuthenticationManager authenticationManager;
  private final List<PaymentProtocol> protocols;
  private final PaygateEndpointRegistry endpointRegistry;
  private final ClientIpResolver clientIpResolver;
  private final String serviceName;

  public PaygateAuthenticationFilter(
      AuthenticationManager authenticationManager,
      List<PaymentProtocol> protocols,
      PaygateEndpointRegistry endpointRegistry) {
    this(authenticationManager, protocols, endpointRegistry, null, null);
  }

  public PaygateAuthenticationFilter(
      AuthenticationManager authenticationManager,
      List<PaymentProtocol> protocols,
      PaygateEndpointRegistry endpointRegistry,
      ClientIpResolver clientIpResolver,
      String serviceName) {
    this.authenticationManager =
        Objects.requireNonNull(authenticationManager, "authenticationManager must not be null");
    this.protocols = protocols != null ? List.copyOf(protocols) : List.of();
    this.endpointRegistry =
        Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    this.clientIpResolver = clientIpResolver;
    this.serviceName = serviceName;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String authHeader = request.getHeader(AUTHORIZATION_HEADER);
    if (authHeader == null || authHeader.isBlank()) {
      return true;
    }
    if (L402HeaderComponents.extract(authHeader).isPresent()) {
      return false;
    }
    return !matchesAnyProtocol(authHeader);
  }

  /**
   * Enforces the paid-route invariant before {@link OncePerRequestFilter} can skip an absent or
   * unrelated credential. The normal once-per-request dispatch behavior remains delegated to the
   * superclass for recognized payment credentials.
   */
  @Override
  public void doFilter(
      jakarta.servlet.ServletRequest servletRequest,
      jakarta.servlet.ServletResponse servletResponse,
      FilterChain filterChain)
      throws ServletException, IOException {
    if (servletRequest instanceof HttpServletRequest request
        && servletResponse instanceof HttpServletResponse response
        && hasNoRecognizedPaymentCredential(request)
        && isPaidRoute(request)) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeUnauthorized(response);
      return;
    }
    super.doFilter(servletRequest, servletResponse, filterChain);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String authHeader = request.getHeader(AUTHORIZATION_HEADER);
    String normalizedPath;
    try {
      normalizedPath = ApplicationRelativeRequestResolver.resolve(request);
    } catch (RuntimeException e) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeMalformedUri(response);
      return;
    }

    HttpServletRequest authRequest = request;
    boolean includeDigest =
        !L402HeaderComponents.extract(authHeader).isPresent() && matchesAnyProtocol(authHeader);
    if (includeDigest) {
      try {
        authRequest = RequestDigestSupport.wrapForDigest(request);
      } catch (RequestBodyTooLargeException e) {
        SecurityContextHolder.clearContext();
        PaygateResponseWriter.writeRequestBodyTooLarge(response);
        return;
      }
    }

    ResolvedEndpoint resolvedEndpoint;
    try {
      resolvedEndpoint = resolveEndpoint(request, normalizedPath);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Endpoint policy resolution failed for {0} {1}: {2}",
          request.getMethod(),
          LogSanitizer.sanitize(normalizedPath),
          e.getClass().getSimpleName());
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeInternalError(response);
      return;
    }
    if (resolvedEndpoint == null) {
      filterChain.doFilter(request, response);
      return;
    }

    if (authHeader == null
        || authHeader.isBlank()
        || (!L402HeaderComponents.extract(authHeader).isPresent()
            && !matchesAnyProtocol(authHeader))) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeUnauthorized(response);
      return;
    }

    PaygateEndpointConfig endpointConfig;
    String capability;
    endpointConfig = resolvedEndpoint.config();
    capability = extractCapability(endpointConfig);

    Map<String, String> requestMetadata;
    try {
      requestMetadata =
          extractRequestMetadata(
              authRequest,
              normalizedPath,
              resolvedEndpoint.routePattern(),
              capability,
              includeDigest);
    } catch (RequestBodyTooLargeException e) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeRequestBodyTooLarge(response);
      return;
    }

    PaygateAuthenticationToken unauthenticatedToken =
        Objects.requireNonNull(
            createAuthToken(authHeader, requestMetadata),
            "Token creation must succeed after shouldNotFilter");

    Authentication authenticated;
    try {
      authenticated = authenticationManager.authenticate(unauthenticatedToken);
      var securityContext = SecurityContextHolder.createEmptyContext();
      securityContext.setAuthentication(authenticated);
      SecurityContextHolder.setContext(securityContext);
    } catch (AuthenticationException e) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeAuthenticationFailed(response);
      return;
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Payment authentication encountered an unexpected error; failing closed with service unavailable");
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeLightningUnavailable(response);
      return;
    }

    generateReceipt(authenticated, endpointConfig, response);

    filterChain.doFilter(authRequest, response);
  }

  private PaygateAuthenticationToken createAuthToken(
      String authHeader, Map<String, String> requestMetadata) {
    var componentsOpt = L402HeaderComponents.extract(authHeader);
    if (componentsOpt.isPresent()) {
      return new PaygateAuthenticationToken(componentsOpt.get(), requestMetadata);
    }
    if (matchesAnyProtocol(authHeader)) {
      return PaygateAuthenticationToken.unauthenticated(authHeader, requestMetadata);
    }
    return null;
  }

  private boolean matchesAnyProtocol(String authHeader) {
    for (PaymentProtocol protocol : protocols) {
      if (protocol.canHandle(authHeader)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasNoRecognizedPaymentCredential(HttpServletRequest request) {
    String authHeader = request.getHeader(AUTHORIZATION_HEADER);
    return authHeader == null
        || authHeader.isBlank()
        || (!L402HeaderComponents.extract(authHeader).isPresent()
            && !matchesAnyProtocol(authHeader));
  }

  private Map<String, String> extractRequestMetadata(
      HttpServletRequest request,
      String normalizedPath,
      String canonicalRoute,
      String capability,
      boolean includeDigest)
      throws IOException {
    Map<String, String> metadata = new HashMap<>(5);
    metadata.put(VerificationContextKeys.REQUEST_PATH, normalizedPath);
    metadata.put(VerificationContextKeys.REQUEST_ROUTE, canonicalRoute);
    metadata.put(VerificationContextKeys.REQUEST_METHOD, request.getMethod());
    String clientIp =
        clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
    metadata.put(VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
    if (includeDigest) {
      metadata.put(
          VerificationContextKeys.REQUEST_DIGEST,
          RequestDigestSupport.computeDigest(request, normalizedPath));
    }
    if (capability != null && !capability.isBlank()) {
      metadata.put(VerificationContextKeys.REQUESTED_CAPABILITY, capability);
    }
    return metadata;
  }

  /**
   * Resolves the endpoint for the current request by looking up the endpoint registry. Returns
   * {@code null} if no endpoint is found for the given method and path.
   */
  private ResolvedEndpoint resolveEndpoint(HttpServletRequest request, String normalizedPath) {
    return endpointRegistry.resolve(request.getMethod(), normalizedPath);
  }

  /**
   * Determines whether the request is paid before deciding whether this filter may be skipped.
   * Resolution failures deliberately keep the filter active, so a registry failure cannot create an
   * authorization bypass.
   */
  private boolean isPaidRoute(HttpServletRequest request) {
    try {
      String normalizedPath = ApplicationRelativeRequestResolver.resolve(request);
      return endpointRegistry.findConfig(request.getMethod(), normalizedPath) != null;
    } catch (RuntimeException e) {
      return true;
    }
  }

  private static String extractCapability(PaygateEndpointConfig config) {
    if (config == null) {
      return null;
    }
    String capability = config.capability();
    if (capability == null || capability.isBlank()) {
      return null;
    }
    return capability;
  }

  /**
   * Generates a payment receipt after successful authentication for protocols that support it
   * (e.g., MPP produces receipts, L402 does not).
   *
   * <p>Receipt generation is best-effort: failures are logged at DEBUG level and do not block
   * request processing.
   */
  private void generateReceipt(
      Authentication authenticated, PaygateEndpointConfig config, HttpServletResponse response) {
    if (config == null) {
      return;
    }
    if (!(authenticated instanceof PaygateAuthenticationToken authToken)) {
      return;
    }
    PaymentCredential credential = authToken.getPaymentCredential();
    if (credential == null) {
      return;
    }

    for (PaymentProtocol protocol : protocols) {
      if (protocol.scheme().equals(credential.sourceProtocolScheme())) {
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
            PaygateResponseWriter.writeReceipt(response, receiptOpt.get());
          }
        } catch (Exception e) {
          log.log(
              System.Logger.Level.DEBUG,
              "Receipt creation failed for protocol {0}",
              protocol.scheme(),
              e);
        }
        return;
      }
    }
  }
}
