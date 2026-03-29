package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Rate-limiting filter for Spring Security mode that applies two phases of protection:
 *
 * <ol>
 *   <li><strong>Pre-check on entry:</strong> consumes a rate limiter token before downstream
 *       processing. If exhausted, short-circuits with 429.</li>
 *   <li><strong>Post-failure penalty:</strong> after the filter chain returns, if the response
 *       status is 401 or 503 (the statuses {@link PaygateAuthenticationFilter} produces) and the
 *       endpoint is protected, consumes an additional penalty token.</li>
 * </ol>
 *
 * <p>This filter should be placed <em>before</em> {@link PaygateAuthenticationFilter} in the
 * Spring Security filter chain. Requests without a payment-scheme {@code Authorization} header
 * bypass this filter entirely.
 */
public final class PaygateAuthFailureRateLimitFilter extends OncePerRequestFilter {

    private static final System.Logger log =
            System.getLogger(PaygateAuthFailureRateLimitFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

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
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry,
                "endpointRegistry must not be null");
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
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (rateLimiter == null) {
            filterChain.doFilter(request, response);
            return;
        }

        PaygateEndpointConfig endpointConfig;
        try {
            endpointConfig = lookupEndpointConfig(request);
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING,
                    "Endpoint registry threw exception, failing closed: {0}", e.getMessage());
            PaygateResponseWriter.writeLightningUnavailable(response);
            return;
        }

        if (endpointConfig == null) {
            // Not a protected endpoint -- pass through without rate limiting
            filterChain.doFilter(request, response);
            return;
        }

        if (!tryAcquireRateLimit(request)) {
            PaygateResponseWriter.writeRateLimited(response);
            return;
        }

        filterChain.doFilter(request, response);

        if (isAuthFailureStatus(response.getStatus())) {
            consumeRateLimitPenalty(request);
        }
    }

    private @Nullable PaygateEndpointConfig lookupEndpointConfig(HttpServletRequest request) {
        String normalizedPath = PathNormalizer.normalize(request.getRequestURI());
        return endpointRegistry.findConfig(request.getMethod(), normalizedPath);
    }

    private static boolean isAuthFailureStatus(int status) {
        return status == HTTP_UNAUTHORIZED || status == HTTP_SERVICE_UNAVAILABLE;
    }

    private boolean tryAcquireRateLimit(HttpServletRequest request) {
        try {
            return rateLimiter.tryAcquire(resolveClientIp(request));
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Rate limiter threw exception, allowing request: {0}", e.getMessage());
            return true;
        }
    }

    private void consumeRateLimitPenalty(HttpServletRequest request) {
        try {
            rateLimiter.tryAcquire(resolveClientIp(request));
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Rate limiter threw exception during penalty consumption: {0}", e.getMessage());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver != null
                ? clientIpResolver.resolve(request)
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
