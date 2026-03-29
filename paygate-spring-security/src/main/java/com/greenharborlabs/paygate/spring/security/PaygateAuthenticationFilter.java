package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
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
 * <p>If the header is absent or does not match any known protocol, the filter chain continues
 * without setting authentication, allowing other filters to handle the request.
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

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String authHeader = request.getHeader(AUTHORIZATION_HEADER);
    String normalizedPath = PathNormalizer.normalize(request.getRequestURI());

    PaygateEndpointConfig endpointConfig;
    String capability;
    try {
      endpointConfig = resolveEndpointConfig(request, normalizedPath);
      if (endpointConfig == null) {
        filterChain.doFilter(request, response);
        return;
      }
      capability = extractCapability(endpointConfig);
    } catch (RuntimeException e) {
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeLightningUnavailable(response);
      return;
    }

    Map<String, String> requestMetadata =
        extractRequestMetadata(request, normalizedPath, capability);

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
          System.Logger.Level.WARNING, "Payment authentication encountered an unexpected error", e);
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeLightningUnavailable(response);
      return;
    }

    generateReceipt(authenticated, endpointConfig, response);

    filterChain.doFilter(request, response);
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

  private Map<String, String> extractRequestMetadata(
      HttpServletRequest request, String normalizedPath, String capability) {
    Map<String, String> metadata = new HashMap<>(4);
    metadata.put(VerificationContextKeys.REQUEST_PATH, normalizedPath);
    metadata.put(VerificationContextKeys.REQUEST_METHOD, request.getMethod());
    String clientIp =
        clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
    metadata.put(VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
    if (capability != null && !capability.isBlank()) {
      metadata.put(VerificationContextKeys.REQUESTED_CAPABILITY, capability);
    }
    return metadata;
  }

  /**
   * Resolves the endpoint configuration for the current request by looking up the endpoint
   * registry. Returns {@code null} if no config is found for the given method and path. Re-throws
   * {@link RuntimeException} to enforce fail-closed behavior.
   */
  private PaygateEndpointConfig resolveEndpointConfig(
      HttpServletRequest request, String normalizedPath) {
    try {
      return endpointRegistry.findConfig(request.getMethod(), normalizedPath);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Failed to resolve endpoint config for {0} {1}; denying request",
          request.getMethod(),
          sanitizeForLog(request.getRequestURI()),
          e);
      throw e;
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

  static String sanitizeForLog(String value) {
    if (value == null) {
      return "null";
    }
    return value
        .codePoints()
        .filter(cp -> cp >= 0x20 && cp != 0x7F)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }
}
