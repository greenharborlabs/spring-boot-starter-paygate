package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.ApplicationRelativeRequestResolver;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import com.greenharborlabs.paygate.spring.ResolvedEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate-limiting filter for Spring Security mode that applies two phases of protection:
 *
 * <ol>
 *   <li><strong>Pre-check on entry:</strong> consumes a rate limiter token before downstream
 *       processing. If exhausted, short-circuits with 429.
 *   <li><strong>Post-failure penalty:</strong> after the filter chain returns, if {@link
 *       PaygateAuthenticationFilter} marked the request as a client-fault failure of a presented
 *       credential (malformed, invalid, or expired), consumes an additional penalty token. Backend
 *       outages and other server-side failures are not penalized, whatever their status.
 * </ol>
 *
 * <p>The protected endpoint is resolved with {@link PaygateEndpointRegistry#resolve(
 * HttpServletRequest)}, the same full request-mapping resolution that {@link
 * PaygateAuthenticationFilter} uses, so a request that the authentication filter treats as paid is
 * always rate limited here.
 *
 * <p>This filter should be placed <em>before</em> {@link PaygateAuthenticationFilter} in the Spring
 * Security filter chain. Requests without a payment-scheme {@code Authorization} header bypass this
 * filter entirely so unauthenticated challenge requests are charged by {@link
 * PaygateAuthenticationEntryPoint} before any MPP request-body digest capture.
 */
public final class PaygateAuthFailureRateLimitFilter extends OncePerRequestFilter {

  private static final System.Logger log =
      System.getLogger(PaygateAuthFailureRateLimitFilter.class.getName());

  private static final String AUTHORIZATION_HEADER = "Authorization";

  private final @Nullable PaygateRateLimiter rateLimiter;
  private final @Nullable ClientIpResolver clientIpResolver;
  private final PaygateEndpointRegistry endpointRegistry;
  private final List<PaymentProtocol> protocols;

  public PaygateAuthFailureRateLimitFilter(
      @Nullable PaygateRateLimiter rateLimiter,
      @Nullable ClientIpResolver clientIpResolver,
      PaygateEndpointRegistry endpointRegistry,
      List<PaymentProtocol> protocols) {
    this.rateLimiter = rateLimiter;
    this.endpointRegistry =
        Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    this.clientIpResolver = clientIpResolver;
    this.protocols = protocols != null ? List.copyOf(protocols) : List.of();
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
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (rateLimiter == null) {
      filterChain.doFilter(request, response);
      return;
    }

    ResolvedEndpoint resolvedEndpoint;
    try {
      resolvedEndpoint = lookupEndpoint(request);
    } catch (MalformedRequestUriException e) {
      PaygateResponseWriter.writeMalformedUri(response);
      return;
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Endpoint registry threw exception, failing closed: {0}",
          e.getMessage());
      PaygateResponseWriter.writeLightningUnavailable(response);
      return;
    }

    if (resolvedEndpoint == null) {
      // Not a protected endpoint -- pass through without rate limiting
      filterChain.doFilter(request, response);
      return;
    }

    if (!tryAcquireRateLimit(request)) {
      PaygateResponseWriter.writeRateLimited(response);
      return;
    }

    filterChain.doFilter(request, response);

    if (PaygateAuthenticationFilter.isClientFaultFailure(request)) {
      consumeRateLimitPenalty(request);
    }
  }

  private @Nullable ResolvedEndpoint lookupEndpoint(HttpServletRequest request) {
    try {
      ApplicationRelativeRequestResolver.resolve(request);
    } catch (RuntimeException e) {
      throw new MalformedRequestUriException(e);
    }
    // Full MVC resolution, identical to PaygateAuthenticationFilter: a path-only lookup can miss a
    // paid route that a params/headers/produces condition hides from the path alone.
    return endpointRegistry.resolve(request);
  }

  private static final class MalformedRequestUriException extends RuntimeException {
    MalformedRequestUriException(RuntimeException cause) {
      super(cause);
    }
  }

  /**
   * Attempts to acquire a rate limiter token for the requesting client. Rate limiter exceptions are
   * treated as denied (fail-closed).
   */
  private boolean tryAcquireRateLimit(HttpServletRequest request) {
    try {
      return rateLimiter.tryAcquire(resolveRateLimitIdentity(request));
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Rate limiter threw exception, denying request: {0}",
          e.getMessage());
      return false;
    }
  }

  private void consumeRateLimitPenalty(HttpServletRequest request) {
    try {
      rateLimiter.tryAcquire(resolveRateLimitIdentity(request));
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

  private boolean matchesAnyProtocol(String authHeader) {
    for (PaymentProtocol protocol : protocols) {
      if (protocol.canHandle(authHeader)) {
        return true;
      }
    }
    return false;
  }
}
